package app.kotoba.core.navigation

import kotlinx.serialization.Serializable

// Top-level destinations (bottom bar)
@Serializable data object HomeRoute
@Serializable data object LearnRoute
@Serializable data object LibraryRoute
@Serializable data object StatsRoute
@Serializable data class TutorRoute(val conversationId: Long = -1, val prompt: String? = null)

// Onboarding & profile
@Serializable data object OnboardingRoute
@Serializable data object AchievementsRoute

/**
 * A study session.
 * @param mode one of [SessionMode] names.
 * @param exercise optional [app.kotoba.core.model.ExerciseType] name for single-exercise sessions.
 * @param minutes time box for Quick Review (1/3/5).
 */
@Serializable
data class SessionRoute(
    val mode: String,
    val deckId: Long = -1,
    val category: String? = null,
    val minutes: Int = 0,
    val exercise: String? = null,
)

enum class SessionMode { DAILY, NEW, REVIEW, WEAK, QUICK, FLASHCARDS, EXERCISE, MIXED }

@Serializable data object WeakWordsRoute

// Library
@Serializable data class CardDetailRoute(val cardId: Long)
@Serializable data class CardEditorRoute(val cardId: Long = -1, val deckId: Long = -1, val term: String? = null)
@Serializable data class DeckRoute(val deckId: Long)
@Serializable data class CategoryRoute(val key: String)
/** filter: "all" | "favorites" | "weak" | "mastery:NEW" … */
@Serializable data class WordListRoute(val filter: String = "all")

// Reading (books, articles, NotebookLM notes) and manga
/** tab: "books" | "manga" | "notes" */
@Serializable data class DocumentsRoute(val tab: String = "books")
@Serializable data class ReaderRoute(val documentId: Long)
@Serializable data class MangaReaderRoute(val documentId: Long)

// AI
@Serializable data class GeneratorRoute(val documentId: Long = -1)
@Serializable data object AiSettingsRoute

// Stats
@Serializable data object VocabularyMapRoute

// Settings
@Serializable data object SettingsRoute
@Serializable data object ImportExportRoute
@Serializable data object StorageRoute
