package app.kotoba.core

import android.content.Context
import android.net.Uri
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Work run once at app start (scheduling reminders, workers…). Features contribute with
 * `@Binds @IntoSet abstract fun x(impl: MyTask): StartupTask`.
 */
interface StartupTask {
    suspend fun run(context: Context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StartupModule {
    @Multibinds abstract fun startupTasks(): Set<StartupTask>
}

/** Content shared into the app (ACTION_SEND text, ACTION_VIEW files). */
sealed interface IncomingContent {
    data class Text(val text: String, val subject: String? = null) : IncomingContent
    data class File(val uri: Uri, val mimeType: String?, val displayName: String?) : IncomingContent
}

@Singleton
class IncomingContentBus @Inject constructor() {
    private val _pending = MutableStateFlow<IncomingContent?>(null)
    val pending: StateFlow<IncomingContent?> = _pending

    fun post(content: IncomingContent) { _pending.value = content }

    /** Returns and clears the pending content if it matches [T]. */
    inline fun <reified T : IncomingContent> consume(): T? {
        val v = pending.value as? T ?: return null
        clear()
        return v
    }

    fun clear() { _pending.value = null }
}

/** File types the importer understands, by extension. */
object FileKinds {
    val deckExtensions = setOf("apkg", "colpkg", "csv", "tsv", "json", "txtdeck")
    val bookExtensions = setOf("txt", "epub", "pdf", "fb2", "docx", "html", "htm", "md")
    val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "cbz", "zip")

    fun extension(name: String?): String = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
}
