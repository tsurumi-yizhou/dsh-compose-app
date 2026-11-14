package dev.fughetta.dshcompose.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.BuildConfig
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.ui.ReadablePane
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.SettingsCardGroup
import dev.fughetta.dshcompose.ui.adaptive.SettingsRow
import dev.fughetta.dshcompose.ui.adaptive.SettingsRowDivider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(onLicenses: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val project = "https://github.com/ccch1mneyyy/dsh-compose"
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreDesignsystemR.string.about_title)) },
                navigationIcon = { UpButton() },
            )
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = inset, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("DSH Compose", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(
                            R.string.about_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsCardGroup {
                    SettingsRow(
                        title = stringResource(R.string.about_project),
                        chevron = true,
                        onClick = { uriHandler.openUri(project) },
                    )
                    SettingsRowDivider(16.dp)
                    SettingsRow(
                        title = stringResource(R.string.about_feedback),
                        chevron = true,
                        onClick = { uriHandler.openUri("$project/issues") },
                    )
                    SettingsRowDivider(16.dp)
                    SettingsRow(
                        title = stringResource(CoreDesignsystemR.string.open_source_licenses),
                        chevron = true,
                        onClick = onLicenses,
                    )
                }
            }
        }
    }
}
