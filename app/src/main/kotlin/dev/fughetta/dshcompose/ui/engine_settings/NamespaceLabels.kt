package dev.fughetta.dshcompose.ui.engine_settings

import androidx.annotation.StringRes
import dev.fughetta.dshcompose.R

/**
 * Product names for DSH settings namespaces (the Loader entry ids `settings/describe` reports).
 * Unknown namespaces show their id.
 */
private val namespaceLabels =
    mapOf(
        "agent-default-model" to R.string.settings_ns_default_model,
        "agent-preset-registry" to R.string.settings_ns_default_preset,
        "agent-loop" to R.string.settings_ns_agent,
        "subagent" to R.string.settings_ns_subagent,
        "subagent-model-selection-settings" to R.string.settings_ns_subagent_models,
        "llm-deepseek" to R.string.settings_ns_deep_seek_model,
        "llm-deepseek-account" to R.string.settings_ns_account_model,
        "llm-pi-ai" to R.string.settings_ns_custom_models,
        "compose-research" to R.string.settings_ns_research,
        "web" to R.string.settings_ns_network,
        "web-search-deepseek" to R.string.settings_ns_web_search,
        "web-deepseek-api" to R.string.settings_ns_search_api,
        "bash-sandbox" to R.string.settings_ns_commands,
        "permission" to R.string.settings_ns_permissions,
        "session-log-deepseek" to R.string.settings_ns_chat_log,
        "cot-translation" to R.string.settings_ns_thinking_translation,
        "translator" to R.string.settings_ns_translation,
        "locale" to R.string.settings_ns_language,
        "ui-theme" to R.string.settings_ns_web_appearance,
        "ui-chat" to R.string.settings_ns_web_chat,
        "ui-conversation" to R.string.settings_ns_web_input,
        "ui-settings" to R.string.settings_ns_web_settings,
        "ui-settings-general" to R.string.settings_ns_web_general,
        "auto-review" to R.string.settings_ns_auto_review,
        "agent-team" to R.string.settings_ns_agent_team,
    )

/** 「子代理」 for `subagent`; null when [ns] has no product name. */
@StringRes fun namespaceLabel(ns: String): Int? = namespaceLabels[ns]
