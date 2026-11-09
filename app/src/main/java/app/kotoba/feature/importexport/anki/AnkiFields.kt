package app.kotoba.feature.importexport.anki

import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Converts Anki field HTML into plain text and pulls out media references. */
object AnkiFields {
    private val soundRegex = Regex("""\[sound:([^\]]+)]""")
    private val clozeRegex = Regex("""\{\{c\d+::(.*?)(?:::[^}]*)?}}""")
    private val blockTags = setOf("p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table", "blockquote")

    fun sounds(field: String): List<String> = soundRegex.findAll(field).map { it.groupValues[1].trim() }.toList()

    fun images(field: String): List<String> {
        if (!field.contains("<img", ignoreCase = true)) return emptyList()
        return Jsoup.parseBodyFragment(field).select("img[src]").map { it.attr("src").trim() }.filter { it.isNotEmpty() }
    }

    /** Plain text keeping line breaks from `<br>` and block elements; sound tags and clozes are flattened. */
    fun plainText(field: String): String {
        if (field.isBlank()) return ""
        val noSound = soundRegex.replace(field, "")
        val noCloze = clozeRegex.replace(noSound) { it.groupValues[1] }
        if (!noCloze.contains('<') && !noCloze.contains('&')) return tidy(noCloze)
        val body = Jsoup.parseBodyFragment(noCloze).body()
        val sb = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> sb.append(node.text())
                    is Element -> when {
                        node.normalName() == "br" -> sb.append('\n')
                        node.normalName() in blockTags && sb.isNotEmpty() && sb.last() != '\n' -> sb.append('\n')
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blockTags && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            }
        }, body)
        return tidy(sb.toString())
    }

    private fun tidy(s: String): String = s.replace('\u00A0', ' ')
        .lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Anki stores tags space-separated with surrounding spaces. */
    fun tags(raw: String): List<String> = raw.trim().split(' ').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Scheduling info of the most advanced Anki card of a note. */
data class AnkiProgress(
    /** 0 new, 1 learning, 2 review, 3 relearning. */
    val type: Int,
    val queue: Int,
    val ivlDays: Int,
    /** Ease in permille (2500 = 250 %). */
    val factor: Int,
    val reps: Int,
    val lapses: Int,
    /** Raw Anki due: day number relative to collection creation for review cards. */
    val due: Long,
)

/** Kotoba SRS fields derived from Anki progress. */
data class ConvertedProgress(
    val state: SrsState,
    val mastery: Mastery,
    val stability: Double,
    val difficulty: Double,
    val due: Long,
    val lastReview: Long?,
    val reps: Int,
    val lapses: Int,
    val suspended: Boolean,
)

object AnkiProgressConverter {
    private const val DAY_MS = 86_400_000L

    fun difficultyFromFactor(factor: Int): Double {
        if (factor <= 0) return 5.0
        val f = factor.coerceIn(1300, 3500)
        // 1300 (hardest ease) → 9, 3500 (easiest) → 2
        return 9.0 - (f - 1300) * 7.0 / 2200.0
    }

    fun masteryFor(ivlDays: Int): Mastery = when {
        ivlDays >= 21 -> Mastery.MASTERED
        ivlDays >= 4 -> Mastery.FAMILIAR
        else -> Mastery.LEARNING
    }

    /**
     * Only graduated review cards (type 2) carry over; anything else returns null and the card starts fresh.
     * @param collectionCreatedSec Anki `col.crt` (seconds) — used to resolve the absolute due date when available.
     */
    fun convert(p: AnkiProgress, now: Long, collectionCreatedSec: Long = 0): ConvertedProgress? {
        if (p.type != 2) return null
        val ivl = p.ivlDays.coerceAtLeast(1)
        val dueFromCol = if (collectionCreatedSec > 0 && p.due in 1..100_000) (collectionCreatedSec + p.due * 86_400L) * 1000 else null
        // Fall back to "due in ivl days" when the collection date is unknown or the result is nonsensical.
        val due = dueFromCol?.takeIf { it > now - 3650 * DAY_MS && it < now + 36_500 * DAY_MS } ?: (now + ivl * DAY_MS)
        val lastReview = (due - ivl * DAY_MS).coerceAtMost(now)
        return ConvertedProgress(
            state = SrsState.REVIEW,
            mastery = masteryFor(ivl),
            stability = ivl.toDouble(),
            difficulty = difficultyFromFactor(p.factor),
            due = due,
            lastReview = lastReview,
            reps = p.reps,
            lapses = p.lapses,
            suspended = p.queue == -1,
        )
    }
}
