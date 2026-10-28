package com.phisherman.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.phisherman.engine.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** One verdict. No message bodies or full URLs: only the host / sender / "call". */
@Entity(tableName = "decisions")
data class Decision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,             // epoch millis
    val channel: String,      // LINK | SMS | VOICE
    val level: String,        // SAFE | SUSPICIOUS | DANGEROUS
    val score: Int,
    val subject: String,      // host for links, sender for SMS, "call" for voice
    val reasons: String,      // comma-joined reason ids
    val latencyMs: Long,
    val userAction: String?,  // BACK | OPEN_ANYWAY | REPORTED | FORWARDED | null
)

@Dao
interface DecisionDao {
    @Insert
    suspend fun insert(d: Decision): Long

    @Query("UPDATE decisions SET userAction = :action WHERE id = :id")
    suspend fun setAction(id: Long, action: String)

    @Query("SELECT * FROM decisions ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Decision>>

    @Query("DELETE FROM decisions")
    suspend fun clear()
}

@Database(entities = [Decision::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decisions(): DecisionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "phisherman.db").build()
                .also { instance = it }
        }
    }
}

object DecisionLog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun record(ctx: Context, v: Verdict, action: String? = null): Long =
        AppDatabase.get(ctx).decisions().insert(
            Decision(ts = System.currentTimeMillis(), channel = v.channel.name, level = v.level.name, score = v.score,
                subject = v.subject, reasons = v.reasons.joinToString(",") { it.id }, latencyMs = v.latencyMs,
                userAction = action))

    /** Fire-and-forget, so a safe link is never delayed by the database. */
    fun recordAsync(ctx: Context, v: Verdict, action: String? = null) {
        val app = ctx.applicationContext
        scope.launch { record(app, v, action) }
    }

    fun setActionAsync(ctx: Context, id: Long, action: String) {
        if (id <= 0) return
        val app = ctx.applicationContext
        scope.launch { AppDatabase.get(app).decisions().setAction(id, action) }
    }
}
