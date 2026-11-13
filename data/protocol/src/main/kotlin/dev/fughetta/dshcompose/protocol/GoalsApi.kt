package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*

val GoalPhase.wire: String
    get() = name.lowercase()
val GoalActivation.wire: String
    get() = name.lowercase()
private val GoalAction.wire: String
    get() = name.lowercase()

private fun GoalRef.toJson() = buildJsonObject {
    put("id", id)
    put("revision", revision)
}

internal fun parseGoalRef(value: JsonElement): GoalRef =
    value.objectValue().let { GoalRef(it.requiredString("id"), it.requiredLong("revision")) }

fun parseGoalSnapshot(value: JsonElement): GoalSnapshot {
    val goal = value.objectValue()
    val phase =
        GoalPhase.entries.firstOrNull { it.wire == goal.string("phase") }
            ?: throw HostProtocolException("Invalid goal phase")
    val reason = goal["blockedReason"] as? JsonObject
    return GoalSnapshot(
        parseGoalRef(value),
        goal.string("objective") ?: throw HostProtocolException("Missing objective"),
        phase,
        goal.long("maxGoalRounds") ?: 0,
        if (phase == GoalPhase.Blocked && !reason?.string("message").isNullOrBlank())
            GoalBlockReason(
                reason.string("code").orEmpty(),
                reason.requiredString("message"),
            )
        else null,
    )
}

fun parseGoalProjection(value: JsonElement?): GoalProjection? {
    if (value == null || value == JsonNull) return null
    val projection = value.objectValue()
    return GoalProjection(
        parseGoalSnapshot(
            projection["goal"] ?: throw HostProtocolException("Missing projected goal"),
        ),
        projection.long("roundsStarted") ?: 0,
        projection.long("createdAt"),
        projection.long("updatedAt"),
    )
}

internal fun parseGoalView(value: JsonElement): GoalView {
    val goal = value.objectValue()
    return GoalView(
        GoalProjection(
            parseGoalSnapshot(value),
            goal.long("roundsStarted") ?: 0,
            goal.long("createdAt"),
            goal.long("updatedAt"),
        ),
        GoalActivation.entries.firstOrNull { it.wire == goal.string("activation") },
    )
}

fun goalActivationChange(event: JsonObject): GoalActivationChange? {
    if (event.string("type") != "emit" || event.string("event") != "goal/activation-changed")
        return null
    val payload = (event["args"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val session = payload.string("sessionId") ?: return null
    val goal = payload["goal"] as? JsonObject
    val id = goal?.string("id")?.takeIf { it.isNotEmpty() }
    val revision = goal?.long("revision")
    if (goal != null && (id == null || revision == null)) return null
    val ref = if (id != null && revision != null) GoalRef(id, revision) else null
    return GoalActivationChange(
        session,
        ref,
        GoalActivation.entries.firstOrNull { it.wire == goal?.string("activation") },
    )
}

suspend fun ProtocolClient.goal(sessionId: String): GoalView? {
    if (!connection.supportsGoalRead) throw HostRemoteException("GOAL_READ_UNSUPPORTED")
    return connection.call("goals/get", buildJsonObject { put("agentId", sessionId) }).let {
        if (it == JsonNull) null else parseGoalView(it)
    }
}

suspend fun ProtocolClient.createGoal(
    sessionId: String,
    objective: String,
    maxGoalRounds: Long? = null,
): GoalRef =
    parseGoalRef(
        connection
            .call(
                "goals/create",
                buildJsonObject {
                    put("agentId", sessionId)
                    putJsonObject("request") {
                        put("objective", objective.trim())
                        maxGoalRounds?.let { put("maxGoalRounds", it) }
                    }
                },
            )
            .objectValue()["ref"] ?: throw HostProtocolException("Missing created goal ref"),
    )

suspend fun ProtocolClient.editGoal(
    sessionId: String,
    ref: GoalRef,
    objective: String? = null,
    maxGoalRounds: Long? = null,
): GoalView {
    require(objective != null || maxGoalRounds != null)
    return parseGoalView(
        connection.call(
            "goals/edit",
            buildJsonObject {
                put("agentId", sessionId)
                put("ref", ref.toJson())
                putJsonObject("request") {
                    objective?.let { put("objective", it.trim()) }
                    maxGoalRounds?.let { put("maxGoalRounds", it) }
                }
            },
        ),
    )
}

suspend fun ProtocolClient.changeGoal(
    sessionId: String,
    ref: GoalRef,
    action: GoalAction,
): GoalView {
    require(action != GoalAction.Clear)
    return parseGoalView(
        connection.call(
            "goals/${action.wire}",
            buildJsonObject {
                put("agentId", sessionId)
                put("ref", ref.toJson())
            },
        ),
    )
}

suspend fun ProtocolClient.clearGoal(sessionId: String, ref: GoalRef): GoalRef =
    parseGoalRef(
        connection.call(
            "goals/clear",
            buildJsonObject {
                put("agentId", sessionId)
                put("ref", ref.toJson())
            },
        ),
    )
