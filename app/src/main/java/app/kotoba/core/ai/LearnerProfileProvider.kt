package app.kotoba.core.ai

import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.srs.WeakWordsRadar
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compact text summary of the learner's statistics that the AI tutor receives
 * (learned words, weak words, frequent confusions, accuracy, level, topics, history).
 */
@Singleton
class LearnerProfileProvider @Inject constructor(
    private val db: KotobaDatabase,
    private val time: TimeProvider,
) {
    suspend fun build(lang: StudyLanguage): String {
        val cards = db.cardDao().allForLanguage(lang.code)
        if (cards.isEmpty()) return "New learner, no words yet."
        val byMastery = cards.groupingBy { it.mastery }.eachCount()
        val weak = cards.filter { it.weakScore >= WeakWordsRadar.WEAK_THRESHOLD }.sortedByDescending { it.weakScore }.take(15)
        val known = cards.filter { it.mastery >= Mastery.FAMILIAR }
        val levelGuess = known.groupingBy { it.level }.eachCount().filterKeys { it.isNotBlank() }
            .maxByOrNull { it.value }?.key ?: "unknown"
        val topics = cards.groupingBy { it.category }.eachCount().filterKeys { it.isNotBlank() }
            .entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} (${it.value})" }
        val byId = cards.associateBy { it.id }
        val confusions = db.confusionDao().observeTop(30).first()
            .mapNotNull { c -> val a = byId[c.cardId]; val b = byId[c.otherCardId]; if (a != null && b != null) "${a.term}↔${b.term}" else null }
            .take(8)
        val week = db.dailyActivityDao().observeFrom(time.today() - 13).first()
        val answered = week.sumOf { it.correct + it.wrong }
        val accuracy = if (answered == 0) null else week.sumOf { it.correct } * 100 / answered
        val recentNew = cards.filter { it.firstStudiedAt != null }.sortedByDescending { it.firstStudiedAt }.take(15)
        return buildString {
            appendLine("Language: ${lang.name}. Words: ${cards.size} total — new ${byMastery[Mastery.NEW] ?: 0}, learning ${byMastery[Mastery.LEARNING] ?: 0}, familiar ${byMastery[Mastery.FAMILIAR] ?: 0}, mastered ${byMastery[Mastery.MASTERED] ?: 0}.")
            appendLine("Estimated level: $levelGuess.")
            if (topics.isNotBlank()) appendLine("Main topics: $topics.")
            if (accuracy != null) appendLine("Last 14 days: $answered answers, accuracy $accuracy%, active days ${week.count { it.reviews + it.newWords > 0 }}.")
            if (weak.isNotEmpty()) appendLine("Weak words: " + weak.joinToString { "${it.term} (${it.translation}, lapses ${it.lapses})" } + ".")
            if (confusions.isNotEmpty()) appendLine("Often confused: ${confusions.joinToString()}.")
            if (recentNew.isNotEmpty()) appendLine("Recently studied: ${recentNew.joinToString { it.term }}.")
        }.trim()
    }
}
