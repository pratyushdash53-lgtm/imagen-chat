package app.imagen.chat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** File-based conversation history: one JSON file per chat, PNGs under images/. */
class ChatStore(context: Context) {
    private val chatsDir = File(context.filesDir, "chats").apply { mkdirs() }
    val imagesDir = File(context.filesDir, "images").apply { mkdirs() }

    fun imageFile(name: String) = File(imagesDir, name)

    fun list(): List<ConversationSummary> =
        (chatsDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { file ->
                runCatching {
                    val o = JSONObject(file.readText())
                    ConversationSummary(o.getString("id"), o.getString("title"), o.getLong("updatedAt"))
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Conversation? = runCatching {
        val o = JSONObject(File(chatsDir, "$id.json").readText())
        val arr = o.getJSONArray("messages")
        Conversation(
            id = o.getString("id"),
            title = o.getString("title"),
            updatedAt = o.getLong("updatedAt"),
            messages = (0 until arr.length()).map { messageFrom(arr.getJSONObject(it)) },
        )
    }.getOrNull()

    fun save(conversation: Conversation) {
        val o = JSONObject()
            .put("id", conversation.id)
            .put("title", conversation.title)
            .put("updatedAt", conversation.updatedAt)
            .put("messages", JSONArray().apply { conversation.messages.forEach { put(messageTo(it)) } })
        val target = File(chatsDir, "${conversation.id}.json")
        val temp = File(chatsDir, "${conversation.id}.json.tmp")
        temp.writeText(o.toString())
        if (!temp.renameTo(target)) {
            target.writeText(o.toString())
            temp.delete()
        }
    }

    fun delete(id: String) {
        load(id)?.messages?.forEach { m ->
            m.imageFile?.let { imageFile(it).delete() }
            m.refImage?.let { imageFile(it).delete() }
        }
        File(chatsDir, "$id.json").delete()
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
