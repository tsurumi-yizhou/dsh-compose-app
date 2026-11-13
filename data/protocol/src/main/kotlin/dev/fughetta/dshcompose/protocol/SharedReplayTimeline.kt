package dev.fughetta.dshcompose.protocol

data class SharedReplayBeat(val start: Double, val end: Double, val thinkEnd: Double)

class SharedReplayTimeline(entries: List<SharedConversationEntry>) {
    val beats: List<SharedReplayBeat>
    val turnEnds: Map<Int, Double>
    val duration: Double

    init {
        val result = ArrayList<SharedReplayBeat>(entries.size)
        var lastOriginal = 0.0
        var lastReplay = 0.0
        var previousOriginal = 0.0
        var previousReplay = 0.0
        entries.forEachIndexed { index, entry ->
            val original = entry.time.toDouble()
            val (thinking, run) = durations(entry)
            val start =
                maxOf(
                    previousReplay,
                    when {
                        index == 0 -> 0.0
                        original >= lastOriginal ->
                            lastReplay +
                                gap(
                                    original - lastOriginal,
                                    if (entry.type in setOf("turn", "user")) 900.0 else 1600.0,
                                )
                        else ->
                            previousReplay + (original - previousOriginal).coerceIn(120.0, 400.0)
                    },
                )
            val beat = SharedReplayBeat(start, start + thinking + run, start + thinking)
            result += beat
            previousOriginal = original
            previousReplay = start
            if (entry.type != "turn") {
                lastOriginal =
                    maxOf(
                        lastOriginal,
                        original + if (entry.type in setOf("reply", "tool")) entry.duration else 0L,
                    )
                lastReplay = maxOf(lastReplay, beat.end)
            }
        }
        val ends = linkedMapOf<Int, Double>()
        entries.forEachIndexed { index, entry ->
            if (entry.type == "turn" && entry.body["d"] != null) {
                var end = result[index].start
                var cursor = index + 1
                val originalEnd = entry.time + entry.duration
                while (
                    cursor < entries.size &&
                        entries[cursor].type != "turn" &&
                        entries[cursor].time <= originalEnd
                ) {
                    end = maxOf(end, result[cursor].end)
                    cursor++
                }
                ends[index] = end + 150.0
            }
        }
        beats = result
        turnEnds = ends
        duration = maxOf(result.maxOfOrNull { it.end } ?: 0.0, ends.values.maxOrNull() ?: 0.0)
    }

    fun visibleAt(at: Double): Int {
        var low = 0
        var high = beats.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (beats[mid].start <= at) low = mid + 1 else high = mid
        }
        return low
    }

    private fun gap(value: Double, cap: Double) =
        if (value <= 500) value.coerceIn(0.0, cap) else minOf(cap, 500 + (value - 500) * 0.2)

    private fun durations(entry: SharedConversationEntry): Pair<Double, Double> =
        when (entry.type) {
            "reply" -> {
                val chars = entry.text.codePointCount(0, entry.text.length).toDouble()
                val thought =
                    entry.body
                        .npString("think")
                        .orEmpty()
                        .let { it.codePointCount(0, it.length) }
                        .toDouble()
                val total = chars + thought
                if (total == 0.0) 0.0 to 0.0
                else {
                    val original =
                        if (entry.body["d"] != null) entry.duration.toDouble()
                        else total / 80 * 1000
                    val thinking =
                        if (thought == 0.0) 0.0
                        else (original * thought / total).coerceIn(600.0, 2400.0)
                    val run =
                        if (chars == 0.0) 0.0
                        else
                            minOf(
                                    6000.0,
                                    maxOf(
                                        chars / 500 * 1000,
                                        minOf(original * chars / total, chars / 30 * 1000),
                                    ),
                                )
                                .coerceAtLeast(300.0)
                    thinking to run
                }
            }
            "tool" ->
                0.0 to
                    if (entry.body["d"] == null) 400.0
                    else
                        (if (entry.duration <= 300) entry.duration.toDouble()
                            else 300 + (entry.duration - 300) * 0.15)
                            .coerceIn(250.0, 2500.0)
            else -> 0.0 to 0.0
        }
}
