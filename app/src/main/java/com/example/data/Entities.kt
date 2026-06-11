package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "archives")
data class ArchiveEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceUrl: String,
    val filePath: String,
    val fileSize: Long,
    val articleCount: Int,
    val dateAdded: Long,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadSpeed: String = ""
)

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String, // format: "archiveId_url"
    val archiveId: String,
    val archiveTitle: String,
    val url: String, // e.g. "A/Kotlin.html"
    val title: String,
    val category: String,
    val excerpt: String,
    val htmlContent: String,
    val isFeedCandidate: Boolean = false
)

@Fts4
@Entity(tableName = "articles_fts")
data class ArticleFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Int = 0,
    val articleId: String,
    val title: String,
    val excerpt: String,
    val archiveId: String
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String, // same as article ID
    val archiveId: String,
    val archiveTitle: String,
    val url: String,
    val title: String,
    val excerpt: String,
    val htmlContent: String,
    val dateSaved: Long = System.currentTimeMillis()
)
