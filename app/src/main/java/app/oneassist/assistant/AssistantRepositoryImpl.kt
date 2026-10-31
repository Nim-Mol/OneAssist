package app.oneassist.assistant

import app.oneassist.core.AssistantPromptBuilder
import app.oneassist.core.DefaultAssistantPromptBuilder
import app.oneassist.data.AssistantRepository
import app.oneassist.data.ChatMessage
import app.oneassist.data.RoomFinanceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends the conversation to Claude with a system prompt and an aggregate-only summary of the
 * user's finances inside <financial_data> tags. Read-only: the model has no tools.
 */
class AssistantRepositoryImpl(
    private val keyStore: ApiKeyStore,
    private val client: AnthropicClient,
    private val finance: RoomFinanceRepository,
    private val promptBuilder: AssistantPromptBuilder = DefaultAssistantPromptBuilder(),
) : AssistantRepository {

    override fun hasApiKey(): Boolean = keyStore.has()
    override fun saveApiKey(key: String) = keyStore.save(key)
    override fun clearApiKey() = keyStore.clear()

    override suspend fun ask(history: List<ChatMessage>, question: String): Result<String> {
        val q = question.trim()
        if (q.isEmpty()) return Result.failure(AssistantException("Type a question first."))
        return try {
            val apiKey = withContext(Dispatchers.IO) { keyStore.get() }
                ?: return Result.failure(AssistantException("Add your Anthropic API key in Settings first."))
            val context = finance.financialContext(promptBuilder)
            val system = buildString {
                append(promptBuilder.systemPrompt())
                append("\n\n<financial_data>\n")
                append(context.replace("</financial_data>", "(/financial_data)"))
                append("\n</financial_data>")
            }
            val messages = buildMessages(history, q)
            val answer = withContext(Dispatchers.IO) { client.send(apiKey, system, messages) }
            Result.success(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssistantException) {
            Result.failure(e)
        } catch (e: java.io.IOException) {
            Result.failure(AssistantException("No connection to the assistant. Check your internet connection."))
        } catch (e: Exception) {
            Result.failure(AssistantException("Something went wrong while asking the assistant."))
        }
    }

    /** Last turns of the chat, starting with a user turn and alternating roles, then the question. */
    private fun buildMessages(history: List<ChatMessage>, question: String): List<ApiMessage> {
        val turns = history.filter { it.text.isNotBlank() }.takeLast(MAX_HISTORY)
            .map { ApiMessage(if (it.fromUser) "user" else "assistant", it.text.take(MAX_MESSAGE_CHARS)) }
            .dropWhile { it.role != "user" } + ApiMessage("user", question.take(MAX_MESSAGE_CHARS))
        val merged = mutableListOf<ApiMessage>()
        for (m in turns) {
            val last = merged.lastOrNull()
            if (last != null && last.role == m.role) merged[merged.size - 1] = ApiMessage(m.role, last.content + "\n\n" + m.content)
            else merged += m
        }
        return merged
    }

    private companion object {
        const val MAX_HISTORY = 20
        const val MAX_MESSAGE_CHARS = 4_000
    }
}
