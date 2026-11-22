package dev.ticbox.companion.ui.toolbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxScreenCodecOptions
import dev.ticbox.companion.api.ToolboxScreenEncoder
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.ui.AppPreferences
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.SwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the page knows of the mirrored device's encoders. */
sealed interface ScreenEncoderRead {
    /** The encoders [declared], or the last ones while others are read. */
    val declared: List<ToolboxScreenEncoder>?
        get() = null

    /** Not connected, limit off, or the device couldn't answer: nothing to show. */
    data object None : ScreenEncoderRead

    /** The connected backend can't say, so the stream won't be limited. */
    data object Unreported : ScreenEncoderRead

    data class Reading(override val declared: List<ToolboxScreenEncoder>?) : ScreenEncoderRead

    data class Declared(override val declared: List<ToolboxScreenEncoder>) : ScreenEncoderRead
}

/**
 * Saved as it's entered; the mirror, stopped while this page is open, resumes with it. With the
 * encoder limit on, it reads what the device's encoders declare at the chosen size, once per
 * connection and size.
 */
class RemoteScreenAdvancedViewModel(
    private val preferences: AppPreferences,
    private val toolbox: ToolboxViewModel? = null,
) : ViewModel() {
    val settings = preferences.remoteScreen
    private val read = MutableStateFlow<ScreenEncoderRead>(ScreenEncoderRead.None)
    val encoders: StateFlow<ScreenEncoderRead> = read.asStateFlow()

    private data class Query(val target: ToolboxTarget?, val reported: Boolean, val maxSize: Int)

    init {
        toolbox?.let { toolbox ->
            viewModelScope.launch {
                var asked: ToolboxTarget? = null
                combine(toolbox.access, settings) { access, settings ->
                        Query(
                            access?.target?.takeIf { access.connected && settings.limitToEncoder },
                            access?.has(ToolboxCapability.SCREEN_ENCODER_LIMITS) == true,
                            settings.maxSize.coerceIn(SCREEN_SIZES),
                        )
                    }
                    .distinctUntilChanged()
                    .collectLatest { query ->
                        val target = query.target
                        read.value =
                            when {
                                target == null -> ScreenEncoderRead.None
                                !query.reported -> ScreenEncoderRead.Unreported
                                else ->
                                    ask(toolbox, target, query.maxSize, settle = target == asked)
                            }
                        asked = target
                    }
            }
        }
    }

    private suspend fun ask(
        toolbox: ToolboxViewModel,
        target: ToolboxTarget,
        maxSize: Int,
        settle: Boolean,
    ): ScreenEncoderRead {
        toolbox.screenEncoders.value?.get(target)?.get(maxSize)?.let {
            return ScreenEncoderRead.Declared(it)
        }
        read.value = ScreenEncoderRead.Reading(read.value.declared)
        // A new size may still be being typed, and each read starts a process on the device.
        if (settle) delay(SIZE_SETTLE_MILLIS)
        return try {
            ScreenEncoderRead.Declared(toolbox.readScreenEncoders(target, maxSize))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ScreenEncoderRead.None
        }
    }

    fun update(change: RemoteScreenSettings.() -> RemoteScreenSettings) =
        preferences.setRemoteScreen(preferences.remoteScreen.value.change())

    private companion object {
        const val SIZE_SETTLE_MILLIS = 600L
    }
}

@Composable
fun RemoteScreenAdvancedScreen(graph: ViewModelStoreOwner, onBack: () -> Unit) {
    val toolbox = toolboxViewModel(graph)
    val access by toolbox.access.collectAsStateWithLifecycle()
    val udpAvailable = access?.connected == true && access?.selected == ToolboxBackendKind.ADB
    val viewModel = companionViewModel { RemoteScreenAdvancedViewModel(it.preferences, toolbox) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val read by viewModel.encoders.collectAsStateWithLifecycle()
    val fit = settings.encoderFit(read.declared)
    PageScaffold(stringResource(R.string.screen_advanced_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            ListGroup(title = stringResource(R.string.screen_group_picture)) {
                SwitchRow(
                    stringResource(R.string.screen_limit_to_encoder),
                    settings.limitToEncoder,
                    onCheckedChange = { on -> viewModel.update { copy(limitToEncoder = on) } },
                    summary = encoderSummary(settings, read, fit),
                )
                GroupFields {
                    NumberField(
                        stringResource(R.string.screen_resolution),
                        saved = settings.maxSize.toString(),
                        hint =
                            stringResource(
                                R.string.screen_resolution_hint,
                                SCREEN_SIZES.first.toString(),
                                SCREEN_SIZES.last.toString(),
                            ),
                        suffix = stringResource(R.string.screen_resolution_unit),
                        parse = { parseWithin(it, SCREEN_SIZES) },
                        onValue = { size -> viewModel.update { copy(maxSize = size) } },
                    )
                    NumberField(
                        stringResource(R.string.screen_fps),
                        saved = settings.maxFps.toString(),
                        hint = limitedHint(settings.maxFps, fit.maxFps, SCREEN_FPS, Int::toString),
                        parse = { parseWithin(it, SCREEN_FPS) },
                        onValue = { fps -> viewModel.update { copy(maxFps = fps) } },
                    )
                    NumberField(
                        stringResource(R.string.screen_bitrate),
                        saved = bitRateInMbps(settings.bitRate),
                        hint =
                            limitedHint(
                                settings.bitRate,
                                fit.maxBitRate?.coerceAtLeast(SCREEN_BIT_RATES.first),
                                SCREEN_BIT_RATES,
                                ::bitRateInMbps,
                            ),
                        suffix = stringResource(R.string.screen_bitrate_unit),
                        fractions = true,
                        imeAction = ImeAction.Done,
                        parse = ::parseBitRate,
                        onValue = { rate -> viewModel.update { copy(bitRate = rate) } },
                    )
                }
            }
            ListGroup(title = stringResource(R.string.screen_group_encoding)) {
                // Shown as the stream would be; the saved choice comes back with the limit off.
                SwitchRow(
                    stringResource(R.string.screen_h265),
                    settings.h265 && fit.h265Available,
                    onCheckedChange = { on -> viewModel.update { copy(h265 = on) } },
                    enabled = fit.h265Available,
                    summary =
                        if (fit.h265Available) null
                        else stringResource(R.string.screen_h265_unavailable),
                )
                GroupFields {
                    CodecOptionsField(
                        saved = settings.codecOptions,
                        supported =
                            access?.connected != true ||
                                access?.has(ToolboxCapability.SCREEN_CODEC_OPTIONS) == true,
                        onValue = { options -> viewModel.update { copy(codecOptions = options) } },
                    )
                }
            }
            // Encryption only applies to UDP, so the two sit together.
            ListGroup(title = stringResource(R.string.screen_group_transport)) {
                SwitchRow(
                    stringResource(R.string.screen_udp),
                    settings.udp && udpAvailable,
                    onCheckedChange = { on -> viewModel.update { copy(udp = on) } },
                    enabled = udpAvailable,
                    summary =
                        stringResource(
                            if (udpAvailable) R.string.screen_udp_summary
                            else R.string.screen_udp_wireless_only
                        ),
                )
                SwitchRow(
                    stringResource(R.string.screen_udp_encryption),
                    settings.udpEncryption,
                    onCheckedChange = { on -> viewModel.update { copy(udpEncryption = on) } },
                    enabled = udpAvailable && settings.udp,
                    summary =
                        stringResource(
                            when {
                                !udpAvailable || !settings.udp ->
                                    R.string.screen_udp_encryption_enable_udp
                                settings.udpEncryption -> R.string.screen_udp_encryption_summary
                                else -> R.string.screen_udp_encryption_off_summary
                            }
                        ),
                )
            }
            ListGroup(title = stringResource(R.string.screen_group_playback)) {
                SwitchRow(
                    stringResource(R.string.screen_audio),
                    settings.audio,
                    onCheckedChange = { on -> viewModel.update { copy(audio = on) } },
                )
                SwitchRow(
                    stringResource(R.string.screen_smooth_playback),
                    settings.smoothPlayback,
                    onCheckedChange = { on -> viewModel.update { copy(smoothPlayback = on) } },
                    summary = stringResource(R.string.screen_smooth_playback_summary),
                )
            }
            ListGroup(title = stringResource(R.string.screen_group_diagnostics)) {
                SwitchRow(
                    stringResource(R.string.screen_frame_stats),
                    settings.frameStats,
                    onCheckedChange = { on -> viewModel.update { copy(frameStats = on) } },
                    summary = stringResource(R.string.screen_frame_stats_summary),
                )
            }
        }
    }
}

/** Text fields in a group, lined up with the rows around them. */
@Composable
private fun GroupFields(content: @Composable ColumnScope.() -> Unit) =
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )

@Composable
private fun encoderSummary(
    settings: RemoteScreenSettings,
    read: ScreenEncoderRead,
    fit: ScreenEncoderFit,
): String {
    val encoder = fit.encoder
    val width = encoder?.width
    val height = encoder?.height
    val maxFps = encoder?.maxFps
    val maxBitRate = encoder?.maxBitRate
    return when {
        !settings.limitToEncoder -> stringResource(R.string.screen_limit_to_encoder_summary)
        read is ScreenEncoderRead.Unreported ->
            stringResource(R.string.screen_limit_to_encoder_unreported)
        read is ScreenEncoderRead.Reading ->
            stringResource(R.string.screen_limit_to_encoder_reading)
        encoder != null &&
            width != null &&
            height != null &&
            maxFps != null &&
            maxBitRate != null ->
            stringResource(
                R.string.screen_limit_to_encoder_declared,
                encoder.name ?: encoder.codec.name,
                maxFps,
                bitRateInMbps(maxBitRate),
                width,
                height,
            )
        else -> stringResource(R.string.screen_limit_to_encoder_summary)
    }
}

/** The usual range, or the encoder's, saying when the saved value is more than it allows. */
@Composable
private fun limitedHint(saved: Int, limit: Int?, range: IntRange, show: (Int) -> String): String =
    when {
        limit == null ->
            stringResource(R.string.screen_value_range, show(range.first), show(range.last))
        saved > limit -> stringResource(R.string.screen_value_encoder_capped, show(limit))
        else -> stringResource(R.string.screen_value_encoder_range, show(range.first), show(limit))
    }

/**
 * Saved once it follows scrcpy's option rule; anything else stays on screen, marked, until fixed.
 */
@Composable
private fun CodecOptionsField(saved: String, supported: Boolean, onValue: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(saved) }
    val valid = ToolboxScreenCodecOptions.normalize(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.take(ToolboxScreenCodecOptions.MAX_LENGTH)
            ToolboxScreenCodecOptions.normalize(text)?.let(onValue)
        },
        label = { Text(stringResource(R.string.screen_codec_options)) },
        supportingText = {
            Text(
                stringResource(
                    when {
                        !valid -> R.string.screen_codec_options_invalid
                        !supported -> R.string.screen_codec_options_unsupported
                        else -> R.string.screen_codec_options_hint
                    }
                )
            )
        },
        isError = !valid,
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Only a value in range is saved; anything else stays on screen, marked, until it's corrected. */
@Composable
private fun <T : Any> NumberField(
    label: String,
    saved: String,
    hint: String,
    parse: (String) -> T?,
    onValue: (T) -> Unit,
    suffix: String? = null,
    fractions: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    var text by rememberSaveable { mutableStateOf(saved) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() || fractions && (it == '.' || it == ',') }.take(12)
            parse(text)?.let(onValue)
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        supportingText = { Text(hint) },
        isError = parse(text) == null,
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (fractions) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = imeAction,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}
