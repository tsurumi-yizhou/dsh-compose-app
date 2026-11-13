package dev.fughetta.dshcompose.domain.providers

/** A complete edit of one profile; only the repository translates it into protocol operations. */
data class ProviderProfileDraft(
    val revision: Long,
    val original: ProviderProfile?,
    val route: String,
    val name: String,
    val api: ProviderApi,
    val baseUrl: String,
    val credentialRef: String,
    val models: List<ProviderModelSpec>,
    val overrides: Map<String, ProviderModelOverride>,
    val removing: Boolean,
)

class SaveProviderProfileUseCase {
    suspend operator fun invoke(
        operations: ProvidersRepository,
        settings: ProviderSettings,
        draft: ProviderProfileDraft,
    ): ProviderSettings {
        if (!settings.writable) throw ProviderOperationException("providers/read-only")
        if (!validProviderRoute(draft.route))
            throw ProviderOperationException("providers/invalid-profile")
        val previous = draft.original
        val change =
            if (draft.removing) {
                if (
                    previous == null ||
                        previous.route != draft.route ||
                        !settings.removable(draft.route)
                )
                    throw ProviderOperationException("providers/not-removable")
                ProviderMutation.Remove(draft.route, draft.revision)
            } else {
                val ref = draft.credentialRef.trim().takeIf { it.isNotEmpty() }
                if (ref != null && !validCredentialRef(ref))
                    throw ProviderOperationException("providers/invalid-profile")
                val full = previous?.handDeclared ?: true
                if (full && (!validProviderBaseUrl(draft.baseUrl) || draft.models.isEmpty()))
                    throw ProviderOperationException("providers/invalid-profile")
                if (previous == null)
                    ProviderMutation.Create(
                        draft.route,
                        draft.name.trim(),
                        draft.api,
                        normalizeProviderBaseUrl(draft.baseUrl, draft.api),
                        ref,
                        draft.models,
                        draft.revision,
                    )
                else {
                    if (previous.route != draft.route)
                        throw ProviderOperationException("providers/invalid-profile")
                    val modelsChanged =
                        draft.models.size != previous.models.size ||
                            draft.models.zip(previous.models).any { (a, b) -> !a.sameSettings(b) }
                    val previousOverrides = previous.modelOverrides.filterValues { !it.isEmpty }
                    val overridesChanged =
                        draft.overrides.keys != previousOverrides.keys ||
                            draft.overrides.any { (id, value) ->
                                previousOverrides[id]?.let(value::sameSettings) != true
                            }
                    ProviderMutation.Update(
                        previous,
                        if (draft.name == previous.displayName.orEmpty()) draft.name
                        else draft.name.trim(),
                        ref,
                        draft.api.takeIf { full && it != previous.api },
                        if (
                            full && (draft.baseUrl != previous.baseUrl || draft.api != previous.api)
                        )
                            normalizeProviderBaseUrl(draft.baseUrl, draft.api)
                        else null,
                        draft.models.takeIf { full && modelsChanged },
                        draft.overrides.takeIf { overridesChanged },
                        draft.revision,
                    )
                }
            }
        return operations.apply(change)
    }
}
