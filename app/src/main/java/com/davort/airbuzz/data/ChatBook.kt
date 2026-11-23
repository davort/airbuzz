package com.davort.airbuzz.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where a chat offered in the chat sheet comes from, in the sheet's order (issue #33). */
enum class ChatSource { ON_PHONE_NOW, CONTACT, SEEN, TYPED }

/** A chat the sheet can offer. [id] is null for a contact or typed chat that hasn't messaged yet. */
data class ChatOption(val pkg: String, val id: String?, val name: String, val group: Boolean, val source: ChatSource)

/** A chat AirBuzz has seen in a notification. Only its ID, name and kind are kept, never message text. */
data class SeenChat(val pkg: String, val id: String, val name: String, val group: Boolean, val lastSeen: Long)

/**
 * The chats AirBuzz has seen per app, kept on the phone and forgotten after [KEEP_MS] without a message. Android gives
 * no app another app's chat list, so this, the notifications showing now and contacts are all the chat sheet can offer.
 */
class ChatBook(private val context: Context) {
    private val file = File(context.filesDir, "chats.json")
    private val state = MutableStateFlow(load())
    val seen: StateFlow<List<SeenChat>> = state

    /** Chats seen so far for [pkg], for the sheet's heading. */
    fun count(pkg: String) = state.value.count { it.pkg == pkg }

    @Synchronized
    fun record(chat: SeenChat) {
        val rest = state.value.filterNot { it.pkg == chat.pkg && it.id == chat.id }
        val list = prune(rest + chat, chat.lastSeen)
        if (list == state.value) return
        state.value = list
        save()
    }

    /** The person's contacts who use [pkg], from the entries WhatsApp, Telegram and Signal add to the phone book. */
    fun contacts(pkg: String): List<ChatOption> {
        if (!hasContacts(context)) return emptyList()
        val type = CONTACT_ACCOUNTS[pkg] ?: return emptyList()
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(type), null,
            )?.use { c ->
                buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(::add) }
            }.orEmpty().distinct().sortedBy { it.lowercase() }.map { ChatOption(pkg, null, it, false, ChatSource.CONTACT) }
        }.getOrDefault(emptyList())
    }

    private fun prune(list: List<SeenChat>, now: Long) = list.filter { now - it.lastSeen < KEEP_MS }.takeLast(500)

    private fun save() {
        val a = JSONArray()
        state.value.forEach { a.put(JSONObject().put("p", it.pkg).put("i", it.id).put("n", it.name).put("g", it.group).put("t", it.lastSeen)) }
        runCatching { file.writeText(a.toString()) }
    }

    private fun load(): List<SeenChat> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SeenChat(o.getString("p"), o.getString("i"), o.getString("n"), o.getBoolean("g"), o.getLong("t"))
        }
    }.getOrDefault(emptyList()).let { prune(it, System.currentTimeMillis()) }

    companion object {
        const val KEEP_MS = 30L * 24 * 3600_000

        /** The phone-book account type each messaging app adds its contacts under. */
        val CONTACT_ACCOUNTS = mapOf(
            "com.whatsapp" to "com.whatsapp",
            "org.telegram.messenger" to "org.telegram.messenger",
            "org.thoughtcrime.securesms" to "org.thoughtcrime.securesms",
        )

        fun hasContacts(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
