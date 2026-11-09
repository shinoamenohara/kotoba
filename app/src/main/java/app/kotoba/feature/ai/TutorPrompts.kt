package app.kotoba.feature.ai

import androidx.annotation.StringRes
import app.kotoba.R
import app.kotoba.core.ai.AiPrompts
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage

/** What a quick-action chip asks for before sending its templated prompt. */
enum class ActionInput { NONE, WORD, WORDS, TOPIC, EXPRESSION, SCENARIO }

enum class QuickAction(
    val emoji: String,
    @StringRes val label: Int,
    /** Localized user message; `%1$s` = input, `%2$s` = target language name. */
    @StringRes val template: Int,
    val input: ActionInput,
    /** Only meaningful when the tutor can see learning statistics. */
    val needsStats: Boolean = false,
) {
    EXPLAIN_WORD("💡", R.string.ai_qa_explain_word, R.string.ai_qa_explain_word_prompt, ActionInput.WORD),
    GRAMMAR("📐", R.string.ai_qa_grammar, R.string.ai_qa_grammar_prompt, ActionInput.TOPIC),
    COMPARE("⚖️", R.string.ai_qa_compare, R.string.ai_qa_compare_prompt, ActionInput.WORDS),
    MISTAKES("🔍", R.string.ai_qa_mistakes, R.string.ai_qa_mistakes_prompt, ActionInput.NONE, needsStats = true),
    WHY_ERRORS("🧠", R.string.ai_qa_why_errors, R.string.ai_qa_why_errors_prompt, ActionInput.NONE, needsStats = true),
    IDIOM("🗝️", R.string.ai_qa_idiom, R.string.ai_qa_idiom_prompt, ActionInput.EXPRESSION),
    EXAMPLES("✍️", R.string.ai_qa_examples, R.string.ai_qa_examples_prompt, ActionInput.WORD),
    THEMATIC("🗂️", R.string.ai_qa_thematic, R.string.ai_qa_thematic_prompt, ActionInput.TOPIC),
    REVIEW_TODAY("📅", R.string.ai_qa_review_today, R.string.ai_qa_review_today_prompt, ActionInput.NONE, needsStats = true),
    DIALOGUE("🎭", R.string.ai_qa_dialogue, R.string.ai_qa_dialogue_prompt, ActionInput.SCENARIO),
}

/** Role-play scenarios; the first entries suit every language, the extras are language-flavoured. */
object DialogueScenarios {
    val common = listOf(
        R.string.ai_scenario_cafe, R.string.ai_scenario_airport, R.string.ai_scenario_hotel, R.string.ai_scenario_job_interview,
        R.string.ai_scenario_doctor, R.string.ai_scenario_shopping, R.string.ai_scenario_restaurant, R.string.ai_scenario_new_friend,
        R.string.ai_scenario_anime_convention,
    )

    fun extra(lang: StudyLanguage): List<Int> = when (lang) {
        StudyLanguage.JA -> listOf(R.string.ai_scenario_konbini, R.string.ai_scenario_izakaya)
        StudyLanguage.ZH -> listOf(R.string.ai_scenario_market, R.string.ai_scenario_tea_house)
        else -> listOf(R.string.ai_scenario_small_talk)
    }
}

object TutorSystemPrompts {
    const val MODE_CHAT = "chat"
    const val MODE_DIALOGUE_PREFIX = "dialogue:"

    fun dialogueMode(scenario: String) = MODE_DIALOGUE_PREFIX + scenario

    fun scenarioOf(mode: String): String? = mode.takeIf { it.startsWith(MODE_DIALOGUE_PREFIX) }?.removePrefix(MODE_DIALOGUE_PREFIX)

    fun system(base: String, mode: String, lang: StudyLanguage, native: NativeLanguage): String {
        val scenario = scenarioOf(mode) ?: return base
        return base + "\n\nROLE-PLAY MODE. Scenario: $scenario. " +
            "Play the other person in this situation and speak only ${lang.name} at the learner's level, 1–3 short sentences per turn, " +
            "and keep the conversation going with a question. After each learner message that contains mistakes, add a separate block " +
            "starting with \"✏️\" written in ${AiPrompts.nativeName(native)}: the corrected sentence and a one-line reason. " +
            "If there are no mistakes, add nothing. Stay in character until the learner asks to stop."
    }

    /** Short conversation title from the first message. */
    fun titleFrom(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (line.length <= 40) line else line.take(39).trimEnd() + "…"
    }
}
