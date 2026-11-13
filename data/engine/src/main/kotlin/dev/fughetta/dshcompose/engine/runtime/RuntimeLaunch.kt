package dev.fughetta.dshcompose.engine.runtime

/** Owns launcher argument placement; feature modules only contribute their overlay paths. */
object RuntimeLaunch {
    private const val COMMAND = "/usr/local/bin/dsh ${RuntimeProfile.ARGS}"
    private const val APP_PATCH = "--patch /etc/dsh-app/dsh-web.patch.yml"

    fun withPatches(command: String, patches: List<String>): String? {
        val start = command.indexOf("$COMMAND ")
        if (start < 0 || command.indexOf("$COMMAND ", start + 1) >= 0) return null
        val patch = command.indexOf(APP_PATCH, start)
        val at =
            if (patch >= 0 && command.indexOf(APP_PATCH, patch + 1) < 0) patch + APP_PATCH.length
            else start + COMMAND.length
        val arguments = patches.joinToString("") { " --patch $it" }
        return command.substring(0, at) + arguments + command.substring(at)
    }
}
