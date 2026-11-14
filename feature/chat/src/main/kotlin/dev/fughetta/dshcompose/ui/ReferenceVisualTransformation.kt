package dev.fughetta.dshcompose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.composer.referenceDisplay

internal class ReferenceVisualTransformation(
    private val spans: List<ReferenceSpan>,
    private val background: Color,
    private val foreground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val display = referenceDisplay(text.text, spans)
        val annotated =
            AnnotatedString.Builder(display.text)
                .apply {
                    text.spanStyles.forEach {
                        addStyle(
                            it.item,
                            display.originalOffset(it.start),
                            display.originalOffset(it.end),
                        )
                    }
                    display.spans.forEach {
                        addStyle(
                            SpanStyle(
                                background = background,
                                color = foreground,
                                fontWeight = FontWeight.Medium,
                            ),
                            it.start,
                            it.end,
                        )
                    }
                }
                .toAnnotatedString()
        return TransformedText(
            annotated,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = display.originalOffset(offset)

                override fun transformedToOriginal(offset: Int) = display.displayOffset(offset)
            },
        )
    }
}
