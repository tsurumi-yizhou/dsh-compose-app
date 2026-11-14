package dev.fughetta.dshcompose.ui.markdown

import androidx.compose.runtime.staticCompositionLocalOf
import com.dokar.quickjs.QuickJs
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class CodeToken(val start: Int, val end: Int, val scope: String)

val LocalCodeHighlighter = staticCompositionLocalOf<CodeHighlighter?> { null }

/** One lazy runtime for the app, with no source/result history and no host or network bindings. */
class CodeHighlighter : AutoCloseable {
    private val lifecycle = Any()
    private val work = Mutex()
    private var runtime: QuickJs? = null
    private var initialized = false
    private var closed = false
    private var unavailable = false

    private fun runtime(): QuickJs =
        synchronized(lifecycle) {
            check(!closed)
            runtime
                ?: QuickJs.create(Dispatchers.Default)
                    .apply {
                        memoryLimit = 64L * 1024 * 1024
                        evaluationTimeoutMillis = 2000
                    }
                    .also { runtime = it }
        }

    internal suspend fun tokens(source: String, language: String): PersistentList<CodeToken> =
        withContext(Dispatchers.Default) {
            // The original renderer also kept outputs above 20,000 characters as plain code.
            if (source.isEmpty() || source.length > 20_000 || language.isBlank())
                return@withContext persistentListOf()
            work.withLock {
                if (unavailable) return@withLock persistentListOf()
                try {
                    val js = runtime()
                    if (!initialized) {
                        val script =
                            CodeHighlighter::class
                                .java
                                .getResourceAsStream("/syntax/highlight.js")!!
                                .bufferedReader()
                                .use { it.readText() }
                        js.evaluate<Boolean>("$script;true;")
                        initialized = true
                    }
                    val input = JsonPrimitive(source).toString()
                    val name = JsonPrimitive(language.trim().lowercase()).toString()
                    val result = js.evaluate<String>("syntaxTokens($input,$name)")
                    Json.parseToJsonElement(result)
                        .jsonArray
                        .map { item ->
                            val values = item.jsonArray
                            CodeToken(
                                values[0].jsonPrimitive.int,
                                values[1].jsonPrimitive.int,
                                values[2].jsonPrimitive.content,
                            )
                        }
                        .toPersistentList()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: LinkageError) {
                    unavailable = true
                    persistentListOf()
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    persistentListOf()
                }
            }
        }

    override fun close() =
        synchronized(lifecycle) {
            closed = true
            runtime?.close()
            runtime = null
        }
}
