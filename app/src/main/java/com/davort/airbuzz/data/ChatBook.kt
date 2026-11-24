package com.davort.airbuzz.data

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.provider.ContactsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Where a chat offered in the chat sheet comes from, in the sheet's order (issue #33). */
enum class ChatSource { ON_PHONE_NOW, CONTACT, SEEN, TYPED }

/** A chat the sheet can offer. [id] is null for a contact or typed chat that hasn't messaged yet. */
data class ChatOption(val pkg: String, val id: String?, val name: String, val group: Boolean, val source: ChatSource)

/** A chat AirBuzz has seen in a notification. Only its ID, name, kind and picture are kept, never message text. */
data class SeenChat(val pkg: String, val id: String, val name: String, val group: Boolean, val lastSeen: Long)

/**
 * The chats AirBuzz has seen per app, kept on the phone and forgotten after [KEEP_MS] without a message. Android gives
 * no app another app's chat list, so this, the notifications showing now and contacts are all the chat sheet can offer.
 */
class ChatBook(private val context: Context) {
    private val file = File(context.filesDir, "chats.json")
    private val photoDir = File(context.filesDir, "chat-photos")
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

    /**
     * Keeps a small copy of the picture an app put on a chat's notification (for a WhatsApp group, the group's own
     * picture). A stand-in such as WhatsApp's grey silhouette for someone without a photo isn't kept, and replaces any
     * copy saved before, since the person has removed their photo.
     */
    fun savePhoto(pkg: String, id: String, icon: Icon?) {
        val f = photoFile(pkg, id)
        val bitmap = icon?.let { runCatching { draw(it) }.getOrNull() } ?: return
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        if (isPlaceholder(pixels)) { f.delete(); return }
        runCatching {
            photoDir.mkdirs()
            val tmp = File(photoDir, f.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(f)
        }
    }

    /**
     * The picture to show for [o]: for a person, their phone-book photo when AirBuzz may read contacts (read each time,
     * never copied), otherwise the chat's saved picture. Null means show initials. Reads files and contacts, so call it
     * off the main thread.
     */
    fun photo(o: ChatOption): Bitmap? {
        if (!o.group) contactPhoto(o.pkg, o.name)?.let { return it }
        val f = o.id?.let { photoFile(o.pkg, it) }?.takeIf { it.exists() } ?: return null
        return runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
    }

    private fun contactPhoto(pkg: String, name: String): Bitmap? {
        if (!hasContacts(context)) return null
        val type = CONTACT_ACCOUNTS[pkg] ?: return null
        return runCatching {
            val r = context.contentResolver
            val contactId = r.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DELETED} = 0 AND " +
                    "${ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE",
                arrayOf(type, name), null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            ContactsContract.Contacts.openContactPhotoInputStream(r, uri)?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    private fun draw(icon: Icon): Bitmap? {
        val d = icon.loadDrawable(context) ?: return null
        return Bitmap.createBitmap(PHOTO_PX, PHOTO_PX, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, PHOTO_PX, PHOTO_PX)
            d.draw(Canvas(it))
        }
    }

    private fun photoFile(pkg: String, id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("$pkg\u0000$id".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return File(photoDir, "$hash.png")
    }

    /** Deletes the pictures of chats no longer kept, so a picture goes 30 days after the chat's last message. */
    private fun dropOrphanPhotos(chats: List<SeenChat>) {
        val keep = chats.map { photoFile(it.pkg, it.id).name }.toSet()
        photoDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
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
        dropOrphanPhotos(state.value)
    }

    private fun load(): List<SeenChat> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SeenChat(o.getString("p"), o.getString("i"), o.getString("n"), o.getBoolean("g"), o.getLong("t"))
        }
    }.getOrDefault(emptyList()).let { prune(it, System.currentTimeMillis()) }
        .also(::dropOrphanPhotos)

    companion object {
        const val KEEP_MS = 30L * 24 * 3600_000
        const val PHOTO_PX = 96

        /**
         * True for a stand-in picture rather than a photo: every visible pixel is grey, like WhatsApp's silhouette for
         * someone without a profile photo (seen on Davor's phone, 24 Nov). A black-and-white photo is dropped too, and
         * shows initials instead.
         */
        fun isPlaceholder(argb: IntArray): Boolean = argb.none { p ->
            val r = p shr 16 and 0xff; val g = p shr 8 and 0xff; val b = p and 0xff
            (p ushr 24) > 128 && maxOf(r, g, b) - minOf(r, g, b) > 24
        }

        /** The phone-book account type each messaging app adds its contacts under. */
        val CONTACT_ACCOUNTS = mapOf(
            "com.whatsapp" to "com.whatsapp",
            "org.telegram.messenger" to "org.telegram.messenger",
            "org.thoughtcrime.securesms" to "org.thoughtcrime.securesms",
        )

        fun hasContacts(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
