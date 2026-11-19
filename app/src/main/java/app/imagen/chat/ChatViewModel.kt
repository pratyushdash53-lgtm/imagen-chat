package app.imagen.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        currentId = newId()
        title = DEFAULT_TITLE
        messages = emptyList()
    }

    fun openConversation(id: String) {
        if (id == currentId) return
        cancelGeneration()
        val loaded = chatStore.load(id) ?: return
        currentId = loaded.id
        title = loaded.title
        messages = loaded.messages
    }

    fun deleteConversation(id: String) {
        chatStore.delete(id)
        if (id == currentId) newChat()
        conversations = chatStore.list()
    }

    fun imageFile(name: String): File = chatStore.imageFile(name)

    private fun persist() {
        if (messages.isEmpty()) return
        chatStore.save(Conversation(currentId, title, System.currentTimeMillis(), messages))
        conversations = chatStore.list()
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
        messages = messages + ChatMessage(role = "user", text = prompt)
        if (title == DEFAULT_TITLE) title = prompt.take(48)
        persist()
        runGeneration(prompt)
        return true
    }

    fun retry(failed: ChatMessage) {
        if (generating) return
        val index = messages.indexOfFirst { it.id == failed.id }
        if (index < 0) return
        val prompt = messages.take(index).lastOrNull { it.isUser }?.text ?: return
        messages = messages.filterNot { it.id == failed.id }
        persist()
        runGeneration(prompt)
    }

    fun cancelGeneration() {
        job?.cancel()
        job = null
    }

    private fun runGeneration(prompt: String) {
        val wantTransparent = transparent
        val size = aspect
        val snapshot = settings
        generating = true
        generatingAspect = size
        stage = "Preparing"
        job = viewModelScope.launch {
            try {
                stage = "Finding model"
                val model = resolveModel(snapshot)
                stage = "Generating image"
                val bytes = ImageApi.generate(
                    snapshot.baseUrl, snapshot.apiKey, model, prompt,
                    size.size, snapshot.quality, wantTransparent,
                )
                stage = "Finishing touches"
                val message = withContext(Dispatchers.Default) { process(bytes, model, wantTransparent) }
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

    private suspend fun resolveModel(s: AppSettings): String {
        if (s.model != "auto") return s.model
        val key = s.baseUrl + "|" + s.apiKey.hashCode()
        autoModel?.let { if (autoModelKey == key) return it }
        val ids = ImageApi.listModels(s.baseUrl, s.apiKey)
        allModels = ids
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
