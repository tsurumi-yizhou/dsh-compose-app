package dev.fughetta.dshcompose.domain.schedules

import java.time.Instant

class SaveScheduleUseCase(
    private val repository: SchedulesRepository,
    private val now: () -> Instant,
) {
    suspend operator fun invoke(input: ScheduleInput, original: ScheduleTask?): ScheduleTask {
        input.validate(now(), original)?.let {
            throw ScheduleOperationException(it, rejected = true)
        }
        if (original == null) return repository.create(input)
        val changes =
            ScheduleChanges(
                title = input.title.trim().takeIf { input.title != original.title },
                prompt = input.prompt.trim().takeIf { input.prompt != original.prompt },
                timing = input.timing.takeIf { it != original.timing },
                target = input.target.takeIf { it != original.target },
            )
        return if (changes.empty) original else repository.update(original, changes)
    }
}
