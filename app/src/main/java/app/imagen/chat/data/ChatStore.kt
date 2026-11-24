package app.imagen.chat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-based conversation history: one JSON file per chat, PNGs under images/.
 * This is the single source of truth. The UI and the background generator both
 * change chats only through [update], which is atomic per call.
 */
class ChatStore(context: Context) {
    private val chatsDir = File(context.filesDir, "chats").apply { mkdirs() }
    val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
    private val lock = Any()

    fun imageFile(name: String) = File(imagesDir, name)

    fun list(): List<ConversationSummary> = synchronized(lock) {
        (chatsDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { file ->
                runCatching {
                    val o = JSONObject(file.readText())
                    ConversationSummary(o.getString("id"), o.getString("title"), o.getLong("updatedAt"))
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    fun load(id: String): Conversation? = synchronized(lock) { read(id) }

    /**
     * Reads, changes and writes one chat atomically. [create] supplies the chat when it doesn't
     * exist yet; without it a missing chat returns null and nothing is written.
     */
    fun update(
        id: String,
        create: (() -> Conversation)? = null,
        block: (Conversation) -> Conversation,
    ): Conversation? = synchronized(lock) {
        val current = read(id) ?: create?.invoke() ?: return null
        val next = block(current)
        write(next)
        next
    }

    fun delete(id: String) = synchronized(lock) {
        read(id)?.messages?.forEach { m ->
            m.imageFile?.let { imageFile(it).delete() }
            m.refImage?.let { imageFile(it).delete() }
        }
        File(chatsDir, "$id.json").delete()
        Unit
    }

    fun deleteAll() = synchronized(lock) {
        chatsDir.listFiles()?.forEach { it.delete() }
        imagesDir.listFiles()?.forEach { it.delete() }
        Unit
    }

    fun sizeBytes(): Long =
        (imagesDir.listFiles()?.sumOf { it.length() } ?: 0L) + (chatsDir.listFiles()?.sumOf { it.length() } ?: 0L)

    /** Removes image files no chat refers to (abandoned uploads, deleted chats), keeping very recent ones. */
    fun cleanOrphans() = synchronized(lock) {
        val referenced = hashSetOf<String>()
        (chatsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()).forEach { file ->
            val id = file.nameWithoutExtension
            read(id)?.messages?.forEach { m ->
                m.imageFile?.let { referenced.add(it) }
                m.refImage?.let { referenced.add(it) }
            }
        }
        val cutoff = System.currentTimeMillis() - 3_600_000L
        imagesDir.listFiles()?.forEach { f ->
            if (f.name !in referenced && f.lastModified() < cutoff) f.delete()
        }
        Unit
    }

    // ---- storage format -------------------------------------------------

    private fun read(id: String): Conversation? = runCatching {
        val o = JSONObject(File(chatsDir, "$id.json").readText())
        val arr = o.getJSONArray("messages")
        Conversation(
            id = o.getString("id"),
            title = o.getString("title"),
            updatedAt = o.getLong("updatedAt"),
            messages = (0 until arr.length()).map { messageFrom(arr.getJSONObject(it)) },
            pending = o.optBoolean("pending", false),
        )
    }.getOrNull()

    private fun write(conversation: Conversation) {
        val o = JSONObject()
            .put("id", conversation.id)
            .put("title", conversation.title)
            .put("updatedAt", conversation.updatedAt)
            .put("pending", conversation.pending)
            .put("messages", JSONArray().apply { conversation.messages.forEach { put(messageTo(it)) } })
        val target = File(chatsDir, "${conversation.id}.json")
        val temp = File(chatsDir, "${conversation.id}.json.tmp")
        temp.writeText(o.toString())
        if (!temp.renameTo(target)) {
            target.writeText(o.toString())
            temp.delete()
        }
    }

    private fun messageTo(m: ChatMessage) = JSONObject()
        .put("id", m.id)
        .put("role", m.role)
        .put("text", m.text)
        .put("imageFile", m.imageFile ?: JSONObject.NULL)
        .put("refImage", m.refImage ?: JSONObject.NULL)
        .put("prompt", m.prompt ?: JSONObject.NULL)
        .put("mode", m.mode ?: JSONObject.NULL)
        .put("model", m.model ?: JSONObject.NULL)
        .put("width", m.width)
        .put("height", m.height)
        .put("hasAlpha", m.hasAlpha)
        .put("backgroundRemoved", m.backgroundRemoved)
        .put("warning", m.warning ?: JSONObject.NULL)
        .put("error", m.error ?: JSONObject.NULL)
        .put("createdAt", m.createdAt)

    private fun messageFrom(o: JSONObject) = ChatMessage(
        id = o.getString("id"),
        role = o.getString("role"),
        text = o.optString("text"),
        imageFile = o.optStringOrNull("imageFile"),
        refImage = o.optStringOrNull("refImage"),
        prompt = o.optStringOrNull("prompt"),
        mode = o.optStringOrNull("mode"),
        model = o.optStringOrNull("model"),
        width = o.optInt("width"),
        height = o.optInt("height"),
        hasAlpha = o.optBoolean("hasAlpha"),
        backgroundRemoved = o.optBoolean("backgroundRemoved"),
        warning = o.optStringOrNull("warning"),
        error = o.optStringOrNull("error"),
        createdAt = o.optLong("createdAt"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
