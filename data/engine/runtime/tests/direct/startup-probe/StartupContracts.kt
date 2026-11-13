package dev.fughetta.dshcompose.engine.runtime

import java.io.File

// The startup-policy probe executes the real EngineProcess/DirectLaunch/ownership
// code. These adjacent services are absent from the fixture.
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
