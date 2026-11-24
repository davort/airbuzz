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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    null, 0 -> "All messages"
    1 -> "Only 1 chat"
    else -> "Only ${chats.size} chats"
}

/** An app as Home's Messages blank names it: "WhatsApp", or "3 WhatsApp chats" when only some chats buzz. */
fun appInSentence(label: String, chats: List<Chat>?): String = when (chats?.size) {
    null, 0 -> label
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

private enum class ChatTab { ALL, PEOPLE, GROUPS }

/** "2 people · 1 group", for the ticked chats in a sheet's subtitle. */
private fun tickCount(chats: List<Chat>): String {
    val people = chats.count { !it.group }
    val groups = chats.size - people
    return listOfNotNull(
        people.takeIf { it > 0 }?.let { if (it == 1) "1 person" else "$it people" },
        groups.takeIf { it > 0 }?.let { if (it == 1) "1 group" else "$it groups" },
    ).joinToString(" \u00B7 ")
}

/** When a chat last wrote, for its row: "Messaged 17:12", "Messaged yesterday" or "Messaged 12 Nov". */
private fun messagedAt(ms: Long): String {
    val day = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    return "Messaged " + when {
        ms >= day -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        ms >= day - 86_400_000L -> "yesterday"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
    }
}

/**
 * One app's chats (#33): All messages, People or Groups (Davor, 24 Nov). People and Groups each list their chats
 * with checkboxes, and the ticks of both add up; All messages lets every chat buzz and keeps the ticks for later.
 * Android lists no app's chats, so a new install starts nearly empty: the optional contacts card, typing a name, and
 * a card saying that more appear as they message fill the gap. Spacing follows Who can ring: 24 dp from the choice to
 * what's under it and from the content to the note, 16 dp from the note to Done.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatsSheet(engine: Engine, s: Settings, pkg: String, onClose: () -> Unit) {
    val a = LocalAirColors.current
    val context = LocalContext.current
    val app = remember(pkg) { appLabel(context, pkg) }
    val seen by engine.chatBook.seen.collectAsStateWithLifecycle()
    val resumed = rememberResumeTick()
    var contactsOk by remember { mutableStateOf(ChatBook.hasContacts(context)) }
    LaunchedEffect(resumed) { contactsOk = ChatBook.hasContacts(context) }
    // An app buzzes for all its chats until at least one is ticked (Davor, 24 Nov), so an empty list is never kept.
    val chosen = s.chats[pkg]?.takeIf { it.isNotEmpty() }
    val ticked = chosen.orEmpty()
    var tab by remember(pkg) {
        mutableStateOf(when { chosen == null -> ChatTab.ALL; chosen.all { it.group } -> ChatTab.GROUPS; else -> ChatTab.PEOPLE })
    }
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
    // While the search has the keyboard up, the choice and the button make way for results (Davor, 24 Nov). Closing the
    // keyboard ends the search's hold on the screen.
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val ime = WindowInsets.isImeVisible
    var hadIme by remember { mutableStateOf(false) }
    LaunchedEffect(ime) { if (hadIme && !ime) focus.clearFocus(); hadIme = ime }
    // Chats ticked when the sheet opened come first; a tick now doesn't move a row under the finger.
    val pinned = remember(pkg) { s.chats[pkg].orEmpty() }
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
    val groups = tab == ChatTab.GROUPS
    val canList = !groups && pkg in ChatBook.CONTACT_ACCOUNTS && !contactsOk
    val contactsCard = canList && !s.contactsNotNow
    val inTab = options.filter { it.group == groups }

    fun setChats(list: List<Chat>?) = engine.store.update { st -> st.copy(chats = if (list.isNullOrEmpty()) st.chats - pkg else st.chats + (pkg to list)) }
    fun toggle(o: ChatOption) {
        val list = s.chats[pkg].orEmpty()
        setChats(if (isChosen(list, o)) list.filterNot { isChosen(listOf(it), o) } else list + Chat(o.id, o.name, o.group))
    }
    fun add(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && ticked.none { it.name.equals(n, ignoreCase = true) && it.group == groups }) setChats(ticked + Chat(null, n, groups))
    }

    val searchable = tab != ChatTab.ALL && (inTab.count { it.source == ChatSource.CONTACT } > 0 || inTab.size > 8)
    Sheet("$app chats", onClose, actionShown = !searching) {
        AnimatedVisibility(!searching, enter = fadeIn(tween(160)) + expandVertically(tween(220, easing = Motion.InOut)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(220, easing = Motion.InOut))) {
            Column {
                // Tucked under the title as its subtitle.
                Label(when {
                    tab == ChatTab.ALL -> kept?.takeIf { it.isNotEmpty() }?.let { "${tickCount(it)} kept" } ?: "Every chat buzzes"
                    ticked.isEmpty() -> "All chats buzz until you tick one"
                    else -> tickCount(ticked) + if (ticked.size == 1) " buzzes" else " buzz"
                }, Modifier.layout { m, c ->
                    val p = m.measure(c)
                    val up = 10.dp.roundToPx()
                    layout(p.width, p.height - up) { p.place(0, -up) }
                })
                Gap(16)
                Seg(listOf(ChatTab.ALL to "All messages", ChatTab.PEOPLE to "People", ChatTab.GROUPS to "Groups"), tab) { next ->
                    if (next == tab) return@Seg
                    if (next == ChatTab.ALL) { kept = chosen; setChats(null) }
                    else if (tab == ChatTab.ALL) setChats(kept)
                    tab = next
                    query = ""
                    typing = false
                }
                // With the sheet's 16 dp, 24 dp from the choice to what's under it, as in Who can ring.
                Gap(8)
            }
        }
        if (searchable) SearchField(query, if (groups) "Search groups" else "Search people", Modifier.onFocusChanged { searching = it.isFocused }) { query = it }
        val note = s.callMode == CallMode.CHOSEN && s.people.isNotEmpty() && query.isBlank()
        ScrollUnderFade {
            // One column with explicit gaps, so every tab spaces the same way.
            Column {
                if (tab == ChatTab.ALL) AllMessagesPanel(app, !kept.isNullOrEmpty())
                else {
                    val shown = inTab.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                        .sortedBy { if (isChosen(pinned, it)) 0 else if (it.source == ChatSource.CONTACT) 2 else 1 }
                    var section: String? = null
                    var first = true
                    shown.forEach { o ->
                        val title = when {
                            isChosen(pinned, o) -> if (groups) "Your groups" else "Your people"
                            o.source == ChatSource.CONTACT -> "Your contacts on $app"
                            groups -> if (shown.any { isChosen(pinned, it) }) "Other groups" else null
                            else -> "Recent"
                        }
                        if (title != section) {
                            if (section != null || !first) Gap(16)
                            if (title != null) { Label(title); Gap(4) }
                            section = title
                            first = true
                        }
                        val last = seen.firstOrNull { it.pkg == pkg && it.group == o.group && (if (o.id != null) it.id == o.id else it.name.equals(o.name, ignoreCase = true)) }
                        val sub = when {
                            o.source == ChatSource.TYPED -> "Typed \u00B7 waits for a message"
                            (week[last?.id ?: o.id] ?: 0) >= 2 -> "${week[last?.id ?: o.id]} this week"
                            last != null -> messagedAt(last.lastSeen)
                            else -> null
                        }
                        ChatRow(o, isChosen(ticked, o), sub, first) { toggle(o) }
                        first = false
                    }
                    // A name that isn't listed yet can be added straight from the search, once no listed name starts with it.
                    val typed = query.trim()
                    if (typed.isNotEmpty() && shown.none { it.name.startsWith(typed, ignoreCase = true) }) TextAction("Add \u201C$typed\u201D as a ${if (groups) "group" else "person"}", {
                        add(typed)
                        query = ""
                        focus.clearFocus()
                    })
                    if (query.isBlank()) {
                        val sparse = if (groups) inTab.isEmpty() else !contactsCard && inTab.size < 3
                        // The last row ends with 10 dp of its own padding.
                        if (shown.isNotEmpty()) Gap(4)
                        if (contactsCard) ContactsCard(app, onNotNow = { engine.store.update { it.copy(contactsNotNow = true) } }) {
                            fromLink = false
                            askContacts.launch(Manifest.permission.READ_CONTACTS)
                        } else if (sparse) SparseCard(app, groups)
                        if (contactsCard || sparse) Gap(12)
                        // The action's row is 48 dp for the finger; its text sits 24 dp under the content above.
                        if (typing) TypeChat(if (groups) "Group\u2019s name" else "Person\u2019s name") { name -> typing = false; add(name) }
                        else ChatAction(if (groups) "Type a group\u2019s name" else "Type a person\u2019s name") { typing = true }
                        if (sparse) Text("Spell it the way $app shows it. Upper or lower case both\u00A0work.",
                            style = Type.small.copy(lineBreak = LineBreak.Paragraph), color = a.muted,
                            modifier = Modifier.padding(start = 34.dp).layout { m, c ->
                                val p = m.measure(c)
                                val up = 8.dp.roundToPx()
                                layout(p.width, p.height - up) { p.place(0, -up) }
                            })
                        if (canList && s.contactsNotNow) ChatAction("List contacts who use $app", Curve.PERSON) {
                            fromLink = true
                            askContacts.launch(Manifest.permission.READ_CONTACTS)
                        }
                    }
                }
                if (note) {
                    // The action rows end with 12 dp of touch room under their text.
                    Gap(if (tab == ChatTab.ALL) 24 else 12)
                    RingersNote(s)
                }
            }
        }
    }
}

/** All messages: the Who can ring pattern, an icon over one sentence that says what will happen. */
@Composable
private fun AllMessagesPanel(app: String, kept: Boolean) {
    val a = LocalAirColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {}) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(a.bg), contentAlignment = Alignment.Center) {
            CurveIcon(Curve.CHAT, 24.dp, a.ink, 1.8f)
        }
        Gap(8)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = a.accent, fontWeight = FontWeight(500))) { append("Every $app message") }
            append(" will buzz your Fitbit\u00A0Air.")
        }, style = Type.body.copy(fontSize = 20.sp, lineHeight = 28.sp, lineBreak = LineBreak.Heading), color = a.ink)
        Gap(6)
        Text(if (kept) "The chats you ticked are kept for when you switch\u00A0back." else "To pick some, choose People or\u00A0Groups.",
            style = Type.small.copy(lineBreak = LineBreak.Heading), color = a.muted)
    }
}

/** An accent action in the chat sheet: a 48 dp row whose text sits 12 dp in from its top and bottom. */
@Composable
private fun ChatAction(text: String, curve: Curve? = null, onClick: () -> Unit) {
    val a = LocalAirColors.current
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        if (curve != null) CurveIcon(curve, 22.dp, a.accent) else GlyphIcon(Glyph.PLUS, 22.dp, a.accent)
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.bodyMedium, color = a.accent)
    }
}

/** A chat with its first letter or the group mark, when it last wrote, and its checkbox. People need no "Person" line. */
@Composable
private fun ChatRow(o: ChatOption, on: Boolean, sub: String?, first: Boolean, onClick: () -> Unit) {
    val a = LocalAirColors.current
    LineRow(first, onClick) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(a.bg), contentAlignment = Alignment.Center) {
            if (o.group) CurveIcon(Curve.PEOPLE, 18.dp, a.ink, 1.8f)
            else Text(o.name.take(1).uppercase(), style = Type.bodyMedium, color = a.ink)
        }
        TwoLine(o.name, Modifier.weight(1f), sub?.let { { Label(it) } })
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

/** A new install's list is short: say plainly where more people or groups come from. */
@Composable
private fun SparseCard(app: String, groups: Boolean) {
    val a = LocalAirColors.current
    Column(Modifier.fillMaxWidth().drawBehind {
        val r = 24.dp.toPx()
        drawRoundRect(a.outline, cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
    }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (groups) "Groups show up here after they message\u00A0you" else "People show up here after they message\u00A0you",
            style = Type.bodyMedium.copy(lineBreak = LineBreak.Heading), color = a.ink)
        Text(if (groups) "Every $app group that messages you while AirBuzz is on joins this list. To add one now, type its\u00A0name."
            else "Everyone who messages you on $app while AirBuzz is on joins this list. To add someone now, type their\u00A0name.",
            style = Type.small.copy(lineBreak = LineBreak.Paragraph), color = a.muted)
    }
}

/** Typing a chat's name: it is ticked straight away and pinned to the chat when that chat next writes. */
@Composable
private fun TypeChat(hint: String, onDone: (String) -> Unit) {
    val a = LocalAirColors.current
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, a.outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (name.isEmpty()) Text(hint, style = Type.body, color = a.muted)
            BasicTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                textStyle = Type.body.copy(color = a.ink), cursorBrush = SolidColor(a.accent),
                keyboardOptions = KeyboardOptions(KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(name) }))
        }
        Spacer(Modifier.width(0.dp))
        Pill("Add", { onDone(name) }, filled = false, small = true)
    }
}
