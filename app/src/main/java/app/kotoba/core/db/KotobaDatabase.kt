package app.kotoba.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.DocumentFormat
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState

class Converters {
    @TypeConverter fun masteryToString(v: Mastery): String = v.name
    @TypeConverter fun stringToMastery(v: String): Mastery = runCatching { Mastery.valueOf(v) }.getOrDefault(Mastery.NEW)
    @TypeConverter fun stateToString(v: SrsState): String = v.name
    @TypeConverter fun stringToState(v: String): SrsState = runCatching { SrsState.valueOf(v) }.getOrDefault(SrsState.NEW)
    @TypeConverter fun exerciseToString(v: ExerciseType): String = v.name
    @TypeConverter fun stringToExercise(v: String): ExerciseType =
        runCatching { ExerciseType.valueOf(v) }.getOrDefault(ExerciseType.QUICK_REVIEW)
    @TypeConverter fun contextSourceToString(v: ContextSource): String = v.name
    @TypeConverter fun stringToContextSource(v: String): ContextSource = runCatching { ContextSource.valueOf(v) }.getOrDefault(ContextSource.IMPORT)
    @TypeConverter fun kindToString(v: DocumentKind): String = v.name
    @TypeConverter fun stringToKind(v: String): DocumentKind = runCatching { DocumentKind.valueOf(v) }.getOrDefault(DocumentKind.BOOK)
    @TypeConverter fun formatToString(v: DocumentFormat): String = v.name
    @TypeConverter fun stringToFormat(v: String): DocumentFormat = runCatching { DocumentFormat.valueOf(v) }.getOrDefault(DocumentFormat.TXT)
}

@Database(
    entities = [
        DeckEntity::class, CardEntity::class, ReviewLogEntity::class, ContextEntity::class,
        DocumentEntity::class, BookmarkEntity::class, DailyActivityEntity::class,
        AchievementEntity::class, ConfusionEntity::class, ChatMessageEntity::class, ConversationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KotobaDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun deckDao(): DeckDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun contextDao(): ContextDao
    abstract fun documentDao(): DocumentDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun dailyActivityDao(): DailyActivityDao
    abstract fun achievementDao(): AchievementDao
    abstract fun confusionDao(): ConfusionDao
    abstract fun chatDao(): ChatDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val NAME = "kotoba.db"
    }
}
