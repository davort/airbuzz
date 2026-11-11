package com.davort.airbuzz.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AirBuzz's own record of its last steps, kept on the phone so a missed buzz can be diagnosed from Settings → Share
 * log. The phone's system log keeps only a few minutes. Lines name apps and outcomes, never message text or callers.
 */
class DebugLog(private val file: File) {
    private val lines = ArrayDeque<String>(load())
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun add(line: String) {
        val stamped = "${format.format(Date())}  $line"
        lines.addLast(stamped)
        if (lines.size > KEEP * 2) {
            while (lines.size > KEEP) lines.removeFirst()
            runCatching { file.writeText(lines.joinToString("\n", postfix = "\n")) }
        } else {
            runCatching { file.appendText(stamped + "\n") }
        }
    }

    @Synchronized
    fun text(): String = lines.takeLast(KEEP).joinToString("\n")

    private fun load(): List<String> = runCatching { file.readLines().filter { it.isNotBlank() }.takeLast(KEEP) }.getOrDefault(emptyList())

    companion object {
        const val KEEP = 1000
    }
}
