package dev.fughetta.dshcompose.ui.markdown

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

data class ReplyImageRequest(
    val sessionId: String,
    val address: String,
    val generation: Long,
    val retry: Int = 0,
) {
    override fun toString() = "ReplyImageRequest(generation=$generation)"
}
