package app.imagen.chat.gen

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.imagen.chat.data.AppSettings
import app.imagen.chat.data.Aspect
import app.imagen.chat.data.CANCELLED_TEXT
import app.imagen.chat.data.ChatMessage
import app.imagen.chat.data.ChatStore
import app.imagen.chat.data.INTERRUPTED_TEXT
import app.imagen.chat.net.ApiException
import app.imagen.chat.net.ImageApi
import app.imagen.chat.util.ImageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class RunInfo(val stage: String, val aspect: Aspect)

/** Everything a generation needs, captured when the user taps send. */
class GenParams(
    val prompt: String,
    val userRef: String?,
    val settings: AppSettings,
    val aspect: Aspect,
    val transparent: Boolean,
    /** The title derived from the first prompt; the planner may replace it only while it's unchanged. */
    val autoTitle: String?,
)

/**
 * Owns image generation for the whole app. It lives in the Application, not in a screen, so a job
 * keeps running when the UI goes away. [GenerationService] keeps the process alive and networked
 * while any job runs. Results are written to [ChatStore]; the UI just observes.
 */
class GenerationManager(
    private val app: Application,
    private val store: ChatStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = HashMap<String, Job>()

    private val _running = MutableStateFlow<Map<String, RunInfo>>(emptyMap())
    val running: StateFlow<Map<String, RunInfo>> = _running.asStateFlow()

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Emits a conversation id whenever a background job changed that chat in the store. */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    @Volatile var appInForeground = false
    @Volatile var visibleConversation: String? = null

    // ---- model list cache -----------------------------------------------

    private class CachedModels(val key: String, val at: Long, val ids: List<String>)

    @Volatile private var cachedModels: CachedModels? = null

    suspend fun models(baseUrl: String, apiKey: String, force: Boolean = false): List<String> {
        val key = baseUrl.trim() + "|" + apiKey.hashCode()
        val cached = cachedModels
        if (!force && cached != null && cached.key == key && System.currentTimeMillis() - cached.at < 10 * 60_000L) {
            return cached.ids
        }
        val ids = ImageApi.listModels(baseUrl, apiKey)
        cachedModels = CachedModels(key, System.currentTimeMillis(), ids)
        return ids
    }

    // ---- control --------------------------------------------------------

    fun isRunning(conversationId: String) = _running.value.containsKey(conversationId)

    fun start(conversationId: String, params: GenParams) {
        synchronized(jobs) {
            if (jobs.containsKey(conversationId)) return
            _running.update { it + (conversationId to RunInfo("Starting", params.aspect)) }
            val job = scope.launch(start = CoroutineStart.LAZY) { run(conversationId, params) }
            jobs[conversationId] = job
            job.start()
        }
        GenerationService.ensureRunning(app)
    }

    fun cancel(conversationId: String) {
        synchronized(jobs) { jobs[conversationId] }?.cancel()
    }

    fun cancelAll() {
        val all = synchronized(jobs) { jobs.values.toList() }
        all.forEach { it.cancel() }
    }

    /**
     * Called once when the process starts: a chat still marked pending lost its job when the process
     * died, so tell the user and let them retry instead of showing a spinner forever.
     */
    fun recoverInterrupted() {
        for (summary in store.list()) {
            val stored = store.load(summary.id) ?: continue
            if (!stored.pending) continue
            store.update(summary.id) {
                it.copy(
                    pending = false,
                    messages = it.messages + ChatMessage(role = "assistant", error = INTERRUPTED_TEXT),
                )
            }
            _changes.tryEmit(summary.id)
        }
    }

    private fun setStage(conversationId: String, stage: String) {
        _running.update { map ->
            val current = map[conversationId] ?: return@update map
            map + (conversationId to RunInfo(stage, current.aspect))
        }
    }

    // ---- the job --------------------------------------------------------

    private suspend fun run(conversationId: String, p: GenParams) {
        var result: ChatMessage? = null
        var cancelled = false
        try {
            ensureOnline()
            store.update(conversationId) { it.copy(pending = true) }
            setStage(conversationId, "Reading the conversation")

            val stored = store.load(conversationId) ?: throw ApiException("This chat no longer exists.")
            val prior = stored.messages.dropLast(1) // everything before the message being answered
            val refFile = p.userRef?.let { store.imageFile(it) }?.takeIf { it.isFile }
            val previousFile = prior
                .lastOrNull { !it.isUser && it.imageFile != null && it.error == null }
                ?.imageFile?.let { store.imageFile(it) }?.takeIf { it.isFile }

            val s = p.settings
            val contextModel = resolveContextModel(s)
            val plan = if (contextModel != null) {
                ImageApi.plan(
                    s.baseUrl, s.apiKey, contextModel, transcript(prior), p.prompt,
                    hasPreviousImage = previousFile != null,
                    hasAttachment = refFile != null,
                )
            } else null

            val mode = when {
                plan != null -> plan.mode
                refFile != null || (previousFile != null && ImageApi.looksLikeEdit(p.prompt)) -> "edit"
                else -> "new"
            }
            val finalPrompt = plan?.prompt ?: p.prompt

            val suggested = plan?.title?.take(48)
            if (!suggested.isNullOrBlank() && p.autoTitle != null) {
                store.update(conversationId) { if (it.title == p.autoTitle) it.copy(title = suggested) else it }
                _changes.tryEmit(conversationId)
            }

            val base: File? = refFile ?: if (mode == "edit") previousFile else null

            setStage(conversationId, "Finding model")
            val model = resolveModel(s)
            setStage(conversationId, if (base != null) "Editing image" else "Generating image")
            val bytes = if (base != null) {
                ImageApi.edit(s.baseUrl, s.apiKey, model, finalPrompt, p.aspect.size, s.quality, p.transparent, base)
            } else {
                ImageApi.generate(s.baseUrl, s.apiKey, model, finalPrompt, p.aspect.size, s.quality, p.transparent)
            }
            setStage(conversationId, "Finishing touches")
            result = ImageProcessor.process(store, bytes, model, p.transparent)
                .copy(prompt = finalPrompt, mode = if (base != null) "edit" else "new")
        } catch (e: CancellationException) {
            cancelled = true
            result = ChatMessage(role = "assistant", error = CANCELLED_TEXT)
            throw e
        } catch (e: ApiException) {
            result = ChatMessage(role = "assistant", error = e.message ?: "Generation failed.")
        } catch (e: OutOfMemoryError) {
            result = ChatMessage(role = "assistant", error = "Not enough memory to process this image.")
        } catch (e: Exception) {
            result = ChatMessage(role = "assistant", error = "Something went wrong (${e.javaClass.simpleName}).")
        } finally {
            withContext(NonCancellable) { finish(conversationId, result, cancelled) }
        }
    }

    private fun finish(conversationId: String, message: ChatMessage?, cancelled: Boolean) {
        val msg = message ?: ChatMessage(role = "assistant", error = "Something went wrong.")
        val updated = store.update(conversationId) {
            it.copy(messages = it.messages + msg, pending = false, updatedAt = System.currentTimeMillis())
        }
        // The chat was deleted while we worked: don't leave the image behind.
        if (updated == null) msg.imageFile?.let { store.imageFile(it).delete() }
        synchronized(jobs) { jobs.remove(conversationId) }
        _changes.tryEmit(conversationId)
        // Flip the "generating" state only after the result is stored, so the UI never flickers.
        _running.update { it - conversationId }

        if (updated != null && !cancelled) {
            val watching = appInForeground && visibleConversation == conversationId
            if (!watching) {
                if (msg.error != null) {
                    Notifications.failed(app, conversationId, updated.title, msg.error)
                } else {
                    Notifications.done(app, conversationId, updated.title, msg, msg.imageFile?.let { store.imageFile(it) })
                }
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun ensureOnline() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            throw ApiException("You're offline. Check your connection and tap Retry.")
        }
    }

    /** Compact text history for the context model (last turns only). */
    private fun transcript(prior: List<ChatMessage>): List<String> = prior.takeLast(12).mapNotNull { m ->
        when {
            m.isUser -> "User: " + m.text + if (m.refImage != null) " [attached a reference image]" else ""
            m.imageFile != null ->
                "Assistant: generated an image (" + (if (m.mode == "edit") "edit" else "new") +
                    ") using the prompt: " + (m.prompt ?: "unknown")
            else -> null
        }
    }

    private suspend fun resolveContextModel(s: AppSettings): String? {
        when (s.contextModel) {
            "off" -> return null
            "auto" -> Unit
            else -> return s.contextModel
        }
        val ids = try {
            models(s.baseUrl, s.apiKey)
        } catch (e: ApiException) {
            emptyList()
        }
        return ImageApi.chooseContext(ids)
    }

    private suspend fun resolveModel(s: AppSettings): String {
        if (s.model != "auto") return s.model
        return ImageApi.chooseAuto(models(s.baseUrl, s.apiKey))
            ?: throw ApiException("No image model was found at this URL. Pick one in Settings.")
    }
}
