package app.oneassist.assistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ApiMessage(val role: String, val content: String)

@Serializable
private data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ApiMessage>,
)

@Serializable
private data class ContentBlock(val type: String, val text: String? = null)

@Serializable
private data class MessagesResponse(
    val content: List<ContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
private data class ErrorBody(val error: ErrorDetail? = null)

@Serializable
private data class ErrorDetail(val type: String? = null, val message: String? = null)

/** Error with a message that is safe to show to the user (never contains the key or data). */
class AssistantException(message: String) : IOException(message)

/**
 * Minimal client for the Anthropic Messages API. No tools, no streaming. Blocking: call it
 * from Dispatchers.IO. Requests and responses are never logged.
 */
class AnthropicClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun send(apiKey: String, system: String, messages: List<ApiMessage>): String {
        val body = json.encodeToString(MessagesRequest.serializer(), MessagesRequest(MODEL, MAX_TOKENS, system, messages))
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw AssistantException(errorMessage(response.code, text))
            val parsed = try {
                json.decodeFromString(MessagesResponse.serializer(), text)
            } catch (_: Exception) {
                throw AssistantException("The assistant sent an answer that could not be read.")
            }
            val answer = parsed.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("").trim()
            if (answer.isEmpty()) throw AssistantException("The assistant returned an empty answer.")
            return if (parsed.stopReason == "max_tokens") "$answer …" else answer
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val type = try { json.decodeFromString(ErrorBody.serializer(), body).error?.type } catch (_: Exception) { null }
        return when {
            code == 401 || type == "authentication_error" -> "The API key was rejected. Check it in Settings."
            code == 403 || type == "permission_error" -> "This API key is not allowed to use the model."
            code == 429 || type == "rate_limit_error" -> "Too many requests or no credit left. Try again later."
            code == 400 && type == "invalid_request_error" -> "The request was not accepted (invalid request)."
            code == 529 || type == "overloaded_error" -> "The assistant is overloaded right now. Try again in a minute."
            code >= 500 -> "The assistant service had a problem ($code). Try again later."
            else -> "The assistant request failed ($code)."
        }
    }

    companion object {
        const val MODEL = "claude-haiku-4-5"
        const val MAX_TOKENS = 800
        const val API_VERSION = "2023-06-01"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
