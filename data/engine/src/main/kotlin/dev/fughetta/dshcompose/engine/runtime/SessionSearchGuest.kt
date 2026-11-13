package dev.fughetta.dshcompose.engine.runtime

/**
 * Message search for the local engine. DSH ships `session-query-sqlite`, but its default config is
 * `openAt: never`: `session/search` answers "session search is disabled", and the drawer's search
 * can only match titles (the official Web client behaves the same). This overlay opens the index on
 * the first search instead, so nothing changes at startup.
 *
 * Verified on 0.2.1-rc.2 (x86_64, Node 24): content search works. The index splits words on
 * punctuation, so English words and identifiers are found, while a Chinese word inside an
 * unpunctuated run is not. The first search reads every log once to build the index.
 *
 * Installed as the Compose search bundle, before the profile's user settings.
 */
object SessionSearchGuest {
    // l10n:keep guest config comment
    val PATCH_YAML =
        """# Compose search bundle. User overrides belong in the profile settings layer.
- id: session-query-sqlite
  config:
    path: /root/.dsh/session-search.sqlite
    openAt: first-search
"""
}
