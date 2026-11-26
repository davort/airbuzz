package com.davort.airbuzz.rules

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

enum class CallMode { CHOSEN, CONTACTS, ANYONE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A chosen caller: the name from the contact picker and its number in E.164 when it could be parsed. */
data class Person(val name: String, val number: String?)

/**
 * A chat within an app: a person, a group or a channel (issue #33). [id] is the app's conversation shortcut ID, which
 * survives renames. It is null for a chat typed by name or picked from contacts until it first messages, when the
 * engine pins it to the ID.
 */
data class Chat(val id: String?, val name: String, val group: Boolean)

/**
 * Which chat a message notification came from. [chatId] is null when the app doesn't post conversations (email, a
 * Telegram channel), and [sender] is who wrote the newest message, when the app says.
 */
data class MessageFrom(
    val chatId: String? = null,
    val chatName: String? = null,
    val group: Boolean = false,
    val sender: Caller? = null,
    /** The phone shows it without sound or vibration, such as a chat muted in its app. */
    val silentOnPhone: Boolean = false,
)

/**
 * Everything the user sets. Defaults follow the brief's decisions: quiet at night and in Do Not Disturb. Quiet while
 * using the phone starts off: it silenced a tester's test calls (Davor, 26 Nov).
 */
data class Settings(
    val enabled: Boolean = false,
    /** Anyone until changed: a call buzzing that wasn't wanted beats a wanted call missed (Davor, 26 Nov). */
    val callMode: CallMode = CallMode.ANYONE,
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
    val quietInUse: Boolean = false,
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
    /** Chosen apps set to "Only these chats", with their chats (issue #33). An app missing here buzzes for all messages. */
    val chats: Map<String, List<Chat>> = emptyMap(),
    /** The person chose "Not now" on the card that asks to list contacts who use a messaging app. */
    val contactsNotNow: Boolean = false,
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

    /** A direct message, in an app that tags conversations, from someone Chosen mode lets ring. */
    fun canRingDirect(from: MessageFrom, s: Settings, region: String): Boolean =
        s.callMode == CallMode.CHOSEN && from.chatId != null && !from.group &&
            from.sender?.let { sender -> s.people.any { matches(it, sender, region) } } == true

    /** By ID once known; a typed or contact chat matches by name until it is pinned. */
    fun chatMatches(chat: Chat, from: MessageFrom): Boolean =
        if (chat.id != null) chat.id == from.chatId
        else !from.chatName.isNullOrBlank() && from.chatName.trim().equals(chat.name.trim(), ignoreCase = true)

    private val countSuffix = Regex("""\s*\(\d+ [^()]*\)$""")

    /** A chat's name without a count the app adds while messages wait, as WhatsApp's "Family (3 messages)". */
    fun cleanChatName(raw: String?): String? = raw?.replace(countSuffix, "")?.trim()?.takeIf { it.isNotEmpty() }

    fun ringsAnyway(caller: Caller, s: Settings, region: String): Boolean =
        s.ringAnyway && s.ringAnywayPeople.any { matches(it, caller, region) }

    /**
     * A chosen app buzzes for all its messages, or only for its chosen chats. Someone who can ring also buzzes when they
     * message directly, from any app, even one not chosen; not in a group (Davor, 23 Nov). That counts only in Chosen
     * mode: in Contacts or Anyone, it would make every direct message from anyone buzz.
     */
    fun decideMessage(pkg: String, from: MessageFrom, s: Settings, m: Moment, lastBuzzForApp: Long?, region: String): Outcome {
        if (!s.enabled) return Outcome.Skip("AirBuzz is off")
        if (!canRingDirect(from, s, region)) {
            if (pkg !in s.apps) return Outcome.Skip("not chosen")
            val only = s.chats[pkg]
            if (only != null && only.none { chatMatches(it, from) }) return Outcome.Skip("chat not chosen")
        }
        // What the phone itself shows silently doesn't buzz the wrist either (Davor, 23 Nov).
        if (from.silentOnPhone) return Outcome.Skip("silent on the phone")
        quietReason(s, m)?.let { return Outcome.Skip(it) }
        if (lastBuzzForApp != null && m.nowMs - lastBuzzForApp < MERGE_MS) return Outcome.Merge
        return Outcome.Buzz
    }
}
