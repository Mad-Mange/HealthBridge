package se.healthbridge.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LogEntry(val timestamp: Long, val level: Level, val message: String) {
    enum class Level { INFO, WARN, ERROR }
}

/** Small persistent ring buffer of sync events, newest first. Shared by the UI and the worker. */
class SyncLog(context: Context) {
    private val file = File(context.filesDir, "sync_log.json")
    private val mutex = Mutex()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    suspend fun info(message: String) = add(LogEntry.Level.INFO, message)
    suspend fun warn(message: String) = add(LogEntry.Level.WARN, message)
    suspend fun error(message: String) = add(LogEntry.Level.ERROR, message)

    suspend fun clear() = mutex.withLock {
        _entries.value = emptyList()
        withContext(Dispatchers.IO) { file.delete() }
    }

    private suspend fun add(level: LogEntry.Level, message: String) = mutex.withLock {
        val updated = (listOf(LogEntry(System.currentTimeMillis(), level, message)) + _entries.value).take(MAX_ENTRIES)
        _entries.value = updated
        withContext(Dispatchers.IO) { save(updated) }
    }

    private fun load(): List<LogEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            LogEntry(o.getLong("t"), LogEntry.Level.valueOf(o.getString("l")), o.getString("m"))
        }
    }.getOrDefault(emptyList())

    private fun save(entries: List<LogEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("t", it.timestamp).put("l", it.level.name).put("m", it.message)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
