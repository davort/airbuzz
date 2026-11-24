package com.davort.airbuzz

import com.davort.airbuzz.data.ChatBook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPhotoTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun greySilhouetteIsAStandIn() {
        // WhatsApp's stand-in: light grey disc, darker grey figure, transparent corners.
        val px = IntArray(100) { i -> when { i < 10 -> argb(0, 255, 0, 0); i % 3 == 0 -> argb(255, 140, 145, 150); else -> argb(255, 207, 212, 216) } }
        assertTrue(ChatBook.isPlaceholder(px))
    }

    @Test
    fun aPhotoWithSomeColourIsKept() {
        // Mostly grey, like a photo of a grey wall, but with a few skin-tone pixels.
        val px = IntArray(100) { i -> if (i < 3) argb(255, 224, 172, 138) else argb(255, 120, 120, 120) }
        assertFalse(ChatBook.isPlaceholder(px))
    }
}
