package dev.fughetta.dshcompose.domain.sharing

import dev.fughetta.dshcompose.domain.chat.ToolPayload

enum class SharedPluginSource {
    npm,
    github,
    git,
    official,
}

data class SharedPlugin(
    val name: String,
    val source: SharedPluginSource,
    val spec: String? = null,
    val version: String? = null,
    val integrity: String? = null,
    val title: String? = null,
    val author: String? = null,
    val summary: String? = null,
) {
    val displayTitle
        get() = title ?: name

    companion object {
        private val packageName = Regex("(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*")
        private val semver =
            Regex(
                "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
            )
        private val github =
            Regex(
                "github:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+#[A-Za-z0-9_./-]+(?:&path:/[A-Za-z0-9_./@+-]+)?",
            )
        private val git =
            Regex(
                "git\\+https://([A-Za-z0-9.-]+)/[A-Za-z0-9_./-]+#[A-Za-z0-9_./-]+(?:&path:/[A-Za-z0-9_./@+-]+)?",
            )

        fun parse(raw: ToolPayload.Obj): SharedPlugin {
            val name =
                raw.text("name")?.takeIf { it.length <= 214 && packageName.matches(it) }
                    ?: throw ShareFormatException("share/invalid-plugin")
            val source =
                SharedPluginSource.entries.find { it.name == raw.text("source") }
                    ?: throw ShareFormatException("share/invalid-plugin")
            val spec = raw.text("spec")
            val version = raw.text("version")
            val valid =
                when (source) {
                    SharedPluginSource.npm ->
                        version != null && semver.matches(version) && spec == "$name@$version"
                    SharedPluginSource.github ->
                        spec != null &&
                            (github.matches(spec) ||
                                git.matchEntire(spec)?.groupValues?.get(1) == "github.com")
                    SharedPluginSource.git -> spec != null && git.matches(spec)
                    SharedPluginSource.official -> spec == null
                }
            if (!valid) throw ShareFormatException("share/invalid-plugin")
            val integrity = raw.text("integrity")?.takeIf { source == SharedPluginSource.npm }
            if (
                integrity != null &&
                    !integrity.matches(Regex("sha(?:256|384|512)-[A-Za-z0-9+/]+={0,2}"))
            )
                throw ShareFormatException("share/invalid-plugin")
            return SharedPlugin(
                name,
                source,
                spec,
                version?.takeIf(semver::matches),
                integrity,
                shareLine(raw.text("title"), 80),
                shareLine(raw.text("author"), 80),
                shareLine(raw.text("summary"), 200),
            )
        }

        fun installed(
            name: String,
            source: String?,
            version: String?,
            title: String? = null,
            author: String? = null,
            summary: String? = null,
        ): SharedPlugin? {
            val spec = source?.trim() ?: return null
            val kind =
                when {
                    github.matches(spec) -> SharedPluginSource.github
                    git.matches(spec) ->
                        if (git.matchEntire(spec)?.groupValues?.get(1) == "github.com")
                            SharedPluginSource.github
                        else SharedPluginSource.git
                    version != null &&
                        semver.matches(version) &&
                        (spec.removePrefix("npm:") == name ||
                            spec.removePrefix("npm:").let {
                                it.startsWith("$name@") &&
                                    it.substring(name.length + 1).matches(Regex("[\\w.^~<>=|* -]+"))
                            }) -> SharedPluginSource.npm
                    else -> return null
                }
            return parse(
                ToolPayload.Obj(
                    buildMap {
                        put("name", ToolPayload.Text(name))
                        put("source", ToolPayload.Text(kind.name))
                        put(
                            "spec",
                            ToolPayload.Text(
                                if (kind == SharedPluginSource.npm) "$name@$version" else spec,
                            ),
                        )
                        version?.let { put("version", ToolPayload.Text(it)) }
                        title?.let { put("title", ToolPayload.Text(it)) }
                        author?.let { put("author", ToolPayload.Text(it)) }
                        summary?.let { put("summary", ToolPayload.Text(it)) }
                    },
                ),
            )
        }
    }
}

fun shareLine(text: String?, limit: Int): String? =
    text
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { if (it.length <= limit) it else sharePrefix(it, limit - 1) + "…" }

fun sharePrefix(text: String, limit: Int): String =
    if (text.length <= limit) text
    else
        text.substring(
            0,
            if (
                limit > 0 &&
                    Character.isHighSurrogate(text[limit - 1]) &&
                    Character.isLowSurrogate(text[limit])
            )
                limit - 1
            else limit,
        )
