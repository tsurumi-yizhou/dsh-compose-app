package dev.fughetta.dshcompose.domain.engine

fun negotiateBridgeCapabilities(
    appCapabilities: Collection<String>,
    offered: Collection<String>,
): List<String> {
    val have = offered.toSet()
    return appCapabilities.filter { it in have }
}
