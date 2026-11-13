package dev.fughetta.dshcompose.domain.presets

import java.util.Locale

fun validPresetId(id: String): Boolean =
    id.length in 1..40 && Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(id)

fun presetIdFromName(name: String): String =
    name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')

fun freePresetId(stem: String, taken: Set<String>): String {
    val base = stem.take(40).trimEnd('-')
    if (base !in taken) return base
    var n = 2
    while (true) {
        val suffix = "-$n"
        val candidate = base.take(40 - suffix.length).trimEnd('-') + suffix
        if (candidate !in taken) return candidate
        n++
    }
}
