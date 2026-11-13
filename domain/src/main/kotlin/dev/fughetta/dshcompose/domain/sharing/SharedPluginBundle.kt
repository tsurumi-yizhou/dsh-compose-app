package dev.fughetta.dshcompose.domain.sharing

import dev.fughetta.dshcompose.domain.plugins.PluginBundle

fun sharedPluginFromBundle(bundle: PluginBundle, locale: String = "en"): SharedPlugin? =
    if (bundle.official)
        SharedPlugin(
            bundle.name,
            SharedPluginSource.official,
            title = bundle.meta?.title?.resolve(locale),
            summary = bundle.meta?.description?.resolve(locale),
        )
    else
        SharedPlugin.installed(
            bundle.name,
            bundle.source,
            bundle.version,
            bundle.meta?.title?.resolve(locale),
            summary = bundle.meta?.description?.resolve(locale) ?: bundle.description,
        )
