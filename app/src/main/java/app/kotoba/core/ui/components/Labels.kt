package app.kotoba.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.kotoba.R
import app.kotoba.core.data.Achievement
import app.kotoba.core.model.Category

@Composable
fun categoryLabel(c: Category): String = stringResource(
    when (c) {
        Category.EVERYDAY -> R.string.category_everyday
        Category.BUSINESS -> R.string.category_business
        Category.TECHNOLOGY -> R.string.category_technology
        Category.SCIENCE -> R.string.category_science
        Category.LITERATURE -> R.string.category_literature
        Category.NATURE -> R.string.category_nature
        Category.FOOD -> R.string.category_food
        Category.TRAVEL -> R.string.category_travel
        Category.EDUCATION -> R.string.category_education
        Category.ENTERTAINMENT -> R.string.category_entertainment
        Category.INTERNET_GAMING -> R.string.category_internet_gaming
        Category.OTHER -> R.string.category_other
    },
)

@Composable
fun achievementTitle(a: Achievement): String = stringResource(achievementTitleRes(a))

@Composable
fun achievementDescription(a: Achievement): String = stringResource(achievementDescRes(a))

fun achievementTitleRes(a: Achievement): Int = when (a) {
    Achievement.FIRST_WORD -> R.string.achievement_first_word_title
    Achievement.WORDS_100 -> R.string.achievement_words_100_title
    Achievement.WORDS_500 -> R.string.achievement_words_500_title
    Achievement.MASTERED_100 -> R.string.achievement_mastered_100_title
    Achievement.MASTERED_1000 -> R.string.achievement_mastered_1000_title
    Achievement.STREAK_7 -> R.string.achievement_streak_7_title
    Achievement.STREAK_30 -> R.string.achievement_streak_30_title
    Achievement.STREAK_100 -> R.string.achievement_streak_100_title
    Achievement.REVIEWS_100 -> R.string.achievement_reviews_100_title
    Achievement.REVIEWS_1000 -> R.string.achievement_reviews_1000_title
    Achievement.COMBO_25 -> R.string.achievement_combo_25_title
    Achievement.CATEGORY_MASTERED -> R.string.achievement_category_mastered_title
    Achievement.DECK_COMPLETED -> R.string.achievement_deck_completed_title
    Achievement.FIRST_BOOK -> R.string.achievement_first_book_title
    Achievement.FIRST_MANGA -> R.string.achievement_first_manga_title
    Achievement.FIRST_IMPORT -> R.string.achievement_first_import_title
    Achievement.AI_TUTOR -> R.string.achievement_ai_tutor_title
    Achievement.POLYGLOT -> R.string.achievement_polyglot_title
}

fun achievementDescRes(a: Achievement): Int = when (a) {
    Achievement.FIRST_WORD -> R.string.achievement_first_word_desc
    Achievement.WORDS_100 -> R.string.achievement_words_100_desc
    Achievement.WORDS_500 -> R.string.achievement_words_500_desc
    Achievement.MASTERED_100 -> R.string.achievement_mastered_100_desc
    Achievement.MASTERED_1000 -> R.string.achievement_mastered_1000_desc
    Achievement.STREAK_7 -> R.string.achievement_streak_7_desc
    Achievement.STREAK_30 -> R.string.achievement_streak_30_desc
    Achievement.STREAK_100 -> R.string.achievement_streak_100_desc
    Achievement.REVIEWS_100 -> R.string.achievement_reviews_100_desc
    Achievement.REVIEWS_1000 -> R.string.achievement_reviews_1000_desc
    Achievement.COMBO_25 -> R.string.achievement_combo_25_desc
    Achievement.CATEGORY_MASTERED -> R.string.achievement_category_mastered_desc
    Achievement.DECK_COMPLETED -> R.string.achievement_deck_completed_desc
    Achievement.FIRST_BOOK -> R.string.achievement_first_book_desc
    Achievement.FIRST_MANGA -> R.string.achievement_first_manga_desc
    Achievement.FIRST_IMPORT -> R.string.achievement_first_import_desc
    Achievement.AI_TUTOR -> R.string.achievement_ai_tutor_desc
    Achievement.POLYGLOT -> R.string.achievement_polyglot_desc
}

@Composable
fun questLabel(key: String): String = stringResource(
    when (key) {
        "weekly_reviews" -> R.string.quest_weekly_reviews
        "weekly_new_words" -> R.string.quest_weekly_new_words
        "weekly_goal_days" -> R.string.quest_weekly_goal_days
        "weekly_minutes" -> R.string.quest_weekly_minutes
        "daily_combo" -> R.string.quest_daily_combo
        else -> R.string.app_name
    },
)
