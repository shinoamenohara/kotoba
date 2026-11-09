package app.kotoba.core.lang

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.runCatchingCancellable
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Text-to-speech pronunciation for any supported language (offline voices where installed). */
@Singleton
class SpeechService @Inject constructor(@ApplicationContext private val context: Context) {
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    var rate: Float = 1f

    @Synchronized
    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) { _speaking.value = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { _speaking.value = false }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { _speaking.value = false }
            })
        }
    }

    suspend fun isAvailable(lang: StudyLanguage): Boolean {
        ensure()
        if (!ready.await()) return false
        val r = tts?.isLanguageAvailable(lang.ttsLocale()) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    suspend fun speak(text: String, lang: StudyLanguage, slow: Boolean = false) {
        ensure()
        if (!ready.await() || text.isBlank()) return
        tts?.run {
            language = lang.ttsLocale()
            setSpeechRate(if (slow) rate * 0.6f else rate)
            speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    fun stop() { tts?.stop(); _speaking.value = false }

    private fun StudyLanguage.ttsLocale(): Locale = when (this) {
        StudyLanguage.EN -> Locale.US
        StudyLanguage.JA -> Locale.JAPAN
        StudyLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
        StudyLanguage.KO -> Locale.KOREA
        else -> locale
    }
}

/** On-device ML Kit translation; models (~30 MB each) download on first use. */
@Singleton
class TranslatorService @Inject constructor() {
    private val translators = HashMap<String, Translator>()
    private val mutex = Mutex()
    private val modelManager by lazy { RemoteModelManager.getInstance() }

    fun supports(code: String): Boolean = TranslateLanguage.fromLanguageTag(code) != null

    suspend fun isModelDownloaded(code: String): Boolean {
        val tag = TranslateLanguage.fromLanguageTag(code) ?: return false
        return runCatchingCancellable { modelManager.isModelDownloaded(TranslateRemoteModel.Builder(tag).build()).await() }.getOrDefault(false)
    }

    suspend fun translate(text: String, from: String, to: String, wifiOnly: Boolean = false): Result<String> = runCatchingCancellable {
        if (from == to) return@runCatchingCancellable text
        val src = TranslateLanguage.fromLanguageTag(from) ?: error("Unsupported source language $from")
        val dst = TranslateLanguage.fromLanguageTag(to) ?: error("Unsupported target language $to")
        val translator = mutex.withLock {
            translators.getOrPut("$src>$dst") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
            }
        }
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        translator.downloadModelIfNeeded(conditions).await()
        translator.translate(text).await()
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
