package app.imagen.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.imagen.chat.data.Aspect
import app.imagen.chat.data.AppSettings
import app.imagen.chat.data.ChatMessage
import app.imagen.chat.data.ChatStore
import app.imagen.chat.data.Conversation
import app.imagen.chat.data.ConversationSummary
import app.imagen.chat.data.DEFAULT_TITLE
import app.imagen.chat.data.SettingsStore
import app.imagen.chat.data.newId
import app.imagen.chat.net.ApiException
import app.imagen.chat.net.ImageApi
import app.imagen.chat.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = SettingsStore(app)
    private val chatStore = ChatStore(app)

    var settings by mutableStateOf(settingsStore.load())
        private set
    var conversations by mutableStateOf<List<ConversationSummary>>(emptyList())
        private set
    var currentId by mutableStateOf(newId())
        private set
    var title by mutableStateOf(DEFAULT_TITLE)
        private set
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    var aspect by mutableStateOf(Aspect.Auto)
    var transparent by mutableStateOf(settings.transparentDefault)

    /** Image file name (inside the images dir) attached to the message being written. */
    var reference by mutableStateOf<String?>(null)
        private set

    var generating by mutableStateOf(false)
        private set
    var stage by mutableStateOf("")
        private set
    var generatingAspect by mutableStateOf(Aspect.Auto)
        private set

    /** One-shot message for the snackbar. */
    var notice by mutableStateOf<String?>(null)

    var allModels by mutableStateOf<List<String>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null
    private var autoModel: String? = null
    private var autoModelKey: String = ""
    private var titleAuto = false

    init {
        conversations = chatStore.list()
    }

    // ---- settings -------------------------------------------------------

    fun saveSettings(new: AppSettings) {
        settingsStore.save(new)
        settings = new
        autoModel = null
        notice = "Settings saved"
    }

    fun loadModels(baseUrl: String, apiKey: String) {
        modelsLoading = true
        modelsError = null
        viewModelScope.launch {
            try {
                allModels = ImageApi.listModels(baseUrl, apiKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                allModels = emptyList()
                modelsError = e.message
            } catch (e: Exception) {
                allModels = emptyList()
                modelsError = "Couldn't load models."
            } finally {
                modelsLoading = false
            }
        }
    }

    // ---- conversations --------------------------------------------------

    fun newChat() {
        cancelGeneration()
        clearReference()
        currentId = newId()
        title = DEFAULT_TITLE
        titleAuto = false
        messages = emptyList()
    }

    fun openConversation(id: String) {
        if (id == currentId) return
        cancelGeneration()
        val loaded = chatStore.load(id) ?: return
        clearReference()
        currentId = loaded.id
        title = loaded.title
        titleAuto = false
        messages = loaded.messages
    }

    fun deleteConversation(id: String) {
        chatStore.delete(id)
        if (id == currentId) newChat()
        conversations = chatStore.list()
    }

    fun rename(id: String, newTitle: String) {
        val t = newTitle.trim().take(60)
        if (t.isEmpty()) return
        if (id == currentId) {
            title = t
            titleAuto = false
        }
        val stored = chatStore.load(id) ?: return
        chatStore.save(stored.copy(title = t))
        conversations = chatStore.list()
    }

    fun imageFile(name: String): File = chatStore.imageFile(name)

    private fun persist() {
        if (messages.isEmpty()) return
        chatStore.save(Conversation(currentId, title, System.currentTimeMillis(), messages))
        conversations = chatStore.list()
    }

    // ---- reference image ------------------------------------------------

    fun attachReference(uri: Uri) {
        viewModelScope.launch {
            val name = "ref_" + newId() + ".png"
            val ok = withContext(Dispatchers.IO) {
                ImageUtils.importReference(getApplication(), uri, chatStore.imageFile(name))
            }
            if (ok) {
                dropReferenceFile()
                reference = name
            } else {
                notice = "Couldn't open that image."
            }
        }
    }

    /** Uses an already generated image as the reference for the next message. */
    fun useAsReference(message: ChatMessage) {
        val file = message.imageFile ?: return
        dropReferenceFile()
        reference = file
        notice = "Describe how to change this image"
    }

    fun clearReference() {
        dropReferenceFile()
        reference = null
    }

    // Only files the user uploaded (ref_*) are ours to delete; generated images stay.
    private fun dropReferenceFile() {
        reference?.let { if (it.startsWith("ref_")) chatStore.imageFile(it).delete() }
    }

    // ---- generation -----------------------------------------------------

    /** Returns false when settings are missing so the UI can point at them. */
    fun send(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || generating) return true
        if (!settings.isConfigured) {
            notice = "Add your API URL and key in Settings first."
            return false
        }
        val attached = reference
        reference = null
        messages = messages + ChatMessage(role = "user", text = prompt, refImage = attached)
        if (title == DEFAULT_TITLE) {
            title = prompt.take(48)
            titleAuto = true
        }
        persist()
        runGeneration(prompt, attached)
        return true
    }

    fun retry(failed: ChatMessage) {
        if (generating) return
        val index = messages.indexOfFirst { it.id == failed.id }
        if (index < 0) return
        val userMessage = messages.take(index).lastOrNull { it.isUser } ?: return
        messages = messages.filterNot { it.id == failed.id }
        persist()
        runGeneration(userMessage.text, userMessage.refImage)
    }

    fun cancelGeneration() {
        job?.cancel()
        job = null
    }

    private fun runGeneration(prompt: String, userRef: String?) {
        val wantTransparent = transparent
        val size = aspect
        val snapshot = settings
        // Everything before the message being answered: this is the chat context.
        val prior = messages.dropLast(1)
        generating = true
        generatingAspect = size
        stage = "Reading the conversation"
        job = viewModelScope.launch {
            try {
                val refFile = userRef?.let { chatStore.imageFile(it) }?.takeIf { it.isFile }
                val previousFile = prior
                    .lastOrNull { !it.isUser && it.imageFile != null && it.error == null }
                    ?.imageFile?.let { chatStore.imageFile(it) }?.takeIf { it.isFile }

                var mode: String
                var finalPrompt = prompt
                val contextModel = resolveContextModel(snapshot)
                val plan = if (contextModel != null) {
                    ImageApi.plan(
                        snapshot.baseUrl, snapshot.apiKey, contextModel,
                        transcript(prior), prompt,
                        hasPreviousImage = previousFile != null,
                        hasAttachment = refFile != null,
                    )
                } else null
                if (plan != null) {
                    mode = plan.mode
                    finalPrompt = plan.prompt
                    val suggested = plan.title?.take(48)
                    if (titleAuto && !suggested.isNullOrBlank()) {
                        title = suggested
                        titleAuto = false
                    }
                } else {
                    mode = if (refFile != null || (previousFile != null && ImageApi.looksLikeEdit(prompt))) "edit" else "new"
                }
                val base: File? = refFile ?: if (mode == "edit") previousFile else null

                stage = "Finding model"
                val model = resolveModel(snapshot)
                stage = if (base != null) "Editing image" else "Generating image"
                val bytes = if (base != null) {
                    ImageApi.edit(
                        snapshot.baseUrl, snapshot.apiKey, model, finalPrompt,
                        size.size, snapshot.quality, wantTransparent, base,
                    )
                } else {
                    ImageApi.generate(
                        snapshot.baseUrl, snapshot.apiKey, model, finalPrompt,
                        size.size, snapshot.quality, wantTransparent,
                    )
                }
                stage = "Finishing touches"
                val message = withContext(Dispatchers.Default) { process(bytes, model, wantTransparent) }
                    .copy(prompt = finalPrompt, mode = if (base != null) "edit" else "new")
                messages = messages + message
            } catch (e: CancellationException) {
                messages = messages + ChatMessage(role = "assistant", error = "Generation cancelled.")
                throw e
            } catch (e: ApiException) {
                messages = messages + ChatMessage(role = "assistant", error = e.message ?: "Generation failed.")
            } catch (e: Exception) {
                messages = messages + ChatMessage(
                    role = "assistant",
                    error = "Something went wrong (${e.javaClass.simpleName}).",
                )
            } finally {
                generating = false
                persist()
            }
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
        var ids = allModels
        if (ids.isEmpty()) {
            ids = try {
                ImageApi.listModels(s.baseUrl, s.apiKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            allModels = ids
        }
        return ImageApi.chooseContext(ids)
    }

    private suspend fun resolveModel(s: AppSettings): String {
        if (s.model != "auto") return s.model
        val key = s.baseUrl + "|" + s.apiKey.hashCode()
        autoModel?.let { if (autoModelKey == key) return it }
        val ids = allModels.ifEmpty { ImageApi.listModels(s.baseUrl, s.apiKey).also { allModels = it } }
        val chosen = ImageApi.chooseAuto(ids)
            ?: throw ApiException("No image model was found at this URL. Pick one in Settings.")
        autoModel = chosen
        autoModelKey = key
        return chosen
    }

    private fun process(bytes: ByteArray, model: String, wantTransparent: Boolean): ChatMessage {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw ApiException("The provider's image couldn't be decoded.")
        var warning: String? = null
        var removed = false
        var stats = ImageUtils.alphaStats(bitmap)
        if (wantTransparent && !stats.borderClear) {
            val cut = ImageUtils.removeBackground(bitmap)
            if (cut != null) {
                bitmap = cut
                removed = true
                stats = ImageUtils.alphaStats(bitmap)
            } else {
                warning = "The provider didn't return a transparent background, and the backdrop wasn't " +
                    "plain enough to cut out automatically."
            }
        }
        val name = newId() + ".png"
        ImageUtils.savePng(bitmap, chatStore.imageFile(name))
        return ChatMessage(
            role = "assistant",
            imageFile = name,
            model = model,
            width = bitmap.width,
            height = bitmap.height,
            hasAlpha = stats.hasAlpha,
            backgroundRemoved = removed,
            warning = warning,
        )
    }
}
