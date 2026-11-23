package com.davort.airbuzz

import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Chat
import com.davort.airbuzz.rules.MessageFrom
import com.davort.airbuzz.rules.Caller
import com.davort.airbuzz.rules.Moment
import com.davort.airbuzz.rules.Outcome
import com.davort.airbuzz.rules.Person
import com.davort.airbuzz.rules.Rules
import com.davort.airbuzz.rules.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val mum = Person("Mum", Rules.normalize("070-123 45 67", "SE"))
    private val on = Settings(enabled = true, people = listOf(mum), apps = setOf("com.whatsapp"))
    private val day = Moment(nowMs = 1_000_000, minuteOfDay = 12 * 60, doNotDisturb = false, phoneInUse = false)
    private val night = day.copy(minuteOfDay = 2 * 60)

    @Test fun numbersMatchAcrossFormats() {
        assertEquals("+46701234567", mum.number)
        assertTrue(Rules.matches(mum, Caller(null, "+46 70 123 45 67"), "SE"))
        assertTrue(Rules.matches(mum, Caller(null, "0046701234567"), "SE"))
        assertFalse(Rules.matches(mum, Caller(null, "+46 70 123 45 68"), "SE"))
    }

    @Test fun nameMatchesWhenTheAppShowsNoNumber() {
        assertTrue(Rules.matches(mum, Caller("mum", null), "SE"))
        assertFalse(Rules.matches(mum, Caller("Mummy", null), "SE"))
    }

    @Test fun quietHoursSilenceChosenPeopleToo() {
        assertEquals(Outcome.Buzz, Rules.decideCall(Caller("Mum", null), on, day, "SE"))
        assertEquals(Outcome.Skip("quiet hours"), Rules.decideCall(Caller("Mum", null), on, night, "SE"))
        val anyone = on.copy(callMode = CallMode.ANYONE)
        assertEquals(Outcome.Skip("quiet hours"), Rules.decideCall(Caller("Bob", null), anyone, night, "SE"))
        assertEquals(Outcome.Buzz, Rules.decideCall(Caller("Bob", null), anyone, day, "SE"))
    }

    @Test fun ringAnywayPeopleRingThroughQuietTimesAndPauseOnlyWhileOn() {
        // Mum calls by number; Bob is chosen but not in the ring-anyway list.
        val mumCalls = Caller(null, "+46 70 123 45 67")
        val bob = Person("Bob", null)
        val anyway = on.copy(people = listOf(mum, bob), ringAnyway = true, ringAnywayPeople = listOf(mum))
        assertEquals(Outcome.Buzz, Rules.decideCall(mumCalls, anyway, night, "SE"))
        assertEquals(Outcome.Skip("quiet hours"), Rules.decideCall(Caller("Bob", null), anyway, night, "SE"))
        assertEquals(Outcome.Skip("quiet hours"), Rules.decideCall(mumCalls, anyway.copy(ringAnyway = false), night, "SE"))
        val paused = anyway.copy(pausedUntil = Long.MAX_VALUE)
        assertEquals(Outcome.Buzz, Rules.decideCall(mumCalls, paused, day, "SE"))
        assertEquals(Outcome.Skip("paused"), Rules.decideCall(Caller("Bob", null), paused, day, "SE"))
        assertEquals(Outcome.Skip("AirBuzz is off"), Rules.decideCall(mumCalls, anyway.copy(enabled = false), night, "SE"))
        // Ring anyway never lets in someone the call mode doesn't, by day or in quiet times.
        val notChosen = anyway.copy(people = listOf(bob))
        assertEquals(Outcome.Skip("not chosen"), Rules.decideCall(mumCalls, notChosen, day, "SE"))
        assertEquals(Outcome.Skip("not chosen"), Rules.decideCall(mumCalls, notChosen, night, "SE"))
        val contacts = anyway.copy(people = emptyList(), callMode = CallMode.CONTACTS)
        assertEquals(Outcome.Skip("not chosen"), Rules.decideCall(mumCalls, contacts, night, "SE"))
        assertEquals(Outcome.Buzz, Rules.decideCall(Caller("Mum", "+46 70 123 45 67"), contacts, night, "SE"))
        assertEquals(Outcome.Skip("not chosen"), Rules.decideCall(mumCalls, anyway.copy(people = emptyList(), ringAnyway = false), day, "SE"))
    }

    @Test fun chosenModeSkipsEveryoneElse() {
        assertEquals(Outcome.Skip("not chosen"), Rules.decideCall(Caller("Bob", null), on, day, "SE"))
        // Contacts mode: a caller shown only as a number is not a contact.
        assertEquals(Outcome.Skip("not chosen"), Rules.decideCall(Caller(null, "+46 8 123 456"), on.copy(callMode = CallMode.CONTACTS), day, "SE"))
        assertEquals(Outcome.Buzz, Rules.decideCall(Caller("Bob", null), on.copy(callMode = CallMode.CONTACTS), day, "SE"))
    }

    @Test fun pauseStopsMessagesAndChosenCallers() {
        val paused = on.copy(pausedUntil = Long.MAX_VALUE)
        assertEquals(Outcome.Skip("paused"), Rules.decideMessage("com.whatsapp", MessageFrom(), paused, day, null, "SE"))
        assertEquals(Outcome.Skip("paused"), Rules.decideCall(Caller("Mum", null), paused, day, "SE"))
    }

    @Test fun messagesMergeWithin15Seconds() {
        assertEquals(Outcome.Merge, Rules.decideMessage("com.whatsapp", MessageFrom(), on, day, day.nowMs - 14_999, "SE"))
        assertEquals(Outcome.Buzz, Rules.decideMessage("com.whatsapp", MessageFrom(), on, day, day.nowMs - 15_000, "SE"))
        assertEquals(Outcome.Skip("not chosen"), Rules.decideMessage("com.instagram.android", MessageFrom(), on, day, null, "SE"))
    }

    @Test fun quietHoursCrossMidnight() {
        assertTrue(Rules.inWindow(23 * 60, 23 * 60, 7 * 60))
        assertTrue(Rules.inWindow(6 * 60 + 59, 23 * 60, 7 * 60))
        assertFalse(Rules.inWindow(7 * 60, 23 * 60, 7 * 60))
        assertFalse(Rules.inWindow(22 * 60 + 59, 23 * 60, 7 * 60))
        assertTrue(Rules.inWindow(13 * 60, 12 * 60, 14 * 60))
    }

    @Test fun onlyChosenChatsBuzzMatchedByIdAfterRename() {
        val family = Chat("120@g.us", "Family", group = true)
        val only = on.copy(chats = mapOf("com.whatsapp" to listOf(family)))
        // Renamed group: same ID, new name, still chosen.
        assertEquals(Outcome.Buzz, Rules.decideMessage("com.whatsapp", MessageFrom("120@g.us", "Family ❤️", group = true), only, day, null, "SE"))
        assertEquals(Outcome.Skip("chat not chosen"), Rules.decideMessage("com.whatsapp", MessageFrom("999@g.us", "Family", group = true), only, day, null, "SE"))
        // No chat ID (an app without conversations) can't match a chosen chat.
        assertEquals(Outcome.Skip("chat not chosen"), Rules.decideMessage("com.whatsapp", MessageFrom(), only, day, null, "SE"))
        // Another chosen app without a chat list still buzzes for everything.
        assertEquals(Outcome.Buzz, Rules.decideMessage("org.telegram.messenger", MessageFrom("1"), only.copy(apps = only.apps + "org.telegram.messenger"), day, null, "SE"))
    }

    @Test fun typedChatMatchesByNameUntilPinned() {
        val typed = on.copy(chats = mapOf("com.whatsapp" to listOf(Chat(null, "Family", group = true))))
        val clean = Rules.cleanChatName("Family (3 messages)")
        assertEquals("Family", clean)
        assertEquals(Outcome.Buzz, Rules.decideMessage("com.whatsapp", MessageFrom("120@g.us", clean, group = true), typed, day, null, "SE"))
        assertEquals("Friday (2024)", Rules.cleanChatName("Friday (2024)"))
    }

    @Test fun peopleWhoCanRingBuzzOnlyInDirectMessages() {
        val mumDirect = MessageFrom("71@lid", "Mum", group = false, sender = Caller("Mum", null))
        // Signal isn't chosen, but Mum writes directly.
        assertEquals(Outcome.Buzz, Rules.decideMessage("org.thoughtcrime.securesms", mumDirect, on, day, null, "SE"))
        // In a group, she doesn't count.
        assertEquals(Outcome.Skip("not chosen"), Rules.decideMessage("org.thoughtcrime.securesms", mumDirect.copy(chatId = "g1", group = true), on, day, null, "SE"))
        // Nor in Anyone mode, where that would let every direct message through.
        assertEquals(Outcome.Skip("not chosen"), Rules.decideMessage("org.thoughtcrime.securesms", mumDirect, on.copy(callMode = CallMode.ANYONE), day, null, "SE"))
        // Quiet times still apply.
        assertEquals(Outcome.Skip("quiet hours"), Rules.decideMessage("org.thoughtcrime.securesms", mumDirect, on, night, null, "SE"))
    }

    @Test fun silentOnThePhoneDoesNotBuzz() {
        assertEquals(Outcome.Skip("silent on the phone"), Rules.decideMessage("com.whatsapp", MessageFrom(silentOnPhone = true), on, day, null, "SE"))
        assertEquals(Outcome.Skip("not chosen"), Rules.decideMessage("com.instagram.android", MessageFrom(silentOnPhone = true), on, day, null, "SE"))
    }
}
