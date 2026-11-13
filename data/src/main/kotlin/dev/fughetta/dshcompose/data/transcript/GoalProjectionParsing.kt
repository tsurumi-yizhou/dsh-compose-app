package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*

internal fun GoalProjectionFold.seed(projections: JsonObject) {
    seed(
        parseGoalProjection((projections["values"] as? JsonObject)?.get("goal")),
        projections.long("asOfSeq") ?: -1,
    )
}

internal fun GoalProjectionFold.apply(event: SessionRecord) {
    if (event.seq > afterSeq) decodeGoalProjectionChange(event)?.let { apply(it) }
}

internal fun GoalProjectionFold.rebuildRecords(events: Iterable<SessionRecord>) {
    rebuild(
        events
            .asSequence()
            .filter { it.seq > afterSeq }
            .mapNotNull(::decodeGoalProjectionChange)
            .asIterable(),
    )
}

private fun decodeGoalProjectionChange(event: SessionRecord): GoalProjectionChange? {
    val data = event.data
    return when (event.type) {
        "goal/change" ->
            GoalProjectionChange.Replace(
                event.seq,
                if (data.string("operation") == "clear") null
                else
                    GoalProjection(
                        parseGoalSnapshot(
                            data["goal"] ?: throw HostProtocolException("Missing goal change"),
                        ),
                        data.long("roundsStarted") ?: 0,
                        data.long("createdAt"),
                        data.long("updatedAt"),
                    ),
            )
        "user/message" -> {
            val source = data["source"] as? JsonObject
            val round = source?.long("round")
            val goalId = source?.string("goalId")
            if (source?.string("kind") == "goal" && goalId != null && round != null)
                GoalProjectionChange.Round(event.seq, goalId, round)
            else null
        }
        else -> null
    }
}
