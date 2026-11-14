package dev.fughetta.dshcompose.ui.reasoning

import dev.fughetta.dshcompose.domain.messages.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ReasoningTranslationUiState(
    val text: String = "",
    val pending: Boolean = false,
    val failed: Boolean = false,
    val enabled: Boolean = false,
)

/** One expanded reasoning block owns its fragments, translated text and request lifetime. */
class ReasoningTranslationState(
    private val scope: CoroutineScope,
    private val parserDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val translate: suspend (String) -> String,
) {
    private val mutable = MutableStateFlow(ReasoningTranslationUiState())
    val uiState = mutable.asStateFlow()
    private var fragments = emptyList<ReasoningFragment>()
    private val cache = mutableMapOf<String, String>()
    private var parse: Job? = null
    private var work: Job? = null
    private var source: Triple<String, Boolean, Int>? = null
    private var revision = 0L
    private var closed = false

    fun update(value: String, running: Boolean, limit: Int) {
        val input = Triple(value, running, limit)
        if (closed || source == input) return
        source = input
        val request = ++revision
        parse?.cancel()
        parse = scope.launch {
            val next = withContext(parserDispatcher) { reasoningFragments(value, running, limit) }
            if (closed || revision != request) return@launch
            fragments = next
            cache.keys.retainAll(next.mapTo(mutableSetOf()) { it.text })
            render()
            run()
        }
    }

    fun enable() {
        if (!closed) {
            mutable.update { it.copy(enabled = true) }
            run()
        }
    }

    fun retry() {
        if (!closed) {
            mutable.update { it.copy(failed = false) }
            run()
        }
    }

    private fun render() {
        mutable.update { state ->
            state.copy(
                text =
                    fragments.joinToString("") {
                        (if (it.complete) cache[it.text] ?: it.text else it.text) + it.suffix
                    },
            )
        }
    }

    private fun run() {
        if (closed || !mutable.value.enabled || mutable.value.failed || work?.isActive == true)
            return
        work = scope.launch {
            mutable.update { it.copy(pending = true) }
            try {
                while (true) {
                    val fragment =
                        fragments.firstOrNull {
                            it.complete && it.text.isNotBlank() && it.text !in cache
                        } ?: break
                    val translated = translate(fragment.text)
                    if (closed) return@launch
                    if (fragments.any { it.text == fragment.text })
                        cache[fragment.text] = translated
                    render()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!closed) mutable.update { it.copy(failed = true) }
            } finally {
                if (!closed) mutable.update { it.copy(pending = false) }
            }
        }
    }

    fun close() {
        closed = true
        parse?.cancel()
        work?.cancel()
        source = null
        fragments = emptyList()
        cache.clear()
        mutable.value = ReasoningTranslationUiState()
    }
}
