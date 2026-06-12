package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val archiveDao = database.archiveDao()
    private val articleDao = database.articleDao()
    private val bookmarkDao = database.bookmarkDao()
    private val sharedPrefs = application.getSharedPreferences("kiwix_prefs", Context.MODE_PRIVATE)

    // Current app theme selection (default is DARK as specified)
    val appTheme = MutableStateFlow(AppTheme.DARK)
    val useOriginalHtml = MutableStateFlow(true)
    val searchInContent = MutableStateFlow(true)
    val deepIndexing = MutableStateFlow(true)
    val customZimDirPath = MutableStateFlow<String?>(null)

    // Navigation and screen state
    val currentTab = mutableStateOf(0) // 0 = Feed, 1 = Search, 2 = Bookmarks
    val activeArticle = mutableStateOf<ArticleEntity?>(null)
    val activeWebUrl = mutableStateOf<String?>(null)
    val insideSettings = mutableStateOf(false)
    val articleBackStack = mutableListOf<ArticleEntity>()

    // Database reactive flows
    val archives: StateFlow<List<ArchiveEntity>> = archiveDao.getAllArchives()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = bookmarkDao.getAllBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pull-to-refresh & custom random Feed
    private val _feedArticles = MutableStateFlow<List<ArticleEntity>>(emptyList())
    val feedArticles: StateFlow<List<ArticleEntity>> = _feedArticles.asStateFlow()

    // Search query implementation
    val searchQuery = MutableStateFlow("")
    
    // Dynamically filtered articles based on search query (returns empty list instantly if query is empty)
    val searchedArticles: StateFlow<List<ArticleEntity>> = combine(searchQuery, searchInContent) { query, inContent ->
        Pair(query.trim(), inContent)
    }
        .debounce(200)
        .distinctUntilChanged()
        .flatMapLatest { (cleanQuery, inContent) ->
            if (cleanQuery.isEmpty()) {
                flowOf(emptyList())
            } else {
                flow {
                    val tokens = cleanQuery.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    val ftsQueryString = tokens.joinToString(" ") { "\"$it\"*" }

                    val startsWithQ = "$cleanQuery%"
                    val exactWordQ = cleanQuery
                    val exactTitleQ = "%$cleanQuery%"

                    // Phase 1: INSTANT TITLE SEARCH
                    val titleResults = withContext(Dispatchers.IO) {
                        try {
                            if (tokens.isEmpty()) {
                                emptyList()
                            } else if (tokens.size <= 3) {
                                val w1 = "%${tokens[0]}%"
                                val w2 = if (tokens.size > 1) "%${tokens[1]}%" else null
                                val w3 = if (tokens.size > 2) "%${tokens[2]}%" else null
                                articleDao.searchArticlesByTitleMulti(w1, w2, w3)
                            } else {
                                emptyList()
                            }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    if (titleResults.isNotEmpty()) {
                        emit(titleResults)
                    }

                    // Phase 2: FULL CONTENT SEARCH IF ENABLED
                    if (inContent) {
                        val contentResults = withContext(Dispatchers.IO) {
                            try {
                                if (ftsQueryString.isEmpty()) emptyList()
                                else articleDao.searchArticlesFts(
                                    query = ftsQueryString,
                                    exactTitleQuery = exactTitleQ,
                                    startsWithQuery = startsWithQ,
                                    exactWord = exactWordQ,
                                    limit = 100
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                                articleDao.searchArticlesByTitle(exactTitleQ)
                            }
                        }
                        
                        val merged = (titleResults + contentResults).distinctBy { it.id }
                        emit(merged)

                        // Fuzzy Search Fallback if completely empty
                        if (merged.isEmpty() && cleanQuery.length >= 4) {
                            val fuzzyResults = withContext(Dispatchers.IO) {
                                val prefix = cleanQuery.take(3)
                                val candidates = articleDao.searchArticlesByTitle("%$prefix%")
                                candidates.filter { article ->
                                    levenshtein(article.title.lowercase(), cleanQuery.lowercase()) <= 2
                                }.sortedBy { levenshtein(it.title.lowercase(), cleanQuery.lowercase()) }.take(20)
                            }
                            if (fuzzyResults.isNotEmpty()) {
                                emit(fuzzyResults)
                            } else {
                                // Transliteration search
                                val cyrillicQuery = transliterateToRussian(cleanQuery)
                                if (cyrillicQuery != cleanQuery) {
                                    val translitResults = withContext(Dispatchers.IO) {
                                        articleDao.searchArticlesByTitle("%$cyrillicQuery%")
                                    }
                                    if (translitResults.isNotEmpty()) {
                                        emit(translitResults.distinctBy { it.id })
                                    }
                                }
                            }
                        } else if (merged.isNotEmpty()) {
                            // Transliteration search even if we found something
                            val cyrillicQuery = transliterateToRussian(cleanQuery)
                            if (cyrillicQuery != cleanQuery) {
                                val translitResults = withContext(Dispatchers.IO) {
                                    articleDao.searchArticlesByTitle("%$cyrillicQuery%")
                                }
                                emit((merged + translitResults).distinctBy { it.id })
                            }
                        }
                    } else if (titleResults.isEmpty() && cleanQuery.length >= 4) {
                        // Fuzzy search fallback for title-only search
                        val fuzzyResults = withContext(Dispatchers.IO) {
                            val prefix = cleanQuery.take(3)
                            val candidates = articleDao.searchArticlesByTitle("%$prefix%")
                            candidates.filter { article ->
                                levenshtein(article.title.lowercase(), cleanQuery.lowercase()) <= 2
                            }.sortedBy { levenshtein(it.title.lowercase(), cleanQuery.lowercase()) }.take(20)
                        }
                        if (fuzzyResults.isNotEmpty()) {
                            emit(fuzzyResults)
                        } else {
                            val cyrillicQuery = transliterateToRussian(cleanQuery)
                            if (cyrillicQuery != cleanQuery) {
                                val translitResults = withContext(Dispatchers.IO) {
                                    articleDao.searchArticlesByTitle("%$cyrillicQuery%")
                                }
                                if (translitResults.isNotEmpty()) {
                                    emit(translitResults.distinctBy { it.id })
                                }
                            }
                        }
                    } else if (titleResults.isNotEmpty()) {
                        val cyrillicQuery = transliterateToRussian(cleanQuery)
                        if (cyrillicQuery != cleanQuery) {
                            val translitResults = withContext(Dispatchers.IO) {
                                articleDao.searchArticlesByTitle("%$cyrillicQuery%")
                            }
                            emit((titleResults + translitResults).distinctBy { it.id })
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val TRANSLIT = mapOf(
        "a" to "а", "b" to "б", "v" to "в", "g" to "г", "d" to "д",
        "e" to "е", "zh" to "ж", "z" to "з", "i" to "и", "k" to "к",
        "l" to "л", "m" to "м", "n" to "н", "o" to "о", "p" to "п",
        "r" to "р", "s" to "с", "t" to "т", "u" to "у", "f" to "ф",
        "kh" to "х", "ts" to "ц", "ch" to "ч", "sh" to "ш", "shch" to "щ",
        "ya" to "я", "yu" to "ю", "yo" to "ё"
    )

    private fun transliterateToRussian(input: String): String {
        var result = input.lowercase()
        TRANSLIT.entries.sortedByDescending { it.key.length }
            .forEach { (lat, cyr) -> result = result.replace(lat, cyr) }
        return result
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }

    // Bookmark check helper
    private val _bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedIds: StateFlow<Set<String>> = _bookmarkedIds.asStateFlow()

    // Download state engine
    val downloadProgress = MutableStateFlow<Float?>(null)
    val downloadSpeed = MutableStateFlow("")
    val bytesDownloadedLabel = MutableStateFlow("")
    val downloadArchiveName = MutableStateFlow("")
    val downloadError = MutableStateFlow<String?>(null)
    val isIndexing = MutableStateFlow(false)
    val indexingProgress = MutableStateFlow(0)
    val indexingTotal = MutableStateFlow(0)
    var downloadingArchiveId: String? = null
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var indexingJob: kotlinx.coroutines.Job? = null

    init {
        // Load persistent theme preference
        val savedThemeOrdinal = sharedPrefs.getInt("theme_key", AppTheme.DARK.ordinal)
        appTheme.value = AppTheme.values().getOrElse(savedThemeOrdinal) { AppTheme.DARK }
        useOriginalHtml.value = sharedPrefs.getBoolean("use_original_html", true)
        searchInContent.value = sharedPrefs.getBoolean("search_in_content", true)
        deepIndexing.value = sharedPrefs.getBoolean("deep_indexing", false)
        customZimDirPath.value = sharedPrefs.getString("custom_zim_dir", null)

        // Start observing bookmarks to keep track of bookmarked IDs
        viewModelScope.launch {
            bookmarkDao.getAllBookmarks().collect { list ->
                _bookmarkedIds.value = list.map { it.id }.toSet()
            }
        }

        // Auto-scan if custom path exists
        customZimDirPath.value?.let { scanLocalArchives(it) }

        // Initialize Feed reactively. If archives are empty, clear the feed, otherwise refresh with random entries.
        viewModelScope.launch {
            archives
                .map { it.size }
                .distinctUntilChanged()
                .collect { count ->
                    if (count == 0) {
                        _feedArticles.value = emptyList()
                    } else {
                        refreshFeed()
                    }
                }
        }
    }

    fun setAppTheme(theme: AppTheme) {
        viewModelScope.launch {
            appTheme.value = theme
            sharedPrefs.edit().putInt("theme_key", theme.ordinal).apply()
        }
    }

    fun setUseOriginalHtml(use: Boolean) {
        viewModelScope.launch {
            useOriginalHtml.value = use
            sharedPrefs.edit().putBoolean("use_original_html", use).apply()
        }
    }

    fun setSearchInContent(use: Boolean) {
        viewModelScope.launch {
            searchInContent.value = use
            sharedPrefs.edit().putBoolean("search_in_content", use).apply()
        }
    }

    fun setDeepIndexing(enabled: Boolean) {
        viewModelScope.launch {
            deepIndexing.value = enabled
            sharedPrefs.edit().putBoolean("deep_indexing", enabled).apply()
            if (enabled) {
                indexingJob?.cancel()
                reindexAllArchives()
            }
        }
    }

    fun setCustomZimDir(path: String?) {
        viewModelScope.launch {
            customZimDirPath.value = path
            sharedPrefs.edit().putString("custom_zim_dir", path).apply()
            if (path != null) {
                scanLocalArchives(path)
            }
        }
    }

    fun refreshCustomDir() {
        customZimDirPath.value?.let { scanLocalArchives(it) }
    }

    fun scanLocalArchives(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val treeUri = Uri.parse(uriString)
                
                // Persist permissions
                try {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Might already have it or fail if not from SAF
                }

                val documentTree = DocumentFile.fromTreeUri(context, treeUri)
                if (documentTree != null && documentTree.isDirectory) {
                    val zimFiles = documentTree.listFiles().filter { 
                        it.name?.lowercase()?.endsWith(".zim") == true 
                    }

                    val currentArchives = archiveDao.getAllArchives().first()

                    for (file in zimFiles) {
                        val fileName = file.name ?: continue
                        val filePath = file.uri.toString()
                        
                        // Check if already exists
                        val existing = currentArchives.find { it.filePath == filePath }
                        if (existing == null) {
                            // Read header to get title and count if possible
                            val (archiveTitle, articleCount) = try {
                                ZimReader.openSource(context, filePath).use { source ->
                                    val header = ZimReader.readHeader(source)
                                    Pair(fileName.substringBeforeLast("."), header.articleCount)
                                }
                            } catch (e: Exception) {
                                Pair(fileName.substringBeforeLast("."), 0)
                            }

                            val newArchive = ArchiveEntity(
                                id = UUID.randomUUID().toString(),
                                title = archiveTitle,
                                sourceUrl = "",
                                filePath = filePath,
                                fileSize = file.length(),
                                articleCount = articleCount,
                                dateAdded = System.currentTimeMillis(),
                                isDownloading = false,
                                downloadProgress = 1.0f,
                                downloadSpeed = ""
                            )
                            archiveDao.insertArchive(newArchive)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Scan failed", e)
            }
        }
    }

    fun selectArticle(article: ArticleEntity) {
        articleBackStack.clear()
        // Navigate immediately
        activeArticle.value = article
        
        // If content is already there, we are done
        if (article.htmlContent.isNotEmpty()) return
        
        // Fetch HTML in background to support themed view or future use
        viewModelScope.launch {
            val loadedArticle = withContext(Dispatchers.IO) {
                try {
                    val archive = archiveDao.getArchiveById(article.archiveId)
                    if (archive != null) {
                        val html = ZimReader.getHtmlByUrl(getApplication(), archive.filePath, article.url)
                        if (html.isNotEmpty()) {
                            article.copy(htmlContent = html)
                        } else null
                    } else null
                } catch (e: Throwable) {
                    null
                }
            }
            
            // If the user hasn't switched to another article yet, update it
            if (activeArticle.value?.id == article.id) {
                if (loadedArticle != null) {
                    activeArticle.value = loadedArticle
                } else {
                    activeArticle.value = article.copy(htmlContent = "<h3>Ошибка загрузки</h3><p>Не удалось загрузить содержимое статьи. Возможно, архив поврежден или статья отсутствует по указанному пути: ${article.url}</p>")
                }
            }
        }
    }

    fun goBackArticle() {
        if (articleBackStack.isNotEmpty()) {
            activeArticle.value = articleBackStack.removeAt(articleBackStack.size - 1)
        } else {
            activeArticle.value = null
        }
    }

    fun refreshFeed() {
        viewModelScope.launch {
            try {
                var randomArticles = withContext(Dispatchers.IO) {
                    articleDao.getRandomFeed(limit = 10)
                }
                if (randomArticles.isEmpty()) {
                    // Fallback to a limited set instead of ALL articles to avoid OOM
                    randomArticles = withContext(Dispatchers.IO) {
                        try {
                            articleDao.getFeedPage(limit = 10, offset = 0)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                _feedArticles.value = randomArticles.shuffled()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Standard download flow triggered from settings click
    fun downloadDefaultArchive(url: String, archiveId: String, archiveTitle: String) {
        if (downloadProgress.value != null) return // Already downloading
        
        downloadError.value = null
        downloadProgress.value = 0f
        downloadSpeed.value = "0 KB/s"
        bytesDownloadedLabel.value = "Начало загрузки..."
        downloadArchiveName.value = archiveTitle
        downloadingArchiveId = archiveId

        downloadJob = viewModelScope.launch {
            try {
                ZimDownloader.downloadFile(
                    context = getApplication(),
                    url = url,
                    archiveId = archiveId,
                    archiveTitle = archiveTitle,
                    onProgress = { progress, speed, bytes, total ->
                        downloadProgress.value = progress
                        downloadSpeed.value = speed
                        val loadedMb = bytes / (1024 * 1024)
                        val totalMb = total / (1024 * 1024)
                        bytesDownloadedLabel.value = "$loadedMb МБ / $totalMb МБ"
                    },
                    onComplete = { file ->
                        viewModelScope.launch {
                            // Archive downloaded successfully, now perform dynamic indexing of articles
                            indexDownloadedArchive(file, archiveId, archiveTitle, url)
                            downloadProgress.value = null
                            downloadingArchiveId = null
                            downloadJob = null
                        }
                    },
                    onError = { exception ->
                        downloadProgress.value = null
                        downloadingArchiveId = null
                        downloadJob = null
                        downloadError.value = "Ошибка скачивания: ${exception.localizedMessage}"
                    }
                )
            } catch (e: Exception) {
                downloadProgress.value = null
                downloadingArchiveId = null
                downloadJob = null
                if (e !is kotlinx.coroutines.CancellationException) {
                    downloadError.value = "Ошибка: ${e.localizedMessage}"
                }
            } finally {
                if (downloadingArchiveId == archiveId) {
                    downloadProgress.value = null
                    downloadingArchiveId = null
                    downloadJob = null
                }
            }
        }
    }

    // Cancel active download and completely delete file/index elements
    fun cancelDownload() {
        val targetId = downloadingArchiveId
        downloadJob?.cancel()
        downloadJob = null
        downloadProgress.value = null
        downloadSpeed.value = ""
        bytesDownloadedLabel.value = ""
        downloadArchiveName.value = ""
        downloadingArchiveId = null
        
        if (targetId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val context = getApplication<Application>()
                val file = File(context.filesDir, "archives/$targetId.zim")
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    private suspend fun doFullIndexing(
        context: Context,
        pathOrUri: String,
        isRealZim: Boolean,
        archiveId: String,
        archiveTitle: String,
        cleanNameForFallback: String
    ): Int {
        var totalArticleCount = 0
        // Clear old articles and FTS if any
        articleDao.deleteArticlesByArchive(archiveId)
        articleDao.deleteFtsByArchive(archiveId)

        if (isRealZim) {
            ZimReader.indexAllArticles(
                context = context,
                pathOrUri = pathOrUri,
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                batchSize = 1000,
                extractExcerpts = deepIndexing.value,
                onBatch = { batch ->
                    val ftsBatch = batch.map {
                        ArticleFts(
                            articleId = it.id,
                            title = it.title,
                            excerpt = it.excerpt,
                            fullText = it.htmlContent, // Content for searching
                            archiveId = it.archiveId
                        )
                    }
                    // Clear plain text from main table to save space; it's re-read from ZIM on demand
                    val cleanBatch = batch.map { it.copy(htmlContent = "") }
                    articleDao.insertArticles(cleanBatch)
                    articleDao.insertArticlesFts(ftsBatch)
                    totalArticleCount += batch.size
                },
                onProgress = { indexed, total ->
                    indexingProgress.value = indexed
                    indexingTotal.value = total
                }
            )
        } else {
            // Fallback for mock/simulated files or when error occurs
            val presetToUse = if (cleanNameForFallback.contains("quote", ignoreCase = true) || cleanNameForFallback.contains("цитат", ignoreCase = true)) {
                "wikiquote"
            } else {
                "wikipedia"
            }
            val preloaded = PreloadedData.getPreloadedArticlesForArchive(presetToUse).map { article ->
                article.copy(
                    id = "${archiveId}_${article.id.substringAfter("_")}",
                    archiveId = archiveId,
                    archiveTitle = archiveTitle,
                    isFeedCandidate = true
                )
            }
            articleDao.insertArticles(preloaded)
            val ftsPreloaded = preloaded.map {
                ArticleFts(
                    articleId = it.id,
                    title = it.title,
                    excerpt = it.excerpt,
                    fullText = it.excerpt, // Use excerpt for preloaded data
                    archiveId = it.archiveId
                )
            }
            articleDao.insertArticlesFts(ftsPreloaded)
            totalArticleCount = preloaded.size
        }
        return totalArticleCount
    }

    // Indexer populates Room database with rich offline localized Russian cards
    private suspend fun indexDownloadedArchive(file: File, archiveId: String, archiveTitle: String, url: String) {
        isIndexing.value = true
        indexingProgress.value = 0
        indexingTotal.value = 0
        
        indexingJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var fileLength = file.length()
                    if (fileLength < 1024 * 1024) {
                        // If the downloaded file is a placeholder/simulated text file, use standardized mock sizes
                        fileLength = when {
                            archiveId.contains("wikipedia", ignoreCase = true) -> 13958643712L // 13 GB representation
                            archiveId.contains("wikiquote", ignoreCase = true) -> 152043520L // 145 MB representation
                            else -> 131072000L // 125 MB fallback representation
                        }
                    }
                    
                    val isRealZim = try {
                        if (file.exists() && file.length() > 1024 * 1024) {
                            FileZimSource(file).use { pSource ->
                                val header = ZimReader.readHeader(pSource)
                                (header.magic and 0xFFFFFF) == 0x4D495A
                            }
                        } else false
                    } catch (e: Exception) {
                        false
                    }

                    val totalCount = doFullIndexing(
                        context = getApplication(),
                        pathOrUri = file.absolutePath,
                        isRealZim = isRealZim,
                        archiveId = archiveId,
                        archiveTitle = archiveTitle,
                        cleanNameForFallback = archiveId
                    )

                    // Register archive in DB
                    val newArchive = ArchiveEntity(
                        id = archiveId,
                        title = archiveTitle,
                        sourceUrl = url,
                        filePath = file.absolutePath,
                        fileSize = fileLength,
                        articleCount = totalCount,
                        dateAdded = System.currentTimeMillis()
                    )
                    archiveDao.insertArchive(newArchive)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // Complete download block
                isIndexing.value = false
                indexingProgress.value = 0
                indexingTotal.value = 0
                downloadProgress.value = null
                indexingJob = null
                refreshFeed() // Reload feed with newly indexed content
            }
        }
    }

    // Load local file from manager (Issue 15 resolved: real size from ContentResolver)
    fun selectLocalZimFile(uri: Uri, name: String) {
        val cleanName = name.replace(".zim", "", ignoreCase = true)
        val id = "local_${System.currentTimeMillis()}"
        val context = getApplication<Application>()
        
        indexingJob = viewModelScope.launch {
            isIndexing.value = true
            indexingProgress.value = 0
            indexingTotal.value = 0
            
            try {
                withContext(Dispatchers.IO) {
                    val isRealZim = try {
                        UriZimSource(context, uri).use { pSource ->
                            val header = ZimReader.readHeader(pSource)
                            (header.magic and 0xFFFFFF) == 0x4D495A
                        }
                    } catch (e: Exception) {
                        false
                    }

                    val totalCount = doFullIndexing(
                        context = context,
                        pathOrUri = uri.toString(),
                        isRealZim = isRealZim,
                        archiveId = id,
                        archiveTitle = "Файл: $cleanName",
                        cleanNameForFallback = cleanName
                    )
                    
                    // Query real file size from ContentResolver
                    val resolvedSize = try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
                            } else 0L
                        } ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                    
                    val fileSizeInBytes = if (resolvedSize > 0) resolvedSize else 1024 * 1024 * 145L // Fallback to 145 MB if resolution fails
                    
                    val newArchive = ArchiveEntity(
                        id = id,
                        title = "Файл: $cleanName",
                        sourceUrl = "Локальный файл",
                        filePath = uri.toString(),
                        fileSize = fileSizeInBytes,
                        articleCount = totalCount,
                        dateAdded = System.currentTimeMillis()
                    )
                    archiveDao.insertArchive(newArchive)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isIndexing.value = false
                indexingProgress.value = 0
                indexingTotal.value = 0
                indexingJob = null
                refreshFeed()
            }
        }
    }

    fun deleteArchive(archiveId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 1. Delete articles belonging to this archive
                articleDao.deleteArticlesByArchive(archiveId)
                articleDao.deleteFtsByArchive(archiveId)
                // 2. Delete the archive itself
                archiveDao.deleteArchiveById(archiveId)
                
                // 3. Attempt physical file removal in context filesDir
                val file = File(getApplication<Application>().filesDir, "archives/$archiveId.zim")
                if (file.exists()) {
                    file.delete()
                }
            }
            // Feed auto-updates or clears reactively when the Flow at archives emitting changes!
        }
    }

    fun toggleBookmark(article: ArticleEntity) {
        viewModelScope.launch {
            val isSaved = bookmarkDao.isBookmarked(article.id)
            if (isSaved) {
                bookmarkDao.deleteBookmarkById(article.id)
            } else {
                val bookmark = BookmarkEntity(
                    id = article.id,
                    archiveId = article.archiveId,
                    archiveTitle = article.archiveTitle,
                    url = article.url,
                    title = article.title,
                    excerpt = article.excerpt,
                    htmlContent = article.htmlContent
                )
                bookmarkDao.insertBookmark(bookmark)
            }
        }
    }

    fun navigateToArticleByUrl(archiveId: String, url: String) {
        viewModelScope.launch {
            val decodedUrl = try {
                java.net.URLDecoder.decode(url, "UTF-8")
            } catch (e: Exception) {
                url
            }
            
            // Normalize path (handle ../ and leading /)
            var cleanUrl = decodedUrl.trim()
            while (cleanUrl.startsWith("/") || cleanUrl.startsWith("./")) {
                cleanUrl = if (cleanUrl.startsWith("/")) cleanUrl.removePrefix("/") else cleanUrl.removePrefix("./")
            }
            while (cleanUrl.contains("../")) {
                cleanUrl = cleanUrl.replace("../", "")
            }
            
            var foundArticle = withContext(Dispatchers.IO) {
                articleDao.getArticleByUrl(archiveId, cleanUrl)
            }
            
            if (foundArticle == null) {
                // Try searching with capitalized prefix or standard formats
                val cleanUrlVariant = if (cleanUrl.startsWith("A/")) cleanUrl else "A/$cleanUrl"
                foundArticle = withContext(Dispatchers.IO) {
                    articleDao.getArticleByUrl(archiveId, cleanUrlVariant)
                }
            }
            
            if (foundArticle == null) {
                // Dynamic fallback loader: try to load straight from the ZIM archive on-the-fly!
                val archive = withContext(Dispatchers.IO) {
                    archiveDao.getArchiveById(archiveId)
                }
                if (archive != null) {
                    val html = withContext(Dispatchers.IO) {
                        ZimReader.getHtmlByUrl(getApplication(), archive.filePath, cleanUrl)
                    }
                    if (html.isNotEmpty()) {
                        val title = cleanUrl.substringAfterLast("/").substringBeforeLast(".").replace("_", " ")
                        foundArticle = ArticleEntity(
                            id = "${archiveId}_${cleanUrl}",
                            archiveId = archiveId,
                            archiveTitle = archive.title,
                            url = cleanUrl,
                            title = title,
                            category = "Статья",
                            excerpt = "Динамический просмотр статьи",
                            htmlContent = html,
                            isFeedCandidate = false
                        )
                    }
                }
            }
            
            if (foundArticle != null) {
                // Navigate immediately
                activeArticle.value?.let { current ->
                    articleBackStack.add(current)
                }
                activeArticle.value = foundArticle
                
                // If content is already there, we are done
                if (foundArticle.htmlContent.isNotEmpty()) return@launch
                
                // Fetch HTML in background (Fixes Infinite Loading when clicking links)
                val articleToLoad = foundArticle
                val loadedArticle = withContext(Dispatchers.IO) {
                    try {
                        val archive = archiveDao.getArchiveById(articleToLoad.archiveId)
                        if (archive != null) {
                            val html = ZimReader.getHtmlByUrl(getApplication(), archive.filePath, articleToLoad.url)
                            if (html.isNotEmpty()) {
                                articleToLoad.copy(htmlContent = html)
                            } else null
                        } else null
                    } catch (e: Throwable) {
                        null
                    }
                }
                
                if (activeArticle.value?.id == articleToLoad.id) {
                    if (loadedArticle != null) {
                        activeArticle.value = loadedArticle
                    } else {
                        activeArticle.value = articleToLoad.copy(htmlContent = "<h3>Ошибка загрузки</h3><p>Не удалось загрузить содержимое статьи.</p>")
                    }
                }
            }
        }
    }

    fun reindexAllArchives() {
        indexingJob?.cancel()
        indexingJob = viewModelScope.launch {
            val allArchives = archives.value
            if (allArchives.isEmpty()) return@launch
            
            isIndexing.value = true
            try {
                for (archive in allArchives) {
                    if (archive.filePath.isEmpty()) continue
                    
                    downloadArchiveName.value = archive.title
                    indexingProgress.value = 0
                    indexingTotal.value = 0
                    
                    withContext(Dispatchers.IO) {
                        val isRealZim = archive.filePath.endsWith(".zim", ignoreCase = true)
                        doFullIndexing(
                            context = getApplication(),
                            pathOrUri = archive.filePath,
                            isRealZim = isRealZim,
                            archiveId = archive.id,
                            archiveTitle = archive.title,
                            cleanNameForFallback = archive.title
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isIndexing.value = false
                indexingProgress.value = 0
                indexingTotal.value = 0
                indexingJob = null
                refreshFeed()
            }
        }
    }

    fun cancelIndexing() {
        indexingJob?.cancel()
        indexingJob = null
        isIndexing.value = false
        indexingProgress.value = 0
        indexingTotal.value = 0
        
        // If we were doing deep indexing, turn it off as the process was interrupted
        if (deepIndexing.value) {
            deepIndexing.value = false
            sharedPrefs.edit().putBoolean("deep_indexing", false).apply()
        }
    }

    // Load local HTML file from manager
    fun selectLocalHtmlFile(uri: Uri, name: String) {
        val cleanName = name.replace(".html", "", ignoreCase = true).replace(".htm", "", ignoreCase = true)
        val id = "local_html_${System.currentTimeMillis()}"
        val context = getApplication<Application>()
        
        indexingJob = viewModelScope.launch {
            isIndexing.value = true
            
            try {
                withContext(Dispatchers.IO) {
                    val htmlContent = try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().use { it.readText() }
                        } ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    
                    if (htmlContent.isNotEmpty()) {
                        // Extract a clean snippet of text as the excerpt
                        val textOnly = try {
                            val parsed = android.text.Html.fromHtml(htmlContent, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                            if (parsed.length > 250) parsed.take(200) + "..." else parsed
                        } catch (e: Exception) {
                            "Локальный HTML-документ"
                        }
                        
                        val article = ArticleEntity(
                            id = "${id}_main",
                            archiveId = id,
                            archiveTitle = "Файл: $name",
                            url = "index.html",
                            title = cleanName,
                            category = "HTML-документ",
                            excerpt = textOnly.trim(),
                            htmlContent = htmlContent,
                            isFeedCandidate = true
                        )
                        
                        articleDao.insertArticles(listOf(article))
                        
                        // Query real file size from ContentResolver
                        val resolvedSize = try {
                            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                    if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
                                } else 0L
                            } ?: 0L
                        } catch (e: Exception) {
                            0L
                        }
                        
                        val fileSizeInBytes = if (resolvedSize > 0) resolvedSize else htmlContent.toByteArray().size.toLong()
                        
                        val newArchive = ArchiveEntity(
                            id = id,
                            title = "Файл: $name",
                            sourceUrl = "Локальный HTML",
                            filePath = uri.toString(),
                            fileSize = fileSizeInBytes,
                            articleCount = 1,
                            dateAdded = System.currentTimeMillis()
                        )
                        archiveDao.insertArchive(newArchive)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isIndexing.value = false
                indexingJob = null
                refreshFeed()
            }
        }
    }

    suspend fun fetchArticleExcerpt(article: ArticleEntity, query: String = ""): String {
        return withContext(Dispatchers.IO) {
            try {
                val archive = archiveDao.getArchiveById(article.archiveId) ?: return@withContext ""
                val path = archive.filePath
                val html = com.example.data.ZimReader.getHtmlByUrl(getApplication(), path, article.url)
                if (html.isEmpty() || html.contains("Ошибка:")) return@withContext ""
                val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                    .toString()
                    .replace(Regex("\\s+"), " ")
                    .trim()
                
                if (query.isNotEmpty() && query.length >= 2) {
                    val tokens = query.split("\\s+".toRegex()).filter { it.length >= 2 }
                    var bestIndex = -1
                    var bestTokenLen = 0
                    
                    for (token in tokens) {
                        val index = text.indexOf(token, ignoreCase = true)
                        if (index != -1 && token.length > bestTokenLen) {
                            bestIndex = index
                            bestTokenLen = token.length
                        }
                    }
                    
                    if (bestIndex != -1) {
                        val start = maxOf(0, bestIndex - 60)
                        val end = minOf(text.length, bestIndex + 140)
                        var snippet = text.substring(start, end)
                        
                        // Try to cut at word boundaries
                        if (start > 0) {
                            val firstSpace = snippet.indexOf(' ')
                            if (firstSpace != -1 && firstSpace < 20) {
                                snippet = "…" + snippet.substring(firstSpace + 1)
                            } else {
                                snippet = "…" + snippet
                            }
                        }
                        
                        if (end < text.length) {
                            val lastSpace = snippet.lastIndexOf(' ')
                            if (lastSpace != -1 && lastSpace > snippet.length - 20) {
                                snippet = snippet.substring(0, lastSpace) + "…"
                            } else {
                                snippet = snippet + "…"
                            }
                        }
                        return@withContext snippet
                    }
                }

                if (text.length > 200) text.take(200) + "…" else text
            } catch (e: Exception) { "" }
        }
    }
}
