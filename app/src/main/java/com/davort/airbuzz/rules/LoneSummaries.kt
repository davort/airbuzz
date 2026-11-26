package com.davort.airbuzz.rules

/**
 * Decides when a chosen app's group summary stands for a message whose own notification never comes. On a OnePlus
 * tester's phone Reddit often showed only its group summary, and the message notifications came 11–16 min later or
 * never (26 Nov). A summary counts as a message when no message of its group counted [waitMs] before it or within
 * [waitMs] after it, and its text isn't one that group just buzzed for. A message whose text its group's summary
 * already buzzed for doesn't buzz again. Times are elapsed realtime; texts are hashes, never the text itself.
 */
class LoneSummaries(private val waitMs: Long = 2_000, private val keepMs: Long = 30 * 60_000) {
    private class Seen(val text: Int, val at: Long)
    private class Pending(val group: String, val text: Int, val at: Long)

    private val lastMessage = HashMap<String, Seen>()
    private val buzzed = HashMap<String, Seen>()
    private val pending = HashMap<String, Pending>()

    /** A message of [group] with [text] arrived; false when its group's summary already buzzed for that text. */
    fun message(group: String, text: Int, at: Long): Boolean {
        lastMessage[group] = Seen(text, at)
        if (lastMessage.size > 200) lastMessage.clear()
        val b = buzzed[group] ?: return true
        if (b.text != text || at - b.at > keepMs) return true
        buzzed -= group
        return false
    }

    /** Summary [key] of [group] arrived with new [text]; true when it should be checked again [waitMs] from now with [due]. */
    fun summary(key: String, group: String, text: Int, at: Long): Boolean {
        val m = lastMessage[group]
        if (m != null && (at - m.at <= waitMs || m.text == text && at - m.at <= keepMs)) return false
        pending[key] = Pending(group, text, at)
        if (pending.size > 50) pending.clear()
        return true
    }

    /** Whether summary [key], checked again at [at], still has no message after it and should buzz as one. */
    fun due(key: String, at: Long): Boolean {
        val p = pending.remove(key) ?: return false
        val m = lastMessage[p.group]
        if (m != null && m.at >= p.at - waitMs) return false
        buzzed[p.group] = Seen(p.text, at)
        if (buzzed.size > 200) buzzed.clear()
        return true
    }

    /** Summary [key] went from the phone before its check. */
    fun removed(key: String) { pending -= key }
}
