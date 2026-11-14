package com.davort.airbuzz

import com.davort.airbuzz.rules.CallMode
import com.davort.airbuzz.rules.Person
import com.davort.airbuzz.rules.Settings
import com.davort.airbuzz.ui.setUpSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/** "You're set" says what setup turned on (issue #17). */
class SetUpSummaryTest {
    private val labels = mapOf("com.whatsapp" to "WhatsApp", "org.thoughtcrime.securesms" to "Signal", "org.telegram.messenger" to "Telegram")
    // Compare with plain spaces: the text keeps "Fitbit Air" on one line with a no-break space.
    private fun summary(s: Settings) = setUpSummary(s) { labels.getValue(it) }.replace("\u00A0", " ")
    private fun people(vararg names: String) = names.map { Person(it, null) }

    @Test fun namesPeopleAndAppsAndReferBackToTheFitbitAir() {
        assertEquals("Calls from Mum now ring your Fitbit Air. Messages from Signal and WhatsApp buzz it once.",
            summary(Settings(people = people("Mum"), apps = setOf("com.whatsapp", "org.thoughtcrime.securesms"))))
    }

    @Test fun countsTheRestOfALongList() {
        assertEquals("Calls from Mum, Noor and 1 other now ring your Fitbit Air.", summary(Settings(people = people("Mum", "Noor", "Sara"))))
        assertEquals("Calls from Mum, Noor and 2 others now ring your Fitbit Air.", summary(Settings(people = people("Mum", "Noor", "Sara", "Ali"))))
    }

    @Test fun messagesAloneNameTheFitbitAirAndTheirPattern() {
        assertEquals("Messages from Signal, Telegram and 1 more app buzz your Fitbit Air twice.",
            summary(Settings(apps = labels.keys, messageTaps = 2)))
    }

    @Test fun modesWithoutAListSayWhoRings() {
        assertEquals("Every call now rings your Fitbit Air.", summary(Settings(callMode = CallMode.ANYONE, people = people("Mum"))))
        assertEquals("Calls from your contacts now ring your Fitbit Air.", summary(Settings(callMode = CallMode.CONTACTS)))
    }

    @Test fun nothingChosenStillSaysItIsOn() {
        assertEquals("AirBuzz is on. Choose who rings and which apps buzz from Home.", summary(Settings()))
    }
}
