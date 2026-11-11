package com.davort.airbuzz.data

import android.content.Context
import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Person
import com.davort.airbuzz.rules.Settings
import com.davort.airbuzz.rules.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The user's settings, kept as one JSON value in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state

    fun update(change: (Settings) -> Settings) {
        synchronized(this) {
            val next = change(state.value)
            state.value = next
            prefs.edit().putString("v1", encode(next).toString()).apply()
        }
    }

    private fun load(): Settings = prefs.getString("v1", null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() } ?: Settings()

    private fun encode(s: Settings) = JSONObject().apply {
        put("enabled", s.enabled)
        put("callMode", s.callMode.name)
        put("people", JSONArray(s.people.map { JSONObject().put("name", it.name).put("number", it.number ?: JSONObject.NULL) }))
        put("apps", JSONArray(s.apps.toList()))
        put("messageTaps", s.messageTaps)
        put("strong", s.strong)
        put("quietDnd", s.quietDnd)
        put("quietHours", s.quietHours)
        put("quietStart", s.quietStart)
        put("quietEnd", s.quietEnd)
        put("quietInUse", s.quietInUse)
        put("pausedUntil", s.pausedUntil)
        put("theme", s.theme.name)
        put("lightAccent", s.lightAccent)
        put("darkAccent", s.darkAccent)
    }

    private fun decode(o: JSONObject): Settings {
        val d = Settings()
        val people = o.optJSONArray("people")?.let { a ->
            (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                Person(p.getString("name"), if (p.isNull("number")) null else p.getString("number"))
            }
        } ?: d.people
        val apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: d.apps
        return Settings(
            enabled = o.optBoolean("enabled", d.enabled),
            callMode = runCatching { CallMode.valueOf(o.getString("callMode")) }.getOrDefault(d.callMode),
            people = people,
            apps = apps,
            messageTaps = o.optInt("messageTaps", d.messageTaps),
            strong = o.optBoolean("strong", d.strong),
            quietDnd = o.optBoolean("quietDnd", d.quietDnd),
            quietHours = o.optBoolean("quietHours", d.quietHours),
            quietStart = o.optInt("quietStart", d.quietStart),
            quietEnd = o.optInt("quietEnd", d.quietEnd),
            quietInUse = o.optBoolean("quietInUse", d.quietInUse),
            pausedUntil = o.optLong("pausedUntil", d.pausedUntil),
            theme = runCatching { ThemeMode.valueOf(o.getString("theme")) }.getOrDefault(d.theme),
            lightAccent = o.optInt("lightAccent", d.lightAccent),
            darkAccent = o.optInt("darkAccent", d.darkAccent),
        )
    }
}

enum class Kind { CALL, MESSAGE, TEST }

enum class Result { BUZZED, MERGED, SKIPPED, FAILED }

/** One alert and what AirBuzz did with it. No message text is kept, only who it was from. */
data class Entry(
    val time: Long,
    val kind: Kind,
    val pkg: String,
    val appLabel: String,
    val who: String?,
    val number: String?,
    val result: Result,
    val reason: String? = null,
    /** Messages merged into this entry's buzz, including its own. */
    val count: Int = 1,
    /** How long a call rang the band. */
    val rangMs: Long? = null,
    /** From the alert to the band's first tap. */
    val latencyMs: Long? = null,
)

/** A week of alerts, kept in a JSON file in the app's private storage. */
class ActivityLog(context: Context) {
    private val file = File(context.filesDir, "activity.json")
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = state

    @Synchronized
    fun add(e: Entry) {
        state.value = prune(state.value + e)
        save()
    }

    /** Replaces the newest entry that [match]es; returns false when there is none. */
    @Synchronized
    fun updateLast(match: (Entry) -> Boolean, change: (Entry) -> Entry): Boolean {
        val list = state.value
        val i = list.indexOfLast(match)
        if (i < 0) return false
        state.value = list.toMutableList().also { it[i] = change(it[i]) }
        save()
        return true
    }

    private fun prune(list: List<Entry>): List<Entry> {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600_000
        return list.filter { it.time >= cutoff }.takeLast(3000)
    }

    private fun save() {
        val a = JSONArray()
        state.value.forEach { e ->
            a.put(JSONObject().apply {
                put("t", e.time); put("k", e.kind.name); put("p", e.pkg); put("a", e.appLabel)
                put("w", e.who ?: JSONObject.NULL); put("n", e.number ?: JSONObject.NULL); put("r", e.result.name)
                put("why", e.reason ?: JSONObject.NULL); put("c", e.count)
                put("rang", e.rangMs ?: JSONObject.NULL); put("lat", e.latencyMs ?: JSONObject.NULL)
            })
        }
        runCatching { file.writeText(a.toString()) }
    }

    private fun load(): List<Entry> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Entry(
                time = o.getLong("t"), kind = Kind.valueOf(o.getString("k")), pkg = o.getString("p"),
                appLabel = o.getString("a"), who = o.optStringOrNull("w"), number = o.optStringOrNull("n"),
                result = Result.valueOf(o.getString("r")), reason = o.optStringOrNull("why"), count = o.optInt("c", 1),
                rangMs = if (o.isNull("rang")) null else o.getLong("rang"),
                latencyMs = if (o.isNull("lat")) null else o.getLong("lat"),
            )
        }
    }.getOrDefault(emptyList()).let(::prune)

    private fun JSONObject.optStringOrNull(key: String) = if (isNull(key)) null else optString(key)
}
