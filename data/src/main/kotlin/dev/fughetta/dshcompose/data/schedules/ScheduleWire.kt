package dev.fughetta.dshcompose.data.schedules

import dev.fughetta.dshcompose.domain.schedules.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.protocol.ScheduleTask as WireTask
import java.time.*
import kotlinx.serialization.json.*

internal fun ScheduleDraft.toWire(): JsonObject = buildJsonObject {
    fun clock() = LocalTime.parse(time).toString().let { if (it.length == 5) "$it:00" else it }
    when (repeat) {
        ScheduleRepeat.Once -> {
            put("kind", "at")
            putJsonObject("at") {
                put("date", date)
                put("time", clock())
                put("time_zone", zone)
            }
        }
        ScheduleRepeat.Interval -> {
            put("kind", "every")
            put("every_seconds", intervalSeconds())
        }
        ScheduleRepeat.Daily -> {
            put("kind", "daily")
            putJsonObject("daily") {
                put("time", clock())
                put("time_zone", zone)
            }
        }
        ScheduleRepeat.Weekly,
        ScheduleRepeat.Weekdays -> {
            put("kind", "weekly")
            putJsonObject("weekly") {
                put("time", clock())
                put("time_zone", zone)
                put(
                    "weekdays",
                    JsonArray(
                        (if (repeat == ScheduleRepeat.Weekdays) setOf(1, 2, 3, 4, 5) else weekdays)
                            .sorted()
                            .map(::JsonPrimitive),
                    ),
                )
            }
        }
        ScheduleRepeat.Monthly -> {
            val local = LocalTime.parse(time)
            put("kind", "cron")
            putJsonObject("cron") {
                put(
                    "expression",
                    "${local.minute} ${local.hour} ${monthDays.sorted().joinToString(",")} * *",
                )
                put("time_zone", zone)
            }
        }
        ScheduleRepeat.Cron -> {
            put("kind", "cron")
            putJsonObject("cron") {
                put("expression", cron.trim().split(Regex("\\s+")).joinToString(" "))
                put("time_zone", zone)
            }
        }
        ScheduleRepeat.Custom -> throw ScheduleRuleException("custom_rule")
    }
}

internal fun wireTiming(
    task: WireTask,
    deviceZone: String = ZoneId.systemDefault().id,
): ScheduleDraft {
    val row = task.raw
    val zone = row.string("timeZone") ?: deviceZone
    val local =
        task.scheduledAt?.atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC")))
    val base =
        ScheduleDraft(
            zone = zone,
            date = local?.toLocalDate()?.toString() ?: LocalDate.now().plusDays(1).toString(),
            time = row.string("time") ?: local?.toLocalTime()?.toString() ?: "09:00:00",
        )
    return when (task.kind) {
        "daily" -> base
        "weekly" -> {
            val days =
                (row["weekdays"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    .toSet()
            base.copy(
                repeat =
                    if (days == setOf(1, 2, 3, 4, 5)) ScheduleRepeat.Weekdays
                    else if (days.size == 7) ScheduleRepeat.Daily else ScheduleRepeat.Weekly,
                weekdays = days,
            )
        }
        "every" -> {
            val seconds = row.long("everySeconds") ?: 3600
            val unit =
                if (seconds % 3600 == 0L) ScheduleIntervalUnit.Hours
                else if (seconds % 60 == 0L) ScheduleIntervalUnit.Minutes
                else ScheduleIntervalUnit.Seconds
            base.copy(
                repeat = ScheduleRepeat.Interval,
                interval = (seconds / unit.seconds).toString(),
                intervalUnit = unit,
            )
        }
        "cron" ->
            base.copy(
                repeat = ScheduleRepeat.Cron,
                cron = row.string("expression").orEmpty(),
            )
        "rrule" -> base.copy(repeat = ScheduleRepeat.Custom, customRule = row.string("rrule"))
        else -> base.copy(repeat = ScheduleRepeat.Once)
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
