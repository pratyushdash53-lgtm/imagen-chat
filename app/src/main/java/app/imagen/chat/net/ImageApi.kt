package app.imagen.chat.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Error whose message is always safe to show to the user. */
class ApiException(message: String) : Exception(message)

/**
 * Minimal OpenAI-compatible client for model discovery and image generation.
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

    const val TRANSPARENT_HINT =
        "Use a fully transparent background with genuine alpha, no backdrop, " +
            "no checkerboard pattern, no ground plane. Leave clear transparent margins around each subject."

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Used for downloading provider image URLs: never carries the API key.
    private val downloader: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

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
        for (suffix in listOf("/images/generations", "/models")) {
            if (url.endsWith(suffix)) url = url.removeSuffix(suffix).trimEnd('/')
        }
        val parsed = url.toHttpUrlOrNull() ?: throw ApiException("The API URL isn't valid.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw ApiException("Don't put credentials inside the URL; use the API key field.")
        }
        return url
    }

    /** All model IDs the provider lists. */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ApiException("Enter your API key in Settings.")
        val request = Request.Builder()
            .url("${normalizeBase(baseUrl)}/models")
            .header("Authorization", "Bearer $apiKey")
            .build()
        guarded {
            client.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw httpError(response.code, text, apiKey)
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

    /** Picks the best known image model from a model list, or null. */
    fun chooseAuto(ids: List<String>): String? =
        PREFERENCE.firstOrNull { it in ids } ?: ids.firstOrNull { isCandidate(it) }

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
        val request = Request.Builder()
            .url("${normalizeBase(baseUrl)}/images/generations")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        guarded {
            client.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw httpError(response.code, text, apiKey)
                val item = try {
                    JSONObject(text).getJSONArray("data").getJSONObject(0)
                } catch (e: Exception) {
                    throw ApiException("The provider returned an empty image response.")
                }
                val encoded = item.optString("b64_json")
                val url = item.optString("url")
                when {
                    encoded.isNotBlank() -> try {
                        Base64.decode(encoded, Base64.DEFAULT)
                    } catch (e: IllegalArgumentException) {
                        throw ApiException("The provider returned invalid image data.")
                    }
                    url.isNotBlank() -> download(url)
                    else -> throw ApiException("The provider returned no image data.")
                }
            }
        }
    }

    private fun download(url: String): ByteArray {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw ApiException("The provider's image link isn't a plain HTTPS URL.")
        }
        downloader.newCall(Request.Builder().url(parsed).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("Couldn't download the generated image.")
            val stream = response.body?.byteStream() ?: throw ApiException("Couldn't download the generated image.")
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
            return out.toByteArray()
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: SocketTimeoutException) {
        throw ApiException("Request timed out. The provider may still finish (and charge) it, so check before retrying.")
    } catch (e: IOException) {
        throw ApiException("Couldn't reach the provider. Check the URL and your connection.")
    }

    private fun httpError(code: Int, body: String, apiKey: String): ApiException {
        val base = when (code) {
            400, 422 -> "The provider rejected the request."
            401 -> "Authentication failed. Check your API key."
            403 -> "The provider denied this operation."
            404 -> "Model or endpoint not found. Check the URL and model."
            429 -> "Rate or quota limit reached."
            in 500..599 -> "The provider had a server error."
            else -> "The provider request failed."
        }
        // For parameter problems the provider's one-line reason is useful (e.g. unsupported size).
        val reason = if (code == 400 || code == 422) {
            runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                ?.replace(apiKey, "***")
                ?.take(180)
        } else null
        return ApiException("HTTP $code: $base" + (reason?.let { " ($it)" } ?: ""))
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }
}
