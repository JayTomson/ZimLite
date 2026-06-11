package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import kotlinx.coroutines.runBlocking

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val archiveDao = database.archiveDao()
    private val articleDao = database.articleDao()
    private val bookmarkDao = database.bookmarkDao()
    private val sharedPrefs = application.getSharedPreferences("kiwix_prefs", Context.MODE_PRIVATE)

    // Current app theme selection (default is DARK as specified)
    val appTheme = MutableStateFlow(AppTheme.DARK)

    // Navigation and screen state
    val currentTab = mutableStateOf(0) // 0 = Feed, 1 = Search, 2 = Bookmarks
    val activeArticle = mutableStateOf<ArticleEntity?>(null)
    val activeWebUrl = mutableStateOf<String?>(null)
    val insideSettings = mutableStateOf(false)

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
    val searchedArticles: StateFlow<List<ArticleEntity>> = searchQuery
        .debounce(250)
        .flatMapLatest { query ->
            val cleanQuery = query.trim()
            if (cleanQuery.length < 2) {
                flowOf(emptyList())
            } else {
                flow {
                    val ftsQuery = cleanQuery
                        .split("\\s+".toRegex())
                        .filter { it.isNotEmpty() }
                        .joinToString(" ") { token ->
                            val safe = token.replace("\"", "").replace("*", "").replace("(", "").replace(")", "")
                            if (safe.isEmpty()) "" else safe
                        }
                        .trim()

                    if (ftsQuery.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val results = withContext(Dispatchers.IO) {
                            try {
                                articleDao.searchArticlesFts(ftsQuery, limit = 50)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                articleDao.searchArticles("%$cleanQuery%").first()
                            }
                        }
                        emit(results)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    init {
        // Load persistent theme preference
        val savedThemeOrdinal = sharedPrefs.getInt("theme_key", AppTheme.DARK.ordinal)
        appTheme.value = AppTheme.values().getOrElse(savedThemeOrdinal) { AppTheme.DARK }

        // Start observing bookmarks to keep track of bookmarked IDs
        viewModelScope.launch {
            bookmarkDao.getAllBookmarks().collect { list ->
                _bookmarkedIds.value = list.map { it.id }.toSet()
            }
        }

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

    fun selectArticle(article: ArticleEntity) {
        viewModelScope.launch {
            if (article.htmlContent.isNotEmpty()) {
                activeArticle.value = article
                return@launch
            }
            isIndexing.value = true
            val loadedArticle = withContext(Dispatchers.IO) {
                try {
                    val archive = archiveDao.getArchiveById(article.archiveId)
                    if (archive != null) {
                        val html = ZimReader.getHtmlByUrl(getApplication(), archive.filePath, article.url)
                        if (html.isNotEmpty()) {
                            article.copy(htmlContent = html)
                        } else {
                            article.copy(htmlContent = "<h3>Ошибка: Не удалось прочитать содержимое статьи из архива</h3>")
                        }
                    } else {
                        article.copy(htmlContent = "<h3>Ошибка: Сбой поиска архива</h3>")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    article.copy(htmlContent = "<h3>Ошибка при чтении статьи: ${e.localizedMessage ?: e.message}</h3>")
                }
            }
            isIndexing.value = false
            activeArticle.value = loadedArticle
        }
    }

    fun refreshFeed() {
        viewModelScope.launch {
            try {
                var randomArticles = withContext(Dispatchers.IO) {
                    articleDao.getRandomFeed(limit = 10)
                }
                if (randomArticles.isEmpty()) {
                    // Fallback to preloaded standard list
                    randomArticles = withContext(Dispatchers.IO) {
                        try {
                            articleDao.getAllArticles().first().shuffled().take(10)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                _feedArticles.value = randomArticles
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
                onBatch = { batch ->
                    articleDao.insertArticles(batch)
                    val ftsBatch = batch.map {
                        ArticleFts(
                            articleId = it.id,
                            title = it.title,
                            excerpt = it.excerpt,
                            archiveId = it.archiveId
                        )
                    }
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
        
        withContext(Dispatchers.IO) {
            var fileLength = file.length()
            if (fileLength < 1024 * 1024) {
                // If the downloaded file is a placeholder/simulated text file, use standardized mock sizes
                fileLength = when {
                    archiveId.contains("wikipedia", ignoreCase = true) -> 2147483648L // 2.0 GB representation
                    archiveId.contains("wikiquote", ignoreCase = true) -> 152043520L // 145 MB representation
                    else -> 131072000L // 125 MB fallback representation
                }
            }
            
            val isRealZim = try {
                if (file.exists() && file.length() > 1024 * 1024) {
                    FileZimSource(file).use { pSource ->
                        val header = ZimReader.readHeader(pSource)
                        header.magic == 1113824004 || header.magic == 72173914
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
        
        // Complete download block
        isIndexing.value = false
        indexingProgress.value = 0
        indexingTotal.value = 0
        downloadProgress.value = null
        refreshFeed() // Reload feed with newly indexed content
    }

    // Load local file from manager (Issue 15 resolved: real size from ContentResolver)
    fun selectLocalZimFile(uri: Uri, name: String) {
        val cleanName = name.replace(".zim", "", ignoreCase = true)
        val id = "local_${System.currentTimeMillis()}"
        val context = getApplication<Application>()
        
        viewModelScope.launch {
            isIndexing.value = true
            indexingProgress.value = 0
            indexingTotal.value = 0
            
            withContext(Dispatchers.IO) {
                val isRealZim = try {
                    UriZimSource(context, uri).use { pSource ->
                        val header = ZimReader.readHeader(pSource)
                        header.magic == 1113824004 || header.magic == 72173914
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
            
            isIndexing.value = false
            indexingProgress.value = 0
            indexingTotal.value = 0
            refreshFeed()
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
            val cleanUrl = decodedUrl.trim().removePrefix("/")
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
                activeArticle.value = foundArticle
            }
        }
    }

    // Load local HTML file from manager
    fun selectLocalHtmlFile(uri: Uri, name: String) {
        val cleanName = name.replace(".html", "", ignoreCase = true).replace(".htm", "", ignoreCase = true)
        val id = "local_html_${System.currentTimeMillis()}"
        val context = getApplication<Application>()
        
        viewModelScope.launch {
            isIndexing.value = true
            
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
            
            isIndexing.value = false
            refreshFeed()
        }
    }
}
