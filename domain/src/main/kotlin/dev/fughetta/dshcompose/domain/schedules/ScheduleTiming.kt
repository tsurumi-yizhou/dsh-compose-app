// Cron field semantics adapted from DeepSeek Harness, MIT, Copyright (c) 2026 DeepSeek. See
// THIRD_PARTY_NOTICES.md.
package dev.fughetta.dshcompose.domain.schedules

import java.time.*

class ScheduleRuleException(val code: String) : IllegalArgumentException(code)

data class ScheduleCronField(val values: Set<Int>, val starred: Boolean, val full: Boolean) {
    val unrestricted
        get() = starred && full
}

data class ScheduleCron(
    val minute: ScheduleCronField,
    val hour: ScheduleCronField,
    val day: ScheduleCronField,
    val month: ScheduleCronField,
    val weekday: ScheduleCronField,
) {
    fun matches(date: LocalDate): Boolean {
        if (date.monthValue !in month.values) return false
        val dom = date.dayOfMonth in day.values
        val dow = date.dayOfWeek.value % 7 in weekday.values
        return if (!day.starred && !weekday.starred) dom || dow else dom && dow
    }

    companion object {
        fun parse(expression: String): ScheduleCron {
            val parts = expression.trim().split(Regex("\\s+"))
            if (parts.size != 5 || expression.isBlank()) throw ScheduleRuleException("cron_fields")
            val bounds = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
            val fields = parts.mapIndexed { index, text ->
                val range = bounds[index]
                val values = sortedSetOf<Int>()
                for (element in text.split(',')) {
                    val segments = element.split('/')
                    if (segments.size > 2) throw ScheduleRuleException("cron_field")
                    val step =
                        if (segments.size == 2)
                            segments[1].toIntOrNull()?.takeIf { it > 0 }
                                ?: throw ScheduleRuleException("cron_field")
                        else 1
                    val endpoints = segments[0].split('-')
                    val first: Int
                    val last: Int
                    if (segments[0] == "*") {
                        first = range.first
                        last = range.last
                    } else {
                        if (endpoints.size !in 1..2 || segments.size == 2 && endpoints.size == 1)
                            throw ScheduleRuleException("cron_field")
                        first =
                            endpoints[0].toIntOrNull() ?: throw ScheduleRuleException("cron_field")
                        last =
                            if (endpoints.size == 2)
                                endpoints[1].toIntOrNull()
                                    ?: throw ScheduleRuleException("cron_field")
                            else first
                        if (first !in range || last !in range || first > last)
                            throw ScheduleRuleException("cron_field")
                    }
                    var item = first
                    while (item <= last) {
                        values.add(if (index == 4 && item == 7) 0 else item)
                        if (step > last - item) break
                        item += step
                    }
                }
                ScheduleCronField(
                    values,
                    text.startsWith('*'),
                    values.size == if (index == 4) 7 else range.last - range.first + 1,
                )
            }
            return ScheduleCron(fields[0], fields[1], fields[2], fields[3], fields[4])
        }
    }
}

enum class ScheduleRepeat {
    Daily,
    Weekdays,
    Weekly,
    Monthly,
    Interval,
    Once,
    Cron,
    Custom,
}

enum class ScheduleIntervalUnit(val seconds: Long) {
    Seconds(1),
    Minutes(60),
    Hours(3600),
}

data class ScheduleDraft(
    val repeat: ScheduleRepeat = ScheduleRepeat.Daily,
    val time: String = "09:00:00",
    val zone: String = ZoneId.systemDefault().id,
    val date: String = LocalDate.now().plusDays(1).toString(),
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val monthDays: Set<Int> = setOf(1),
    val interval: String = "1",
    val intervalUnit: ScheduleIntervalUnit = ScheduleIntervalUnit.Hours,
    val cron: String = "0 9 * * 1-5",
    val customRule: String? = null,
) {
    fun validate(now: Instant): String? =
        try {
            if (repeat != ScheduleRepeat.Interval && repeat != ScheduleRepeat.Custom)
                ZoneId.of(zone)
            if (
                repeat !in
                    setOf(ScheduleRepeat.Interval, ScheduleRepeat.Cron, ScheduleRepeat.Custom)
            )
                LocalTime.parse(time)
            when (repeat) {
                ScheduleRepeat.Interval -> if (intervalSeconds() < 60) "interval_minimum" else null
                ScheduleRepeat.Weekly -> if (weekdays.isEmpty()) "choose_day" else null
                ScheduleRepeat.Monthly ->
                    if (monthDays.isEmpty()) "choose_day"
                    else if (LocalTime.parse(time).second != 0 || LocalTime.parse(time).nano != 0)
                        "minute_precision"
                    else null
                ScheduleRepeat.Once -> {
                    val instant =
                        wallInstant(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of(zone))
                    if (instant == null) "time_gap"
                    else if (!instant.isAfter(now)) "future_time" else null
                }
                ScheduleRepeat.Cron -> {
                    ScheduleCron.parse(cron)
                    if (preview(now, count = 1).isEmpty()) "cron_no_runs" else null
                }
                else -> null
            }
        } catch (error: ScheduleRuleException) {
            error.code
        } catch (_: DateTimeException) {
            "date_time_zone"
        } catch (_: ArithmeticException) {
            "interval_invalid"
        }

    fun intervalSeconds(): Long {
        val count =
            interval.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ScheduleRuleException("interval_invalid")
        return Math.multiplyExact(count, intervalUnit.seconds)
    }

    fun preview(now: Instant, count: Int = 3, anchor: Instant? = null): List<Instant> {
        if (repeat == ScheduleRepeat.Custom) return listOfNotNull(anchor?.takeIf { it > now })
        if (repeat == ScheduleRepeat.Interval) {
            val seconds = intervalSeconds()
            if (seconds < 60) return emptyList()
            var next = anchor ?: now.plusSeconds(seconds)
            if (next <= now)
                next =
                    next.plusSeconds(
                        Math.multiplyExact(
                            Duration.between(next, now).seconds / seconds + 1,
                            seconds,
                        ),
                    )
            return (0 until count).map {
                next.plusSeconds(Math.multiplyExact(it.toLong(), seconds))
            }
        }
        val location = ZoneId.of(zone)
        val clock = if (repeat == ScheduleRepeat.Cron) null else LocalTime.parse(time)
        if (repeat == ScheduleRepeat.Once)
            return listOfNotNull(
                wallInstant(LocalDate.parse(date), clock!!, location)?.takeIf { it > now },
            )
        val parsed = if (repeat == ScheduleRepeat.Cron) ScheduleCron.parse(cron) else null
        val times =
            if (parsed == null) listOf(clock!!)
            else
                parsed.hour.values.sorted().flatMap { h ->
                    parsed.minute.values.sorted().map { m -> LocalTime.of(h, m) }
                }
        val result = mutableListOf<Instant>()
        var day = now.atZone(location).toLocalDate()
        kotlin.repeat(
            if (repeat == ScheduleRepeat.Cron) 366 * 8
            else if (repeat == ScheduleRepeat.Monthly) 366 * 2 else 15,
        ) {
            val matches =
                when (repeat) {
                    ScheduleRepeat.Daily -> true
                    ScheduleRepeat.Weekdays -> day.dayOfWeek.value <= 5
                    ScheduleRepeat.Weekly -> day.dayOfWeek.value in weekdays
                    ScheduleRepeat.Monthly -> day.dayOfMonth in monthDays
                    else -> parsed!!.matches(day)
                }
            if (matches)
                for (time in times) {
                    val instant = wallInstant(day, time, location)
                    if (instant != null && instant > now) {
                        result.add(instant)
                        if (result.size >= count) return result
                    }
                }
            day = day.plusDays(1)
        }
        return result
    }
}

private fun wallInstant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant? {
    val local = LocalDateTime.of(date, time)
    return zone.rules.getValidOffsets(local).map { local.toInstant(it) }.minOrNull()
}
