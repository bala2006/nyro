package com.nyro.companion.data

import android.content.Context
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Rolling summary of messages older than the live window. */
    val summary: String = "",
    /** Id of the last message included in [summary]. */
    val summaryUpTo: Long = 0,
)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user" | "assistant" */
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    /** "user" when added in the Memory screen, "assistant" when saved through the remember tool. */
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val request: String,
    /** running | completed | failed | cancelled */
    val status: String,
    val summary: String = "",
    /** Newline-separated step log. */
    val steps: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0,
)

@Dao
interface NyroDao {
    // Conversations
    @Insert suspend fun insertConversation(c: ConversationEntity): Long
    @Query("UPDATE conversations SET updatedAt = :t, title = CASE WHEN title = '' THEN :title ELSE title END WHERE id = :id")
    suspend fun touchConversation(id: Long, title: String, t: Long = System.currentTimeMillis())
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun conversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1") suspend fun conversation(id: Long): ConversationEntity?
    @Query("UPDATE conversations SET summary = :summary, summaryUpTo = :upTo WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String, upTo: Long)
    @Query("SELECT * FROM messages WHERE conversationId = :cid AND id > :afterId AND id < :beforeId ORDER BY id ASC LIMIT 60")
    suspend fun messagesBetween(cid: Long, afterId: Long, beforeId: Long): List<MessageEntity>
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: Long)
    @Query("DELETE FROM conversations") suspend fun clearConversations()

    // Messages
    @Insert suspend fun insertMessage(m: MessageEntity): Long
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id ASC")
    fun messages(cid: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :cid ORDER BY id DESC LIMIT :limit) ORDER BY id ASC")
    suspend fun recentMessages(cid: Long, limit: Int): List<MessageEntity>
    @Query("DELETE FROM messages WHERE conversationId = :cid") suspend fun deleteMessages(cid: Long)
    @Query("DELETE FROM messages") suspend fun clearMessages()

    // Memories
    @Insert suspend fun insertMemory(m: MemoryEntity): Long
    @Query("SELECT * FROM memories ORDER BY createdAt DESC") fun memories(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 50")
    suspend fun searchMemories(q: String): List<MemoryEntity>
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :q || '%' ORDER BY id DESC LIMIT 50")
    suspend fun searchMessages(q: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE role = 'user' ORDER BY id DESC LIMIT :limit")
    suspend fun recentUserMessages(limit: Int): List<MessageEntity>
    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit") suspend fun memoryList(limit: Int): List<MemoryEntity>
    @Query("DELETE FROM memories WHERE id = :id") suspend fun deleteMemory(id: Long): Int
    @Query("DELETE FROM memories") suspend fun clearMemories()

    // Tasks
    @Insert suspend fun insertTask(t: TaskEntity): Long
    @Query("UPDATE tasks SET status = :status, summary = :summary, steps = :steps, endedAt = :endedAt WHERE id = :id")
    suspend fun finishTask(id: Long, status: String, summary: String, steps: String, endedAt: Long = System.currentTimeMillis())
    @Query("SELECT * FROM tasks ORDER BY startedAt DESC LIMIT 200") fun tasks(): Flow<List<TaskEntity>>
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("UPDATE tasks SET status = 'cancelled', endedAt = :t WHERE status = 'running'")
    suspend fun markOrphanedTasks(t: Long = System.currentTimeMillis())
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class, TaskEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class NyroDatabase : RoomDatabase() {
    abstract fun dao(): NyroDao

    companion object {
        /** Adds conversation summaries. Existing conversations start with an empty summary. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryUpTo INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): NyroDatabase =
            Room.databaseBuilder(context, NyroDatabase::class.java, "nyro.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
