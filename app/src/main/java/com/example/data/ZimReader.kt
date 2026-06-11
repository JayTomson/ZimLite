package com.example.data

import android.content.Context
import android.net.Uri
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
        source.seek(0)
        val bytes = ByteArray(72)
        source.readFully(bytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
        val magic = buf.getInt()          // 0-4
        val version = buf.getInt()        // 4-8
        val uuid = ByteArray(16)
        buf.get(uuid)                     // 8-24
        val articleCount = buf.getInt()   // 24-28
        val clusterCount = buf.getInt()   // 28-32
        val urlPtrPos = buf.getLong()     // 32-40
        val titlePtrPos = buf.getLong()   // 40-48
        val clusterPtrPos = buf.getLong() // 48-56
        val mimeListPos = buf.getLong()   // 56-64
        val mainPage = buf.getInt()       // 64-68
        val layoutPage = buf.getInt()     // 68-72
        
        return ZimHeader(
            magic = magic,
            version = version,
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
    }

    fun getUrlOffset(source: ZimSource, entryOffset: Long, namespaceChar: Char): Int {
        try {
            // Modern ZIM v6 almost always has a 4-byte revision field.
            // We search for the namespace character at offset 4 or 8 to decide.
            source.seek(entryOffset + 4)
            val b4 = source.read()
            val b5 = source.read()
            
            source.seek(entryOffset + 8)
            val b8 = source.read()
            val b9 = source.read()

            // If we see "X/" where X is the namespace, we're very confident
            if (b4 == namespaceChar.code && b5 == '/'.code) return 4
            if (b8 == namespaceChar.code && b9 == '/'.code) return 8

            // Match only namespace char (could be "XArticleTitle")
            if (b4 == namespaceChar.code) return 4
            if (b8 == namespaceChar.code) return 8

            // Printable ASCII or UTF-8 start at offset 4 suggests no revision
            if (b4 in 0x20..0x7E || b4 >= 0xC0) return 4
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 8 // Default to revision present
    }

    fun readDirectoryEntry(source: ZimSource, entryOffset: Long): DirectoryEntry {
        source.seek(entryOffset)
        
        val b1 = source.read()
        val b2 = source.read()
        val mimeType = (b2 shl 8) or b1
        
        val parameterLen = source.read()
        val namespace = source.read().toChar()
        
        val urlOffset = getUrlOffset(source, entryOffset, namespace)
        source.seek(entryOffset + urlOffset.toLong())
        
        val url = readNullTerminatedString(source)
        val title = readNullTerminatedString(source) // Title follows URL
        
        var clusterNumber = -1
        var blobNumber = -1
        var redirectIndex = -1
        
        if (mimeType == 0xFFFF) {
            redirectIndex = readLEInt(source)
        } else {
            clusterNumber = readLEInt(source)
            blobNumber = readLEInt(source)
        }
        
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
        source.seek(header.clusterPtrPos + clusterNumber * 8)
        val clusterOffset = readLELong(source)
        
        val nextClusterOffset = if (clusterNumber < header.clusterCount - 1) {
            source.seek(header.clusterPtrPos + (clusterNumber + 1) * 8)
            readLELong(source)
        } else {
            if (header.mimeListPos > clusterOffset) header.mimeListPos else fileSize
        }
        
        return Pair(clusterOffset, nextClusterOffset - clusterOffset)
    }

    fun decompressCluster(source: ZimSource, clusterOffset: Long, clusterSize: Long): ByteArray {
        source.seek(clusterOffset)
        val compressionType = source.read()
        
        val compressedDataSize = clusterSize - 1
        if (compressedDataSize <= 0) return ByteArray(0)
        
        val compressedBytes = ByteArray(compressedDataSize.toInt())
        source.readFully(compressedBytes)
        
        val bais = ByteArrayInputStream(compressedBytes)
        val decompressedStream = when (compressionType) {
            0, 1 -> bais
            2 -> InflaterInputStream(bais)
            4 -> LZMA2InputStream(bais, 8192)
            5 -> ZstdInputStream(bais)
            else -> throw IOException("Unsupported compression type: $compressionType")
        }
        
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(32768)
        var read: Int
        while (decompressedStream.read(buffer).also { read = it } != -1) {
            baos.write(buffer, 0, read)
        }
        decompressedStream.close()
        return baos.toByteArray()
    }

    fun getHtmlForArticle(context: Context, pathOrUri: String, entry: DirectoryEntry, header: ZimHeader): String {
        if (entry.mimeType == 0xFFFF) {
            openSource(context, pathOrUri).use { source ->
                source.seek(header.urlPtrPos + entry.redirectIndex * 8)
                val targetOffset = readLELong(source)
                val targetEntry = readDirectoryEntry(source, targetOffset)
                return getHtmlForArticle(context, pathOrUri, targetEntry, header)
            }
        }
        
        if (entry.clusterNumber < 0 || entry.blobNumber < 0) return ""
        
        openSource(context, pathOrUri).use { source ->
            val (clusterOffset, clusterSize) = getClusterOffsetAndSize(source, header, entry.clusterNumber, source.length())
            val clusterBytes = decompressCluster(source, clusterOffset, clusterSize)
            
            if (clusterBytes.isEmpty()) return ""
            
            val buf = ByteBuffer.wrap(clusterBytes).order(ByteOrder.LITTLE_ENDIAN)
            val firstPointer = buf.getInt(0)
            val numBlobs = firstPointer / 4
            
            if (entry.blobNumber >= numBlobs) return ""
            
            val offsets = IntArray(numBlobs + 1)
            for (i in 0..numBlobs) {
                offsets[i] = buf.getInt(i * 4)
            }
            
            val startOff = offsets[entry.blobNumber]
            val endOff = offsets[entry.blobNumber + 1]
            val size = endOff - startOff
            
            if (size <= 0 || startOff + size > clusterBytes.size) return ""
            
            return String(clusterBytes, startOff, size, Charsets.UTF_8)
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
                        source.seek(header.urlPtrPos + mid * 8)
                        val entryOffset = readLELong(source)
                        
                        // Read namespace at offset 3 of directory entry
                        source.seek(entryOffset + 3)
                        val nsChar = source.read().toChar()
                        
                        // Dynamically locate and seek to the correct URL offset based on whether revision field is present
                        val urlOffset = getUrlOffset(source, entryOffset, nsChar)
                        source.seek(entryOffset + urlOffset)
                        val rawUrl = readNullTerminatedString(source)
                        
                        // Construct comparison key matching namespace + "/" + rawUrl structure
                        val entryUrlNormalized = if (rawUrl.startsWith("$nsChar/", ignoreCase = true)) {
                            rawUrl
                        } else if (rawUrl.startsWith("$nsChar", ignoreCase = true)) {
                            "$nsChar/${rawUrl.substring(1)}"
                        } else {
                            "$nsChar/$rawUrl"
                        }
                        
                        val comp = entryUrlNormalized.compareTo(key, ignoreCase = true)
                        if (comp < 0) {
                            low = mid + 1
                        } else if (comp > 0) {
                            high = mid - 1
                        } else {
                            val entry = readDirectoryEntry(source, entryOffset)
                            val html = getHtmlForArticle(context, pathOrUri, entry, header)
                            if (html.isNotEmpty()) return html
                        }
                    }
                }
                
                // Direct fallback: if binary search fails because of some weird encoding/sorting mismatch,
                // try to query directly by comparing rawUrl and cleanUrl
                var low = 0
                var high = header.articleCount - 1
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    source.seek(header.urlPtrPos + mid * 8)
                    val entryOffset = readLELong(source)
                    
                    source.seek(entryOffset + 3)
                    val nsChar = source.read().toChar()
                    val urlOffset = getUrlOffset(source, entryOffset, nsChar)
                    source.seek(entryOffset + urlOffset)
                    val rawUrl = readNullTerminatedString(source)
                    
                    val comp = rawUrl.compareTo(cleanUrl, ignoreCase = true)
                    if (comp < 0) {
                        low = mid + 1
                    } else if (comp > 0) {
                        high = mid - 1
                    } else {
                        val entry = readDirectoryEntry(source, entryOffset)
                        val html = getHtmlForArticle(context, pathOrUri, entry, header)
                        if (html.isNotEmpty()) return html
                    }
                }
                
                // Ultimate linear scan backup for a small number of items if binary search inexplicably fails
                // (Only do this for small archives or up to 2000 items to avoid freezing)
                if (header.articleCount < 5000) {
                    for (mid in 0 until header.articleCount) {
                        source.seek(header.urlPtrPos + mid * 8L)
                        val entryOffset = readLELong(source)
                        
                        source.seek(entryOffset + 3)
                        val nsChar = source.read().toChar()
                        val urlOffset = getUrlOffset(source, entryOffset, nsChar)
                        source.seek(entryOffset + urlOffset)
                        val rawUrl = readNullTerminatedString(source)
                        
                        val cleanRawUrl = if (rawUrl.contains("/")) rawUrl.substring(rawUrl.indexOf('/') + 1) else rawUrl
                        if (cleanRawUrl.equals(cleanUrl, ignoreCase = true)) {
                            val entry = readDirectoryEntry(source, entryOffset)
                            val html = getHtmlForArticle(context, pathOrUri, entry, header)
                            if (html.isNotEmpty()) return html
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                    source.seek(header.urlPtrPos + mid * 8)
                    val entryOffset = readLELong(source)
                    
                    source.seek(entryOffset + 3)
                    val nsChar = source.read().toChar()
                    val urlOffset = getUrlOffset(source, entryOffset, nsChar)
                    source.seek(entryOffset + urlOffset)
                    val url = readNullTerminatedString(source).lowercase()
                    
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
                    source.seek(header.urlPtrPos + i * 8)
                    val entryOffset = readLELong(source)
                    val entry = readDirectoryEntry(source, entryOffset)
                    i++
                    
                    if (entry.namespace == 'A' && entry.mimeType != 0xFFFF && entry.title.isNotEmpty()) {
                        val titleLower = entry.title.lowercase()
                        val urlLower = entry.url.lowercase()
                        
                        val entryUrl = entry.url
                        val correctUrl = if (entryUrl.startsWith("${entry.namespace}/", ignoreCase = true)) {
                            entryUrl
                        } else if (entryUrl.startsWith("${entry.namespace}", ignoreCase = true)) {
                            "${entry.namespace}/${entryUrl.substring(1)}"
                        } else {
                            "${entry.namespace}/$entryUrl"
                        }
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
                
                if (results.size < limit) {
                    var j = startIdx - 1
                    var scannedBack = 0
                    while (j >= 0 && results.size < limit && scannedBack < 1000) {
                        scannedBack++
                        source.seek(header.urlPtrPos + j * 8)
                        val entryOffset = readLELong(source)
                        val entry = readDirectoryEntry(source, entryOffset)
                        j--
                        
                        if (entry.namespace == 'A' && entry.mimeType != 0xFFFF && entry.title.isNotEmpty()) {
                            val titleLower = entry.title.lowercase()
                            val urlLower = entry.url.lowercase()
                            
                            val entryUrl = entry.url
                            val correctUrl = if (entryUrl.startsWith("${entry.namespace}/", ignoreCase = true)) {
                                entryUrl
                            } else if (entryUrl.startsWith("${entry.namespace}", ignoreCase = true)) {
                                "${entry.namespace}/${entryUrl.substring(1)}"
                            } else {
                                "${entry.namespace}/$entryUrl"
                            }
                            if (titleLower.contains(cleanQuery) || urlLower.contains(cleanQuery)) {
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
        } catch (e: Exception) {
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
                    source.seek(header.urlPtrPos + index * 8)
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
                        val entryUrl = entry.url
                        val correctUrl = if (entryUrl.startsWith("${entry.namespace}/", ignoreCase = true)) {
                            entryUrl
                        } else if (entryUrl.startsWith("${entry.namespace}", ignoreCase = true)) {
                            "${entry.namespace}/${entryUrl.substring(1)}"
                        } else {
                            "${entry.namespace}/$entryUrl"
                        }
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
        } catch (e: Exception) {
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
                            extractExcerpt(context, pathOrUri, entry, header, maxChars = 150)
                        } catch (e: Exception) {
                            ""
                        }
                    } else {
                        "Статья из архива: ${entry.title}"
                    }

                    val entryUrl = entry.url
                    val correctUrl = if (entryUrl.startsWith("${entry.namespace}/", ignoreCase = true)) {
                        entryUrl
                    } else if (entryUrl.startsWith("${entry.namespace}", ignoreCase = true)) {
                        "${entry.namespace}/${entryUrl.substring(1)}"
                    } else {
                        "${entry.namespace}/$entryUrl"
                    }

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Читает только начало HTML-контента статьи и вырезает первые maxChars символов текста.
     */
    private fun extractExcerpt(
        context: Context,
        pathOrUri: String,
        entry: DirectoryEntry,
        header: ZimHeader,
        maxChars: Int
    ): String {
        if (entry.clusterNumber < 0 || entry.blobNumber < 0) return ""

        val html = getHtmlForArticle(context, pathOrUri, entry, header)
        if (html.isEmpty()) return ""

        return try {
            val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.length > maxChars) text.take(maxChars) + "…" else text
        } catch (e: Exception) {
            ""
        }
    }
}
