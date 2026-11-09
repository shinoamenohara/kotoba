# Kotoba 言葉

Android app for growing your vocabulary from content you actually enjoy: books, manga, articles and your own decks.
It started from the ReWord approach (flashcards, categories, spaced repetition, daily sessions) and adds Anki-style
decks, Duolingo-style motivation, Readlang-style reading and NotebookLM-style AI work with your own materials.

**Languages:** English, Japanese and Chinese have first-class support: kana/pinyin readings, CEFR/JLPT/HSK levels,
CJK word segmentation, Japanese/Chinese OCR and starter decks. Korean, Spanish, German, French, Italian, Portuguese
and Russian also work with the same pipeline. Interface: Russian and English.

## Features

| Area | What's inside |
|---|---|
| Flashcards | Term, reading (kana/pinyin), IPA, audio (TTS), translation, examples with translations, image + association, part of speech, synonyms/antonyms, forms, level, source, notes. Templates: term→translation, translation→term, term→context, audio→term. |
| Smart SRS | **FSRS-5** scheduler, learning/relearning steps, desired retention, new/day and review limits, priority by forgetting risk. |
| Word Mastery | New → Learning → Familiar → Mastered, derived from accuracy, stability, response time, exercise variety and recency; words drop back a level when they're forgotten. |
| Weak Words Radar | Flags words that are forgotten, confused with each other, slow or unstable. They get boosted priority and their own practice sessions. |
| Exercises | Multiple choice, typing, listening, spelling, word scramble, fill in the blank, sentence builder, matching, context quiz, sentence translation (AI-graded), pronunciation (speech recognition), mixed mode, Quick Review (1/3/5 min). |
| Reading | EPUB / PDF / TXT / FB2 / DOCX / HTML / Markdown reader. Tap a word to translate it, see its reading and add it with the sentence; select phrases; bookmarks, highlights and notes; per-book stats. |
| Manga | Images / CBZ / PDF; ML Kit OCR (Latin, Japanese incl. vertical text, Chinese, Korean); tappable speech bubbles; OCR you can correct; cards that keep the original line and bubble crop. |
| AI Tutor | Bring your own key: OpenAI, Anthropic, Gemini, OpenRouter, DeepSeek, Mistral, Groq, or any OpenAI-compatible endpoint (Ollama, LM Studio). Explanations, grammar, mistake analysis based on your stats, dialogue practice, generated exercises. Keys are encrypted with Android Keystore; daily token/request limits. |
| Deck Generator | Builds a deck from a book, article, NotebookLM export or pasted text: AI or offline extraction, filters, then you review and pick the words; nothing is added automatically. |
| Import / export | Anki `.apkg`/`.colpkg` (fields, tags, decks, media, optional progress), CSV/TSV, JSON, custom dictionaries; export to CSV/TSV/JSON; full backups, automatic backup to a folder (e.g. Google Drive) for syncing across devices. |
| Gamification | XP and levels, streak, daily goal and reward, weekly quests, daily challenge, combos, hard-word bonus, achievements. Weekly leagues (Bronze → Diamond), a friends leaderboard via shareable friend codes (no account), and seasonal events with boosted XP and badges: Spring Festival and Mid-Autumn (×2 for Chinese), Hanami and Tanabata (×2 for Japanese), Halloween (×2 for English), New Year and Summer Reading. |
| Stats | Day/week/month reports, vocabulary growth, activity, accuracy, categories, CEFR/JLPT/HSK distribution, error analytics, Vocabulary Map. |
| System | Offline-first (Room), reminders, Word-of-the-day widget, light/dark/dynamic theme, haptics, share text into the app. |

## Tech stack

Kotlin 2.1 · Jetpack Compose + Material 3 · Navigation Compose (type-safe routes) · Hilt · Room · DataStore ·
WorkManager · Glance widget · kotlinx.serialization · OkHttp · Coil · ML Kit (text recognition, translation) ·
PdfBox-Android · Jsoup · zstd-jni. Min SDK 26, target SDK 35.

```
app/src/main/java/app/kotoba/
  core/        model, db (Room), srs (FSRS, mastery, weak radar), data (repositories), ai (providers),
               lang (segmentation, TTS, translation, dictionary), docs (document parsing), ui, navigation
  feature/     home, learn, library, reader, manga, ai, stats, settings, importexport, notify, widget
```

## Build

Requires JDK 17 and the Android SDK (platform 35).

```bash
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # unit tests
```

CI (`.github/workflows/android.yml`) runs the tests and uploads the debug APK as an artifact.
