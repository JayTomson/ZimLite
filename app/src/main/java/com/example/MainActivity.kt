package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.automirrored.filled.Feed
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ArticleEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val theme by viewModel.appTheme.collectAsStateWithLifecycle()

            MyApplicationTheme(appTheme = theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZimLiteApp(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZimLiteApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val currentTab by remember { derivedStateOf { viewModel.currentTab.value } }
    val insideSettings by remember { derivedStateOf { viewModel.insideSettings.value } }
    val activeArticle by remember { derivedStateOf { viewModel.activeArticle.value } }
    
    val archives by viewModel.archives.collectAsStateWithLifecycle()

    // File selection launcher for custom local ZIM archives or HTML files
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileNameFromUri(context, it) ?: "local_file.zim"
            val loweredName = fileName.lowercase()
            if (loweredName.endsWith(".zim")) {
                viewModel.selectLocalZimFile(it, fileName)
            } else if (loweredName.endsWith(".html") || loweredName.endsWith(".htm")) {
                viewModel.selectLocalHtmlFile(it, fileName)
            } else {
                android.widget.Toast.makeText(context, "Выберите файл с расширением .zim или .html", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Directory selection launcher for ZIM archive folder
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.setCustomZimDir(it.toString())
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = "ZIMLITE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp
                                )
                            )
                            Text(
                                text = when (currentTab) {
                                    0 -> "Лента"
                                    1 -> "Поиск"
                                    else -> "Закладки"
                                },
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            )
                        }
                    },
                    actions = {
                        if (currentTab == 0 && archives.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.refreshFeed() },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Обновить",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(44.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(50)
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable { viewModel.insideSettings.value = !insideSettings }
                                .testTag("settings_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (insideSettings) Icons.Default.Close else Icons.Default.Settings,
                                contentDescription = "Настройки",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                // Main screen view based on downloaded state
                if (archives.isEmpty()) {
                    EmptyStateScreen(
                        onGoToSettings = { viewModel.insideSettings.value = true }
                    )
                } else {
                    when (currentTab) {
                        0 -> FeedScreen(viewModel = viewModel)
                        1 -> SearchScreen(viewModel = viewModel)
                        2 -> BookmarksScreen(viewModel = viewModel)
                    }

                    // Floating bottom navigation bar placed on top of content to avoid black background rectangle
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .shadow(
                                    elevation = 12.dp,
                                    shape = RoundedCornerShape(28.dp),
                                    clip = false
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(28.dp)
                                )
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    shape = RoundedCornerShape(28.dp)
                                )
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tabs = listOf(
                                Triple(0, "Лента", Icons.AutoMirrored.Filled.Feed),
                                Triple(1, "Поиск", Icons.Default.Search),
                                Triple(2, "Закладки", Icons.Default.Bookmark)
                            )
                            
                            tabs.forEach { (index, title, icon) ->
                                val isSelected = currentTab == index
                                val testTag = when (index) {
                                    0 -> "feed_tab"
                                    1 -> "search_tab"
                                    else -> "bookmarks_tab"
                                }
                                
                                val iconTint by animateColorAsState(
                                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    animationSpec = tween(durationMillis = 200),
                                    label = "iconTint"
                                )
                                val pillBgColor by animateColorAsState(
                                    targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f) else Color.Transparent,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "pillBgColor"
                                )
                                val textStyleColor by animateColorAsState(
                                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    animationSpec = tween(durationMillis = 200),
                                    label = "textStyleColor"
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable { viewModel.currentTab.value = index }
                                        .padding(vertical = 4.dp)
                                        .testTag(testTag),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = pillBgColor,
                                                shape = RoundedCornerShape(16.dp)
                                            )
                                            .padding(horizontal = 20.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = title,
                                            tint = iconTint,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = title,
                                        color = textStyleColor,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Animated overlay for Settings Screen
        AnimatedVisibility(
            visible = insideSettings,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(250)) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                SettingsScreen(
                    viewModel = viewModel,
                    onSelectLocalFile = { fileLauncher.launch("*/*") },
                    onSelectFolder = { folderLauncher.launch(null) },
                    onClose = { viewModel.insideSettings.value = false }
                )
            }
        }

        // Animated overlay for Article Viewer Screen
        AnimatedVisibility(
            visible = activeArticle != null,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(250)) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            activeArticle?.let { article ->
                ArticleReaderScreen(
                    viewModel = viewModel,
                    article = article,
                    onBack = { viewModel.activeArticle.value = null }
                )
            }
        }

        val activeWebUrl by remember { derivedStateOf { viewModel.activeWebUrl.value } }

        // Animated overlay for WebView / External Web Reader Screen
        AnimatedVisibility(
            visible = activeWebUrl != null,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(250)) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            activeWebUrl?.let { url ->
                WebReaderScreen(
                    url = url,
                    onBack = { viewModel.activeWebUrl.value = null }
                )
            }
        }

        // Dim background and linear progress indexer overlay with livedata metrics
        val isIndexing by viewModel.isIndexing.collectAsStateWithLifecycle()
        if (isIndexing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val progress by viewModel.indexingProgress.collectAsStateWithLifecycle()
                        val total by viewModel.indexingTotal.collectAsStateWithLifecycle()

                        if (total > 0) {
                            val fraction = progress.toFloat() / total
                            LinearProgressIndicator(
                                progress = { fraction },
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Индексация: $progress / $total статей",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(fraction * 100).toInt()}%",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Индексация архива...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Читаем структуру архива и строим локальную базу. Большие архивы могут занять минуту.",
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(
                            onClick = { viewModel.cancelIndexing() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Отменить индексацию", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateScreen(onGoToSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(50)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = "No Archives",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Библиотека пуста",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Для работы приложения и чтения энциклопедий вам необходимо загрузить или выбрать хотя бы один ZIM-архив в настройках.",
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(30.dp))
        Button(
            onClick = onGoToSettings,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            modifier = Modifier.testTag("go_to_settings_button")
        ) {
            Icon(imageVector = Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Перейти к загрузкам", fontSize = 16.sp)
        }
    }
}

@Composable
fun FeedScreen(viewModel: MainViewModel) {
    val feedArticles by viewModel.feedArticles.collectAsStateWithLifecycle()
    val archives by viewModel.archives.collectAsStateWithLifecycle()
    val isIndexing by viewModel.isIndexing.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (isIndexing) {
            val progress by viewModel.indexingProgress.collectAsStateWithLifecycle()
            val total by viewModel.indexingTotal.collectAsStateWithLifecycle()

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    if (total > 0) {
                        val fraction = progress.toFloat() / total
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Индексация: $progress / $total статей",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Индексация архива...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (archives.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "Добро пожаловать в ZimLite!",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Для начала чтения скачайте архив в настройках (иконка шестерёнки вверху справа) или выберите локальный .zim / .html файл.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { viewModel.insideSettings.value = true },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Перейти к загрузкам")
                    }
                }
            }
        } else if (feedArticles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Загрузка ленты...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(feedArticles, key = { it.id }) { article ->
                    FeedCard(
                        article = article,
                        viewModel = viewModel,
                        onClick = { viewModel.selectArticle(article) }
                    )
                }
            }
        }
    }
}

@Composable
fun FeedCard(
    article: ArticleEntity,
    viewModel: MainViewModel,
    onClick: () -> Unit
) {
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val isBookmarked = bookmarkedIds.contains(article.id)

    var displayExcerpt by remember(article.id) { mutableStateOf(article.excerpt) }
    LaunchedEffect(article.id) {
        if (displayExcerpt.startsWith("Статья из архива")) {
            val excerpt = viewModel.fetchArticleExcerpt(article)
            if (excerpt.isNotEmpty()) {
                displayExcerpt = excerpt
            }
        }
    }

    val isWiki = article.category.contains("wiki", ignoreCase = true)
    val isQuote = article.category.contains("quote", ignoreCase = true)

    val tagBgColor = when {
        isWiki -> Color(0xFFD0BCFF)
        isQuote -> Color(0xFFBAC3FF)
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    val tagTextColor = when {
        isWiki -> Color(0xFF381E72)
        isQuote -> Color(0xFF1B2C66)
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = tagBgColor,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = article.category.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = tagTextColor,
                        letterSpacing = 0.5.sp
                    )
                }
                IconButton(
                    onClick = { viewModel.toggleBookmark(article) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = article.title,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onBackground,
                lineHeight = 24.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayExcerpt,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Source,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = article.archiveTitle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun SearchScreen(viewModel: MainViewModel) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchedArticles by viewModel.searchedArticles.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TextField(
            value = searchQuery,
            onValueChange = { viewModel.searchQuery.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(24.dp)
                )
                .testTag("search_text_field"),
            placeholder = { Text("Искать статьи или темы...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск", tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )

        if (searchedArticles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (searchQuery.trim().isEmpty()) "Введите поисковый запрос" else "Ничего не найдено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(searchedArticles) { article ->
                    SearchCard(article = article, viewModel = viewModel) {
                        viewModel.selectArticle(article)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchCard(
    article: ArticleEntity,
    viewModel: MainViewModel,
    onClick: () -> Unit
) {
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val isBookmarked = bookmarkedIds.contains(article.id)

    var displayExcerpt by remember(article.id) { mutableStateOf(article.excerpt) }
    LaunchedEffect(article.id) {
        if (displayExcerpt.startsWith("Статья из архива")) {
            val excerpt = viewModel.fetchArticleExcerpt(article)
            if (excerpt.isNotEmpty()) {
                displayExcerpt = excerpt
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = article.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.toggleBookmark(article) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = displayExcerpt,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Source,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = article.archiveTitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun BookmarksScreen(viewModel: MainViewModel) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        if (bookmarks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(40)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Закладок пока нет",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Нажмите на иконку закладки на карточке любой статьи, чтобы быстро сохранить её на потом.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    lineHeight = 18.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(bookmarks) { bookmark ->
                    // Convert BookmarkEntity properties to a standard representation
                    val articleFromBookmark = ArticleEntity(
                        id = bookmark.id,
                        archiveId = bookmark.archiveId,
                        archiveTitle = bookmark.archiveTitle,
                        url = bookmark.url,
                        title = bookmark.title,
                        category = "Сохранено",
                        excerpt = bookmark.excerpt,
                        htmlContent = bookmark.htmlContent
                    )

                    SearchCard(article = articleFromBookmark, viewModel = viewModel) {
                        viewModel.selectArticle(articleFromBookmark)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onSelectLocalFile: () -> Unit,
    onSelectFolder: () -> Unit,
    onClose: () -> Unit
) {
    val currentTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val useOriginalHtml by viewModel.useOriginalHtml.collectAsStateWithLifecycle()
    val searchInContent by viewModel.searchInContent.collectAsStateWithLifecycle()
    val deepIndexing by viewModel.deepIndexing.collectAsStateWithLifecycle()
    val customZimDirPath by viewModel.customZimDirPath.collectAsStateWithLifecycle()
    val archives by viewModel.archives.collectAsStateWithLifecycle()

    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadSpeed by viewModel.downloadSpeed.collectAsStateWithLifecycle()
    val downloadLabel by viewModel.bytesDownloadedLabel.collectAsStateWithLifecycle()
    val downloadArcName by viewModel.downloadArchiveName.collectAsStateWithLifecycle()
    val downloadError by viewModel.downloadError.collectAsStateWithLifecycle()

    var directUrlInput by remember { mutableStateOf("") }
    var showDeepIndexingConfirm by remember { mutableStateOf(false) }

    if (showDeepIndexingConfirm) {
        AlertDialog(
            onDismissRequest = { showDeepIndexingConfirm = false },
            title = { Text("Включить глубокий поиск?") },
            text = { Text("Это позволит искать не только в заголовках, но и по всему тексту статей. " +
                    "Внимание: это потребует полной переиндексации всех ваших архивов, что может занять много времени (до часа на очень больших базах). Разряжает батарею.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDeepIndexing(true)
                    showDeepIndexingConfirm = false
                }) {
                    Text("Включить и переиндексировать", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeepIndexingConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Constants for Wikipedia & Wikiquote Ru nopic files
    val defaultWikiUrl = "https://download.kiwix.org/zim/wikipedia/wikipedia_ru_all_nopic_2026-01.zim"
    val defaultQuoteUrl = "https://download.kiwix.org/zim/wikiquote/wikiquote_ru_all_nopic_2026-01.zim"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Core Top Settings Navigator Layout
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Настройки приложения",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: Themes Setup
            item {
                Text(
                    text = "Выбор темы",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeButton(
                        text = "Светлая",
                        isSelected = currentTheme == AppTheme.LIGHT,
                        onClick = { viewModel.setAppTheme(AppTheme.LIGHT) },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeButton(
                        text = "Темная",
                        isSelected = currentTheme == AppTheme.DARK,
                        onClick = { viewModel.setAppTheme(AppTheme.DARK) },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeButton(
                        text = "AMOLED",
                        isSelected = currentTheme == AppTheme.AMOLED,
                        onClick = { viewModel.setAppTheme(AppTheme.AMOLED) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Text(
                    text = "Локальное хранилище",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                // Folder Selection Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Папка с архивами",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = customZimDirPath?.let { 
                            Uri.parse(it).path ?: "Выбрано: $it" 
                        } ?: "Папка не выбрана. ZIM файлы из неё будут добавлены автоматически.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSelectFolder,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Выбрать", fontSize = 13.sp)
                        }
                        
                        if (customZimDirPath != null) {
                            OutlinedButton(
                                onClick = { viewModel.refreshCustomDir() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Обновить", fontSize = 13.sp)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Direct file button
                OutlinedButton(
                    onClick = onSelectLocalFile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Открыть отдельный файл .zim")
                }
            }

            item {
                Text(
                    text = "Отображение контента",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .clickable { viewModel.setUseOriginalHtml(!useOriginalHtml) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Оригинальный HTML",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (useOriginalHtml) "Оригинальный вид (как в ZIM)" else "Стилизация под тему приложения",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useOriginalHtml,
                        onCheckedChange = { viewModel.setUseOriginalHtml(it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .clickable { viewModel.setSearchInContent(!searchInContent) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Поиск по содержанию",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (searchInContent) "Искать в заголовках и внутри статей (FTS)" else "Искать только в заголовках",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = searchInContent,
                        onCheckedChange = { viewModel.setSearchInContent(it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .clickable { 
                            if (!deepIndexing) {
                                showDeepIndexingConfirm = true
                            } else {
                                viewModel.setDeepIndexing(false)
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Глубокий поиск (Контент)",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (deepIndexing) "Включено (извлекаются отрывки и весь текст)" else "Выключено (только заголовки)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = deepIndexing,
                        onCheckedChange = { 
                            if (it) showDeepIndexingConfirm = true 
                            else viewModel.setDeepIndexing(false) 
                        }
                    )
                }
            }

            // Section 2: Download Archives
            item {
                Text(
                    text = "Скачивание архивов (.ZIM)",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Стандартные ZIM Архивы:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        DownloadArchiveRow(
                            title = "Wikipedia RU (Русская)",
                            info = "Статьи без картинок (~2 ГБ)",
                            onDownload = {
                                viewModel.downloadDefaultArchive(
                                    url = defaultWikiUrl,
                                    archiveId = "wikipedia_ru_all_nopic_2026-01",
                                    archiveTitle = "Wikipedia RU"
                                )
                            },
                            isDownloading = downloadProgress != null
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        DownloadArchiveRow(
                            title = "Wikiquote RU (Русский)",
                            info = "Сборник цитат (~145 МБ)",
                            onDownload = {
                                viewModel.downloadDefaultArchive(
                                    url = defaultQuoteUrl,
                                    archiveId = "wikiquote_ru_all_nopic",
                                    archiveTitle = "Wikiquote RU"
                                )
                            },
                            isDownloading = downloadProgress != null
                        )
                    }
                }
            }

            // Section 3: Custom Link Direct Download Box
            item {
                Text(
                    text = "Скачать по прямой ссылке",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = directUrlInput,
                        onValueChange = { directUrlInput = it },
                        placeholder = { Text("https://example.com/wiki.zim", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Button(
                        onClick = {
                            if (directUrlInput.startsWith("http")) {
                                val id = "custom_" + System.currentTimeMillis()
                                val rawName = directUrlInput.substringAfterLast("/")
                                val title = if (rawName.endsWith(".zim")) rawName.substringBefore(".zim") else "Custom Archive"
                                viewModel.downloadDefaultArchive(
                                    url = directUrlInput,
                                    archiveId = id,
                                    archiveTitle = title
                                )
                            }
                        },
                        enabled = directUrlInput.isNotEmpty() && downloadProgress == null,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Скачать")
                    }
                }
            }

            // Section 4: File Selection
            item {
                Text(
                    text = "Импорт локального файла",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSelectLocalFile,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Выбрать файл .ZIM или .HTML")
                }
            }

            // Section 5: List Loaded Archives
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Все установленные архивы",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${archives.size}",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                
                if (archives.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "У вас нет загруженных архивов. Скачайте Википедию/Викицитатник или выберите локальный .zim / .html файл.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        archives.forEach { archive ->
                            LoadedArchiveCard(archive = archive, onDelete = {
                                viewModel.deleteArchive(archive.id)
                            })
                        }
                    }
                }
            }
        }

        // Keep explicit track of download popup details
        downloadProgress?.let { progress ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Скачиваем: $downloadArcName",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.cancelDownload() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Отмена",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = downloadLabel,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = downloadSpeed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (progress > 0f) {
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { viewModel.cancelDownload() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Отменить скачивание",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Show download errors elegantly (with dismiss action - Issue 9)
        downloadError?.let { err ->
            Snackbar(
                action = {
                    TextButton(onClick = { viewModel.downloadError.value = null }) {
                        Text("ОК", color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(text = err, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ThemeButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        modifier = modifier
    ) {
        Text(text = text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun DownloadArchiveRow(
    title: String,
    info: String,
    onDownload: () -> Unit,
    isDownloading: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = info,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(
            onClick = onDownload,
            enabled = !isDownloading,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Скачать", fontSize = 12.sp)
        }
    }
}

@Composable
fun LoadedArchiveCard(
    archive: com.example.data.ArchiveEntity,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить архив?") },
            text = { Text("Архив «${archive.title}» будет удалён из памяти устройства. Все ассоциированные с ним статьи станут недоступны.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = archive.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Статей: ${archive.articleCount} • Размер: ${archive.fileSize / (1024 * 1024)} МБ",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить архив",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleReaderScreen(
    viewModel: MainViewModel,
    article: ArticleEntity,
    onBack: () -> Unit
) {
    val currentTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val useOriginalHtml by viewModel.useOriginalHtml.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val isBookmarked = bookmarkedIds.contains(article.id)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = article.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleBookmark(article) }) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Bookmark",
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (article.htmlContent.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val displayHtml = remember(article, currentTheme, useOriginalHtml) {
                    if (useOriginalHtml) {
                        article.htmlContent
                    } else {
                        compileHtmlWithTheme(article.htmlContent, currentTheme)
                    }
                }
    
                AndroidView(
                    factory = { context ->
                    WebView(context).apply {
                        webViewClient = object : WebViewClient() {
                            private fun handleLinkClicked(targetUrl: String): Boolean {
                                val urlStr = targetUrl.trim()
                                if (urlStr.isEmpty()) return false
                                
                                // 1. Anchor within the same article
                                if (urlStr.startsWith("#") || urlStr.startsWith("zim://local/#") || urlStr.startsWith("https://app.zim/#")) {
                                    return false // Let WebView handle internal scrolling
                                }
                                
                                // 2. Internal wiki article links
                                val relativePath = when {
                                    urlStr.startsWith("https://app.zim/") -> urlStr.substringAfter("https://app.zim/")
                                    urlStr.startsWith("zim://local/") -> urlStr.substringAfter("zim://local/")
                                    !urlStr.startsWith("http://") && !urlStr.startsWith("https://") -> urlStr
                                    else -> null
                                }
                                
                                if (relativePath != null) {
                                    if (relativePath.isNotEmpty() && relativePath != "/") {
                                        viewModel.navigateToArticleByUrl(article.archiveId, relativePath)
                                    }
                                    return true
                                }
                                
                                // 3. External web/app HTTP/HTTPS links - open inside app's beautiful WebReaderScreen
                                if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
                                    viewModel.activeWebUrl.value = urlStr
                                    return true
                                }
                                
                                // 4. Other system intent links
                                return try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr))
                                    context.startActivity(intent)
                                    true
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    false
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                return handleLinkClicked(url)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                return handleLinkClicked(request.url.toString())
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val urlStr = request.url.toString()
                                if (urlStr.startsWith("https://app.zim/")) {
                                    val pathEncoded = urlStr.substringAfter("https://app.zim/")
                                    if (pathEncoded.isEmpty() || pathEncoded == "/") {
                                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, java.io.ByteArrayInputStream("".toByteArray()))
                                    }
                                    
                                    val path = Uri.decode(pathEncoded)
                                    val archive = viewModel.archives.value.find { it.id == article.archiveId }
                                    if (archive != null) {
                                        val result = com.example.data.ZimReader.getBlobByUrl(context, archive.filePath, path)
                                        if (result != null) {
                                            return WebResourceResponse(result.second, "UTF-8", result.first.inputStream())
                                        }
                                    }
                                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, java.io.ByteArrayInputStream("".toByteArray()))
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        settings.apply {
                            javaScriptEnabled = false
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                        }
                    }
                },
                update = { webView ->
                    val currentContent = webView.getTag(R.id.tag_webview_content) as? String
                    if (currentContent != displayHtml) {
                        webView.loadDataWithBaseURL(
                            "https://app.zim/",
                            displayHtml,
                            "text/html",
                            "UTF-8",
                            null
                        )
                        webView.setTag(R.id.tag_webview_content, displayHtml)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            }
        }
    }
}

// Injects responsive styles dynamically into index queries matching Light/Dark/AMOLED themes
fun compileHtmlWithTheme(html: String, theme: AppTheme): String {
    val (bg, txt) = when (theme) {
        AppTheme.LIGHT -> "#FFFFFF" to "#191C21"
        AppTheme.DARK -> "#0F1113" to "#E2E2E6"
        AppTheme.AMOLED -> "#000000" to "#FFFFFF"
    }
    val accent = if (theme == AppTheme.LIGHT) "#1D5AAB" else "#D0BCFF"
    val divider = when (theme) {
        AppTheme.LIGHT -> "#E0E0E0"
        AppTheme.DARK -> "#49454F"
        AppTheme.AMOLED -> "#49454F"
    }
    val muted = when (theme) {
        AppTheme.LIGHT -> "#757575"
        AppTheme.DARK -> "#938F99"
        AppTheme.AMOLED -> "#938F99"
    }
    val surface = when (theme) {
        AppTheme.LIGHT -> "#F5F5F5"
        AppTheme.DARK -> "#1C1B1F"
        AppTheme.AMOLED -> "#121212"
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
            * {
                color: $txt !important;
                background-color: transparent !important;
            }
            body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                line-height: 1.6;
                padding: 16px;
                margin: 0;
                color: $txt !important;
                background-color: $bg !important;
            }
            h1 { font-size: 1.5em; margin-top: 4px; margin-bottom: 4px; color: $accent !important; font-weight: 700; }
            h2 { font-size: 1.25em; margin-top: 24px; border-bottom: 1px solid $divider; padding-bottom: 6px; color: $accent !important; }
            .subtitle { font-size: 0.85em; color: $muted !important; margin-bottom: 20px; font-style: italic; }
            .info-box {
                background-color: $surface !important;
                border: 1px solid $divider !important;
                border-radius: 12px;
                padding: 12px;
                margin-top: 15px;
                margin-bottom: 15px;
                font-size: 0.9em;
            }
            .quote-card {
                background-color: $surface !important;
                border-left: 4px solid $accent !important;
                border-radius: 0 12px 12px 0;
                padding: 12px;
                margin: 16px 0;
            }
            blockquote { margin: 0; padding: 0; font-style: italic; }
            .author { text-align: right; margin-top: 4px; font-weight: bold; font-size: 0.85em; color: $muted !important; }
            pre {
                background-color: $surface !important;
                padding: 12px;
                border-radius: 8px;
                overflow-x: auto;
                font-family: "Courier New", Courier, monospace;
                font-size: 0.85em;
            }
            ul, ol { padding-left: 20px; }
            li { margin-bottom: 6px; }
            table, .infobox, .navbox, .metadata {
                width: 100%;
                border-collapse: collapse;
                margin: 16px 0;
                font-size: 0.9em;
            }
            th, td {
                border: 1px solid $divider !important;
                padding: 6px 10px;
                text-align: left;
            }
            th { background-color: $surface !important; }
            a, a * { color: $accent !important; text-decoration: none; }
            a:hover { text-decoration: underline; }
        </style>
        </head>
        <body>
            $html
        </body>
        </html>
    """.trimIndent()
}

// Utility to resolve actual file names from content resolver uris
fun getFileNameFromUri(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    return cursor.getString(displayNameIndex)
                }
            }
        }
    }
    return uri.path?.substringAfterLast('/')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebReaderScreen(
    url: String,
    onBack: () -> Unit
) {
    var webTitle by remember { mutableStateOf("Веб-страница") }
    var webProgress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = webTitle,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = url,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { webViewRef?.reload() },
                        enabled = webViewRef != null
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { webProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                }
                
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewRef = this
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, urlStr: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, urlStr, favicon)
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, urlStr: String?) {
                                    super.onPageFinished(view, urlStr)
                                    isLoading = false
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    return false // Load inside current WebView
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    webProgress = newProgress
                                    if (newProgress == 100) {
                                        isLoading = false
                                    }
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    super.onReceivedTitle(view, title)
                                    if (!title.isNullOrEmpty()) {
                                        webTitle = title
                                    }
                                }
                            }
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                            }
                            loadUrl(url)
                        }
                    },
                    update = { view ->
                        // WebView retains state unless URL changes
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
