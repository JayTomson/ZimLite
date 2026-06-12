package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.luben.zstd.ZstdInputStream
import org.tukaani.xz.LZMA2InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

interface ZimSource : AutoCloseable {
    fun seek(pos: Long)
    fun readFully(b: ByteArray)
    fun readFully(b: ByteArray, off: Int, len: Int)
    fun read(): Int
    fun length(): Long
}

class FileZimSource(file: File) : ZimSource {
    private val raf = RandomAccessFile(file, "r")
    
    override fun seek(pos: Long) {
        raf.seek(pos)
    }
    
    override fun readFully(b: ByteArray) {
        raf.readFully(b)
    }
    
    override fun readFully(b: ByteArray, off: Int, len: Int) {
        raf.readFully(b, off, len)
    }
    
    override fun read(): Int {
        return raf.read()
    }
    
    override fun length(): Long {
        return raf.length()
    }
    
    override fun close() {
        raf.close()
    }
}

class UriZimSource(context: Context, uri: Uri) : ZimSource {
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open Uri: $uri")
    private val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
    
    override fun seek(pos: Long) {
        channel.position(pos)
    }
    
    override fun readFully(b: ByteArray) {
        readFully(b, 0, b.size)
    }
    
    override fun readFully(b: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(b, off, len)
        var totalRead = 0
        while (totalRead < len) {
            val count = channel.read(buf)
            if (count == -1) {
                throw IOException("Reached EOF before reading required bytes")
            }
            totalRead += count
        }
    }
    
    override fun read(): Int {
        val b = ByteArray(1)
        val count = channel.read(ByteBuffer.wrap(b))
        return if (count <= 0) -1 else b[0].toInt() and 0xFF
    }
    
    override fun length(): Long {
        return channel.size()
    }
    
    override fun close() {
        channel.close()
        pfd.close()
    }
}

class ZimHeader(
    val magic: Int,
    val version: Int,
    val uuid: ByteArray,
    val articleCount: Int,
    val clusterCount: Int,
    val urlPtrPos: Long,
    val titlePtrPos: Long,
    val clusterPtrPos: Long,
    val mimeListPos: Long,
    val mainPage: Int,
    val layoutPage: Int
)

class DirectoryEntry(
    val mimeType: Int,
    val namespace: Char,
    val url: String,
    val title: String,
    val clusterNumber: Int,
    val blobNumber: Int,
    val redirectIndex: Int
)

class DecompressedCluster(val data: ByteArray, val isExtended: Boolean)

object ZimReader {
    private val lastClusterLock = Any()
    private var lastClusterOffsetCache: Long = -1L
    private var lastClusterDataCache: DecompressedCluster? = null


    private fun readLEInt(source: ZimSource): Int {
        val b = ByteArray(4)
        source.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }

    private fun readLELong(source: ZimSource): Long {
        val b = ByteArray(8)
        source.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong()
    }

    private fun readNullTerminatedString(source: ZimSource): String {
        val baos = ByteArrayOutputStream()
        var b = source.read()
        while (b != 0 && b != -1) {
            baos.write(b)
            b = source.read()
        }
        return baos.toString("UTF-8")
    }

    internal fun openSource(context: Context, pathOrUri: String): ZimSource {
        return if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
            UriZimSource(context, Uri.parse(pathOrUri))
        } else {
            FileZimSource(File(pathOrUri))
        }
    }

    fun readHeader(source: ZimSource): ZimHeader {
        try {
            source.seek(0)
            if (source.length() < 80) throw IOException("ZIM file too small")
            val bytes = ByteArray(80)
            source.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            
            val magic = buf.getInt()           // 0-3
            val versionMajor = buf.getShort()  // 4-5
            val versionMinor = buf.getShort()  // 6-7
            val uuid = ByteArray(16)
            buf.get(uuid)                      // 8-23
            val articleCount = buf.getInt()    // 24-27
            val clusterCount = buf.getInt()    // 28-31
            val urlPtrPos = buf.getLong()      // 32-39
            val titlePtrPos = buf.getLong()    // 40-47
            val clusterPtrPos = buf.getLong()  // 48-55
            val mimeListPos = buf.getLong()    // 56-63
            val mainPage = buf.getInt()        // 64-67
            val layoutPage = buf.getInt()      // 68-71
            
            return ZimHeader(
                magic = magic,
                version = ((versionMajor.toInt() and 0xFFFF) shl 16) or (versionMinor.toInt() and 0xFFFF),
                uuid = uuid,
                articleCount = articleCount,
                clusterCount = clusterCount,
                urlPtrPos = urlPtrPos,
                titlePtrPos = titlePtrPos,
                clusterPtrPos = clusterPtrPos,
                mimeListPos = mimeListPos,
                mainPage = mainPage,
                layoutPage = layoutPage
            )
        } catch (e: Throwable) {
            if (e is IOException) throw e
            throw IOException("Failed to read header: ${e.message}", e)
        }
    }

    fun readDirectoryEntry(source: ZimSource, entryOffset: Long): DirectoryEntry {
        source.seek(entryOffset)
        
        val b = ByteArray(2)
        source.readFully(b)
        val mimeType = ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
        
        val parameterLen = source.read()
        val namespace = source.read().toChar()
        
        // ZIM v5 and v6 ALWAYS have a 4-byte revision field
        val revision = readLEInt(source) // offset 4..7, skipped for now
        
        var clusterNumber = -1
        var blobNumber = -1
        var redirectIndex = -1
        
        if (mimeType == 0xFFFF) {
            // Redirect entry: redirectIndex follows revision
            redirectIndex = readLEInt(source) // offset 8..11
        } else {
            // Content entry: cluster + blob
            clusterNumber = readLEInt(source) // offset 8..11
            blobNumber = readLEInt(source)    // offset 12..15
        }
        
        val url = readNullTerminatedString(source)
        val title = readNullTerminatedString(source)
        
        return DirectoryEntry(
            mimeType = mimeType,
            namespace = namespace,
            url = url,
            title = title,
            clusterNumber = clusterNumber,
            blobNumber = blobNumber,
            redirectIndex = redirectIndex
        )
    }

    fun getClusterOffsetAndSize(source: ZimSource, header: ZimHeader, clusterNumber: Int, fileSize: Long): Pair<Long, Long> {
        source.seek(header.clusterPtrPos + clusterNumber * 8L)
        val clusterOffset = readLELong(source)
        
        val nextClusterOffset = if (clusterNumber < header.clusterCount - 1) {
            source.seek(header.clusterPtrPos + (clusterNumber + 1) * 8L)
            readLELong(source)
        } else {
            if (header.mimeListPos > clusterOffset) header.mimeListPos else fileSize
        }
        
        return Pair(clusterOffset, nextClusterOffset - clusterOffset)
    }

    private fun getDecompressionStream(compressionType: Int, bais: ByteArrayInputStream, data: ByteArray): java.io.InputStream {
        if (data.size >= 4) {
            // Zstd magic: 0xFD2FB528 (Little Endian in bytes: 28 B5 2F FD)
            if (data[0] == 0x28.toByte() && data[1] == 0xB5.toByte() && data[2] == 0x2F.toByte() && data[3] == 0xFD.toByte()) {
                try {
                    com.github.luben.zstd.util.Native.load()
                } catch (e: Throwable) {
                    Log.e("ZimReader", "Failed to load Zstd native library", e)
                }
                return ZstdInputStream(bais)
            }
            
            // XZ magic: FD 37 7A 58 5A 00
            if (data[0] == 0xFD.toByte() && data[1] == 0x37.toByte() && data[2] == 0x7A.toByte() && data[3] == 0x58.toByte()) {
                return org.tukaani.xz.XZInputStream(bais)
            }
            
            // Zlib magic: 0x78 0x01, 0x78 0x9C, 0x78 0xDA
            if (data[0] == 0x78.toByte() && (data[1] == 0x01.toByte() || data[1] == 0x9C.toByte() || data[1] == 0xDA.toByte())) {
                return InflaterInputStream(bais)
            }
        }
        
        return when (compressionType) {
            0, 1 -> bais // None / Legacy None
            2 -> InflaterInputStream(bais)
            4 -> org.tukaani.xz.XZInputStream(bais)
            5 -> {
                try {
                    com.github.luben.zstd.util.Native.load()
                } catch (e: Throwable) {
                    Log.e("ZimReader", "Failed to load Zstd native library", e)
                }
                ZstdInputStream(bais)
            }
            else -> throw IOException("Unsupported compression type: $compressionType")
        }
    }

    fun decompressCluster(source: ZimSource, clusterOffset: Long, clusterSize: Long): DecompressedCluster {
        if (clusterSize <= 0) return DecompressedCluster(ByteArray(0), false)
        synchronized(lastClusterLock) {
            if (lastClusterOffsetCache == clusterOffset && lastClusterDataCache != null) {
                return lastClusterDataCache!!
            }
        }
        source.seek(clusterOffset)
        val compressionTypeByte = source.read()
        // Compression type is the lower 4 bits
        val compressionType = compressionTypeByte and 0x0F
        // Bit 4 (0x10) indicates extended (8-byte) offsets
        val isExtended = (compressionTypeByte and 0x10) != 0
        
        val compressedDataSize = clusterSize - 1
        if (compressedDataSize <= 0) return DecompressedCluster(ByteArray(0), isExtended)
        if (compressedDataSize > 120 * 1024 * 1024) throw IOException("Cluster size abnormally large: $compressedDataSize")
        
        val compressedBytes = ByteArray(compressedDataSize.toInt())
        source.readFully(compressedBytes)
        
        val bais = ByteArrayInputStream(compressedBytes)
        val decompressedStream = try {
            getDecompressionStream(compressionType, bais, compressedBytes)
        } catch (t: Throwable) {
            val magic = if (compressedBytes.size >= 4) {
                "%02x %02x %02x %02x".format(compressedBytes[0], compressedBytes[1], compressedBytes[2], compressedBytes[3])
            } else "N/A"
            throw IOException("Error initializing decompression stream ($compressionType), magic=[$magic]: ${t.message ?: t.toString()}", t)
        }
        
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(32768)
        var read: Int
        try {
            while (decompressedStream.read(buffer).also { read = it } != -1) {
                baos.write(buffer, 0, read)
                if (baos.size() > 180 * 1024 * 1024) throw IOException("Decompressed cluster too large")
            }
        } catch (t: Throwable) {
            throw IOException("Error reading decompressed data ($compressionType): ${t.message ?: t.toString()}", t)
        } finally {
            try { decompressedStream.close() } catch (e: Exception) {}
        }
        val clusterResult = DecompressedCluster(baos.toByteArray(), isExtended)
        synchronized(lastClusterLock) {
            lastClusterOffsetCache = clusterOffset
            lastClusterDataCache = clusterResult
        }
        return clusterResult
    }

    fun readMimeList(source: ZimSource, mimeListPos: Long): List<String> {
        if (mimeListPos <= 0) return emptyList()
        source.seek(mimeListPos)
        val list = mutableListOf<String>()
        while (true) {
            val s = try { readNullTerminatedString(source) } catch (e: Exception) { "" }
            if (s.isEmpty()) break
            list.add(s)
        }
        return list
    }

    fun getBlobForEntry(source: ZimSource, entry: DirectoryEntry, header: ZimHeader, depth: Int = 0): ByteArray? {
        if (depth > 10) return null
        if (entry.mimeType == 0xFFFF) {
            source.seek(header.urlPtrPos + entry.redirectIndex * 8L)
            val targetOffset = readLELong(source)
            if (targetOffset < 0 || targetOffset > source.length()) return null
            val targetEntry = readDirectoryEntry(source, targetOffset)
            return getBlobForEntry(source, targetEntry, header, depth + 1)
        }
        
        if (entry.clusterNumber < 0 || entry.blobNumber < 0 || entry.clusterNumber >= header.clusterCount) return null
        
        val fileSize = source.length()
        val (clusterOffset, clusterSize) = getClusterOffsetAndSize(source, header, entry.clusterNumber, fileSize)
        
        if (clusterOffset < 0 || clusterOffset + clusterSize > fileSize) return null
        
        val cluster = try { decompressCluster(source, clusterOffset, clusterSize) } catch (e: Exception) { DecompressedCluster(ByteArray(0), false) }
        val clusterBytes = cluster.data
        
        if (clusterBytes.size < 4) return null
        
        val buf = ByteBuffer.wrap(clusterBytes).order(ByteOrder.LITTLE_ENDIAN)
        val offsetSize = if (cluster.isExtended) 8 else 4
        
        val startOff: Int
        val endOff: Int
        try {
            if (cluster.isExtended) {
                startOff = buf.getLong(entry.blobNumber * 8).toInt()
                endOff = buf.getLong((entry.blobNumber + 1) * 8).toInt()
            } else {
                startOff = buf.getInt(entry.blobNumber * 4)
                endOff = buf.getInt((entry.blobNumber + 1) * 4)
            }
        } catch (e: Exception) { return null }
        
        val size = endOff - startOff
        if (size <= 0 || startOff < 0 || startOff + size > clusterBytes.size) return null
        
        val result = ByteArray(size)
        System.arraycopy(clusterBytes, startOff, result, 0, size)
        return result
    }

    fun getBlobByUrl(context: Context, pathOrUri: String, targetUrl: String): Pair<ByteArray, String>? {
        try {
            openSource(context, pathOrUri).use { source ->
                val header = try { readHeader(source) } catch (e: Exception) { return null }
                val mimes = readMimeList(source, header.mimeListPos)
                
                val searchKeys = LinkedHashSet<String>()
                searchKeys.add(targetUrl)
                
                val cleanUrl = if (targetUrl.contains("/")) targetUrl.substring(targetUrl.indexOf('/') + 1) else targetUrl
                val cleanUrlUnderscore = cleanUrl.replace(" ", "_")
                
                // Add variants similar to getHtmlByUrl
                searchKeys.add("A/$cleanUrl")
                searchKeys.add("a/$cleanUrl")
                searchKeys.add("A/$cleanUrlUnderscore")
                searchKeys.add("a/$cleanUrlUnderscore")
                searchKeys.add(targetUrl.replace(" ", "_"))

                if (targetUrl.startsWith("C/", true)) {
                    searchKeys.add("C/$cleanUrlUnderscore")
                    searchKeys.add("c/$cleanUrlUnderscore")
                }
                
                // Other common namespaces as fallback
                for (ns in listOf("I", "i", "S", "s", "J", "j", "m", "M", "-", "")) {
                    if (ns == "A" || ns == "a") continue // already added
                    if (ns.isEmpty()) {
                        searchKeys.add(cleanUrl)
                        searchKeys.add(cleanUrlUnderscore)
                    } else {
                        searchKeys.add("$ns/$cleanUrl")
                        searchKeys.add("$ns/$cleanUrlUnderscore")
                    }
                }
                
                for (key in searchKeys) {
                    var low = 0
                    var high = header.articleCount - 1
                    while (low <= high) {
                        val mid = (low + high) ushr 1
                        source.seek(header.urlPtrPos + mid * 8L)
                        val entryOffset = readLELong(source)
                        val entry = readDirectoryEntry(source, entryOffset)
                        val entryFullPath = "${entry.namespace}/${entry.url}"
                        val comp = entryFullPath.compareTo(key)
                        
                        if (comp == 0) {
                            val data = getBlobForEntry(source, entry, header)
                            if (data != null) {
                                val mime = mimes.getOrElse(entry.mimeType) { "application/octet-stream" }
                                return Pair(data, mime)
                            }
                        }
                        if (comp < 0) low = mid + 1 else high = mid - 1
                    }
                    
                    // Fallback case-insensitive
                    low = 0
                    high = header.articleCount - 1
                    while (low <= high) {
                        val mid = (low + high) ushr 1
                        source.seek(header.urlPtrPos + mid * 8L)
                        val entryOffset = readLELong(source)
                        val entry = readDirectoryEntry(source, entryOffset)
                        val entryFullPath = "${entry.namespace}/${entry.url}"
                        val comp = entryFullPath.compareTo(key, ignoreCase = true)
                        if (comp == 0) {
                            val data = getBlobForEntry(source, entry, header)
                            if (data != null) {
                                val mime = mimes.getOrElse(entry.mimeType) { "application/octet-stream" }
                                return Pair(data, mime)
                            }
                            break
                        }
                        if (comp < 0) low = mid + 1 else high = mid - 1
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("ZimReader", "getBlobByUrl failed: $targetUrl", e)
        }
        return null
    }

    fun getHtmlForArticle(source: ZimSource, entry: DirectoryEntry, header: ZimHeader, depth: Int = 0): String {
        if (depth > 10) return "<h3>Ошибка: Слишком много перенаправлений (цикл)</h3>"
        if (entry.mimeType == 0xFFFF) {
            source.seek(header.urlPtrPos + entry.redirectIndex * 8L)
            val targetOffset = try { readLELong(source) } catch (e: Exception) { -1L }
            if (targetOffset < 0 || targetOffset > source.length()) return "<h3>Ошибка: Некорректный адрес перенаправления</h3>"
            val targetEntry = try { readDirectoryEntry(source, targetOffset) } catch (e: Exception) { null }
            if (targetEntry == null) return "<h3>Ошибка: Не удалось прочитать запись перенаправления</h3>"
            return getHtmlForArticle(source, targetEntry, header, depth + 1)
        }
        
        if (entry.clusterNumber < 0 || entry.blobNumber < 0 || entry.clusterNumber >= header.clusterCount) {
            return "<h3>Ошибка: Недопустимый индекс данных в архиве</h3>" +
                    "<p>Кластер: ${entry.clusterNumber}, Блоб: ${entry.blobNumber}</p>"
        }
        
        val fileSize = source.length()
        val (clusterOffset, clusterSize) = try {
            getClusterOffsetAndSize(source, header, entry.clusterNumber, fileSize)
        } catch (e: Exception) {
            return "<h3>Ошибка: Не удалось определить положение данных (кластера)</h3><p>${e.message}</p>"
        }
        
        if (clusterOffset < 0 || clusterOffset + clusterSize > fileSize) {
            return "<h3>Ошибка: Данные выходят за границы файла</h3>" +
                    "<p>Смещение: $clusterOffset, Размер: $clusterSize, Размер файла: $fileSize</p>"
        }
        
        val clusterBytes: ByteArray
        try {
            val cluster = decompressCluster(source, clusterOffset, clusterSize)
            clusterBytes = cluster.data
            
            if (clusterBytes.size < 4) return "<h3>Ошибка: Извлеченные данные пусты или повреждены</h3>"
            
            val buf = ByteBuffer.wrap(clusterBytes).order(ByteOrder.LITTLE_ENDIAN)
            
            val offsetSize = if (cluster.isExtended) 8 else 4
            
            // Read start and end offsets of the blob
            val startOff: Int
            val endOff: Int
            
            try {
                if (cluster.isExtended) {
                    startOff = buf.getLong(entry.blobNumber * 8).toInt()
                    endOff = buf.getLong((entry.blobNumber + 1) * 8).toInt()
                } else {
                    startOff = buf.getInt(entry.blobNumber * 4)
                    endOff = buf.getInt((entry.blobNumber + 1) * 4)
                }
            } catch (e: Exception) {
                return "<h3>Ошибка чтения таблицы смещений внутри кластера</h3><p>Блоб: ${entry.blobNumber}, Ошибка: ${e.message}</p>"
            }
            
            val size = endOff - startOff
            
            if (size <= 0 || startOff < 0 || startOff + size > clusterBytes.size) {
                return "<h3>Ошибка: Смещение блоба за пределами кластера</h3>" +
                        "<p>Старт: $startOff, Размер: $size, Размер кластера: ${clusterBytes.size}</p>"
            }
            
            return try {
                String(clusterBytes, startOff, size, Charsets.UTF_8)
            } catch (e: Exception) {
                "<h3>Ошибка декодирования содержимого (UTF-8)</h3><p>${e.message}</p>"
            }
        } catch (e: Throwable) {
            return "<h3>Ошибка распаковки кластера</h3><p>${e.message}</p>"
        }
    }

    fun getHtmlByUrl(context: Context, pathOrUri: String, targetUrl: String): String {
        val errorLog = mutableListOf<String>()
        try {
            openSource(context, pathOrUri).use { source ->
                val header = try { readHeader(source) } catch (e: Exception) {
                    return "<h3>Ошибка чтения заголовка ZIM</h3><p>${e.message}</p>"
                }
                
                // We'll prepare multiple possible target search keys to make it extremely robust.
                val searchKeys = LinkedHashSet<String>()
                searchKeys.add(targetUrl) // e.g., "A/нварский_дождь"
                
                // Extract clean URL (without namespace)
                val cleanUrl = if (targetUrl.contains("/")) targetUrl.substring(targetUrl.indexOf('/') + 1) else targetUrl
                val cleanUrlUnderscore = cleanUrl.replace(" ", "_")
                
                // Add common variants
                searchKeys.add("A/$cleanUrl")
                searchKeys.add("a/$cleanUrl")
                searchKeys.add("A/$cleanUrlUnderscore")
                searchKeys.add("a/$cleanUrlUnderscore")
                searchKeys.add(targetUrl.replace(" ", "_"))
                
                // Support category namespace specifically
                if (targetUrl.startsWith("C/", true)) {
                    searchKeys.add("C/$cleanUrlUnderscore")
                    searchKeys.add("c/$cleanUrlUnderscore")
                }
                
                for (key in searchKeys) {
                    var low = 0
                    var high = header.articleCount - 1
                    
                    while (low <= high) {
                        val mid = (low + high) ushr 1
                        source.seek(header.urlPtrPos + mid * 8L)
                        val entryOffset = readLELong(source)
                        val entry = readDirectoryEntry(source, entryOffset)
                        
                        val entryFullPath = "${entry.namespace}/${entry.url}"
                        // Use case-sensitive comparison first as ZIM index is sorted that way
                        val comp = entryFullPath.compareTo(key)
                        
                        if (comp == 0) {
                            val html = getHtmlForArticle(source, entry, header)
                            if (html.isNotEmpty() && !html.contains("Ошибка:")) return html
                            errorLog.add("Найдена запись по ключу '$key', но контент пуст или поврежден: $html")
                        }
                        if (comp < 0) {
                            low = mid + 1
                        } else {
                            high = mid - 1
                        }
                    }
                    
                    // Fallback: Case-insensitive search if case-sensitive failed (some ZIMs might be weird)
                    low = 0
                    high = header.articleCount - 1
                    while (low <= high) {
                        val mid = (low + high) ushr 1
                        source.seek(header.urlPtrPos + mid * 8L)
                        val entryOffset = try { readLELong(source) } catch (e: Exception) { -1L }
                        if (entryOffset == -1L) { low = mid + 1; continue }
                        val entry = readDirectoryEntry(source, entryOffset)
                        val entryFullPath = "${entry.namespace}/${entry.url}"
                        val comp = entryFullPath.compareTo(key, ignoreCase = true)
                        if (comp == 0) {
                            val html = getHtmlForArticle(source, entry, header)
                            if (html.isNotEmpty() && !html.contains("Ошибка:")) return html
                            errorLog.add("Найдена запись (insensitive) по ключу '$key', но контент пуст: $html")
                            break // Success or at least found the entry
                        }
                        if (comp < 0) low = mid + 1 else high = mid - 1
                    }
                }
                
                // Direct fallback binary search by rawUrl
                var low = 0
                var high = header.articleCount - 1
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    source.seek(header.urlPtrPos + mid * 8L)
                    val entryOffset = try { readLELong(source) } catch (e: Exception) { -1L }
                    if (entryOffset == -1L) { low = mid + 1; continue }
                    val entry = readDirectoryEntry(source, entryOffset)
                    
                    val comp = entry.url.compareTo(cleanUrl, ignoreCase = true)
                    if (comp < 0) {
                        low = mid + 1
                    } else if (comp > 0) {
                        high = mid - 1
                    } else {
                        val html = getHtmlForArticle(source, entry, header)
                        if (html.isNotEmpty() && !html.contains("Ошибка:")) return html
                        errorLog.add("Найдена запись (exact URL) по '${entry.url}', но контент пуст: $html")
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("ZimReader", "getHtmlByUrl failed for url=$targetUrl in $pathOrUri", e)
            return "<h3>Критическая ошибка чтения</h3><p>${e.message}</p>"
        }
        
        return buildString {
            append("<h3>Статья не найдена</h3>")
            append("<p>Не удалось найти ресурс: <b>$targetUrl</b></p>")
            if (errorLog.isNotEmpty()) {
                append("<p>Журнал попыток:</p><ul>")
                errorLog.forEach { append("<li>$it</li>") }
                append("</ul>")
            }
            append("<p>Возможные причины: ресурс отсутствует в данном архиве или указан неверный путь.</p>")
        }
    }

    fun searchArticlesInZim(context: Context, pathOrUri: String, query: String, archiveId: String, archiveTitle: String, limit: Int = 40): List<ArticleEntity> {
        val results = ArrayList<ArticleEntity>(limit)
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return emptyList()

        try {
            openSource(context, pathOrUri).use { source ->
                val header = readHeader(source)
                
                // Binary search by TITLE-index instead of URL-index
                var low = 0
                var high = header.articleCount - 1
                var bestMatchIdx = -1

                while (low <= high) {
                    val mid = (low + high) ushr 1
                    source.seek(header.titlePtrPos + mid * 4L)
                    val articleIdx = readLEInt(source).toLong() and 0xFFFFFFFFL
                    source.seek(header.urlPtrPos + articleIdx * 8L)
                    val entryOffset = readLELong(source)
                    val entry = readDirectoryEntry(source, entryOffset)

                    val titleLower = entry.title.lowercase()
                    val comp = titleLower.compareTo(cleanQuery)
                    when {
                        comp >= 0 -> { bestMatchIdx = mid; high = mid - 1 }
                        else -> low = mid + 1
                    }
                }

                // Scan forward from found position
                val startIdx = if (bestMatchIdx != -1) bestMatchIdx else 0
                for (i in startIdx until minOf(startIdx + 500, header.articleCount)) {
                    if (results.size >= limit) break
                    source.seek(header.titlePtrPos + i * 4L)
                    val articleIdx = readLEInt(source).toLong() and 0xFFFFFFFFL
                    source.seek(header.urlPtrPos + articleIdx * 8L)
                    val entryOffset = readLELong(source)
                    val entry = readDirectoryEntry(source, entryOffset)

                    if (!entry.title.lowercase().startsWith(cleanQuery)) break // out of bounds

                    if (entry.namespace == 'A' || entry.namespace == 'a' || entry.namespace == 'C' || entry.namespace == 'c') {
                        results.add(ArticleEntity(
                            id = "${archiveId}_${entry.namespace}_${entry.url}",
                            archiveId = archiveId,
                            archiveTitle = archiveTitle,
                            url = "${entry.namespace}/${entry.url}",
                            title = entry.title,
                            category = if (entry.namespace == 'C' || entry.namespace == 'c') "Категория" else "Статья",
                            excerpt = "Найдено в $archiveTitle",
                            htmlContent = "",
                            isFeedCandidate = false
                        ))
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        
        return results
    }

    fun getRandomArticlesForFeed(context: Context, pathOrUri: String, header: ZimHeader, archiveId: String, archiveTitle: String, limit: Int): List<ArticleEntity> {
        val result = ArrayList<ArticleEntity>()
        val totalCount = header.articleCount
        if (totalCount <= 0) return emptyList()
        
        try {
            openSource(context, pathOrUri).use { source ->
                val startIdx = if (totalCount > limit * 8) {
                    (0 until (totalCount - limit * 8)).random().toLong()
                } else {
                    0L
                }
                
                var index = startIdx
                var checked = 0
                while (result.size < limit && checked < limit * 100 && index < totalCount) {
                    checked++
                    source.seek(header.urlPtrPos + index * 8L)
                    val entryOffset = readLELong(source)
                    val entry = readDirectoryEntry(source, entryOffset)
                    index++
                    
                    val isSupportedNamespace = entry.namespace == 'A' || entry.namespace == 'a' || 
                            entry.namespace == 'C' || entry.namespace == 'c' || 
                            entry.namespace == '\u0000' || entry.namespace == '-' || entry.namespace == ' '
                    val isNotRedirect = entry.mimeType != 0xFFFF
                    val isOkTitle = entry.title.isNotEmpty() &&
                            !entry.title.startsWith("Category:") &&
                            !entry.title.startsWith("Категория:") &&
                            !entry.title.startsWith("Шаблон:") &&
                            !entry.title.startsWith("Template:") &&
                            !entry.title.startsWith("File:") &&
                            !entry.title.startsWith("Файл:") &&
                            !entry.title.startsWith("MediaWiki:") &&
                            !entry.title.startsWith("Portal:") &&
                            !entry.title.startsWith("Портал:")
                    
                    if (isSupportedNamespace && isNotRedirect && isOkTitle) {
                        val correctUrl = "${entry.namespace}/${entry.url}"
                        result.add(
                            ArticleEntity(
                                id = "${archiveId}_${entry.namespace}_${entry.url}",
                                archiveId = archiveId,
                                archiveTitle = archiveTitle,
                                url = correctUrl,
                                title = entry.title,
                                category = if (entry.namespace == 'C' || entry.namespace == 'c') "Категория" else "Статья",
                                excerpt = "Откройте для чтения из $archiveTitle",
                                htmlContent = "",
                                isFeedCandidate = true
                            )
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return result
    }

    /**
     * Индексирует все статьи из ZIM-архива, вызывая onBatch каждые batchSize статей.
     * Читает только заголовок (title) и URL — не читает HTML-контент на больших базах.
     */
    suspend fun indexAllArticles(
        context: Context,
        pathOrUri: String,
        archiveId: String,
        archiveTitle: String,
        batchSize: Int = 1000,
        extractExcerpts: Boolean = false,
        onBatch: suspend (List<ArticleEntity>) -> Unit,
        onProgress: suspend (indexed: Int, total: Int) -> Unit
    ) {
        try {
            openSource(context, pathOrUri).use { source ->
                val header = readHeader(source)
                val total = header.articleCount
                val batch = ArrayList<ArticleEntity>(batchSize)
                
                val shouldExtractExcerpts = extractExcerpts

                for (i in 0 until total) {
                    if (i % 1000 == 0) {
                        onProgress(i, total)
                        if (!currentCoroutineContext().isActive) break
                    }

                    source.seek(header.urlPtrPos + i * 8L)
                    val entryOffset = readLELong(source)
                    val entry = try {
                        readDirectoryEntry(source, entryOffset)
                    } catch (e: Exception) {
                        continue
                    }

                    // Пропускаем не-статьи (разрешаем 'A', 'a', 'C', 'c', '\u0000', '-', ' ')
                    val isArticleNamespace = entry.namespace == 'A' || entry.namespace == 'a' || 
                            entry.namespace == 'C' || entry.namespace == 'c' || 
                            entry.namespace == '\u0000' || entry.namespace == '-' || entry.namespace == ' '
                    val isNotRedirect = entry.mimeType != 0xFFFF
                    if (!isArticleNamespace || !isNotRedirect) continue
                    if (entry.title.isEmpty()) continue
                    
                    val isOkTitle = !entry.title.startsWith("Category:") &&
                            !entry.title.startsWith("Категория:") &&
                            !entry.title.startsWith("Шаблон:") &&
                            !entry.title.startsWith("Template:") &&
                            !entry.title.startsWith("File:") &&
                            !entry.title.startsWith("Файл:") &&
                            !entry.title.startsWith("MediaWiki:") &&
                            !entry.title.startsWith("Portal:") &&
                            !entry.title.startsWith("Портал:")

                    if (!isOkTitle) continue

                    // Читаем короткий excerpt (~150 символов) если база небольшая, иначе берем быстрый шаблон
                    var fullContent = ""
                    val excerpt = if (shouldExtractExcerpts) {
                        try {
                            fullContent = extractFullText(source, entry, header)
                            if (fullContent.length > 150) fullContent.take(150) + "…" else fullContent
                        } catch (e: Exception) {
                            ""
                        }
                    } else {
                        "Статья из архива: ${entry.title}"
                    }

                    val correctUrl = "${entry.namespace}/${entry.url}"

                    batch.add(
                        ArticleEntity(
                            id = "${archiveId}_${entry.namespace}_${entry.url}",
                            archiveId = archiveId,
                            archiveTitle = archiveTitle,
                            url = correctUrl,
                            title = entry.title,
                            category = "Статья",
                            excerpt = excerpt.ifBlank { "Статья из архива: ${entry.title}" },
                            htmlContent = fullContent, // Текст для индексации (если shouldExtractExcerpts)
                            isFeedCandidate = true
                        )
                    )

                    if (batch.size >= batchSize) {
                        onBatch(ArrayList(batch))
                        batch.clear()
                    }
                }

                // Последний батч
                if (batch.isNotEmpty()) {
                    onBatch(batch)
                }

                onProgress(total, total)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * Читает HTML-контент статьи и переводит его в чистый текст для индексации.
     */
    private fun extractFullText(
        source: ZimSource,
        entry: DirectoryEntry,
        header: ZimHeader,
        limit: Int = 100000 // До 100к символов для индексации
    ): String {
        if (entry.clusterNumber < 0 || entry.blobNumber < 0) return ""

        val html = try {
            getHtmlForArticle(source, entry, header)
        } catch (t: Throwable) {
            ""
        }
        if (html.isEmpty()) return ""

        return try {
            val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.length > limit) text.take(limit) else text
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Читает только начало HTML-контента статьи и вырезает первые maxChars символов текста.
     */
    private fun extractExcerpt(
        source: ZimSource,
        entry: DirectoryEntry,
        header: ZimHeader,
        maxChars: Int
    ): String {
        if (entry.clusterNumber < 0 || entry.blobNumber < 0) return ""

        val html = try {
            getHtmlForArticle(source, entry, header)
        } catch (t: Throwable) {
            ""
        }
        if (html.isEmpty()) return ""

        return try {
            val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.length > maxChars) text.take(maxChars) + "…" else text
        } catch (t: Throwable) {
            ""
        }
    }
}
