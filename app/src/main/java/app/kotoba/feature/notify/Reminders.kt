package app.kotoba.feature.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kotoba.MainActivity
import app.kotoba.R
import app.kotoba.core.StartupTask
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.StudyRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.DailyActivityDao
import app.kotoba.feature.widget.WidgetUpdater
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object ReminderNotifications {
    const val CHANNEL_ID = "study_reminders"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.notify_channel_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** "12 words due · streak 5🔥" or "Daily goal 20/50 XP · streak 5🔥". Null when there is nothing to remind about. */
    fun message(context: Context, due: Int, xp: Int, goal: Int, streak: Int): String? {
        if (due <= 0 && xp >= goal) return null
        val first = if (due > 0) context.resources.getQuantityString(R.plurals.notify_due, due, due)
        else context.getString(R.string.notify_goal, xp, goal)
        val second = if (streak > 0) context.getString(R.string.notify_streak, streak) else context.getString(R.string.notify_start_streak)
        return "$first · $second"
    }

    fun post(context: Context, text: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notify_title))
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}

/** Delay from [now] until the next [hour]:[minute] (tomorrow if already passed). */
internal fun delayUntil(now: LocalDateTime, hour: Int, minute: Int): Duration {
    var target = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
    if (!target.isAfter(now)) target = target.plusDays(1)
    return Duration.between(now, target)
}

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val cardDao: CardDao,
    private val activityDao: DailyActivityDao,
    private val gamification: GamificationRepository,
    private val time: TimeProvider,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = settings.settings.first()
        if (!s.remindersEnabled || !s.onboarded) return Result.success()
        val due = cardDao.countDue(s.activeLanguage.code, time.now()).first()
        val xp = activityDao.get(time.today())?.xp ?: 0
        val text = ReminderNotifications.message(applicationContext, due, xp, s.dailyXpGoal, gamification.currentStreak())
        if (text != null) ReminderNotifications.post(applicationContext, text)
        WidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }
}

@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val study: StudyRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { study.applyMasteryDecay() }
        WidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }
}

/**
 * Keeps WorkManager in sync with reminder settings, schedules daily maintenance and
 * refreshes the widget when the due count or today's activity changes.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    private val settings: SettingsRepository,
    private val cardDao: CardDao,
    private val activityDao: DailyActivityDao,
    private val time: TimeProvider,
) : StartupTask {

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun run(context: Context) = coroutineScope {
        val wm = WorkManager.getInstance(context)
        ReminderNotifications.ensureChannel(context)
        wm.enqueueUniquePeriodicWork(
            MAINTENANCE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntil(LocalDateTime.now(time.zone()), 4, 0).toMinutes(), TimeUnit.MINUTES)
                .build(),
        )

        launch {
            var first = true
            settings.settings.map { Triple(it.remindersEnabled, it.reminderHour, it.reminderMinute) }
                .distinctUntilChanged()
                .collect { (enabled, hour, minute) ->
                    // On app start keep the existing schedule; replace it only when the user changes the time/toggle.
                    val policy = if (first) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                    first = false
                    if (enabled) {
                        val delay = delayUntil(LocalDateTime.now(time.zone()), hour, minute)
                        wm.enqueueUniquePeriodicWork(
                            REMINDER_WORK,
                            policy,
                            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                                .build(),
                        )
                    } else {
                        wm.cancelUniqueWork(REMINDER_WORK)
                    }
                }
        }

        launch {
            WidgetUpdater.updateAll(context)
            settings.settings.map { it.activeLanguage }.distinctUntilChanged()
                .flatMapLatest { lang ->
                    combine(cardDao.countDue(lang.code, time.now()), activityDao.observe(time.today())) { due, day -> due to day?.reviews }
                }
                .distinctUntilChanged()
                .drop(1)
                .debounce(3_000)
                .collect { WidgetUpdater.updateAll(context) }
        }
        Unit
    }

    companion object {
        const val REMINDER_WORK = "daily_reminder"
        const val MAINTENANCE_WORK = "daily_maintenance"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotifyModule {
    @Binds @IntoSet abstract fun reminderScheduler(impl: ReminderScheduler): StartupTask
}
