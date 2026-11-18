package com.davort.airbuzz.rules

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

enum class CallMode { CHOSEN, CONTACTS, ANYONE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A chosen caller: the name from the contact picker and its number in E.164 when it could be parsed. */
data class Person(val name: String, val number: String?)

/** Everything the user sets. Defaults follow the brief's decisions: quiet by default, all three don't-buzz rules on. */
data class Settings(
    val enabled: Boolean = false,
    val callMode: CallMode = CallMode.CHOSEN,
    val people: List<Person> = emptyList(),
    val apps: Set<String> = emptySet(),
    /** Taps per message: 1, 2 or 3. */
    val messageTaps: Int = 1,
    val strong: Boolean = false,
    val quietDnd: Boolean = true,
    val quietHours: Boolean = true,
    /** Minutes after midnight. */
    val quietStart: Int = 23 * 60,
    val quietEnd: Int = 7 * 60,
    val quietInUse: Boolean = true,
    /** Epoch millis when a pause ends. Pausing until switched back on is Off instead (decision 37). */
    val pausedUntil: Long = 0L,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val lightAccent: Int = 0,
    val darkAccent: Int = 0,
    /** False until onboarding is finished (decision 39). */
    val onboarded: Boolean = false,
    /** True once Home's › after "Today" has nudged, the one-time hint that the line opens Activity. */
    val activityHinted: Boolean = false,
    /** Whether [ringAnywayPeople] ring through quiet times and a pause. Starts off, with nobody in the list. */
    val ringAnyway: Boolean = false,
    val ringAnywayPeople: List<Person> = emptyList(),
    /** Numbers, or names when there is no number, dismissed from "Called recently". */
    val hiddenCallers: Set<String> = emptySet(),
)

/** Who is calling, as far as the call notification says. */
data class Caller(val name: String?, val number: String?) {
    /** A caller shown by name rather than a bare number is someone in the phone's (or the app's) contacts. */
    val isContact: Boolean get() = !name.isNullOrBlank() && !Rules.looksLikeNumber(name)
}

/** The phone's state when an alert arrives. */
data class Moment(val nowMs: Long, val minuteOfDay: Int, val doNotDisturb: Boolean, val phoneInUse: Boolean)

sealed interface Outcome {
    data object Buzz : Outcome
    /** Within [Rules.MERGE_MS] of the last buzz for the same app. */
    data object Merge : Outcome
    data class Skip(val reason: String) : Outcome
}

object Rules {
    const val MERGE_MS = 15_000L

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    fun looksLikeNumber(text: String): Boolean = text.count(Char::isDigit) >= 5 && text.all { it.isDigit() || it in "+-() ./" }

    /** E.164 for [raw] in [region] (an ISO country code such as "SE"), or its digits when it can't be parsed. */
    fun normalize(raw: String, region: String): String? {
        val digits = raw.filter { it.isDigit() || it == '+' }
        if (digits.count(Char::isDigit) < 3) return null
        return try {
            val parsed = phoneUtil.parse(raw, region.uppercase())
            phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: NumberParseException) {
            digits
        }
    }

    /** A stored number as people write it, such as "+385 91 234 5612". */
    fun display(number: String): String = try {
        phoneUtil.format(phoneUtil.parse(number, "ZZ"), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
    } catch (_: NumberParseException) {
        number
    }

    fun matches(person: Person, caller: Caller, region: String): Boolean {
        val callerNumber = caller.number?.let { normalize(it, region) }
        if (person.number != null && callerNumber != null) return person.number == callerNumber
        val name = caller.name?.trim()
        return !name.isNullOrEmpty() && name.equals(person.name.trim(), ignoreCase = true)
    }

    /** The first don't-buzz rule that applies now, or null. Pause counts as one. */
    fun quietReason(s: Settings, m: Moment): String? = when {
        s.pausedUntil > m.nowMs -> "paused"
        s.quietDnd && m.doNotDisturb -> "Do Not Disturb"
        s.quietHours && inWindow(m.minuteOfDay, s.quietStart, s.quietEnd) -> "quiet hours"
        s.quietInUse && m.phoneInUse -> "phone in use"
        else -> null
    }

    /** Whether [minute] falls in [start, end), where the window may cross midnight. */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start == end) false else if (start < end) minute in start until end else minute >= start || minute < end

    /**
     * Who may ring comes from the call mode alone; quiet times and a pause then silence everyone except the ring-anyway
     * people, while ring anyway is on. Ring anyway never lets in someone the mode doesn't (Davor, 18 Nov). Off silences
     * everyone.
     */
    fun decideCall(caller: Caller, s: Settings, m: Moment, region: String): Outcome {
        if (!s.enabled) return Outcome.Skip("AirBuzz is off")
        val allowed = when (s.callMode) {
            CallMode.CHOSEN -> s.people.any { matches(it, caller, region) }
            CallMode.CONTACTS -> caller.isContact
            CallMode.ANYONE -> true
        }
        if (!allowed) return Outcome.Skip("not chosen")
        val quiet = quietReason(s, m) ?: return Outcome.Buzz
        return if (ringsAnyway(caller, s, region)) Outcome.Buzz else Outcome.Skip(quiet)
    }

    fun ringsAnyway(caller: Caller, s: Settings, region: String): Boolean =
        s.ringAnyway && s.ringAnywayPeople.any { matches(it, caller, region) }

    fun decideMessage(pkg: String, s: Settings, m: Moment, lastBuzzForApp: Long?): Outcome {
        if (!s.enabled) return Outcome.Skip("AirBuzz is off")
        if (pkg !in s.apps) return Outcome.Skip("not chosen")
        quietReason(s, m)?.let { return Outcome.Skip(it) }
        if (lastBuzzForApp != null && m.nowMs - lastBuzzForApp < MERGE_MS) return Outcome.Merge
        return Outcome.Buzz
    }
}
