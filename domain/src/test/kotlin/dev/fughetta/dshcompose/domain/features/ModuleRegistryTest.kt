package dev.fughetta.dshcompose.domain.features

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModuleRegistryTest {
    @Test
    fun publishersCannotReplaceAnotherOwnersModulesAndRetireOnlyTheirOwnEntries() = runBlocking {
        val registry = ModuleRegistry<String>(this)
        try {
            registry.publish("app", listOf(HostModule("shell", contributions = listOf("settings"))))
            registry.publish(
                "compose",
                listOf(HostModule("extension", setOf("shell"), listOf("panel"))),
            )
            assertEquals(listOf("settings", "panel"), registry.state.value.contributions)
            try {
                registry.publish(
                    "compose",
                    listOf(HostModule("shell", contributions = listOf("replacement"))),
                )
                fail("An extension must not replace the app shell")
            } catch (expected: IllegalArgumentException) {
                assertEquals("module/duplicate-id", expected.message)
            }
            assertEquals(listOf("settings", "panel"), registry.state.value.contributions)
            registry.publish("compose", emptyList())
            assertEquals(listOf("settings"), registry.state.value.contributions)
            assertNotNull(registry.scope("shell"))
            assertNull(registry.scope("extension"))
        } finally {
            registry.close()
        }
    }

    @Test
    fun dependencyChangesRetireDependentsAndTheirWorkBeforePublishingNewContributions() =
        runBlocking {
            val events = mutableListOf<String>()
            val jobs = mutableListOf<Job>()
            fun module(id: String, dependency: String? = null, value: String = id) =
                HostModule(id, setOfNotNull(dependency), listOf(value)) { lifetime ->
                    events.add("start:$value")
                    lifetime.onDispose { events.add("close:$value") }
                    jobs.add(
                        lifetime.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            awaitCancellation()
                        },
                    )
                }
            val registry = ModuleRegistry<String>(this)
            try {
                val storage = module("storage")
                val editor = module("editor", "storage")
                val standalone = module("standalone")
                registry.replace(listOf(editor, storage, standalone))
                assertEquals(
                    listOf("storage", "editor", "standalone"),
                    registry.state.value.contributions,
                )
                val editorScope = registry.scope("editor")
                registry.setEnabled("storage", false)
                assertEquals(ModuleStatus.DependencyInactive, registry.state.value.states["editor"])
                assertNull(registry.scope("editor"))
                assertTrue(jobs[0].isCancelled && jobs[1].isCancelled)
                assertFalse(jobs[2].isCancelled)
                assertEquals(listOf("close:editor", "close:storage"), events.takeLast(2))
                assertEquals(listOf("standalone"), registry.state.value.contributions)

                registry.setEnabled("storage", true)
                assertNotSame(editorScope, registry.scope("editor"))
                val beforeReplacement = jobs.toList()
                registry.replace(
                    listOf(editor, module("storage", value = "new-storage"), standalone),
                )
                assertTrue(beforeReplacement[3].isCancelled && beforeReplacement[4].isCancelled)
                assertFalse(beforeReplacement[2].isCancelled)
                assertEquals(
                    listOf("new-storage", "editor", "standalone"),
                    registry.state.value.contributions,
                )
                assertEquals(
                    listOf("close:editor", "close:storage", "start:new-storage", "start:editor"),
                    events.takeLast(4),
                )
            } finally {
                registry.close()
            }
            jobs.forEach { it.join() }
            assertTrue(jobs.all { it.isCancelled })
            assertTrue(registry.state.value.contributions.isEmpty())
        }

    @Test
    fun invalidDependenciesAndFailedActivationCannotPublishEntriesOrLeakResources(): Unit =
        runBlocking {
            val released = mutableListOf<String>()
            val registry = ModuleRegistry<String>(this)
            var failedJob: Job? = null
            try {
                registry.replace(
                    listOf(
                        HostModule("a", setOf("b"), listOf("a")),
                        HostModule("b", setOf("a"), listOf("b")),
                        HostModule("missing", setOf("absent"), listOf("missing")),
                        HostModule("broken", contributions = listOf("broken")) { lifetime ->
                            lifetime.onDispose { released.add("first") }
                            lifetime.onDispose { released.add("second") }
                            failedJob =
                                lifetime.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    awaitCancellation()
                                }
                            error("extension initialization failed")
                        },
                        HostModule("dependent", setOf("broken"), listOf("dependent")),
                        HostModule("working", contributions = listOf("working")),
                    ),
                )
                assertEquals(listOf("working"), registry.state.value.contributions)
                assertEquals(ModuleStatus.DependencyCycle, registry.state.value.states["a"])
                assertEquals(ModuleStatus.MissingDependency, registry.state.value.states["missing"])
                assertEquals(ModuleStatus.ActivationFailed, registry.state.value.states["broken"])
                assertEquals(
                    ModuleStatus.DependencyInactive,
                    registry.state.value.states["dependent"],
                )
                assertEquals(listOf("second", "first"), released)
                assertTrue(failedJob!!.isCancelled)
                assertNull(registry.scope("broken"))
            } finally {
                registry.close()
            }
            failedJob?.join()
        }
}
