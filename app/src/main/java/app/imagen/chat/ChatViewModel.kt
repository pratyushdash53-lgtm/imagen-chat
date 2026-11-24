package app.imagen.chat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.imagen.chat.data.AppSettings
import app.imagen.chat.data.Aspect
import app.imagen.chat.data.ChatMessage
import app.imagen.chat.data.Conversation
import app.imagen.chat.data.ConversationSummary
import app.imagen.chat.data.DEFAULT_TITLE
import app.imagen.chat.data.newId
import app.imagen.chat.gen.GenParams
import app.imagen.chat.gen.RunInfo
import app.imagen.chat.net.ApiException
import app.imagen.chat.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Screen state only. Chats live in the store and generation runs in the app-level manager, so
 * nothing here is lost or interrupted when the screen is destroyed or the app goes to the background.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val imagen = app as ImagenApp
    private val store = imagen.chats
    private val settingsStore = imagen.settingsStore
    private val manager = imagen.manager

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

    /** Chats with a generation in flight (any of them, not just the open one). */
    var running by mutableStateOf<Map<String, RunInfo>>(emptyMap())
        private set

    val generating: Boolean get() = running.containsKey(currentId)
    val stage: String get() = running[currentId]?.stage ?: ""
    val generatingAspect: Aspect get() = running[currentId]?.aspect ?: Aspect.Auto

    /** One-shot message for the snackbar. */
    var notice by mutableStateOf<String?>(null)

    var allModels by mutableStateOf<List<String>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set

    init {
        manager.visibleConversation = currentId
        conversations = store.list()
        viewModelScope.launch { manager.running.collect { running = it } }
        viewModelScope.launch {
            manager.changes.collect { id ->
                conversations = store.list()
                if (id == currentId) loadCurrent()
            }
        }
    }

    // ---- settings -------------------------------------------------------

    fun saveSettings(new: AppSettings) {
        settingsStore.save(new)
        settings = new
        notice = "Settings saved"
    }

    fun loadModels(baseUrl: String, apiKey: String) {
        modelsLoading = true
        modelsError = null
        viewModelScope.launch {
            try {
                allModels = manager.models(baseUrl, apiKey, force = true)
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

    /** True exactly once, so we ask for notification permission in context and never nag. */
    fun shouldAskNotifications(): Boolean {
        if (settingsStore.notificationsAsked) return false
        settingsStore.notificationsAsked = true
        return true
    }

    // ---- conversations --------------------------------------------------

    private fun loadCurrent() {
        val conversation = store.load(currentId) ?: return
        messages = conversation.messages
        title = conversation.title
    }

    private fun select(id: String) {
        currentId = id
        manager.visibleConversation = id
    }

    fun newChat() {
        clearReference()
        select(newId())
        title = DEFAULT_TITLE
        messages = emptyList()
    }

    fun openConversation(id: String) {
        if (id != currentId) {
            clearReference()
            select(id)
        }
        val conversation = store.load(id)
        if (conversation == null) {
            title = DEFAULT_TITLE
            messages = emptyList()
        } else {
            title = conversation.title
            messages = conversation.messages
        }
        conversations = store.list()
    }

    fun deleteConversation(id: String) {
        manager.cancel(id)
        store.delete(id)
        if (id == currentId) newChat()
        conversations = store.list()
    }

    fun rename(id: String, newTitle: String) {
        val t = newTitle.trim().take(60)
        if (t.isEmpty()) return
        if (id == currentId) title = t
        store.update(id) { it.copy(title = t) }
        conversations = store.list()
    }

    fun imageFile(name: String): File = store.imageFile(name)

    // ---- storage --------------------------------------------------------

    fun storageBytes(): Long = store.sizeBytes()

    fun clearAll() {
        manager.cancelAll()
        store.deleteAll()
        newChat()
        conversations = store.list()
        notice = "All chats deleted"
    }

    // ---- reference image ------------------------------------------------

    fun attachReference(uri: Uri) {
        viewModelScope.launch {
            val name = "ref_" + newId() + ".png"
            val ok = withContext(Dispatchers.IO) {
                ImageUtils.importReference(getApplication(), uri, store.imageFile(name))
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
        reference?.let { if (it.startsWith("ref_")) store.imageFile(it).delete() }
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
        val isFirst = messages.isEmpty()
        val autoTitle = if (isFirst) prompt.take(48) else null
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(role = "user", text = prompt, refImage = attached)
        store.update(
            currentId,
            create = { Conversation(currentId, autoTitle ?: title, now, emptyList()) },
        ) { it.copy(messages = it.messages + userMessage, updatedAt = now) }
        loadCurrent()
        conversations = store.list()
        manager.start(currentId, params(prompt, attached, autoTitle))
        return true
    }

    fun retry(failed: ChatMessage) {
        if (generating) return
        val index = messages.indexOfFirst { it.id == failed.id }
        if (index < 0) return
        val userMessage = messages.take(index).lastOrNull { it.isUser } ?: return
        store.update(currentId) { c -> c.copy(messages = c.messages.filterNot { it.id == failed.id }) }
        loadCurrent()
        manager.start(currentId, params(userMessage.text, userMessage.refImage, null))
    }

    fun cancelGeneration() = manager.cancel(currentId)

    private fun params(prompt: String, ref: String?, autoTitle: String?) =
        GenParams(prompt, ref, settings, aspect, transparent, autoTitle)
}
