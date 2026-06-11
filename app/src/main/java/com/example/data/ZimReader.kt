package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.luben.zstd.ZstdInputStream
import org.tukaani.xz.LZMA2InputStream
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

    private fun openSource(context: Context, pathOrUri: String): ZimSource {
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

    fun decompressCluster(source: ZimSource, clusterOffset: Long, clusterSize: Long): DecompressedCluster {
        if (clusterSize <= 0) return DecompressedCluster(ByteArray(0), false)
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
            when (compressionType) {
                0, 1 -> bais // None / Legacy None
                2 -> InflaterInputStream(bais) // Zlib/deflate
                4 -> LZMA2InputStream(bais, 8192) // LZMA2
                5 -> ZstdInputStream(bais) // Zstandard: primary in ZIM v5/v6
                else -> {
                    // Try Zstd as fallback if unknown, as it is most common in modern ZIMs
                    try {
                        ZstdInputStream(ByteArrayInputStream(compressedBytes))
                    } catch (e: Exception) {
                        bais
                    }
                }
            }
        } catch (t: Throwable) {
            throw IOException("Error initializing decompression stream ($compressionType): ${t.message}", t)
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
            throw IOException("Error reading decompressed data: ${t.message}", t)
        } finally {
            try { decompressedStream.close() } catch (e: Exception) {}
        }
        return DecompressedCluster(baos.toByteArray(), isExtended)
    }

    fun getHtmlForArticle(source: ZimSource, entry: DirectoryEntry, header: ZimHeader, depth: Int = 0): String {
        if (depth > 10) return "" // Prevent infinite redirection
        if (entry.mimeType == 0xFFFF) {
            source.seek(header.urlPtrPos + entry.redirectIndex * 8L)
            val targetOffset = readLELong(source)
            if (targetOffset < 0 || targetOffset > source.length()) return ""
            val targetEntry = readDirectoryEntry(source, targetOffset)
            return getHtmlForArticle(source, targetEntry, header, depth + 1)
        }
        
        if (entry.clusterNumber < 0 || entry.blobNumber < 0 || entry.clusterNumber >= header.clusterCount) return ""
        
        val fileSize = source.length()
        val (clusterOffset, clusterSize) = getClusterOffsetAndSize(source, header, entry.clusterNumber, fileSize)
        
        if (clusterOffset < 0 || clusterOffset + clusterSize > fileSize) return ""
        
        val cluster = decompressCluster(source, clusterOffset, clusterSize)
        val clusterBytes = cluster.data
        
        if (clusterBytes.size < 4) return ""
        
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
            return ""
        }
        
        val size = endOff - startOff
        
        if (size <= 0 || startOff < 0 || startOff + size > clusterBytes.size) return ""
        
        return try {
            String(clusterBytes, startOff, size, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun getHtmlByUrl(context: Context, pathOrUri: String, targetUrl: String): String {
        try {
            openSource(context, pathOrUri).use { source ->
                val header = readHeader(source)
                
                // We'll prepare multiple possible target search keys to make it extremely robust.
                val searchKeys = LinkedHashSet<String>()
                searchKeys.add(targetUrl) // e.g., "A/нварский_дождь"
                
                // Extract clean URL (without namespace)
                val cleanUrl = if (targetUrl.contains("/")) targetUrl.substring(targetUrl.indexOf('/') + 1) else targetUrl
                
                // Add common variants
                searchKeys.add("A/$cleanUrl")
                searchKeys.add("a/$cleanUrl")
                
                for (key in searchKeys) {
                    var low = 0
                    var high = header.articleCount - 1
                    
                    while (low <= high) {
                        val mid = (low + high) ushr 1
                        source.seek(header.urlPtrPos + mid * 8L)
                        val entryOffset = readLELong(source)
                        val entry = readDirectoryEntry(source, entryOffset)
                        
                        val entryFullPath = "${entry.namespace}/${entry.url}"
                        val comp = entryFullPath.compareTo(key, ignoreCase = true)
                        
                        if (comp < 0) {
                            low = mid + 1
                        } else if (comp > 0) {
                            high = mid - 1
                        } else {
                            val html = getHtmlForArticle(source, entry, header)
                            if (html.isNotEmpty()) return html
                        }
                    }
                }
                
                // Direct fallback binary search by rawUrl
                var low = 0
                var high = header.articleCount - 1
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    source.seek(header.urlPtrPos + mid * 8L)
                    val entryOffset = readLELong(source)
                    val entry = readDirectoryEntry(source, entryOffset)
                    
                    val comp = entry.url.compareTo(cleanUrl, ignoreCase = true)
                    if (comp < 0) {
                        low = mid + 1
                    } else if (comp > 0) {
                        high = mid - 1
                    } else {
                        val html = getHtmlForArticle(source, entry, header)
                        if (html.isNotEmpty()) return html
                    }
                }
                
                // Ultimate linear scan backup for small archives
                if (header.articleCount < 5000) {
                    for (i in 0 until header.articleCount) {
                        source.seek(header.urlPtrPos + i * 8L)
                        val entryOffset = try { readLELong(source) } catch (e: Throwable) { continue }
                        val entry = try { readDirectoryEntry(source, entryOffset) } catch (e: Throwable) { continue }
                        val rawUrl = entry.url
                        
                        val cleanRawUrl = if (rawUrl.contains("/")) rawUrl.substring(rawUrl.indexOf('/') + 1) else rawUrl
                        if (cleanRawUrl.equals(cleanUrl, ignoreCase = true)) {
                            val html = getHtmlForArticle(source, entry, header)
                            if (html.isNotEmpty()) return html
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("ZimReader", "getHtmlByUrl failed for url=$targetUrl in $pathOrUri", e)
        }
        return ""
    }

    fun searchArticlesInZim(context: Context, pathOrUri: String, query: String, archiveId: String, archiveTitle: String, limit: Int = 40): List<ArticleEntity> {
        val results = ArrayList<ArticleEntity>()
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return emptyList()
        
        try {
            openSource(context, pathOrUri).use { source ->
                val header = readHeader(source)
                var low = 0
                var high = header.articleCount - 1
                var bestMatchIdx = -1
                
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    source.seek(header.urlPtrPos + mid * 8L)
                    val entryOffset = readLELong(source)
                    val entry = readDirectoryEntry(source, entryOffset)
                    val url = entry.url.lowercase()
                    
                    val cleanUrl = if (url.startsWith("a/")) url.substring(2) else url
                    val comp = cleanUrl.compareTo(cleanQuery)
                    if (comp >= 0) {
                        bestMatchIdx = mid
                        high = mid - 1
                    } else {
                        low = mid + 1
                    }
                }
                
                val startIdx = if (bestMatchIdx != -1) bestMatchIdx else 0
                var i = startIdx
                val maxScan = 2000
                var scanned = 0
                while (i < header.articleCount && results.size < limit && scanned < maxScan) {
                    scanned++
                    source.seek(header.urlPtrPos + i * 8L)
                    val entryOffset = readLELong(source)
                    val entry = readDirectoryEntry(source, entryOffset)
                    i++
                    
                    if (entry.namespace == 'A' && entry.mimeType != 0xFFFF && entry.title.isNotEmpty()) {
                        val titleLower = entry.title.lowercase()
                        val urlLower = entry.url.lowercase()
                        
                        if (titleLower.contains(cleanQuery) || urlLower.contains(cleanQuery)) {
                            val correctUrl = "${entry.namespace}/${entry.url}"
                            results.add(
                                ArticleEntity(
                                    id = "${archiveId}_${entry.url}",
                                    archiveId = archiveId,
                                    archiveTitle = archiveTitle,
                                    url = correctUrl,
                                    title = entry.title,
                                    category = "Статья",
                                    excerpt = "Статья по запросу из $archiveTitle",
                                    htmlContent = "",
                                    isFeedCandidate = false
                                )
                            )
                        }
                    }
                }
                
                if (results.size < limit) {
                    var j = startIdx - 1
                    var scannedBack = 0
                    while (j >= 0 && results.size < limit && scannedBack < 1000) {
                        scannedBack++
                        source.seek(header.urlPtrPos + j * 8L)
                        val entryOffset = readLELong(source)
                        val entry = readDirectoryEntry(source, entryOffset)
                        j--
                        
                        if (entry.namespace == 'A' && entry.mimeType != 0xFFFF && entry.title.isNotEmpty()) {
                            val titleLower = entry.title.lowercase()
                            val urlLower = entry.url.lowercase()
                            
                            if (titleLower.contains(cleanQuery) || urlLower.contains(cleanQuery)) {
                                val correctUrl = "${entry.namespace}/${entry.url}"
                                results.add(
                                    ArticleEntity(
                                        id = "${archiveId}_${entry.url}",
                                        archiveId = archiveId,
                                        archiveTitle = archiveTitle,
                                        url = correctUrl,
                                        title = entry.title,
                                        category = "Статья",
                                        excerpt = "Статья по запросу из $archiveTitle",
                                        htmlContent = "",
                                        isFeedCandidate = false
                                    )
                                )
                            }
                        }
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
                    
                    val isArticleNamespace = entry.namespace == 'A' || entry.namespace == 'a'
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
                    
                    if (isArticleNamespace && isNotRedirect && isOkTitle) {
                        val correctUrl = "${entry.namespace}/${entry.url}"
                        result.add(
                            ArticleEntity(
                                id = "${archiveId}_${entry.url}",
                                archiveId = archiveId,
                                archiveTitle = archiveTitle,
                                url = correctUrl,
                                title = entry.title,
                                category = "Статья",
                                excerpt = "Откройте для чтения статью из $archiveTitle",
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
        onBatch: suspend (List<ArticleEntity>) -> Unit,
        onProgress: suspend (indexed: Int, total: Int) -> Unit
    ) {
        try {
            openSource(context, pathOrUri).use { source ->
                val header = readHeader(source)
                val total = header.articleCount
                val batch = ArrayList<ArticleEntity>(batchSize)
                
                // Dynamic decision on whether to extract excerpts during indexing to prevent severe disk IO thrashing on massive ZIMs
                val shouldExtractExcerpts = total <= 30000

                for (i in 0 until total) {
                    if (i % 1000 == 0) {
                        onProgress(i, total)
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
                    val excerpt = if (shouldExtractExcerpts) {
                        try {
                            extractExcerpt(source, entry, header, maxChars = 150)
                        } catch (e: Exception) {
                            ""
                        }
                    } else {
                        "Статья из архива: ${entry.title}"
                    }

                    val correctUrl = "${entry.namespace}/${entry.url}"

                    batch.add(
                        ArticleEntity(
                            id = "${archiveId}_${entry.url}",
                            archiveId = archiveId,
                            archiveTitle = archiveTitle,
                            url = correctUrl,
                            title = entry.title,
                            category = "Статья",
                            excerpt = excerpt.ifBlank { "Статья из архива: ${entry.title}" },
                            htmlContent = "", // HTML грузится лениво при открытии
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
