package com.davort.airbuzz

import com.davort.airbuzz.rules.CallMode
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
        assertEquals(Outcome.Skip("paused"), Rules.decideMessage("com.whatsapp", paused, day, null))
        assertEquals(Outcome.Skip("paused"), Rules.decideCall(Caller("Mum", null), paused, day, "SE"))
    }

    @Test fun messagesMergeWithin15Seconds() {
        assertEquals(Outcome.Merge, Rules.decideMessage("com.whatsapp", on, day, day.nowMs - 14_999))
        assertEquals(Outcome.Buzz, Rules.decideMessage("com.whatsapp", on, day, day.nowMs - 15_000))
        assertEquals(Outcome.Skip("not chosen"), Rules.decideMessage("com.instagram.android", on, day, null))
    }

    @Test fun quietHoursCrossMidnight() {
        assertTrue(Rules.inWindow(23 * 60, 23 * 60, 7 * 60))
        assertTrue(Rules.inWindow(6 * 60 + 59, 23 * 60, 7 * 60))
        assertFalse(Rules.inWindow(7 * 60, 23 * 60, 7 * 60))
        assertFalse(Rules.inWindow(22 * 60 + 59, 23 * 60, 7 * 60))
        assertTrue(Rules.inWindow(13 * 60, 12 * 60, 14 * 60))
    }
}
