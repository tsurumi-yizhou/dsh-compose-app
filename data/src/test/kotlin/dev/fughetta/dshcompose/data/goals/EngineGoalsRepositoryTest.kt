package dev.fughetta.dshcompose.data.goals

import dev.fughetta.dshcompose.data.testing.ProtocolHost
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngineGoalsRepositoryTest {
    @Test
    fun goalRevisionsActivationEdgesAndClearRepliesKeepTheirSessionAndNeverResurrectOldGoals():
        Unit = runBlocking {
        withTimeout(15000) {
            var ref = GoalRef("goal", 1)
            var objective = "Initial objective"
            var phase = GoalPhase.Active
            var activation = GoalActivation.Disarmed
            var rounds = 0L
            var hold: CompletableDeferred<Unit>? = null
            val entered = Channel<String>(8)
            var mutations = 0
            fun projection() =
                GoalProjection(GoalSnapshot(ref, objective, phase, 256, null), rounds, 1, 2)
            fun wireView(value: GoalProjection = projection(), live: GoalActivation = activation) =
                buildJsonObject {
                    put("id", value.goal.ref.id)
                    put("revision", value.goal.ref.revision)
                    put("objective", value.goal.objective)
                    put("phase", value.goal.phase.wire)
                    put("maxGoalRounds", value.goal.maxGoalRounds)
                    put("roundsStarted", value.roundsStarted)
                    put("activation", live.wire)
                    put("createdAt", 1)
                    put("updatedAt", 2)
                }
            fun host(remote: Boolean = false) =
                ProtocolHost(goalRead = !remote) { method, args ->
                    assertEquals(
                        "session",
                        args.getValue("agentId").jsonPrimitive.content,
                    )
                    val waiting = hold
                    var failure: String? = null
                    val result: JsonElement =
                        if (method == "goals/get") wireView()
                        else {
                            mutations++
                            val expected = args.getValue("ref").jsonObject
                            assertEquals(setOf("id", "revision"), expected.keys)
                            if (
                                expected.getValue("id").jsonPrimitive.content != ref.id ||
                                    expected.getValue("revision").jsonPrimitive.long != ref.revision
                            ) {
                                failure = "GOAL_STALE_REVISION"
                                JsonNull
                            } else
                                when (method) {
                                    "goals/edit" -> {
                                        objective =
                                            args
                                                .getValue("request")
                                                .jsonObject
                                                .getValue("objective")
                                                .jsonPrimitive
                                                .content
                                        ref = ref.copy(revision = ref.revision + 1)
                                        wireView()
                                    }
                                    "goals/pause" -> {
                                        ref = ref.copy(revision = ref.revision + 1)
                                        phase = GoalPhase.Paused
                                        activation = GoalActivation.Disarmed
                                        wireView()
                                    }
                                    "goals/resume" -> {
                                        phase = GoalPhase.Active
                                        activation = GoalActivation.Armed
                                        wireView()
                                    }
                                    "goals/clear" ->
                                        buildJsonObject {
                                            put("id", ref.id)
                                            put("revision", ref.revision + 1)
                                        }
                                    else -> error("Unexpected goal mutation")
                                }
                        }
                    if (waiting != null) {
                        entered.send(method)
                        withContext(NonCancellable) { waiting.await() }
                    }
                    failure?.let { throw HostRemoteException(it) }
                    result
                }
            val local = host()
            val remote = host(true)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val controller = EngineGoalsRepository()
            val state = controller.state
            fun change(action: GoalAction) =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    controller.change(
                        state.value!!.owner,
                        state.value!!.projection!!.goal.ref,
                        action,
                    )
                }
            fun save(ref: GoalRef, text: String) =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    controller.edit(state.value!!.owner, ref, text)
                }
            fun clear() =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    controller.clear(state.value!!.owner, state.value!!.projection!!.goal.ref)
                }
            suspend fun idle() = state.filterNotNull().first { !it.busy }
            try {
                local.client.mux.ready()
                controller.connect(local.client, scope)
                controller.session("session")
                controller.observe("session", true)
                controller.transcript("session", projection(), false, true)
                state.filterNotNull().first { it.activation == GoalActivation.Disarmed }
                assertTrue(state.value!!.canResume)
                assertFalse(state.value!!.canPause)
                hold = CompletableDeferred()
                change(GoalAction.Resume)
                assertEquals("goals/resume", entered.receive())
                activation = GoalActivation.Disarmed
                controller.activation(GoalActivationChange("session", ref, activation))
                hold.complete(Unit)
                hold = null
                idle()
                assertEquals(GoalActivation.Disarmed, state.value!!.activation)
                change(GoalAction.Resume)
                idle()
                assertEquals(GoalActivation.Armed, state.value!!.activation)
                val staleRef = state.value!!.projection!!.goal.ref
                ref = ref.copy(revision = 2)
                objective = "Changed elsewhere"
                controller.transcript("session", projection(), false, true)
                save(staleRef, " User revision ")
                idle()
                assertNotNull(state.value!!.error)
                assertEquals("Changed elsewhere", state.value!!.projection!!.goal.objective)
                save(state.value!!.projection!!.goal.ref, " New objective\nwith details ")
                idle()
                assertEquals(
                    "New objective\nwith details",
                    state.value!!.projection!!.goal.objective,
                )
                controller.transcript("session", projection(), false, true)
                hold = CompletableDeferred()
                change(GoalAction.Pause)
                assertEquals("goals/pause", entered.receive())
                ref = ref.copy(revision = ref.revision + 1)
                objective = "More recent objective"
                controller.transcript("session", projection(), false, true)
                controller.activation(GoalActivationChange("session", ref, GoalActivation.Disarmed))
                hold.complete(Unit)
                hold = null
                idle()
                assertEquals(ref, state.value!!.projection!!.goal.ref)
                assertEquals("More recent objective", state.value!!.projection!!.goal.objective)
                hold = CompletableDeferred()
                clear()
                assertEquals("goals/clear", entered.receive())
                ref = GoalRef("replacement", 1)
                objective = "Replacement goal"
                phase = GoalPhase.Active
                activation = GoalActivation.Armed
                controller.transcript("session", projection(), false, true)
                controller.activation(GoalActivationChange("session", ref, activation))
                hold.complete(Unit)
                hold = null
                idle()
                assertEquals("replacement", state.value!!.projection!!.goal.ref.id)
                assertTrue(state.value!!.visible)
                clear()
                idle()
                assertFalse(state.value!!.visible)
                rounds++
                controller.transcript("session", projection(), false, true)
                assertFalse(state.value!!.visible)
                controller.transcript("session", null, false, true)
                assertNull(state.value!!.projection)
                remote.client.mux.ready()
                controller.connect(remote.client, scope)
                controller.session("session")
                controller.transcript("session", projection(), false, true)
                controller.observe("session", true)
                assertFalse(state.value!!.canRead)
                assertNull(state.value!!.activation)
                assertTrue(state.value!!.canPause)
                controller.activation(GoalActivationChange("session", ref, GoalActivation.Disarmed))
                assertTrue(state.value!!.canResume)
                controller.observe("session", false)
                controller.close()
                assertNull(state.value)
                assertTrue(mutations >= 6)
            } finally {
                hold?.complete(Unit)
                controller.close()
                scope.cancel()
                local.close()
                remote.close()
            }
        }
    }

    @Test
    fun switchingHostsRetiresAnInFlightMutationEvenWhenTheSessionAndGoalIdsMatch() = runBlocking {
        withTimeout(10000) {
            fun projection(objective: String, revision: Long = 1) =
                GoalProjection(
                    GoalSnapshot(GoalRef("goal", revision), objective, GoalPhase.Active, 256, null),
                    0,
                    1,
                    2,
                )
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var edits = 0
            val source =
                ProtocolHost(goalRead = false) { endpoint, _ ->
                    check(endpoint == "goals/edit")
                    edits++
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    buildJsonObject {
                        put("id", "goal")
                        put("revision", 2)
                        put("objective", "Late old-host answer")
                        put("phase", "active")
                        put("activation", "armed")
                        put("maxGoalRounds", 256)
                    }
                }
            val next =
                ProtocolHost(goalRead = false) { endpoint, _ ->
                    error("Unexpected new-host request: $endpoint")
                }
            val repository = EngineGoalsRepository()
            try {
                source.client.mux.ready()
                repository.connect(source.client, this)
                repository.session("same-session")
                repository.transcript("same-session", projection("Old host"), false, true)
                val firstOwner = repository.state.value!!.owner
                val mutation = launch { repository.edit(firstOwner, GoalRef("goal", 1), "Edited") }
                entered.await()
                assertTrue(repository.state.value!!.busy)
                next.client.mux.ready()
                repository.connect(next.client, this)
                repository.session("same-session")
                repository.transcript("same-session", projection("New host"), false, true)
                assertNotEquals(firstOwner, repository.state.value!!.owner)
                repository.edit(firstOwner, GoalRef("goal", 1), "Stale user intent")
                assertEquals(1, edits)
                release.complete(Unit)
                mutation.join()
                assertEquals("New host", repository.state.value!!.projection!!.goal.objective)
                assertFalse(repository.state.value!!.busy)
                assertEquals(0L, repository.state.value!!.mutationRevision)
            } finally {
                release.complete(Unit)
                repository.close()
                source.close()
                next.close()
            }
        }
    }
}
