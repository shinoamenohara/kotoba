package app.kotoba.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.kotoba.MainActivity
import app.kotoba.R
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun cardRepository(): CardRepository
    fun cardDao(): CardDao
    fun settingsRepository(): SettingsRepository
    fun gamificationRepository(): GamificationRepository
    fun timeProvider(): TimeProvider
}

data class WordOfDayData(
    val flag: String,
    val term: String?,
    val reading: String,
    val translation: String,
    val due: Int,
    val streak: Int,
)

class WordOfDayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) { load(context) }
        provideContent {
            GlanceTheme { Content(context, data) }
        }
    }

    private suspend fun load(context: Context): WordOfDayData {
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val lang = ep.settingsRepository().settings.first().activeLanguage
        val card = ep.cardRepository().wordOfTheDay(lang)
        val due = ep.cardDao().countDue(lang.code, ep.timeProvider().now()).first()
        val streak = ep.gamificationRepository().currentStreak()
        return WordOfDayData(
            flag = lang.flag,
            term = card?.term,
            reading = card?.let { it.reading.ifBlank { it.ipa } }.orEmpty(),
            translation = card?.translation.orEmpty(),
            due = due,
            streak = streak,
        )
    }

    @Composable
    private fun Content(context: Context, data: WordOfDayData) {
        val size = LocalSize.current
        val compact = size.height < 110.dp
        val colors = GlanceTheme.colors
        Column(
            GlanceModifier.fillMaxSize()
                .background(colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${data.flag} ${context.getString(R.string.widget_name)}",
                    style = TextStyle(color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (data.due > 0) {
                    Text(context.getString(R.string.widget_due, data.due), style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
                    Spacer(GlanceModifier.width(8.dp))
                }
                Text(context.getString(R.string.widget_streak, data.streak), style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.height(if (compact) 2.dp else 6.dp))
            if (data.term == null) {
                Text(context.getString(R.string.widget_empty), style = TextStyle(color = colors.onSurface, fontSize = 14.sp), maxLines = 2)
                return@Column
            }
            Text(
                data.term,
                style = TextStyle(color = colors.onSurface, fontSize = if (compact) 22.sp else 30.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (data.reading.isNotBlank() && !compact) {
                Text(data.reading, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 14.sp), maxLines = 1)
            }
            Text(
                data.translation,
                style = TextStyle(color = colors.secondary, fontSize = if (compact) 13.sp else 16.sp),
                maxLines = if (size.height >= LARGE.height) 3 else 1,
            )
        }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 60.dp)
        private val WIDE = DpSize(250.dp, 110.dp)
        private val LARGE = DpSize(250.dp, 180.dp)
    }
}

class WordOfDayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WordOfDayWidget()
}

object WidgetUpdater {
    /** Re-renders every placed widget; cheap no-op when none are placed. */
    suspend fun updateAll(context: Context) {
        runCatching { WordOfDayWidget().updateAll(context) }
    }
}
