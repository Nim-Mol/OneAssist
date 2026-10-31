package app.oneassist.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oneassist.data.AssistantRepository
import app.oneassist.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AssistantUiState(
    /** Null until we know whether an API key is stored. */
    val hasApiKey: Boolean? = null,
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: AssistantError? = null,
)

data class AssistantError(val message: String, val detail: String?, val question: String)

class AssistantViewModel(private val repository: AssistantRepository) : ViewModel() {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    init {
        refreshApiKey()
    }

    /** Called when the screen resumes, for example after the user added a key in Settings. */
    fun refreshApiKey() {
        viewModelScope.launch {
            val hasKey = withContext(Dispatchers.IO) { runCatching { repository.hasApiKey() }.getOrDefault(false) }
            _state.update { it.copy(hasApiKey = hasKey) }
        }
    }

    fun send(text: String) {
        val question = text.trim()
        val current = _state.value
        if (question.isEmpty() || current.sending) return
        val history = current.messages
        _state.update {
            it.copy(messages = history + ChatMessage(fromUser = true, text = question), sending = true, error = null)
        }
        viewModelScope.launch {
            val result = try {
                repository.ask(history, question)
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { answer ->
                    _state.update {
                        it.copy(messages = it.messages + ChatMessage(fromUser = false, text = answer), sending = false)
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            sending = false,
                            error = AssistantError(
                                message = "The assistant could not answer right now. Check your internet " +
                                    "connection and API key, then try again.",
                                detail = e.message?.take(200),
                                question = question,
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Removes the unanswered question and asks it again. */
    fun retry() {
        val error = _state.value.error ?: return
        _state.update { state ->
            val messages = state.messages
            val trimmed = if (messages.lastOrNull()?.let { it.fromUser && it.text == error.question } == true) {
                messages.dropLast(1)
            } else {
                messages
            }
            state.copy(messages = trimmed, error = null)
        }
        send(error.question)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearConversation() = _state.update { it.copy(messages = emptyList(), error = null) }
}
