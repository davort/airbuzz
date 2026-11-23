package com.davort.airbuzz.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davort.airbuzz.Engine
import com.davort.airbuzz.data.ChatBook
import com.davort.airbuzz.data.ChatOption
import com.davort.airbuzz.data.ChatSource
import com.davort.airbuzz.data.Kind
import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Chat
import com.davort.airbuzz.rules.Settings

/** Apps whose messages name their chat, so the chat sheet can pick some of them (#33). The rest buzz app-wide. */
val CHAT_APPS = listOf("com.whatsapp", "org.telegram.messenger", "com.discord")

/** An app's second line in Apps that buzz: "All messages", or how many chats it is limited to. */
fun chatsSummary(chats: List<Chat>?): String = when (chats?.size) {
    null -> "All messages"
    0 -> "No chats ticked"
    1 -> "Only 1 chat"
    else -> "Only ${chats.size} chats"
}

/** An app as Home's Messages blank names it: "WhatsApp", or "3 WhatsApp chats" when only some chats buzz. */
fun appInSentence(label: String, chats: List<Chat>?): String = when (chats?.size) {
    null -> label
    0 -> "no $label chats"
    1 -> "1 $label chat"
    else -> "${chats.size} $label chats"
}

/** Whether [o] is one of [chosen]: by ID when both have one, otherwise by name, as the engine pins a typed chat. */
private fun isChosen(chosen: List<Chat>, o: ChatOption) = chosen.any {
    (o.id != null && it.id == o.id) || ((o.id == null || it.id == null) && it.name.equals(o.name, ignoreCase = true))
}

/**
 * The fixed note that people in Who can ring buzz when they message directly, in any app (#33). It holds only in
 * Chosen, the one mode with a list of people, so it shows nothing otherwise.
 */
@Composable
fun RingersNote(s: Settings) {
    if (s.callMode != CallMode.CHOSEN || s.people.isEmpty()) return
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(a.bg).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 1.dp)) { CurveIcon(Curve.PHONE, 18.dp, a.muted, 1.8f) }
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = a.ink, fontWeight = FontWeight.Medium)) { append(summarize(s.people.map { it.name }, "")) }
            append(" from Who can ring always buzz when they message you directly, in any\u00A0app.")
        }, style = Type.small.copy(lineBreak = LineBreak.Paragraph), color = a.muted)
    }
}

/**
 * One app's chats (#33): "All messages" or "Only these chats", then the chats AirBuzz can offer, in the engine's
 * order. Android lists no app's chats, so a new install starts nearly empty: the optional contacts card, typing a
 * name, and a note that more chats appear as they message fill the gap.
 */
@Composable
fun ChatsSheet(engine: Engine, s: Settings, pkg: String, onClose: () -> Unit) {
    val a = LocalAirColors.current
    val context = LocalContext.current
    val app = remember(pkg) { appLabel(context, pkg) }
    val seen by engine.chatBook.seen.collectAsStateWithLifecycle()
    val resumed = rememberResumeTick()
    var contactsOk by remember { mutableStateOf(ChatBook.hasContacts(context)) }
    LaunchedEffect(resumed) { contactsOk = ChatBook.hasContacts(context) }
    val chosen = s.chats[pkg]
    val options = remember(pkg, seen, chosen, contactsOk) {
        val offered = engine.chatOptions(pkg)
        // A ticked chat that hasn't written for a month has left the chat book; it stays listed so it can be unticked.
        offered + chosen.orEmpty().filter { c -> c.id != null && offered.none { it.id == c.id } }
            .map { ChatOption(pkg, it.id, it.name, it.group, ChatSource.SEEN) }
    }
    // Messages per chat this week, from the activity log, for the rows' labels.
    val week = remember(pkg) {
        engine.log.entries.value.filter { it.kind == Kind.MESSAGE && it.pkg == pkg && it.chatId != null }.groupingBy { it.chatId }.eachCount()
    }
    // Switching to All messages and back within one visit keeps the ticks.
    var kept by remember { mutableStateOf<List<Chat>?>(null) }
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var fromLink by remember { mutableStateOf(false) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        contactsOk = ok
        if (!ok) {
            engine.store.update { it.copy(contactsNotNow = true) }
            // Once Android stops asking, the link can only send the person to AirBuzz's settings to allow Contacts.
            val activity = context as? Activity
            if (fromLink && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CONTACTS))
                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
    val canList = pkg in ChatBook.CONTACT_ACCOUNTS && !contactsOk
    val contactsCard = canList && !s.contactsNotNow
    val contacts = options.count { it.source == ChatSource.CONTACT }
    val chats = options.count { it.source == ChatSource.ON_PHONE_NOW || it.source == ChatSource.SEEN }

    fun setChats(list: List<Chat>?) = engine.store.update { st -> st.copy(chats = if (list == null) st.chats - pkg else st.chats + (pkg to list)) }
    fun toggle(o: ChatOption) {
        val list = s.chats[pkg].orEmpty()
        setChats(if (isChosen(list, o)) list.filterNot { isChosen(listOf(it), o) } else list + Chat(o.id, o.name, o.group))
    }

    Sheet("$app chats", onClose) {
        Label(buildString {
            append(when (chats) { 0 -> "No chats yet"; 1 -> "1 chat so far"; else -> "$chats chats so far" })
            if (contacts > 0) append(" · $contacts contacts")
        }, Modifier.padding(bottom = 4.dp).offset(y = (-10).dp))
        Seg(listOf(false to "All messages", true to "Only these chats"), chosen != null) { only ->
            if (only) setChats(kept ?: emptyList()) else { kept = chosen; setChats(null) }
        }
        ScrollUnderFade {
            if (chosen == null) {
                Text("Every $app message buzzes, from every chat. To pick some, choose Only these\u00A0chats.",
                    style = Type.small.copy(lineBreak = LineBreak.Paragraph), color = a.muted)
                return@ScrollUnderFade
            }
            Column {
                if (contactsOk && contacts > 0 || options.size > 8) {
                    SearchField(query, "Search chats") { query = it }
                    Gap(16)
                }
                val shown = options.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                // Chats showing now and contacts get a heading; chats seen earlier and typed ones follow under "Earlier"
                // only when a heading came before them, so a plain list stays plain.
                val headed = shown.any { it.source == ChatSource.ON_PHONE_NOW || it.source == ChatSource.CONTACT }
                var section: String? = null
                var first = true
                shown.forEach { o ->
                    val title = when (o.source) {
                        ChatSource.ON_PHONE_NOW -> "On your phone now"
                        ChatSource.CONTACT -> "Your contacts on $app"
                        else -> if (headed) "Earlier" else null
                    }
                    if (title != null && title != section) {
                        if (section != null || !first) Gap(16)
                        Label(title)
                        Gap(4)
                        section = title
                        first = true
                    }
                    ChatRow(o, isChosen(chosen, o), week[o.id] ?: 0, first) { toggle(o) }
                    first = false
                }
            }
            if (contactsCard) ContactsCard(app, onNotNow = { engine.store.update { it.copy(contactsNotNow = true) } }) {
                fromLink = false
                askContacts.launch(Manifest.permission.READ_CONTACTS)
            } else if (options.size < 3 && query.isBlank()) SparseCard(app)
            Column {
                if (typing) TypeChat { name ->
                    typing = false
                    if (name.isNotBlank() && chosen.none { it.name.equals(name.trim(), ignoreCase = true) }) setChats(chosen + Chat(null, name.trim(), false))
                } else TextAction("Type a chat\u2019s name", { typing = true })
                if (canList && s.contactsNotNow) TextAction("List contacts who use $app", {
                    fromLink = true
                    askContacts.launch(Manifest.permission.READ_CONTACTS)
                }, curve = Curve.PERSON)
            }
            Text(buildString {
                append("Only ticked chats buzz.")
                if (s.callMode == CallMode.CHOSEN && s.people.isNotEmpty())
                    append(" ${summarize(s.people.map { it.name }, "")} from Who can ring still buzz when they message you\u00A0directly.")
            }, style = Type.small.copy(lineBreak = LineBreak.Paragraph), color = a.muted)
        }
    }
}

@Composable
private fun ChatRow(o: ChatOption, on: Boolean, week: Int, first: Boolean, onClick: () -> Unit) {
    val a = LocalAirColors.current
    LineRow(first, onClick) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(a.bg), contentAlignment = Alignment.Center) {
            if (o.group) CurveIcon(Curve.PEOPLE, 18.dp, a.ink, 1.8f)
            else Text(o.name.take(1).uppercase(), style = Type.bodyMedium, color = a.ink)
        }
        val kind = if (o.group) "Group" else "Person"
        val sub = when {
            o.source == ChatSource.TYPED -> "Typed · waits for a message"
            o.source == ChatSource.ON_PHONE_NOW -> "$kind · New message"
            week > 0 -> "$kind · $week this week"
            else -> kind
        }
        TwoLine(o.name, Modifier.weight(1f)) { Label(sub) }
        Mark(on, radio = false)
    }
}

/** The optional ask to list contacts who use [app] (Davor, 23 Nov): why, what stays private, and a clear way to skip. */
@Composable
private fun ContactsCard(app: String, onNotNow: () -> Unit, onShow: () -> Unit) {
    val a = LocalAirColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(a.bg).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("List your contacts who use\u00A0$app?", style = Type.bodyMedium.copy(lineBreak = LineBreak.Heading), color = a.ink)
        Text("Then you can pick people before they message you. Your contacts stay on this phone. AirBuzz has no internet access, so it can\u2019t send them\u00A0anywhere.",
            style = Type.small.copy(lineBreak = LineBreak.Paragraph), color = a.muted)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onNotNow).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text("Not now", style = Type.bodyMedium, color = a.ink, modifier = Modifier.padding(vertical = 12.dp))
            }
            Pill("Show contacts", onShow, Modifier.heightIn(max = 48.dp), filled = false)
        }
    }
}

/** A new install's list is short: say plainly where more chats come from. */
@Composable
private fun SparseCard(app: String) {
    val a = LocalAirColors.current
    Column(Modifier.fillMaxWidth().drawBehind {
        val r = 24.dp.toPx()
        drawRoundRect(a.outline, cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
    }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("More chats show up as they message\u00A0you", style = Type.bodyMedium.copy(lineBreak = LineBreak.Heading), color = a.ink)
        Text("Every $app chat that messages you while AirBuzz is on joins this list. To add one now, type its\u00A0name.",
            style = Type.small.copy(lineBreak = LineBreak.Paragraph), color = a.muted)
    }
}

/** Typing a chat's name: it is ticked straight away and pinned to the chat when that chat next writes. */
@Composable
private fun TypeChat(onDone: (String) -> Unit) {
    val a = LocalAirColors.current
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, a.outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (name.isEmpty()) Text("Chat or person\u2019s name", style = Type.body, color = a.muted)
            BasicTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                textStyle = Type.body.copy(color = a.ink), cursorBrush = SolidColor(a.accent),
                keyboardOptions = KeyboardOptions(KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(name) }))
        }
        Spacer(Modifier.width(0.dp))
        Pill("Add", { onDone(name) }, filled = false, small = true)
    }
}
