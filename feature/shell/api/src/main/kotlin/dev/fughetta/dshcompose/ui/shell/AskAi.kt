package dev.fughetta.dshcompose.ui.shell

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR

/**
 * What a 「问 AI」 entry asks. Each has one question the bundled user guide (the `dsh-compose-guide`
 * skill) answers.
 */
enum class AskAiTopic(@StringRes val question: Int) {
    Overview(CoreDesignsystemR.string.ask_ai_question_overview),
    PhoneControl(CoreDesignsystemR.string.ask_ai_question_phone_control),
    PhoneAlways(CoreDesignsystemR.string.ask_ai_question_phone_always),
    AppRules(CoreDesignsystemR.string.ask_ai_question_app_rules),
    FullAccess(CoreDesignsystemR.string.ask_ai_question_full_access),
    PhoneFiles(CoreDesignsystemR.string.ask_ai_question_phone_files),
}

/**
 * The composer fill for [question]: this phone's engine installs the guide skill at every start, so
 * a `/dsh-compose-guide` word makes the Host load it; elsewhere the question goes alone.
 */
fun askAiText(question: String, local: Boolean, aboutApp: (String) -> String): String =
    if (local) "/dsh-compose-guide $question" else aboutApp(question)

/** Builds the 「问 AI」 text for the active host from a question. */
@Composable
fun rememberAskAiText(local: Boolean): (String) -> String {
    val context = LocalContext.current
    return remember(local, context) {
        { question ->
            askAiText(question, local) {
                context.getString(CoreDesignsystemR.string.ask_ai_about_app, it)
            }
        }
    }
}
