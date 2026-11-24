package app.imagen.chat.net

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.Reader
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** What the context model decided: edit the previous/attached image or make a new one. */
class Plan(val mode: String, val prompt: String, val title: String?)

/**
 * Error whose message is always safe to show to the user.
 *
 * [retryable]: repeating a read-only call (model list, planning, download) may succeed.
 * [neverProcessed]: the provider certainly didn't start the job, so even an image request
 * can be repeated without risking a second charge.
 */
class ApiException(
    message: String,
    val retryable: Boolean = false,
    val neverProcessed: Boolean = false,
    val retryAfterMs: Long? = null,
) : Exception(message)

/**
 * Minimal OpenAI-compatible client for model discovery, chat planning and image generation.
 * Request shapes follow the three profiles of the reference Python tool:
 * "gateway" (gpt-image-2.5-*), "gpt-image" (gpt-image-*) and "generic".
 */
object ImageApi {
    val GATEWAY_MODELS = listOf("gpt-image-2.5-flare", "gpt-image-2.5-sunburst")
    val PREFERENCE = GATEWAY_MODELS + listOf("gpt-image-1.5", "gpt-image-1", "gpt-image-1-mini")

    private val CANDIDATE = Regex(
        "image|flux|dall|imagen|ideogram|recraft|sdxl|stable.diffusion|seedream|nano.banana",
        RegexOption.IGNORE_CASE,
    )
    private val MODEL_ID = Regex("[A-Za-z0-9_.:/-]{1,160}")
    private val JSON_TYPE = "application/json".toMediaType()

    const val TRANSPARENT_HINT =
        "Use a fully transparent background with genuine alpha, no backdrop, " +
            "no checkerboard pattern, no ground plane. Leave clear transparent margins around each subject."

    // One shared client: connection pooling and HTTP/2 reuse. Image models can take minutes, so the read
    // timeout is generous; the generation itself runs in a foreground service so it isn't cut off.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val planner: OkHttpClient by lazy {
        client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    }

    // Used for downloading provider image URLs: never carries the API key and never follows redirects.
    private val downloader: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                // Belt and braces: make sure no credentials leak to storage hosts.
                chain.proceed(chain.request().newBuilder().removeHeader("Authorization").build())
            }
            .build()
    }

    fun isCandidate(id: String) = CANDIDATE.containsMatchIn(id)

    fun profile(model: String): String = when {
        model in GATEWAY_MODELS -> "gateway"
        model.startsWith("gpt-image-") -> "gpt-image"
        else -> "generic"
    }

    fun normalizeBase(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) throw ApiException("Enter your API base URL in Settings.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        for (suffix in listOf("/images/generations", "/images/edits", "/chat/completions", "/models")) {
            if (url.endsWith(suffix)) url = url.removeSuffix(suffix).trimEnd('/')
        }
        val parsed = url.toHttpUrlOrNull() ?: throw ApiException("The API URL isn't valid.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw ApiException("Don't put credentials inside the URL; use the API key field.")
        }
        return url
    }

    // ---- retry plumbing -------------------------------------------------

    /**
     * Repeats [block] with exponential backoff. Read-only calls ([idempotent]) are retried on any
     * transient failure; image requests only when the provider certainly never started the job.
     */
    private suspend fun <T> retrying(idempotent: Boolean, attempts: Int = 3, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ApiException) {
                attempt++
                val allowed = if (idempotent) e.retryable else e.neverProcessed
                if (!allowed || attempt >= attempts) throw e
                val exponential = minOf(8_000L, 1_000L shl (attempt - 1))
                delay(maxOf(e.retryAfterMs ?: 0L, exponential) + Random.nextLong(0, 400))
            }
        }
    }

    /** Turns low-level I/O failures into friendly, correctly classified errors. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: SocketTimeoutException) {
        if (e.message?.contains("connect", ignoreCase = true) == true) {
            throw ApiException("Couldn't reach the provider (connection timed out).", retryable = true, neverProcessed = true)
        }
        throw ApiException(
            "Request timed out. The provider may still finish (and charge) it, so check before retrying.",
            retryable = true,
        )
    } catch (e: UnknownHostException) {
        throw ApiException("Can't find that server. Check the URL and your connection.", retryable = true, neverProcessed = true)
    } catch (e: ConnectException) {
        throw ApiException("Couldn't connect to the provider. Check the URL and your connection.", retryable = true, neverProcessed = true)
    } catch (e: NoRouteToHostException) {
        throw ApiException("No route to the provider. Check your connection.", retryable = true, neverProcessed = true)
    } catch (e: SSLHandshakeException) {
        throw ApiException("Secure connection failed. Check the URL and your device's date and time.", neverProcessed = false)
    } catch (e: SSLException) {
        throw ApiException("Secure connection was interrupted.", retryable = true)
    } catch (e: IOException) {
        throw ApiException("Connection lost. Check your connection and retry.", retryable = true)
    }

    private fun httpError(response: Response, apiKey: String): ApiException {
        val code = response.code
        val body = runCatching { response.peekBody(64L * 1024).string() }.getOrDefault("")
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()?.let { (it * 1000).coerceIn(0L, 20_000L) }
        val base = when (code) {
            400, 422 -> "The provider rejected the request."
            401 -> "Authentication failed. Check your API key."
            403 -> "The provider denied this operation."
            404 -> "Model or endpoint not found. Check the URL and model."
            408, 504, 524 -> "The provider timed out. It may still finish (and charge) it, so check before retrying."
            413 -> "The image or prompt is too large for this provider."
            429 -> "Rate or quota limit reached."
            502 -> "The provider's gateway had a problem."
            in 500..599 -> "The provider had a server error."
            else -> "The provider request failed."
        }
        // For parameter problems the provider's one-line reason is useful (e.g. unsupported size).
        val reason = if (code == 400 || code == 413 || code == 422) {
            runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                ?.let { if (apiKey.isNotEmpty()) it.replace(apiKey, "***") else it }
                ?.take(180)
        } else null
        // 429 and 503 are rejected before any work starts, so they're safe to repeat even for images.
        val neverProcessed = code == 429 || code == 503
        val retryable = neverProcessed || code == 500 || code == 502 || code == 504 || code == 408 || code == 524
        return ApiException(
            "HTTP $code: $base" + (reason?.let { " ($it)" } ?: ""),
            retryable = retryable,
            neverProcessed = neverProcessed,
            retryAfterMs = retryAfter,
        )
    }

    // ---- models ---------------------------------------------------------

    /** All model IDs the provider lists. */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ApiException("Enter your API key in Settings.")
        val url = "${normalizeBase(baseUrl)}/models"
        retrying(idempotent = true) {
            guarded {
                val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw httpError(response, apiKey)
                    val text = response.body?.string().orEmpty()
                    val data = try {
                        JSONObject(text).optJSONArray("data") ?: JSONArray()
                    } catch (e: Exception) {
                        throw ApiException("The provider returned an unexpected model list.")
                    }
                    val ids = sortedSetOf<String>()
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id").orEmpty()
                        if (MODEL_ID.matches(id) && id != apiKey) ids.add(id)
                    }
                    ids.toList()
                }
            }
        }
    }

    /** Picks the best known image model from a model list, or null. */
    fun chooseAuto(ids: List<String>): String? =
        PREFERENCE.firstOrNull { it in ids } ?: ids.firstOrNull { isCandidate(it) }

    // ---- chat context ---------------------------------------------------

    const val CONTEXT_PREFERENCE = "gpt-6.1-sol"
    private val NOT_CHAT = Regex("embed|whisper|tts|audio|moderation|realtime|transcribe|speech|rerank", RegexOption.IGNORE_CASE)
    private val CHAT_FAMILY = Regex("^(gpt|claude|gemini|llama|qwen|deepseek|mistral|o\\d)", RegexOption.IGNORE_CASE)
    private val EDITISH = Regex(
        "\\b(remove|without|no|don'?t|do not|make it|make the|change|add|replace|instead|same|keep|but|" +
            "less|more|bigger|smaller|darker|lighter|brighter|fix|adjust|turn|now)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isChatModel(id: String) = !isCandidate(id) && !NOT_CHAT.containsMatchIn(id)

    /** Picks gpt-6.1-sol when listed, otherwise the first general-purpose text model. */
    fun chooseContext(ids: List<String>): String? =
        ids.firstOrNull { it == CONTEXT_PREFERENCE }
            ?: ids.firstOrNull { it.startsWith(CONTEXT_PREFERENCE) }
            ?: ids.firstOrNull { isChatModel(it) && CHAT_FAMILY.containsMatchIn(it) }

    /** Offline guess used when no context model is available. */
    fun looksLikeEdit(message: String) = message.length < 140 && EDITISH.containsMatchIn(message)

    private val PLANNER_PROMPT = """
You are the prompt planner inside an image-generation chat app. You receive the conversation so far and the user's newest message. Reply with ONLY one JSON object, no prose and no code fences:
{"mode": "edit" or "new", "prompt": "...", "title": "..."}

Rules:
- mode "edit": the user wants to change, refine or continue the most recent image (for example "remove the outline", "make it bluer", "same but with a hat"), or they attached a reference image to build on. Only use "edit" when a previous image exists or a reference image is attached.
- mode "new": the user wants a different, unrelated image.
- prompt: a complete, self-contained instruction for the image model. For "edit", state the requested change clearly AND say that everything else (subject, pose, composition, colours, style) must stay the same. For "new", write a full standalone prompt. Carry over style, subject and constraints from earlier turns unless the user changes them. Never refer to the conversation or to "the previous image" in a "new" prompt.
- title: a short chat title of at most 5 words describing the subject.
""".trimIndent()

    /** Asks the context model what to do with the newest message. Null on any failure. */
    suspend fun plan(
        baseUrl: String,
        apiKey: String,
        model: String,
        history: List<String>,
        message: String,
        hasPreviousImage: Boolean,
        hasAttachment: Boolean,
    ): Plan? = withContext(Dispatchers.IO) {
        try {
            val user = buildString {
                append("Conversation so far:\n")
                append(if (history.isEmpty()) "(no earlier messages)" else history.joinToString("\n"))
                append("\n\nA previous generated image exists: ").append(if (hasPreviousImage) "yes" else "no")
                append("\nThe user attached a reference image to the newest message: ").append(if (hasAttachment) "yes" else "no")
                append("\n\nNewest user message:\n").append(message)
            }
            val payload = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", PLANNER_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", user)),
                ).toString()
            val url = "${normalizeBase(baseUrl)}/chat/completions"
            val canEdit = hasPreviousImage || hasAttachment
            retrying(idempotent = true, attempts = 2) {
                guarded {
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $apiKey")
                        .post(payload.toRequestBody(JSON_TYPE))
                        .build()
                    planner.newCall(request).await().use { response ->
                        if (!response.isSuccessful) throw httpError(response, apiKey)
                        parsePlan(contentOf(response.body?.string().orEmpty()), canEdit)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun contentOf(responseText: String): String {
        val message = JSONObject(responseText).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text").orEmpty())
            }
            else -> ""
        }
    }

    private fun parsePlan(content: String, canEdit: Boolean): Plan? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = JSONObject(content.substring(start, end + 1))
        val prompt = o.optString("prompt").trim()
        if (prompt.isEmpty()) return null
        val mode = if (o.optString("mode").equals("edit", ignoreCase = true) && canEdit) "edit" else "new"
        val title = o.optString("title").trim().trim('"').takeIf { it.isNotEmpty() && !it.equals("null", true) }
        return Plan(mode, prompt, title)
    }

    // ---- images ---------------------------------------------------------

    /** Generates one image and returns its encoded bytes (PNG, JPEG or WebP). */
    suspend fun generate(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        size: String,
        quality: String,
        transparent: Boolean,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ApiException("Enter your API key in Settings.")
        val profile = profile(model)
        val body = JSONObject()
            .put("model", model)
            .put("prompt", if (transparent) "$prompt\n$TRANSPARENT_HINT" else prompt)
        if (profile == "gateway" || profile == "gpt-image") {
            body.put("size", size).put("quality", quality).put("output_format", "png")
            if (transparent && profile == "gpt-image") body.put("background", "transparent")
        } else if (size != "auto") {
            body.put("size", size)
        }
        val url = "${normalizeBase(baseUrl)}/images/generations"
        val payload = body.toString()
        executeImage(apiKey) {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody(JSON_TYPE))
                .build()
        }
    }

    /** Edits (or builds on) a reference image via /images/edits and returns the encoded result. */
    suspend fun edit(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        size: String,
        quality: String,
        transparent: Boolean,
        image: File,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ApiException("Enter your API key in Settings.")
        val profile = profile(model)
        val url = "${normalizeBase(baseUrl)}/images/edits"
        executeImage(apiKey) {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", if (transparent) "$prompt\n$TRANSPARENT_HINT" else prompt)
            if (profile == "gateway" || profile == "gpt-image") {
                form.addFormDataPart("size", size)
                    .addFormDataPart("quality", quality)
                    .addFormDataPart("output_format", "png")
                if (transparent && profile == "gpt-image") form.addFormDataPart("background", "transparent")
            } else if (size != "auto") {
                form.addFormDataPart("size", size)
            }
            form.addFormDataPart("image", image.name, image.asRequestBody("image/png".toMediaType()))
            Request.Builder().url(url).header("Authorization", "Bearer $apiKey").post(form.build()).build()
        }
    }

    /** Sends an image request and returns the decoded image bytes. */
    private suspend fun executeImage(apiKey: String, build: () -> Request): ByteArray {
        // Parse the response as a stream: image payloads are many megabytes of base64, and building a
        // full JSON tree from them would multiply peak memory use.
        val (encoded, link) = retrying(idempotent = false) {
            guarded {
                client.newCall(build()).await().use { response ->
                    if (!response.isSuccessful) throw httpError(response, apiKey)
                    val body = response.body ?: throw ApiException("The provider returned an empty response.")
                    try {
                        parseImage(body.charStream())
                    } catch (e: IllegalStateException) {
                        throw ApiException("The provider returned an unexpected response.")
                    } catch (e: MalformedJsonException) {
                        throw ApiException("The provider returned an unexpected response.")
                    }
                }
            }
        }
        return when {
            !encoded.isNullOrBlank() -> try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw ApiException("The provider returned invalid image data.")
            }
            !link.isNullOrBlank() -> download(link)
            else -> throw ApiException("The provider returned no image data.")
        }
    }

    /** Reads `data[0].b64_json` / `data[0].url` without materialising the whole document. */
    private fun parseImage(source: Reader): Pair<String?, String?> {
        JsonReader(source).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "data") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                var encoded: String? = null
                var link: String? = null
                if (reader.hasNext()) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "b64_json" -> encoded = reader.nextStringOrNull()
                            "url" -> link = reader.nextStringOrNull()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                return encoded to link
            }
        }
        return null to null
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private suspend fun download(url: String): ByteArray {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw ApiException("The provider's image link isn't a plain HTTPS URL.")
        }
        return retrying(idempotent = true) {
            guarded {
                downloader.newCall(Request.Builder().url(parsed).build()).await().use { response ->
                    if (!response.isSuccessful) {
                        throw ApiException(
                            "Couldn't download the generated image (HTTP ${response.code}).",
                            retryable = response.code >= 500,
                        )
                    }
                    val stream = response.body?.byteStream()
                        ?: throw ApiException("Couldn't download the generated image.")
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 100_000_000L) throw ApiException("The generated image is larger than 100 MB.")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }
}
