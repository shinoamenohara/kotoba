package app.kotoba.core.di

import android.content.Context
import androidx.room.Room
import app.kotoba.core.ai.AiProviders
import app.kotoba.core.data.SystemTimeProvider
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.KotobaDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): KotobaDatabase =
        Room.databaseBuilder(context, KotobaDatabase::class.java, KotobaDatabase.NAME).build()

    @Provides fun cardDao(db: KotobaDatabase) = db.cardDao()
    @Provides fun deckDao(db: KotobaDatabase) = db.deckDao()
    @Provides fun reviewLogDao(db: KotobaDatabase) = db.reviewLogDao()
    @Provides fun contextDao(db: KotobaDatabase) = db.contextDao()
    @Provides fun documentDao(db: KotobaDatabase) = db.documentDao()
    @Provides fun bookmarkDao(db: KotobaDatabase) = db.bookmarkDao()
    @Provides fun dailyActivityDao(db: KotobaDatabase) = db.dailyActivityDao()
    @Provides fun achievementDao(db: KotobaDatabase) = db.achievementDao()
    @Provides fun confusionDao(db: KotobaDatabase) = db.confusionDao()
    @Provides fun chatDao(db: KotobaDatabase) = db.chatDao()
    @Provides fun maintenanceDao(db: KotobaDatabase) = db.maintenanceDao()

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** No logging interceptor on purpose: request headers carry API keys. */
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun aiProviders(client: OkHttpClient, json: Json): AiProviders = AiProviders(client, json)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun timeProvider(impl: SystemTimeProvider): TimeProvider
}
