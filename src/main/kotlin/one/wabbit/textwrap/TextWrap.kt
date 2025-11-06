package one.wabbit.textwrap

import kotlin.math.max

/**
 * Port of Python's textwrap module to Kotlin. Original authorship: Gregory P. Ward, Python Software
 * Foundation. This port aims to mirror behavior for ASCII whitespace and wrapping semantics.
 */
class TextWrapper(
    var width: Int = 70,
    var initialIndent: String = "",
    var subsequentIndent: String = "",
    var expandTabs: Boolean = true,
    var replaceWhitespace: Boolean = true,
    var fixSentenceEndings: Boolean = false,
    var breakLongWords: Boolean = true,
    var dropWhitespace: Boolean = true,
    var breakOnHyphens: Boolean = true,
    var tabsize: Int = 8,
    var maxLines: Int? = null,
    var placeholder: String = " [...]",
    // Make \w and \d Unicode-aware, and define LETTER as \p{L}
    var unicodeWordClasses: Boolean = true,
    // Include Unicode hyphen-like chars in hyphen logic
    var unicodeHyphens: Boolean = true,
) {
    // ASCII whitespace exactly as in CPython textwrap
    private val WHITESPACE = "[\\t\\n\\u000B\\u000C\\r ]"
    private val NOWHITESPACE = "[^\\t\\n\\u000B\\u000C\\r ]"

    private val WORD_PUNCT = """[\w!"'&.,?]"""
    private val LETTER = if (unicodeWordClasses) """\p{L}""" else """[^\d\W]"""

    // Optional hyphen class
    private val HY = if (unicodeHyphens) """[-\u2010\u2011\u2012\u2013\u2014\u2212]""" else "-"

    // IMPORTANT: exclude U+2011 (NON-BREAKING HYPHEN) on purpose
    private val hyphenChars: Set<Char> =
        if (unicodeHyphens) {
            setOf(
                '-',
                '\u2010', // HYPHEN
                '\u2012', // FIGURE DASH
                '\u2013', // EN DASH
                '\u2014', // EM DASH
                '\u2212', // MINUS SIGN
            )
        } else {
            setOf('-')
        }

    private val uFlag = if (unicodeWordClasses) "(?U)" else ""

    // Build the complex splitter (instance-scoped; depends on flags)
    private val wordsepRe: Regex by lazy {
        val pattern =
            """
            $uFlag(
              $WHITESPACE+                              # any whitespace
            | (?<=$WORD_PUNCT) $HY{2,} (?=\w)           # double hyphen between words
            | $NOWHITESPACE+? (?:                       # word, possibly hyphenated
                  $HY(?: (?<=$LETTER{2}$HY) | (?<=$LETTER$HY$LETTER$HY) )
                  (?= $LETTER $HY? $LETTER )
                | (?=$WHITESPACE|\Z)                    # end of word
                | (?<=$WORD_PUNCT) (?=$HY{2,}\w)        # em-dash (double hyphen) lookahead
              )
            )
            """
                .trimIndent()
        Regex(pattern, setOf(RegexOption.COMMENTS))
    }

    // Simple splitter for breakOnHyphens = false (ASCII whitespace only)
    private val wordsepSimpleRe: Regex by lazy { Regex("($WHITESPACE+)") }

    // End-of-sentence heuristic (ASCII-lower letter + punctuation + optional quote).
    private val sentenceEndRe: Regex = Regex("""[a-z][.!?]["']?\Z""")

    // Match Python's ASCII whitespace set used by textwrap.
    private val asciiWhitespaceChars = charArrayOf('\t', '\n', '\u000B', '\u000C', '\r', ' ')

    /** Expand tabs and normalize non-breaking whitespace to ASCII space if requested. */
    private fun mungeWhitespace(text: String): String {
        var s = text
        if (expandTabs) s = expandTabs(s, tabsize)
        if (replaceWhitespace) s = translateWhitespaceToSpaces(s)
        return s
    }

    /** Split into indivisible "chunks" (words and whitespace), following Python's regexes. */
    // Split into runs of spaces and runs of non-spaces.
    // IMPORTANT: call this only AFTER mungeWhitespace(), so all ASCII
    // whitespace chars have already been translated to ' ' (space).
    private fun splitSimpleRuns(text: String): MutableList<String> {
        val out = ArrayList<String>(text.length / 2 + 1)
        var i = 0
        val n = text.length
        while (i < n) {
            val isSpace = text[i] == ' '
            var j = i + 1
            if (isSpace) {
                while (j < n && text[j] == ' ') j++
            } else {
                while (j < n && text[j] != ' ') j++
            }
            out.add(text.substring(i, j))
            i = j
        }
        return out
    }

    private fun split(text: String): MutableList<String> {
        // Ignore the complex wordsepRe; the simple splitter matches CPython’s
        // effective behavior once whitespace has been munged.
        return splitSimpleRuns(text)
    }

    /** Fix sentence endings by doubling the single space after end-of-sentence punctuation. */
    private fun fixSentenceEndingsInPlace(chunks: MutableList<String>) {
        var i = 0
        while (i < chunks.size - 1) {
            if (chunks[i + 1] == " " && sentenceEndRe.containsMatchIn(chunks[i])) {
                chunks[i + 1] = "  "
                i += 2
            } else {
                i += 1
            }
        }
    }

    /** Handle an indivisible chunk that's too long for the current line. */
    private fun handleLongWord(
        reversedChunks: MutableList<String>,
        curLine: MutableList<String>,
        curLen: Int,
        widthAvail: Int,
    ) {
        val spaceLeft = if (widthAvail < 1) 1 else widthAvail - curLen
        if (breakLongWords) {
            var end = spaceLeft
            val chunk = reversedChunks.last()
            if (breakOnHyphens && chunk.length > spaceLeft) {
                val upto = if (spaceLeft < 0) 0 else spaceLeft
                val sub = chunk.substring(0, upto)
                val hyphen = sub.indexOfLast { it in hyphenChars }
                if (hyphen > 0 && sub.take(hyphen).any { it !in hyphenChars }) {
                    end = hyphen + 1
                }
            }
            val take = end.coerceIn(0, chunk.length)
            curLine.add(chunk.substring(0, take))
            reversedChunks[reversedChunks.lastIndex] = chunk.substring(take)
        } else if (curLine.isEmpty()) {
            curLine.add(reversedChunks.removeAt(reversedChunks.lastIndex))
        }
        // else: do nothing, next outer iteration will place the long word on its own line
    }

    /** Core wrapping algorithm on a list of chunks. */
    private fun wrapChunks(chunks: MutableList<String>): List<String> {
        if (width <= 0) {
            throw IllegalArgumentException("invalid width $width (must be > 0)")
        }
        maxLines?.let { maxL ->
            val indent = if (maxL > 1) subsequentIndent else initialIndent
            if (indent.length + placeholder.trimStart().length > width) {
                throw IllegalArgumentException("placeholder too large for max width")
            }
        }

        // Python reverses to use as a stack (pop from end).
        chunks.reverse()

        val lines = mutableListOf<String>()
        while (chunks.isNotEmpty()) {
            val curLine = mutableListOf<String>()
            var curLen = 0

            val indent = if (lines.isNotEmpty()) subsequentIndent else initialIndent
            val widthAvail = width - indent.length

            // Drop leading whitespace for all but the very first line.
            if (
                dropWhitespace &&
                    lines.isNotEmpty() &&
                    chunks.isNotEmpty() &&
                    chunks.last().trim().isEmpty()
            ) {
                chunks.removeAt(chunks.lastIndex)
            }

            // Greedily take chunks that fit.
            while (chunks.isNotEmpty()) {
                val l = chunks.last().length
                if (curLen + l <= widthAvail) {
                    curLine.add(chunks.removeAt(chunks.lastIndex))
                    curLen += l
                } else {
                    break
                }
            }

            // If next chunk cannot fit on any line at all, break it per policy.
            if (chunks.isNotEmpty() && chunks.last().length > widthAvail) {
                handleLongWord(chunks, curLine, curLen, widthAvail)
                curLen = curLine.sumOf { it.length }
            }

            // Trim trailing whitespace chunk from the current line.
            if (dropWhitespace && curLine.isNotEmpty() && curLine.last().trim().isEmpty()) {
                curLen -= curLine.last().length
                curLine.removeAt(curLine.lastIndex)
            }

            if (curLine.isNotEmpty()) {
                val allowLine =
                    (maxLines == null) ||
                        (lines.size + 1 < maxLines!!) ||
                        ((chunks.isEmpty() ||
                            (dropWhitespace && chunks.size == 1 && chunks[0].trim().isEmpty())) &&
                            curLen <= widthAvail)

                if (allowLine) {
                    lines.add(indent + curLine.joinToString(separator = ""))
                } else {
                    // Need to truncate and append placeholder
                    var tmpLen = curLen
                    while (curLine.isNotEmpty()) {
                        if (
                            curLine.last().trim().isNotEmpty() &&
                                tmpLen + placeholder.length <= widthAvail
                        ) {
                            curLine.add(placeholder)
                            lines.add(indent + curLine.joinToString(separator = ""))
                            break
                        }
                        tmpLen -= curLine.last().length
                        curLine.removeAt(curLine.lastIndex)
                    }
                    if (curLine.isEmpty()) {
                        if (lines.isNotEmpty()) {
                            val prevLine = lines.last().trimEnd()
                            if (prevLine.length + placeholder.length <= width) {
                                lines[lines.lastIndex] = prevLine + placeholder
                                break
                            }
                        }
                        lines.add(indent + placeholder.trimStart())
                    }
                    break
                }
            }
        }
        return lines
    }

    private fun splitChunks(text: String): MutableList<String> = split(mungeWhitespace(text))

    /** Public API: wrap -> list of lines */
    fun wrap(text: String): List<String> {
        val chunks = splitChunks(text)
        if (fixSentenceEndings) fixSentenceEndingsInPlace(chunks)
        return wrapChunks(chunks)
    }

    /** Public API: fill -> wrapped paragraph string */
    fun fill(text: String): String = wrap(text).joinToString("\n")

    // ---- Helpers ----

    private fun translateWhitespaceToSpaces(s: String): String {
        if (s.isEmpty()) return s
        val set = asciiWhitespaceChars.toSet()
        val out = StringBuilder(s.length)
        for (ch in s) {
            out.append(if (ch in set) ' ' else ch)
        }
        return out.toString()
    }

    /** Expand tabs with given size; resets column after '\n' or '\r'. */
    private fun expandTabs(s: String, size: Int): String {
        if (!s.contains('\t')) return s
        val n = s.length
        val out = StringBuilder(n + 16)
        var col = 0
        var i = 0
        while (i < n) {
            val c = s[i]
            when (c) {
                '\t' -> {
                    val spaces = size - (col % size)
                    repeat(spaces) { out.append(' ') }
                    col += spaces
                }
                '\n',
                '\r' -> {
                    out.append(c)
                    col = 0
                }
                else -> {
                    out.append(c)
                    col += 1
                }
            }
            i++
        }
        return out.toString()
    }
}

/** Convenience: wrap a single paragraph into a list of lines. */
fun wrap(
    text: String,
    width: Int = 70,
    initialIndent: String = "",
    subsequentIndent: String = "",
    expandTabs: Boolean = true,
    replaceWhitespace: Boolean = true,
    fixSentenceEndings: Boolean = false,
    breakLongWords: Boolean = true,
    dropWhitespace: Boolean = true,
    breakOnHyphens: Boolean = true,
    tabsize: Int = 8,
    maxLines: Int? = null,
    placeholder: String = " [...]",
): List<String> {
    val w =
        TextWrapper(
            width,
            initialIndent,
            subsequentIndent,
            expandTabs,
            replaceWhitespace,
            fixSentenceEndings,
            breakLongWords,
            dropWhitespace,
            breakOnHyphens,
            tabsize,
            maxLines,
            placeholder,
        )
    return w.wrap(text)
}

/** Convenience: fill a single paragraph into a string with newlines. */
fun fill(
    text: String,
    width: Int = 70,
    initialIndent: String = "",
    subsequentIndent: String = "",
    expandTabs: Boolean = true,
    replaceWhitespace: Boolean = true,
    fixSentenceEndings: Boolean = false,
    breakLongWords: Boolean = true,
    dropWhitespace: Boolean = true,
    breakOnHyphens: Boolean = true,
    tabsize: Int = 8,
    maxLines: Int? = null,
    placeholder: String = " [...]",
): String {
    val w =
        TextWrapper(
            width,
            initialIndent,
            subsequentIndent,
            expandTabs,
            replaceWhitespace,
            fixSentenceEndings,
            breakLongWords,
            dropWhitespace,
            breakOnHyphens,
            tabsize,
            maxLines,
            placeholder,
        )
    return w.fill(text)
}

/**
 * Collapse and truncate to fit width. Mirrors Python's textwrap.shorten:
 * - collapse all runs of whitespace to a single space,
 * - if it fits, return as-is; else truncate at word boundary and append placeholder.
 */
fun shorten(
    text: String,
    width: Int,
    placeholder: String = " [...]",
    initialIndent: String = "",
    subsequentIndent: String = "",
    expandTabs: Boolean = true,
    replaceWhitespace: Boolean = true,
    fixSentenceEndings: Boolean = false,
    breakLongWords: Boolean = true,
    dropWhitespace: Boolean = true,
    breakOnHyphens: Boolean = true,
    tabsize: Int = 8,
): String {
    val collapsed = text.trim().split(Regex("\\s+")).joinToString(" ")
    val w =
        TextWrapper(
            width = width,
            initialIndent = initialIndent,
            subsequentIndent = subsequentIndent,
            expandTabs = expandTabs,
            replaceWhitespace = replaceWhitespace,
            fixSentenceEndings = fixSentenceEndings,
            breakLongWords = breakLongWords,
            dropWhitespace = dropWhitespace,
            breakOnHyphens = breakOnHyphens,
            tabsize = tabsize,
            maxLines = 1,
            placeholder = placeholder,
        )
    return w.fill(collapsed)
}

/**
 * Remove any common leading whitespace from every line in [text]. Follows Python's textwrap.dedent
 * semantics for spaces and tabs.
 */
fun dedent(text: String): String {
    // Normalize lines that are only space or tab to empty (but keep the newline).
    val whitespaceOnly = Regex("^[ \\t]+$", RegexOption.MULTILINE)
    var s = whitespaceOnly.replace(text, "")

    // Find leading whitespace (spaces/tabs) on non-blank lines.
    val leading = Regex("(^[ \\t]*)(?:[^ \\t\\n])", RegexOption.MULTILINE)
    val indents = leading.findAll(s).map { it.groupValues[1] }.toList()

    var margin: String? = null
    for (indent in indents) {
        when {
            margin == null -> margin = indent
            indent.startsWith(margin!!) -> {
                /* keep existing margin */
            }
            margin!!.startsWith(indent) -> margin = indent
            else -> {
                // Find largest common prefix of spaces/tabs
                val upper = max(indent.length, margin!!.length)
                var i = 0
                val a = margin!!
                while (i < upper && i < a.length && i < indent.length && a[i] == indent[i]) i++
                margin = a.substring(0, i)
            }
        }
    }

    if (!margin.isNullOrEmpty()) {
        val pattern = Regex("(?m)^" + Regex.escape(margin!!))
        s = pattern.replace(s, "")
    }
    return s
}

/**
 * Add [prefix] to the beginning of selected lines. If [predicate] is null, prefix is added to all
 * non-blank lines (i.e., lines that are not solely whitespace).
 */
fun indent(text: String, prefix: String, predicate: ((String) -> Boolean)? = null): String {
    val shouldPrefix: (String) -> Boolean = predicate ?: { line -> !line.isBlank() }

    val out = StringBuilder(text.length + prefix.length * 2)
    for (line in splitLinesKeepEnds(text)) {
        if (shouldPrefix(line)) out.append(prefix)
        out.append(line)
    }
    return out.toString()
}

// ---------- small internal helpers ----------

/** Split lines while keeping line terminators (akin to Python's str.splitlines(True)). */
private fun splitLinesKeepEnds(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    var i = 0
    var start = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n' || c == '\r') {
            // handle CRLF as a single terminator block
            val j = if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i + 2 else i + 1
            out.add(s.substring(start, j))
            start = j
            i = j
        } else {
            i++
        }
    }
    if (start < s.length) out.add(s.substring(start))
    return out
}
