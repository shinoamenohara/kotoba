package app.kotoba.feature.ai

import app.kotoba.core.ai.AiPrompts
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage
import kotlinx.serialization.Serializable

@Serializable
data class QuizDto(val questions: List<QuizQuestionDto> = emptyList())

@Serializable
data class QuizQuestionDto(
    /** "multiple_choice" | "fill_blank" */
    val type: String = "multiple_choice",
    val prompt: String = "",
    val options: List<String> = emptyList(),
    val answer: String = "",
    /** Extra accepted spellings for fill-in-the-blank. */
    val accepted: List<String> = emptyList(),
    val explanation: String = "",
    /** The vocabulary item being tested (dictionary form), used to map back to a card. */
    val term: String = "",
)

enum class QuizKind(val exercise: ExerciseType) { MULTIPLE_CHOICE(ExerciseType.MULTIPLE_CHOICE), FILL_BLANK(ExerciseType.FILL_BLANK) }

data class QuizQuestion(
    val kind: QuizKind,
    val prompt: String,
    val options: List<String>,
    val answer: String,
    val accepted: List<String>,
    val explanation: String,
    val term: String,
) {
    fun isCorrect(response: String): Boolean {
        val r = normalize(response)
        if (r.isEmpty()) return false
        return (listOf(answer) + accepted).any { normalize(it) == r }
    }

    companion object {
        fun normalize(s: String): String =
            s.trim().lowercase().replace(Regex("""[\s\p{Punct}。、！？「」・…]+"""), " ").trim()
    }
}

object QuizBuilder {
    const val MIN_QUESTIONS = 5
    const val MAX_QUESTIONS = 8

    fun prompt(lang: StudyLanguage, native: NativeLanguage, targetWords: List<String>, level: String?): String = buildString {
        appendLine("Create a short vocabulary quiz of $MIN_QUESTIONS–$MAX_QUESTIONS questions for a learner of ${lang.name}.")
        appendLine(AiPrompts.languageGuide(lang))
        if (targetWords.isNotEmpty()) appendLine("Focus on these words the learner keeps getting wrong: ${targetWords.joinToString()}.")
        if (!level.isNullOrBlank()) appendLine("Learner level: $level.")
        appendLine("Mix two types:")
        appendLine("- multiple_choice: `prompt` asks for the meaning or the right word in a sentence; 4 `options`; `answer` is exactly one of the options.")
        appendLine("- fill_blank: `prompt` is a sentence in the target language with ___ in place of the word, followed by a hint in ${AiPrompts.nativeName(native)} in parentheses; `answer` is the missing word as it appears in the sentence; `accepted` lists other correct forms.")
        appendLine("Set `term` to the tested word in dictionary form. `explanation` is one short sentence in ${AiPrompts.nativeName(native)}.")
        append("Return JSON: {\"questions\":[{\"type\",\"prompt\",\"options\",\"answer\",\"accepted\",\"explanation\",\"term\"}]}")
    }

    /** Validates model output: drops broken items, makes sure the answer is among the options. */
    fun sanitize(dto: QuizDto): List<QuizQuestion> = dto.questions.mapNotNull { q ->
        val answer = q.answer.trim()
        if (q.prompt.isBlank() || answer.isEmpty()) return@mapNotNull null
        val fill = q.type.contains("fill", ignoreCase = true) || q.type.contains("blank", ignoreCase = true)
        if (fill) {
            QuizQuestion(QuizKind.FILL_BLANK, q.prompt.trim(), emptyList(), answer, q.accepted.map { it.trim() }.filter { it.isNotEmpty() }, q.explanation.trim(), q.term.trim())
        } else {
            val options = q.options.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
            if (options.none { it.equals(answer, ignoreCase = true) }) options += answer
            if (options.size < 2) return@mapNotNull null
            val canonical = options.first { it.equals(answer, ignoreCase = true) }
            QuizQuestion(QuizKind.MULTIPLE_CHOICE, q.prompt.trim(), options.take(6).let { if (canonical in it) it else it.dropLast(1) + canonical }, canonical, emptyList(), q.explanation.trim(), q.term.trim())
        }
    }.take(MAX_QUESTIONS)
}
