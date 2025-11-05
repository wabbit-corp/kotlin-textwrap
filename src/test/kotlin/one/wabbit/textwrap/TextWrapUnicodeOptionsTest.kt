package one.wabbit.textwrap

import kotlin.test.Test
import kotlin.test.assertEquals

class TextWrapUnicodeOptionsTest {

    @Test
    fun unicode_hyphen_disabled_does_not_prefer_u2010() {
        val tw = TextWrapper(
            width = 4,
            unicodeWordClasses = true,
            unicodeHyphens = false,
            breakOnHyphens = true
        )
        val text = "co\u2010operate" // "co‑operate" with U+2010 (HYPHEN)
        // Splits purely by width since U+2010 is ignored in the long-word hyphen preference.
        val expected = listOf("co\u2010o", "pera", "te")
        assertEquals(expected, tw.wrap(text))
    }

    @Test fun unicode_hyphen_enabled_prefers_u2010_break() {
        val tw = TextWrapper(
            width = 4,
            unicodeWordClasses = true,
            unicodeHyphens = true,
            breakOnHyphens = true
        )
        val text = "co\u2010operate" // "co‑operate" with U+2010 (HYPHEN)
        // Prefers to break right after the hyphen when possible.
        val expected = listOf("co\u2010", "oper", "ate")
        assertEquals(expected, tw.wrap(text))
    }
}
