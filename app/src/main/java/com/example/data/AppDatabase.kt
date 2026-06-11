package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchiveDao {
    @Query("SELECT * FROM archives ORDER BY dateAdded DESC")
    fun getAllArchives(): Flow<List<ArchiveEntity>>

    @Query("SELECT * FROM archives WHERE id = :id LIMIT 1")
    suspend fun getArchiveById(id: String): ArchiveEntity?

    @Query("SELECT COUNT(*) FROM archives")
    suspend fun getArchiveCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchive(archive: ArchiveEntity)

    @Update
    suspend fun updateArchive(archive: ArchiveEntity)

    @Query("DELETE FROM archives WHERE id = :id")
    suspend fun deleteArchiveById(id: String)
}

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles")
    fun getAllArticles(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE archiveId = :archiveId")
    fun getArticlesByArchive(archiveId: String): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE title LIKE :query ORDER BY length(title) ASC LIMIT 50")
    suspend fun searchArticlesByTitle(query: String): List<ArticleEntity>

    // Search query that searches titles and contents
    @Query("SELECT * FROM articles WHERE title LIKE :query OR excerpt LIKE :query OR category LIKE :query ORDER BY length(title) ASC LIMIT 50")
    suspend fun searchArticles(query: String): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE isFeedCandidate = 1 ORDER BY RANDOM() LIMIT :limit")
    fun getRandomFeedArticles(limit: Int): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE isFeedCandidate = 1 LIMIT :limit OFFSET :offset")
    suspend fun getFeedPage(limit: Int, offset: Int): List<ArticleEntity>

    @Query("SELECT COUNT(*) FROM articles WHERE isFeedCandidate = 1")
    suspend fun getFeedCount(): Int

    @Query("SELECT * FROM articles WHERE isFeedCandidate = 1 ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomFeed(limit: Int): List<ArticleEntity>

    @Query("""
        SELECT * FROM articles
        WHERE id IN (
            SELECT articleId FROM articles_fts
            WHERE articles_fts MATCH :query
        )
        ORDER BY (CASE WHEN title LIKE :exactTitleQuery THEN 0 ELSE 1 END), length(title) ASC
        LIMIT :limit
    """)
    suspend fun searchArticlesFts(query: String, exactTitleQuery: String, limit: Int = 100): List<ArticleEntity>

    // Advanced title search that doesn't depend on FTS (for 100% reliability with short prefixes)
    @Query("""
        SELECT * FROM articles 
        WHERE (:w1 IS NULL OR LOWER(title) LIKE LOWER(:w1))
          AND (:w2 IS NULL OR LOWER(title) LIKE LOWER(:w2))
          AND (:w3 IS NULL OR LOWER(title) LIKE LOWER(:w3))
        ORDER BY length(title) ASC
        LIMIT :limit
    """)
    suspend fun searchArticlesByTitleMulti(w1: String?, w2: String?, w3: String?, limit: Int = 50): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun getArticleById(id: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE archiveId = :archiveId AND (url = :url OR url LIKE '%' || :url OR :url LIKE '%' || url) LIMIT 1")
    suspend fun getArticleByUrl(archiveId: String, url: String): ArticleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArticles(articles: List<ArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArticlesFts(items: List<ArticleFts>)

    @Query("DELETE FROM articles WHERE archiveId = :archiveId")
    suspend fun deleteArticlesByArchive(archiveId: String)

    @Query("DELETE FROM articles_fts WHERE archiveId = :archiveId")
    suspend fun deleteFtsByArchive(archiveId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY dateSaved DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE id = :id LIMIT 1)")
    suspend fun isBookmarked(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: String)
}

@Database(entities = [ArchiveEntity::class, ArticleEntity::class, ArticleFts::class, BookmarkEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao
    abstract fun articleDao(): ArticleDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kiwix_lite_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
