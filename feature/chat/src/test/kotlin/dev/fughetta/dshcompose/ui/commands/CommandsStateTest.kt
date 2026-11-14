package dev.fughetta.dshcompose.ui.commands

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class CommandsStateTest {
    @Test
    fun compositionCatalogOwnershipAndSkillsKeepCommandsOutOfNormalMessages(): Unit = runBlocking {
        withTimeout(10000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            class Catalog(private val slow: Boolean) :
                dev.fughetta.dshcompose.testing.TestSessionsRepository() {
                override var active = true
                val executed = mutableListOf<String>()

                override suspend fun commandCatalog(sessionId: String): CommandCatalog {
                    if (slow) {
                        started.complete(Unit)
                        release.await()
                    }
                    return CommandCatalog(
                        kotlinx.collections.immutable.persistentListOf(
                            SlashCommand(
                                "compact",
                                "compact",
                                "",
                                null,
                                definitionId = "@deepseek-ai/dsh-command-compact",
                            ),
                            SlashCommand("plan", "plan", "", "objective"),
                        ),
                        kotlinx.collections.immutable.persistentListOf(
                            SlashCommand("review", "review", "Manual", "", SlashCommandKind.Skill),
                        ),
                    )
                }

                override suspend fun executeCommand(
                    sessionId: String,
                    line: String,
                ): CommandResult {
                    executed += line
                    return CommandResult("reply", true, "confirmed")
                }

                override fun selectCommandSession(sessionId: String?) {}

                override fun invalidateCommands() {}
            }
            val one = Catalog(true)
            val two = Catalog(false)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val controller =
                CommandsState(
                    scope,
                    appCommands = {
                        listOf(SlashCommand("new", "新对话", "New", null, SlashCommandKind.App))
                    },
                    decorate = { if (it.name == "compact") it.copy(label = "压缩") else it },
                    failed = { "failed" },
                )
            val state = controller.state
            try {
                controller.connect(one)
                controller.composer("session", "/压", true)
                assertNull(state.value)
                controller.composer("session", "/压", false)
                started.await()
                one.active = false
                controller.connect(two)
                controller.composer("other", "/pla", false)
                val ready = state.filterNotNull().first { !it.loading && it.commands.isNotEmpty() }
                assertEquals("other", ready.sessionId)
                assertEquals("plan", ready.commands.single().name)
                release.complete(Unit)
                assertEquals(
                    "plan",
                    controller.resolve("other", "/PLAN an objective")!!.command.name,
                )
                assertNull(controller.resolve("other", "/review check it"))
                assertNull(controller.resolve("other", "/home/file.txt read this"))
                val partial = controller.resolve("other", "/cmp")!!
                assertTrue(partial.picked)
                assertEquals("/compact", partial.line)
                assertEquals("/compact", controller.resolve("other", "/压缩")!!.line)
                assertEquals(
                    SlashCommandKind.App,
                    controller.resolve("other", "/新对话")!!.command.kind,
                )
                controller.composer("other", "/plan objective", false)
                val hint = state.filterNotNull().first { !it.loading && it.hint != null }
                assertEquals("objective", hint.hint!!.hint)
                controller.composer("other", "/review text", false)
                state.first { it == null }
                controller.composer("other", "ordinary text", false)
                assertNull(state.value)
                assertEquals("new", controller.pick("other", "new")!!.name)
                val intent = controller.resolve("other", "/plan captured")!!
                assertTrue(controller.execute(intent).success)
                assertEquals(listOf("/plan captured"), two.executed)
                one.active = true
                controller.connect(one)
                try {
                    controller.execute(intent)
                    fail("A resolved command cannot be retargeted to another connection")
                } catch (_: CancellationException) {}
                assertTrue(one.executed.isEmpty())
            } finally {
                controller.close()
                scope.cancel()
            }
        }
    }
}
