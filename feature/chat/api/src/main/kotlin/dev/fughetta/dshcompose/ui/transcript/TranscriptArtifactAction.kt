package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*

enum class TranscriptArtifactAction {
    Preview,
    Open,
    OpenWith,
    Share,
    Save,
    CopyPath,
}
