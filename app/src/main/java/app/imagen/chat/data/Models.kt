package app.imagen.chat.data

import java.util.UUID

enum class Aspect(val label: String, val size: String, val ratio: Float) {
    Auto("Auto", "auto", 1f),
    Square("Square", "1024x1024", 1f),
    Landscape("Landscape", "1536x1024", 1.5f),
    Portrait("Portrait", "1024x1536", 2f / 3f);

    fun next(): Aspect = entries[(ordinal + 1) % entries.size]
}

data class AppSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "auto",
    val quality: String = "auto",
    val transparentDefault: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}

data class ChatMessage(
    val id: String = newId(),
    val role: String, // "user" or "assistant"
    val text: String = "",
    val imageFile: String? = null,
    val model: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val hasAlpha: Boolean = false,
    val backgroundRemoved: Boolean = false,
    val warning: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isUser: Boolean get() = role == "user"
}

data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
)

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long)

fun newId(): String = UUID.randomUUID().toString()

const val DEFAULT_TITLE = "New image"
