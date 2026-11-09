package app.kotoba.core.model

/** Word Mastery stage. Ordinal order matters: higher = better known. */
enum class Mastery { NEW, LEARNING, FAMILIAR, MASTERED }

/** FSRS card state. */
enum class SrsState { NEW, LEARNING, REVIEW, RELEARNING }

/** FSRS grade. */
enum class Rating(val value: Int) {
    AGAIN(1), HARD(2), GOOD(3), EASY(4);

    companion object {
        fun of(value: Int): Rating = entries.first { it.value == value }
    }
}

/** Every exercise reports results with one of these so analytics can split by type. */
enum class ExerciseType {
    FLASHCARD_TERM_TO_TRANSLATION,
    FLASHCARD_TRANSLATION_TO_TERM,
    FLASHCARD_TERM_TO_CONTEXT,
    FLASHCARD_AUDIO_TO_TERM,
    MULTIPLE_CHOICE,
    REVERSE_MULTIPLE_CHOICE,
    TYPING,
    LISTENING,
    SPELLING,
    WORD_SCRAMBLE,
    FILL_BLANK,
    SENTENCE_BUILDER,
    MATCHING,
    CONTEXT_QUIZ,
    TRANSLATION,
    PRONUNCIATION,
    QUICK_REVIEW,
}

/** Where a context sentence came from (Readlang/NotebookLM-style sentence system). */
enum class ContextSource { BOOK, MANGA, BUILTIN, AI, USER, IMPORT }

/** Imported material kind. */
enum class DocumentKind { BOOK, ARTICLE, MANGA, NOTEBOOKLM, NOTE }

enum class DocumentFormat { TXT, EPUB, PDF, FB2, DOCX, HTML, MARKDOWN, IMAGES, CBZ }

/** Where a card was created from. Stored as a string in [app.kotoba.core.db.CardEntity.source]. */
object CardSource {
    const val MANUAL = "manual"
    const val BUILTIN = "builtin"
    const val BOOK = "book"
    const val MANGA = "manga"
    const val AI = "ai"
    const val ANKI = "anki"
    const val NOTEBOOKLM = "notebooklm"
    const val IMPORT = "import"
}

/** Thematic categories (Vocabulary Map). Keys are stable and stored in the DB. */
enum class Category(val key: String, val emoji: String) {
    EVERYDAY("everyday", "☕"),
    BUSINESS("business", "💼"),
    TECHNOLOGY("technology", "💻"),
    SCIENCE("science", "🔬"),
    LITERATURE("literature", "📚"),
    NATURE("nature", "🌿"),
    FOOD("food", "🍜"),
    TRAVEL("travel", "✈️"),
    EDUCATION("education", "🎓"),
    ENTERTAINMENT("entertainment", "🎬"),
    INTERNET_GAMING("internet_gaming", "🎮"),
    OTHER("other", "🗂️"),
    ;

    companion object {
        fun fromKey(key: String?): Category = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

enum class PartOfSpeech(val key: String) {
    NOUN("noun"), VERB("verb"), ADJECTIVE("adjective"), ADVERB("adverb"), PRONOUN("pronoun"),
    PREPOSITION("preposition"), CONJUNCTION("conjunction"), INTERJECTION("interjection"),
    PARTICLE("particle"), COUNTER("counter"), PHRASE("phrase"), IDIOM("idiom"), OTHER("other");

    companion object {
        fun fromKey(key: String?): PartOfSpeech =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: OTHER
    }
}
