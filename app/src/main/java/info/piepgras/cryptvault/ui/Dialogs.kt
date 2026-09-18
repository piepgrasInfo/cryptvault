package info.piepgras.cryptvault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.security.sensitive

/**
 * Every dialog is its own window, so FLAG_SECURE on the Activity does not cover it; this gives
 * dialogs the same policy (BUILD_BRIEF.md §4.4), with the debug-only screenshot escape.
 */
@Composable
fun secureDialogProperties(dismissOnClickOutside: Boolean = true): DialogProperties {
    val activity = androidx.activity.compose.LocalActivity.current
    return DialogProperties(dismissOnClickOutside = dismissOnClickOutside, securePolicy = info.piepgras.cryptvault.security.SecureWindow.dialogPolicy(activity))
}

/** Dialog content must scroll (CLAUDE.md): a dialog that overflows hides its own buttons. */
@Composable
fun ScrollingDialogText(text: String) {
    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
        Text(text)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = stringResource(R.string.action_ok),
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { ScrollingDialogText(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        properties = secureDialogProperties(),
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String? = null,
    confirmLabel: String = stringResource(R.string.action_ok),
    password: Boolean = false,
    singleLine: Boolean = true,
    validate: (String) -> String? = { null },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val error = validate(value)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = label?.let { { Text(it) } },
                    singleLine = singleLine,
                    isError = error != null && value.isNotEmpty(),
                    supportingText = error?.takeIf { value.isNotEmpty() }?.let { { Text(it) } },
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                        imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).let { if (password) it.sensitive() else it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank() && error == null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        properties = secureDialogProperties(dismissOnClickOutside = false),
    )
}

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { ScrollingDialogText(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        properties = secureDialogProperties(),
    )
}
