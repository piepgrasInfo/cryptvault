package info.piepgras.cryptvault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.recovery.RecoveryKey
import info.piepgras.cryptvault.security.sensitive
import info.piepgras.cryptvault.unlock.PasswordPolicy
import kotlinx.coroutines.launch

/** "Forgot password": the 44 words, validated word by word, and a new password. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverScreen(vaultId: String, onBack: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = CryptVaultApp.container(context)
    val record = container.repository.record(vaultId)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var words by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val typed = words.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val unknown = RecoveryKey.unknownWords(words)
    val complete = typed.size == RecoveryKey.WORD_COUNT && unknown.isEmpty()
    val valid = complete && RecoveryKey.isValid(words)
    val policy = PasswordPolicy.check(password)
    val canReset = valid && policy.ok && confirm == password && !busy

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recover_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(stringResource(R.string.recover_intro, record?.name ?: ""), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = words,
                onValueChange = { words = it.lowercase() },
                label = { Text(stringResource(R.string.recover_words_label)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().sensitive(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                isError = unknown.isNotEmpty() || (complete && !valid),
                supportingText = {
                    Text(
                        when {
                            unknown.isNotEmpty() -> stringResource(R.string.recover_unknown_words, unknown.map { typed[it] }.joinToString(", "))
                            complete && !valid -> stringResource(R.string.recover_checksum_failed)
                            else -> stringResource(R.string.recover_word_count, typed.size, RecoveryKey.WORD_COUNT)
                        },
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.password_new_label)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().sensitive(),
                supportingText = { Text(passwordAdvice(policy, password)) },
                isError = password.isNotEmpty() && !policy.ok,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirm, onValueChange = { confirm = it },
                label = { Text(stringResource(R.string.password_confirm_label)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().sensitive(),
                isError = confirm.isNotEmpty() && confirm != password,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    busy = true
                    val chars = password.toCharArray()
                    scope.launch {
                        val result = runCatching { container.repository.resetPassword(vaultId, words, chars) }
                        chars.fill('\u0000')
                        busy = false
                        result.onSuccess {
                            container.biometricWrap.disable(vaultId)
                            container.repository.setBiometric(vaultId, false)
                            password = ""; confirm = ""; words = ""
                            snackbar.showSnackbar(resources.getString(R.string.msg_password_changed))
                            onReset()
                        }.onFailure { snackbar.showSnackbar(it.message ?: it.javaClass.simpleName) }
                    }
                },
                enabled = canReset,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(R.string.recover_action))
            }
        }
    }
}

/** The line under a password field: zxcvbn's advice, the length floor, or "Good.". */
@Composable
fun passwordAdvice(policy: PasswordPolicy.Result, password: String): String = when {
    password.isEmpty() -> stringResource(R.string.password_hint)
    policy.ok -> stringResource(R.string.password_ok)
    policy.tooShort -> androidx.compose.ui.res.pluralStringResource(R.plurals.password_too_weak, PasswordPolicy.MIN_LENGTH, PasswordPolicy.MIN_LENGTH)
    policy.advice.isNotBlank() -> policy.advice
    else -> stringResource(R.string.password_weak)
}
