package dev.fughetta.dshcompose.domain.providers

import java.math.BigDecimal
import kotlinx.collections.immutable.*

enum class ProviderApi {
    OpenAiCompletions,
    OpenAiResponses,
    AnthropicMessages,
}

val providerThinkingLevels = persistentListOf("minimal", "low", "medium", "high", "xhigh", "max")

class ProviderProfile(
    val route: String,
    val displayName: String?,
    val api: ProviderApi?,
    val baseUrl: String?,
    val credentialRef: String?,
    val models: PersistentList<ProviderModelSpec>,
    val defaultInput: List<String> = listOf("text"),
    val defaultContextWindow: Long? = null,
    val defaultMaxTokens: Long? = null,
    val modelOverrides: PersistentMap<String, ProviderModelOverride> = persistentMapOf(),
) {
    val name
        get() = displayName?.takeIf { it.isNotBlank() } ?: route

    val handDeclared
        get() = api != null && !baseUrl.isNullOrBlank() && models.isNotEmpty()

    override fun toString() = "ProviderProfile(route=$route)"
}

class ProviderSettings(
    val profiles: PersistentList<ProviderProfile>,
    val revision: Long,
    val writable: Boolean,
    val userRoutes: PersistentSet<String>,
    val baseRoutes: PersistentSet<String>,
) {
    fun find(route: String) = profiles.firstOrNull { it.route == route }

    fun removable(route: String) = route in userRoutes && route !in baseRoutes

    override fun toString() = "ProviderSettings(revision=$revision, count=${profiles.size})"
}

fun ProviderSettings?.accounts(): PersistentList<ProviderAccount> =
    persistentListOf(ProviderAccount("builtin-deepseek", "DeepSeek", "DEEPSEEK_API_KEY"))
        .addingAll(
            this?.profiles.orEmpty().map {
                ProviderAccount("custom/${it.route}", it.name, it.credentialRef)
            },
        )

fun deriveProviderKeyRef(route: String) = route.uppercase().replace('-', '_') + "_API_KEY"

fun parseProviderTokenCount(raw: String): Long? {
    val value = raw.filterNot { it.isWhitespace() || it == ',' || it == '_' }
    val match = Regex("^(\\d+(?:\\.\\d+)?)([kKmM])?$").matchEntire(value) ?: return null
    val scale =
        when (match.groupValues[2].lowercase()) {
            "k" -> 1000L
            "m" -> 1_000_000L
            else -> 1L
        }
    return runCatching {
        BigDecimal(match.groupValues[1]).multiply(BigDecimal.valueOf(scale)).longValueExact()
    }
        .getOrNull()
        ?.takeIf { it > 0 }
}

fun validProviderRoute(route: String) = route.matches(Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"))

fun normalizeProviderBaseUrl(raw: String, api: ProviderApi): String =
    raw.trim().trimEnd('/').let {
        if (api == ProviderApi.AnthropicMessages) it.removeSuffix("/v1") else it
    }
