package app.kotoba.core.data

import androidx.room.withTransaction
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ConfusionEntity
import app.kotoba.core.db.DailyActivityEntity
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.db.ReviewLogEntity
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.Rating
import app.kotoba.core.model.SrsState
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.srs.Fsrs
import app.kotoba.core.srs.LearningSnapshot
import app.kotoba.core.srs.MasteryEvaluator
import app.kotoba.core.srs.SrsCard
import app.kotoba.core.srs.WeakWordsRadar
import app.kotoba.core.srs.WeaknessReport
import app.kotoba.core.srs.XpRules
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

enum class QueueMode {
    /** Due reviews interleaved with today's quota of new words. */
    DAILY,
    NEW_ONLY,
    REVIEW_ONLY,
    WEAK_ONLY,
    /** Quick Review: overdue first, then weak, then soonest due. */
    QUICK,
    /** Any already-studied cards (for exercises outside the schedule). */
    PRACTICE,
    /** Every card of a deck/category regardless of schedule. */
    ALL,
}

data class QueueRequest(
    val mode: QueueMode,
    val language: StudyLanguage,
    val deckId: Long? = null,
    val category: String? = null,
    val limit: Int = 50,
)

data class AnswerInput(
    val cardId: Long,
    val rating: Rating,
    val exercise: ExerciseType,
    val responseMs: Long,
    val correct: Boolean = rating != Rating.AGAIN,
    /** Card the learner picked instead (multiple choice / matching) — feeds the confusion radar. */
    val confusedWithCardId: Long? = null,
    /** Current correct-answer streak in the session, including this answer. */
    val combo: Int = 0,
)

data class AnswerOutcome(
    val card: CardEntity,
    val xpGained: Int,
    val masteryBefore: Mastery,
    val masteryAfter: Mastery,
    val intervalMs: Long,
    val dailyGoalJustReached: Boolean,
    val weakness: WeaknessReport,
)

data class SessionSummary(
    val answered: Int,
    val correct: Int,
    val xp: Int,
    val bestCombo: Int,
    val durationMs: Long,
    val newAchievements: List<Achievement>,
)

/** Builds study queues and records answers (FSRS + Word Mastery + Weak Words Radar + XP). */
@Singleton
class StudyRepository @Inject constructor(
    private val db: KotobaDatabase,
    private val settings: SettingsRepository,
    private val gamification: GamificationRepository,
    private val time: TimeProvider,
) {
    private val cards = db.cardDao()
    private val logs = db.reviewLogDao()
    private val activity = db.dailyActivityDao()
    private val confusions = db.confusionDao()

    suspend fun fsrs(): Fsrs = Fsrs(settings.current().fsrsParams())

    suspend fun buildQueue(req: QueueRequest): List<CardEntity> {
        val s = settings.current()
        val lang = req.language.code
        val now = time.now()
        val fsrs = Fsrs(s.fsrsParams())
        return when (req.mode) {
            QueueMode.DAILY -> {
                val today = activity.get(time.today())
                val reviewBudget = (s.maxReviewsPerDay - (today?.reviews ?: 0)).coerceAtLeast(0)
                val newBudget = (s.newPerDay - cards.countIntroducedSince(lang, time.startOfDay(time.today()))).coerceAtLeast(0)
                val due = prioritize(cards.dueCards(lang, now, minOf(reviewBudget, req.limit * 4)), fsrs, now)
                    .filter { req.deckId == null || it.deckId == req.deckId }
                    .filter { req.category == null || it.category == req.category }
                    .take(minOf(reviewBudget, req.limit))
                val fresh = cards.newCards(lang, minOf(newBudget, req.limit), req.deckId, req.category)
                interleave(due, fresh, req.limit)
            }
            QueueMode.NEW_ONLY -> cards.newCards(lang, req.limit, req.deckId, req.category)
            QueueMode.REVIEW_ONLY -> prioritize(cards.dueCards(lang, now, req.limit * 4), fsrs, now)
                .filter { req.deckId == null || it.deckId == req.deckId }
                .filter { req.category == null || it.category == req.category }
                .take(req.limit)
            QueueMode.WEAK_ONLY -> cards.weakCards(lang, WeakWordsRadar.WEAK_THRESHOLD, req.limit)
            QueueMode.QUICK -> {
                val due = prioritize(cards.dueCards(lang, now, req.limit), fsrs, now)
                val weak = cards.weakCards(lang, WeakWordsRadar.WEAK_THRESHOLD, req.limit)
                val soon = cards.studiedCards(lang, req.limit)
                (due + weak + soon).distinctBy { it.id }.take(req.limit)
            }
            QueueMode.PRACTICE -> cards.studiedCards(lang, req.limit * 3, req.deckId, req.category)
                .shuffled().take(req.limit)
            QueueMode.ALL -> {
                val studied = cards.studiedCards(lang, req.limit, req.deckId, req.category)
                val fresh = cards.newCards(lang, req.limit, req.deckId, req.category)
                (studied + fresh).take(req.limit)
            }
        }
    }

    /** Lowest retrievability first (highest forgetting risk), weak words boosted. */
    private fun prioritize(list: List<CardEntity>, fsrs: Fsrs, now: Long): List<CardEntity> =
        list.sortedBy { c ->
            val r = fsrs.retrievability(c.toSrs(), now)
            r - if (c.weakScore >= WeakWordsRadar.WEAK_THRESHOLD) 0.2 else 0.0
        }

    private fun interleave(reviews: List<CardEntity>, fresh: List<CardEntity>, limit: Int): List<CardEntity> {
        val out = ArrayList<CardEntity>(reviews.size + fresh.size)
        val r = reviews.iterator()
        val n = fresh.iterator()
        while ((r.hasNext() || n.hasNext()) && out.size < limit) {
            repeat(3) { if (r.hasNext() && out.size < limit) out += r.next() }
            if (n.hasNext() && out.size < limit) out += n.next()
        }
        return out
    }

    /** Distractors for multiple-choice / matching, preferring the same part of speech. */
    suspend fun distractors(card: CardEntity, count: Int): List<CardEntity> {
        val known = confusions.topForCard(card.id, count).mapNotNull { cards.get(it.otherCardId) }
        val pool = cards.randomCards(card.language, card.id, count * 4)
            .filter { it.translation != card.translation && it.term != card.term }
        val samePos = pool.filter { card.partOfSpeech.isNotBlank() && it.partOfSpeech == card.partOfSpeech }
        return (known + samePos + pool).distinctBy { it.id }.take(count)
    }

    suspend fun previewIntervals(card: CardEntity): Map<Rating, Long> = fsrs().preview(card.toSrs(), time.now())

    suspend fun recordAnswer(input: AnswerInput): AnswerOutcome = db.withTransaction {
        val s = settings.current()
        val fsrs = Fsrs(s.fsrsParams())
        val now = time.now()
        val card = requireNotNull(cards.get(input.cardId)) { "Card ${input.cardId} not found" }
        val wasNew = card.state == SrsState.NEW
        val wasWeak = card.weakScore >= WeakWordsRadar.WEAK_THRESHOLD

        // Practising a review card before it is due shouldn't push it further out unless it was forgotten.
        val reschedule = card.state != SrsState.REVIEW || card.due <= now || input.rating == Rating.AGAIN
        val scheduled = if (reschedule) fsrs.next(card.toSrs(), input.rating, now) else null
        val srs = scheduled?.card ?: card.toSrs()

        val response = input.responseMs.coerceIn(0, 60_000)
        val avg = if (card.avgResponseMs == 0L) response else (card.avgResponseMs * 0.7 + response * 0.3).roundToLong()

        input.confusedWithCardId?.takeIf { it != card.id }?.let { other ->
            val existing = confusions.get(card.id, other)
            confusions.upsert(ConfusionEntity(card.id, other, (existing?.count ?: 0) + 1, now))
        }

        val history = logs.recentForCard(card.id, 30)
        val recent = listOf(input.correct) + history.map { it.correct }
        val correctTypes = (history.filter { it.correct }.map { it.exercise } + if (input.correct) listOf(input.exercise) else emptyList()).toSet()
        val snapshot = LearningSnapshot(
            state = srs.state,
            stability = srs.stability,
            reps = srs.reps,
            lapses = srs.lapses,
            correctCount = card.correctCount + if (input.correct) 1 else 0,
            wrongCount = card.wrongCount + if (input.correct) 0 else 1,
            avgResponseMs = avg,
            retrievability = if (srs.state == SrsState.REVIEW) 1.0 else 0.0,
            recentResults = recent,
            correctExerciseTypes = correctTypes,
            previous = card.mastery,
            confusionCount = confusions.totalForCard(card.id),
        )
        val mastery = MasteryEvaluator.evaluate(snapshot)
        val weakness = WeakWordsRadar.analyze(snapshot)

        val updated = card.copy(
            state = srs.state,
            stability = srs.stability,
            difficulty = srs.difficulty,
            due = srs.due,
            lastReview = if (reschedule) srs.lastReview else card.lastReview,
            step = srs.step,
            reps = if (reschedule) srs.reps else card.reps + 1,
            lapses = srs.lapses,
            correctCount = snapshot.correctCount,
            wrongCount = snapshot.wrongCount,
            avgResponseMs = avg,
            mastery = mastery,
            weakScore = weakness.score,
            firstStudiedAt = card.firstStudiedAt ?: now,
            masteredAt = card.masteredAt ?: if (mastery == Mastery.MASTERED) now else null,
            updatedAt = now,
        )
        cards.update(updated)
        logs.insert(
            ReviewLogEntity(
                cardId = card.id, timestamp = now, rating = input.rating.value, correct = input.correct,
                exercise = input.exercise, responseMs = response, stateBefore = card.state,
                masteryBefore = card.mastery, masteryAfter = mastery, stabilityAfter = srs.stability,
                scheduledDays = scheduled?.scheduledDays ?: 0.0,
            ),
        )

        val wasHard = !wasNew && card.difficulty >= XpRules.HARD_DIFFICULTY
        val day = time.today()
        val event = SeasonalEvents.active(java.time.LocalDate.ofEpochDay(day))
        var xp = SeasonalEvents.boostedXp(
            XpRules.forAnswer(input.correct, wasNew, wasWeak, input.combo, wasHard), event, StudyLanguage.fromCode(card.language),
        )
        val row = activity.get(day) ?: DailyActivityEntity(day)
        val forgotten = card.state == SrsState.REVIEW && input.rating == Rating.AGAIN
        val recovered = card.state == SrsState.RELEARNING && srs.state == SrsState.REVIEW
        var nextRow = row.copy(
            newWords = row.newWords + if (wasNew) 1 else 0,
            reviews = row.reviews + if (wasNew) 0 else 1,
            correct = row.correct + if (input.correct) 1 else 0,
            wrong = row.wrong + if (input.correct) 0 else 1,
            studyMs = row.studyMs + response,
            forgotten = row.forgotten + if (forgotten) 1 else 0,
            recovered = row.recovered + if (recovered) 1 else 0,
            bestCombo = maxOf(row.bestCombo, input.combo),
        )
        val goalJustReached = !row.goalReached && row.xp + xp >= s.dailyXpGoal
        if (goalJustReached) xp += XpRules.DAILY_GOAL_REWARD
        nextRow = nextRow.copy(xp = row.xp + xp, goalReached = row.goalReached || goalJustReached, rewardClaimed = row.rewardClaimed || goalJustReached)
        activity.upsert(nextRow)

        AnswerOutcome(
            card = updated,
            xpGained = xp,
            masteryBefore = card.mastery,
            masteryAfter = mastery,
            intervalMs = scheduled?.intervalMs ?: (card.due - now).coerceAtLeast(0),
            dailyGoalJustReached = goalJustReached,
            weakness = weakness,
        )
    }

    /** Call when a session ends: checks progress achievements. */
    suspend fun finishSession(answered: Int, correct: Int, xp: Int, bestCombo: Int, durationMs: Long): SessionSummary {
        val unlocked = gamification.checkProgressAchievements(bestCombo)
        gamification.checkEventBadge()
        return SessionSummary(answered, correct, xp, bestCombo, durationMs, unlocked)
    }

    /**
     * Applies recency decay: words not reviewed for long (retrievability < 70%) drop a mastery level.
     * Run daily by the maintenance worker.
     */
    suspend fun applyMasteryDecay(): Int {
        val fsrs = fsrs()
        val now = time.now()
        val changed = cards.all().mapNotNull { c ->
            if (c.state != SrsState.REVIEW || c.mastery.ordinal <= Mastery.LEARNING.ordinal) return@mapNotNull null
            if (fsrs.retrievability(c.toSrs(), now) >= 0.7) return@mapNotNull null
            c.copy(mastery = Mastery.entries[c.mastery.ordinal - 1], updatedAt = now)
        }
        if (changed.isNotEmpty()) cards.updateAll(changed)
        return changed.size
    }

    /** Resets scheduling for a card (keeps content and history). */
    suspend fun resetProgress(cardId: Long) {
        val c = cards.get(cardId) ?: return
        cards.update(
            c.copy(
                state = SrsState.NEW, mastery = Mastery.NEW, stability = 0.0, difficulty = 0.0, due = time.now(),
                step = 0, reps = 0, lapses = 0, correctCount = 0, wrongCount = 0, weakScore = 0.0, masteredAt = null,
            ),
        )
    }
}

fun CardEntity.toSrs() = SrsCard(state, stability, difficulty, due, lastReview, step, reps, lapses)
