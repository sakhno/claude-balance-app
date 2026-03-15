package com.anthropic.balanceapp.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val level: Level,
    val message: String
) {
    enum class Level { DEBUG, WARN, ERROR }

    val timestamp: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}

object AppLogger {
    private const val TAG = "BalanceApp"
    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(message: String) {
        Log.d(TAG, message)
        append(LogEntry(level = LogEntry.Level.DEBUG, message = message))
    }

    fun w(message: String) {
        Log.w(TAG, message)
        append(LogEntry(level = LogEntry.Level.WARN, message = message))
    }

    fun e(message: String) {
        Log.e(TAG, message)
        append(LogEntry(level = LogEntry.Level.ERROR, message = message))
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun append(entry: LogEntry) {
        val current = _entries.value
        _entries.value = (current + entry).takeLast(MAX_ENTRIES)
    }
}
