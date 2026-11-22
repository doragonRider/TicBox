package dev.ticbox.companion.demo

import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.SosAssistancePhase
import dev.ticbox.companion.api.SosAssistanceState
import dev.ticbox.companion.api.SosClient
import dev.ticbox.companion.api.SosCommandResult
import dev.ticbox.companion.api.SosCommandStatus
import dev.ticbox.companion.api.SosEmergencyContact
import dev.ticbox.companion.api.SosFallDetectionMode
import dev.ticbox.companion.api.SosPhase
import dev.ticbox.companion.api.SosPhoneAssistanceClient
import dev.ticbox.companion.api.SosPhoneAssistanceConfig
import dev.ticbox.companion.api.SosPhoneCapabilities
import dev.ticbox.companion.api.SosState
import dev.ticbox.companion.api.SosWatchSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Settings kept in memory. It holds no phone number of anyone's, and never sends a text. */
internal class DemoSos(
    peer: PeerAddress,
    private val delayMillis: Long,
    private val now: () -> Long,
    private var sampleName: String,
) : SosClient {
    private val mutableState =
        MutableStateFlow(
            SosState(
                sessionId = 1,
                peer = peer,
                phase = SosPhase.CONNECTED,
                settings =
                    SosWatchSettings(
                        updatedAtMillis = now(),
                        buttonTriggerEnabled = true,
                        emergencyNumber = "112",
                        fallDetectionValue = SosFallDetectionMode.OFF.wireValue,
                        shareLocation = false,
                        contacts = listOf(SosEmergencyContact(sampleName, SAMPLE_NUMBER)),
                        contactsPayload = "",
                    ),
                settingsCurrent = true,
            )
        )
    override val state = mutableState.asStateFlow()

    override val phoneAssistance: SosPhoneAssistanceClient =
        object : SosPhoneAssistanceClient {
            override val state =
                MutableStateFlow(
                    SosAssistanceState(
                        capabilities =
                            SosPhoneCapabilities(
                                smsSupported = true,
                                smsPermissionGranted = true,
                                foregroundLocationPermissionGranted = true,
                                backgroundLocationPermissionGranted = true,
                                notificationPermissionGranted = true,
                                usableSmsSubscription = true,
                            )
                    )
                )

            override suspend fun refreshCapabilities() = Unit

            override suspend fun configure(config: SosPhoneAssistanceConfig) {
                delay(delayMillis)
                val phase =
                    if (config.enabled) SosAssistancePhase.READY else SosAssistancePhase.DISABLED
                state.update { it.copy(config = config, phase = phase) }
            }

            override suspend fun stop() = Unit

            override fun close() = Unit
        }

    override suspend fun refresh() = change { it }

    override suspend fun setEmergencyNumber(number: String) = change {
        it.copy(emergencyNumber = number)
    }

    override suspend fun setContacts(contacts: List<SosEmergencyContact>) = change {
        it.copy(contacts = contacts)
    }

    override suspend fun setButtonTrigger(enabled: Boolean) = change {
        it.copy(buttonTriggerEnabled = enabled)
    }

    override suspend fun setShareLocation(enabled: Boolean) = change {
        it.copy(shareLocation = enabled)
    }

    override suspend fun setFallDetection(mode: SosFallDetectionMode) = change {
        it.copy(fallDetectionValue = mode.wireValue)
    }

    /** The sample contact follows the app's language, unless someone has edited it. */
    fun renameSample(name: String) {
        val old = sampleName
        sampleName = name
        mutableState.update { state ->
            val contacts =
                state.settings?.contacts?.map {
                    if (it.name == old && it.number == SAMPLE_NUMBER) it.copy(name = name) else it
                }
            state.copy(settings = state.settings?.copy(contacts = contacts))
        }
    }

    override fun close() = Unit

    private suspend fun change(edit: (SosWatchSettings) -> SosWatchSettings): SosCommandResult {
        delay(delayMillis)
        mutableState.update { it.copy(settings = it.settings?.let(edit)) }
        return SosCommandResult(SosCommandStatus.CONFIRMED)
    }

    private companion object {
        /** From the range set aside for fiction. */
        const val SAMPLE_NUMBER = "+1 202 555 0100"
    }
}
