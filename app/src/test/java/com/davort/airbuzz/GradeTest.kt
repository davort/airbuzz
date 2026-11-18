package com.davort.airbuzz

import com.davort.airbuzz.ui.EXCELLENT_MS
import com.davort.airbuzz.ui.GOOD_MS
import com.davort.airbuzz.ui.Grade
import com.davort.airbuzz.ui.gradeOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** Test buzz grades the time it shows, to a tenth of a second (issue #13), with bands that follow [Engine.OPEN_LINK]. */
class GradeTest {
    @Test fun bandsFollowTheLink() {
        assertEquals(if (Engine.OPEN_LINK) 3000L else 5000L, EXCELLENT_MS)
        assertEquals(if (Engine.OPEN_LINK) 5000L else 10_000L, GOOD_MS)
    }

    @Test fun gradesTheShownTime() {
        assertEquals(Grade.EXCELLENT, gradeOf(EXCELLENT_MS + 40)) // shows the limit itself
        assertEquals(Grade.GOOD, gradeOf(EXCELLENT_MS + 50))      // shows 0.1 s over it
        assertEquals(Grade.GOOD, gradeOf(GOOD_MS + 40))
        assertEquals(Grade.SLOW, gradeOf(GOOD_MS + 50))
    }
}
