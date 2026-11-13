package dev.fughetta.dshcompose.domain.schedules

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ScheduleTimingTest {
    @Test
    fun wallClockRecurrenceHandlesDaylightSavingAndCronDayUnionWithoutResettingIntervals() {
        val daily = ScheduleDraft(time = "02:30:00", zone = "America/New_York")
        assertEquals(
            Instant.parse("2027-03-15T06:30:00Z"),
            daily.preview(Instant.parse("2027-03-14T05:00:00Z"), 1).single(),
        )
        assertEquals(
            Instant.parse("2026-11-01T05:30:00Z"),
            daily
                .copy(time = "01:30:00")
                .preview(Instant.parse("2026-11-01T04:00:00Z"), 1)
                .single(),
        )
        val cron = ScheduleDraft(repeat = ScheduleRepeat.Cron, cron = "0 9 1 * 1", zone = "UTC")
        assertEquals(
            Instant.parse("2026-11-02T09:00:00Z"),
            cron.preview(Instant.parse("2026-11-01T10:00:00Z"), 1).single(),
        )
        val interval = ScheduleDraft(repeat = ScheduleRepeat.Interval)
        assertEquals(
            Instant.parse("2026-11-08T12:15:00Z"),
            interval
                .preview(
                    Instant.parse("2026-11-08T11:20:00Z"),
                    1,
                    Instant.parse("2026-11-08T09:15:00Z"),
                )
                .single(),
        )
        assertEquals(
            "time_gap",
            daily
                .copy(repeat = ScheduleRepeat.Once, date = "2027-03-14")
                .validate(Instant.parse("2027-03-13T00:00:00Z")),
        )
    }
}
