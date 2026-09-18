package info.piepgras.cryptvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.security.sensitive

/**
 * The recovery-key ceremony (BUILD_BRIEF.md §3, docs/VAULT_LAYOUT.md §5): the 44 words, hidden
 * until revealed, no copy and no share by design, and a "written down" confirmation before the
 * screen can be left. The words come from `Container.recoveryKeyToShow` and are cleared on Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryKeyScreen(vaultId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = CryptVaultApp.container(context)
    val pending by container.recoveryKeyToShow.collectAsState()
    val words = pending?.takeIf { it.first == vaultId }?.second?.split(" ") ?: emptyList()
    var revealed by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val record = container.repository.record(vaultId)

    fun finish() {
        container.recoveryKeyToShow.value = null
        onDone()
    }

    BackHandler(enabled = words.isNotEmpty() && !confirmed) { /* leaving means confirming; the button does it */ }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.recovery_title)) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            if (words.isEmpty()) {
                Text(stringResource(R.string.recovery_nothing_to_show))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { finish() }) { Text(stringResource(R.string.action_ok)) }
                return@Column
            }
            Text(stringResource(R.string.recovery_intro, record?.name ?: ""), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth().sensitive()) {
                Column(Modifier.padding(16.dp)) {
                    words.chunked(4).forEachIndexed { row, chunk ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            chunk.forEachIndexed { i, w ->
                                val n = row * 4 + i + 1
                                Text(
                                    text = if (revealed) "$n. $w" else "$n. ••••••",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { revealed = !revealed }, Modifier.fillMaxWidth()) {
                Text(stringResource(if (revealed) R.string.recovery_hide else R.string.recovery_reveal))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.recovery_rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().toggleable(value = confirmed, role = Role.Checkbox, onValueChange = { confirmed = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = confirmed, onCheckedChange = null)
                Text(stringResource(R.string.recovery_confirm), Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { finish() }, enabled = confirmed, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}
