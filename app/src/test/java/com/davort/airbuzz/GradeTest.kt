package com.davort.airbuzz

import com.davort.airbuzz.ui.Grade
import com.davort.airbuzz.ui.gradeOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** Test buzz grades the time it shows, to a tenth of a second (issue #13). */
class GradeTest {
    @Test fun gradesTheShownTime() {
        assertEquals(Grade.EXCELLENT, gradeOf(3040)) // shows 3.0 s
        assertEquals(Grade.GOOD, gradeOf(3050))      // shows 3.1 s
        assertEquals(Grade.GOOD, gradeOf(5040))      // shows 5.0 s
        assertEquals(Grade.SLOW, gradeOf(5050))      // shows 5.1 s
    }
}
