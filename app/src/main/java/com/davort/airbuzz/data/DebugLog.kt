package com.davort.airbuzz.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * AirBuzz's own record of its last steps, kept on the phone so a missed buzz can be diagnosed from Settings → Share
 * log. The phone's system log keeps only a few minutes. Lines name apps and outcomes, never message text or callers.
 * Lines older than a week are deleted, as the privacy policy says, and at most [KEEP] are kept.
 */
class DebugLog(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val lines = ArrayDeque<String>(load())
    private var prunedAt = now()

    @Synchronized
    fun add(line: String) {
        val t = now()
        val stamped = "${format.format(Date(t))}  $line"
        lines.addLast(stamped)
        // Dropping week-old lines rewrites the file, so it happens at most hourly; text() never shows them.
        val dropped = (if (t - prunedAt >= PRUNE_EVERY_MS) prune(t).also { prunedAt = t } else 0) +
            if (lines.size > KEEP * 2) (lines.size - KEEP).also { repeat(it) { lines.removeFirst() } } else 0
        if (dropped > 0) save() else runCatching { file.appendText(stamped + "\n") }
    }

    @Synchronized
    fun text(): String {
        if (prune(now()) > 0) save()
        return lines.takeLast(KEEP).joinToString("\n")
    }

    /**
     * The newest lines that fit in [maxChars] with their line breaks, oldest of them first. Share log sends this as text
     * alongside the full log as a file: a OnePlus tester's shared text arrived cut at 20,000 characters, which kept only
     * the oldest lines (27 Nov).
     */
    @Synchronized
    fun newest(maxChars: Int): String {
        val all = text().lines()
        var used = -1 // the newest line has no break after it
        val from = all.indices.reversed().firstOrNull { i -> (used + all[i].length + 1).also { used = it } > maxChars }?.plus(1) ?: 0
        return all.drop(from).joinToString("\n")
    }

    /** Drops lines from the front until the first is under a week old; a line without a readable time goes too. */
    private fun prune(t: Long): Int {
        var n = 0
        while (lines.isNotEmpty() && (timeOf(lines.first(), t)?.let { t - it > WEEK_MS } != false)) {
            lines.removeFirst()
            n++
        }
        return n
    }

    /** When [line] was written. Its stamp has no year, so it is the latest such moment no later than a day after [t]. */
    private fun timeOf(line: String, t: Long): Long? {
        val parsed = runCatching { format.parse(line.take(STAMP_LENGTH)) }.getOrNull() ?: return null
        val c = Calendar.getInstance().apply { time = parsed; set(Calendar.YEAR, Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR)) }
        if (c.timeInMillis > t + 24 * 3600_000L) c.add(Calendar.YEAR, -1)
        return c.timeInMillis
    }

    private fun save() {
        runCatching { file.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")) }
    }

    private fun load(): List<String> {
        val read = runCatching { file.readLines().filter { it.isNotBlank() }.takeLast(KEEP) }.getOrDefault(emptyList())
        val t = now()
        val kept = read.dropWhile { timeOf(it, t)?.let { at -> t - at > WEEK_MS } != false }
        if (kept.size < read.size) runCatching { file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n")) }
        return kept
    }

    companion object {
        const val KEEP = 1000
        const val WEEK_MS = 7L * 24 * 3600_000
        private const val PRUNE_EVERY_MS = 3600_000L
        private const val STAMP_LENGTH = 18
    }
}
