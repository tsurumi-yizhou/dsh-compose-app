package dev.fughetta.dshcompose.engine.runtime

object RestartPolicy {
    const val HEALTHY_MS = 120_000L

    fun autoRestartDelay(
        kind: FailureKind,
        exitCode: Int,
        upMs: Long?,
        recent: List<Long>,
        now: Long,
    ): Long? {
        if (kind != FailureKind.KILLED || exitCode == 0 || upMs == null || upMs <= 60_000L)
            return null
        val count = recent.count { now - it < 600_000L }
        return listOf(5_000L, 15_000L, 45_000L).getOrNull(count)
    }

    fun directLaunchFailed(failure: RunFailure, direct: Boolean): Boolean =
        direct && failure.kind == FailureKind.LAUNCH_FAILED
}
