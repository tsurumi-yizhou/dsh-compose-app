package dev.fughetta.dshcompose.engine.runtime

import java.io.File

// killStrays does not use these startup-only dependencies. Keep their signatures
// available while compiling the unchanged complete production EngineProcess file.
class RuntimeManifest(
    val abi: String = "x86_64",
    val launchArgv: List<String> = emptyList(),
    val launchEnv: Map<String, String> = emptyMap(),
)
object SessionSearchGuest {
    fun file(root: File): File = File(root, "missing-search-overlay")
    fun launchArg(arg: String): String? = null
}
object AppAuthGuest {
    fun file(root: File): File = File(root, "missing-auth-overlay")
    fun launchArg(arg: String): String? = null
}

object RuntimeProfile {
    const val ARGS = "--profile compose"
    fun profileArg(arg: String): String? = null
}
