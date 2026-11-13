package dev.fughetta.dshcompose.domain.sharing

data class SharedSkill(val name: String, val files: Map<String, String>) {
    companion object {
        fun safePath(path: String) =
            path.matches(Regex("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+){0,3}")) &&
                path.split('/').none { it.startsWith('.') }

        fun checked(name: String, files: Map<String, String>): SharedSkill {
            if (
                !name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) ||
                    files.size !in 1..12 ||
                    "SKILL.md" !in files ||
                    files.any { (path, text) -> !safePath(path) || text.length > 64 * 1024 }
            )
                throw ShareFormatException("share/invalid-skill")
            return SharedSkill(name, files)
        }
    }
}
