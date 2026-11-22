package dev.ticbox.companion.ui.sos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.api.SosEmergencyContact
import dev.ticbox.companion.api.SosLimits
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.designsystem.RadioRow

private const val NUMBER_SYMBOLS = "+*#(). -"

private val SMS_NUMBER = Regex("\\+?[0-9]{1,32}")

/** Keeps only what the watch accepts in a number, however it was typed or pasted. */
internal fun sosNumberChars(text: String): String =
    text.filter { it in '0'..'9' || it in NUMBER_SYMBOLS }.take(SosLimits.MAX_NUMBER_CHARS)

internal fun validSosNumber(number: String): Boolean = number.any { it in '0'..'9' }

/** Empty is allowed, and is how the watch's number gets cleared. */
internal fun validEmergencyNumber(number: String): Boolean =
    number.isEmpty() || validSosNumber(number)

/**
 * The same test SMS assistance uses to skip a contact, so the warning here matches what it does.
 */
internal fun canReceiveSms(number: String): Boolean =
    SMS_NUMBER.matches(number.filterNot { it in " -()." })

/** Changes the contact at [index], or adds one when there's no index. */
internal fun List<SosEmergencyContact>.withContact(
    index: Int?,
    contact: SosEmergencyContact,
): List<SosEmergencyContact> =
    if (index == null) this + contact else mapIndexed { i, old -> if (i == index) contact else old }

internal fun List<SosEmergencyContact>.withoutContact(index: Int): List<SosEmergencyContact> =
    filterIndexed { i, _ ->
        i != index
    }

private val phoneKeyboard = KeyboardOptions(keyboardType = KeyboardType.Phone)

@Composable
internal fun EmergencyNumberDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var number by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sos_number)) },
        text = { EmergencyNumberField(number, onChange = { number = it }) },
        confirmButton = {
            TextButton(
                onClick = { onSave(number.trim()) },
                enabled = validEmergencyNumber(number),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun EmergencyNumberField(number: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = number,
        onValueChange = { onChange(sosNumberChars(it)) },
        supportingText = { Text(stringResource(R.string.sos_number_note)) },
        isError = !validEmergencyNumber(number),
        singleLine = true,
        keyboardOptions = phoneKeyboard,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ContactDialog(
    initial: SosEmergencyContact?,
    onSave: (SosEmergencyContact) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var number by remember { mutableStateOf(initial?.number.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.sos_contact_add else R.string.sos_contact_edit
                )
            )
        },
        text = { ContactFields(name, number, onName = { name = it }, onNumber = { number = it }) },
        confirmButton = {
            TextButton(
                onClick = { onSave(SosEmergencyContact(name.trim(), number.trim())) },
                enabled = name.isNotBlank() && validSosNumber(number),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
internal fun ContactFields(
    name: String,
    number: String,
    onName: (String) -> Unit,
    onNumber: (String) -> Unit,
) {
    val numberValid = validSosNumber(number)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { new ->
                onName(new.filterNot(Char::isISOControl).take(SosLimits.MAX_NAME_CHARS))
            },
            label = { Text(stringResource(R.string.sos_contact_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = number,
            onValueChange = { onNumber(sosNumberChars(it)) },
            label = { Text(stringResource(R.string.sos_contact_number)) },
            supportingText = {
                when {
                    number.isNotEmpty() && !numberValid ->
                        Text(stringResource(R.string.sos_number_invalid))
                    numberValid && !canReceiveSms(number) ->
                        Text(stringResource(R.string.sos_contact_no_sms))
                }
            },
            isError = number.isNotEmpty() && !numberValid,
            singleLine = true,
            keyboardOptions = phoneKeyboard,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SimDialog(
    ids: List<Int>,
    current: Int?,
    onChoose: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sos_sim)) },
        text = {
            Column(Modifier.selectableGroup()) {
                RadioRow(
                    stringResource(R.string.sos_sim_default),
                    selected = current == null,
                    onClick = { onChoose(null) },
                )
                ids.forEach { id ->
                    RadioRow(
                        stringResource(R.string.sos_sim_id, id),
                        selected = current == id,
                        onClick = { onChoose(id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
