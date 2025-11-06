package one.wabbit.textwrap

import kotlin.test.Test
import kotlin.test.assertEquals

class TextWrapParityTest {
    private fun makeTW(
        width: Int = 70,
        initial: String = "",
        subsequent: String = "",
        fixSent: Boolean = false,
        breakLong: Boolean = true,
        dropWS: Boolean = true,
        breakOnHyphens: Boolean = true,
        tabsize: Int = 8,
        maxLines: Int? = null,
        placeholder: String = " [...]",
    ) =
        TextWrapper(
            width = width,
            initialIndent = initial,
            subsequentIndent = subsequent,
            expandTabs = true,
            replaceWhitespace = true,
            fixSentenceEndings = fixSent,
            breakLongWords = breakLong,
            dropWhitespace = dropWS,
            breakOnHyphens = breakOnHyphens,
            tabsize = tabsize,
            maxLines = maxLines,
            placeholder = placeholder,
            unicodeWordClasses = false, // CPython parity
            unicodeHyphens = false, // CPython parity
        )

    // ---- wrap() ----

    @Test
    fun wrap_basic_wrap() {
        val tw = makeTW(width = 12)
        val text = "This is a simple paragraph that will be wrapped."
        val expected = listOf("This is a", "simple", "paragraph", "that will be", "wrapped.")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_initial_and_subsequent_indent() {
        val tw = makeTW(width = 12, initial = ">>> ", subsequent = "... ")
        val text = "Pack my box with five dozen liquor jugs."
        val expected =
            listOf(
                ">>> Pack my",
                "... box with",
                "... five",
                "... dozen",
                "... liquor",
                "... jugs.",
            )
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_expand_tabs_and_replace_ws() {
        val tw = makeTW(width = 8, tabsize = 4)
        val text = "foo\tbar\n\nbaz    qux"
        val expected = listOf("foo bar", "baz", "qux")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_fix_sentence_endings() {
        val tw = makeTW(width = 20, fixSent = true)
        val text = "Hello world.\nBar baz? \"Quux\"!"
        val expected = listOf("Hello world.  Bar", "baz?  \"Quux\"!")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_break_long_words_true() {
        val tw = makeTW(width = 10, breakLong = true)
        val text = "Supercalifragilisticexpialidocious"
        val expected = listOf("Supercalif", "ragilistic", "expialidoc", "ious")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_break_long_words_false() {
        val tw = makeTW(width = 10, breakLong = false)
        val text = "Supercalifragilisticexpialidocious"
        val expected = listOf("Supercalifragilisticexpialidocious")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_break_on_hyphens_true() {
        val tw = makeTW(width = 10, breakOnHyphens = true)
        val text = "Look, goof-ball -- use the -b option!"
        val expected = listOf("Look,", "goof-ball", "-- use the", "-b option!")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_break_on_hyphens_false() {
        val tw = makeTW(width = 10, breakOnHyphens = false)
        val text = "Look, goof-ball -- use the -b option!"
        val expected = listOf("Look,", "goof-ball", "-- use the", "-b option!")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_drop_whitespace_behavior() {
        val tw = makeTW(width = 3, dropWS = true)
        val text = "  A  B   C  "
        val expected = listOf("  A", "B", "C")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_max_lines_with_placeholder() {
        val tw = makeTW(width = 12, maxLines = 2, placeholder = " [...]")
        val text = "alpha beta gamma delta epsilon zeta eta theta iota"
        val expected = listOf("alpha beta", "gamma [...]")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_unicode_letters_basic() {
        val tw = makeTW(width = 12)
        val text = "Résumé élève ångström coöperate naïve façade"
        val expected = listOf("Résumé élève", "ångström", "coöperate", "naïve façade")
        assertEquals(expected, tw.wrap(text))
    }

    @Test
    fun wrap_nbsp_is_nonbreaking() {
        val tw = makeTW(width = 2)
        val text = "A\u00A0B C"
        val expected = listOf("A\u00A0", "B", "C")
        assertEquals(expected, tw.wrap(text))
    }

    // ---- fill() ----

    @Test
    fun fill_basic() {
        val tw = makeTW(width = 15)
        val text = "This is a simple paragraph that will be filled to lines."
        val expected = "This is a\nsimple\nparagraph that\nwill be filled\nto lines."
        assertEquals(expected, tw.fill(text))
    }

    @Test
    fun fill_with_indent() {
        val tw = makeTW(width = 12, initial = ">>> ", subsequent = "... ")
        val text = "Pack my box with five dozen liquor jugs."
        val expected = ">>> Pack my\n... box with\n... five\n... dozen\n... liquor\n... jugs."
        assertEquals(expected, tw.fill(text))
    }

    @Test
    fun fill_fix_sentence_endings() {
        val tw = makeTW(width = 20, fixSent = true)
        val text = "Hello world.\nBar baz? \"Quux\"!"
        val expected = "Hello world.  Bar\nbaz?  \"Quux\"!"
        assertEquals(expected, tw.fill(text))
    }

    // ---- shorten() ----
    // We emulate CPython's shorten via TextWrapper(maxLines=1) + whitespace collapse.

    private fun kotlinShorten(text: String, width: Int, placeholder: String = " [...]"): String {
        val collapsed = text.trim().split(Regex("\\s+")).joinToString(" ")
        val tw = makeTW(width = width, maxLines = 1, placeholder = placeholder)
        return tw.fill(collapsed)
    }

    @Test
    fun shorten_fits() {
        val expected = "Hello world!"
        assertEquals(expected, kotlinShorten("Hello  world!", width = 12))
    }

    @Test
    fun shorten_truncates() {
        val expected = "Hello [...]"
        assertEquals(expected, kotlinShorten("Hello  world!", width = 11))
    }

    @Test
    fun shorten_unicode() {
        val expected = "Résumé [...]"
        assertEquals(expected, kotlinShorten("Résumé élève coöperate", width = 14))
    }

    // ---- dedent() ----

    @Test
    fun dedent_basic() {
        val text = "    Hello there.\n      This is indented."
        val expected = "Hello there.\n  This is indented."
        assertEquals(expected, dedent(text))
    }

    @Test
    fun dedent_mixed_tabs_spaces() {
        val text = "\tfoo\n\tbar\n  \tbaz"
        val expected = "\tfoo\n\tbar\n  \tbaz"
        assertEquals(expected, dedent(text))
    }

    // ---- indent() ----

    @Test
    fun indent_default_predicate() {
        val text = "a\n\nb\n"
        val expected = "> a\n\n> b\n"
        assertEquals(expected, indent(text, "> "))
    }

    @Test
    fun indent_predicate_startswith_b() {
        val text = "a\nb\nc\n"
        val expected = "a\n# b\nc\n"
        assertEquals(expected, indent(text, "# ") { it.startsWith("b") })
    }
}
