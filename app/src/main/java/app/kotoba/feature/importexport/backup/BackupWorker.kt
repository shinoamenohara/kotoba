package app.kotoba.feature.importexport.backup

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kotoba.core.StartupTask
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Daily automatic backup into the user's chosen SAF folder. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backup: BackupManager,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = settings.current()
        if (!s.autoBackupEnabled || s.backupFolderUri.isBlank()) return Result.success()
        return runCatching { backup.backupToFolder(Uri.parse(s.backupFolderUri)) }.fold(
            onSuccess = { settings.setLastBackupAt(time.now()); Result.success() },
            onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
        )
    }

    companion object {
        const val UNIQUE_NAME = "kotoba-auto-backup"
    }
}

/** Keeps the periodic backup job in sync with the auto-backup setting for the app's lifetime. */
class BackupScheduler @Inject constructor(private val settings: SettingsRepository) : StartupTask {
    override suspend fun run(context: Context) {
        val wm = WorkManager.getInstance(context)
        settings.settings
            .map { it.autoBackupEnabled && it.backupFolderUri.isNotBlank() }
            .distinctUntilChanged()
            .collect { enabled ->
                if (enabled) {
                    val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                        .build()
                    wm.enqueueUniquePeriodicWork(BackupWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                } else {
                    wm.cancelUniqueWork(BackupWorker.UNIQUE_NAME)
                }
            }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds @IntoSet abstract fun backupScheduler(impl: BackupScheduler): StartupTask
}
