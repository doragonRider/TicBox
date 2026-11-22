package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.uiText
import kotlinx.coroutines.TimeoutCancellationException

/** What a toolbox failure means for the user. An unknown code is still worth showing as it is. */
internal fun toolboxMessage(code: String): UiText =
    when (code) {
        "device_changed" -> uiText(R.string.toolbox_device_changed)
        "device_identity_required" -> uiText(R.string.toolbox_identity_required)
        "invalid_file_name" -> uiText(R.string.toolbox_invalid_file_name)
        "transport_host_protected" -> uiText(R.string.toolbox_host_protected)
        "file_result_unknown",
        "operation_cancelled" -> uiText(R.string.toolbox_file_result_unknown)
        "local_network_permission_required" -> uiText(R.string.toolbox_error_local_network)
        "adb_endpoint_required",
        "adb_connect_failed",
        "adb_shell_unavailable",
        "connection_failed" -> uiText(R.string.toolbox_error_adb_connect)
        "adb_pairing_required" -> uiText(R.string.toolbox_error_adb_pairing_required)
        "adb_pair_failed" -> uiText(R.string.toolbox_error_adb_pair)
        "adb_pairing_timeout",
        "timeout",
        "monitoring_timeout",
        "screen_control_backlog" -> uiText(R.string.toolbox_error_timeout)
        "agent_deploy_integrity" -> uiText(R.string.toolbox_error_deploy)
        "agent_version_mismatch",
        "terminal_protocol_mismatch",
        "monitoring_bridge_update_required" -> uiText(R.string.toolbox_error_version)
        "terminal_unavailable" -> uiText(R.string.toolbox_error_terminal_unavailable)
        "terminal_start_failed",
        "terminal_start_timeout" -> uiText(R.string.toolbox_error_terminal_start)
        "terminal_closed" -> uiText(R.string.terminal_disconnected)
        "bridge_update_needs_adb" -> uiText(R.string.toolbox_error_bridge_too_old)
        "bridge_update_interrupted" -> uiText(R.string.toolbox_error_update_interrupted)
        "phone_app_outdated" -> uiText(R.string.toolbox_error_phone_older)
        "companion_link_required" -> uiText(R.string.error_not_connected)
        "bluetooth_permission_required",
        "bluetooth_unavailable",
        "bluetooth_disabled",
        "bluetooth_bond_required" -> uiText(R.string.toolbox_error_bluetooth)
        "root_required",
        "monitoring_root_required" -> uiText(R.string.toolbox_error_root)
        "bridge_unavailable",
        "bridge_not_connected",
        "bridge_auth_failed",
        "bridge_connect_timeout" -> uiText(R.string.toolbox_error_bridge)
        "file_changed" -> uiText(R.string.toolbox_file_changed)
        "file_resume_mismatch",
        "file_checksum_mismatch" -> uiText(R.string.toolbox_file_resume_mismatch)
        "file_export_failed" -> uiText(R.string.toolbox_file_export_failed)
        "transfer_storage_unavailable",
        "transfer_checkpoint_invalid" -> uiText(R.string.toolbox_transfer_storage)
        "file_resume_unavailable" -> uiText(R.string.toolbox_error_version)
        "permission_denied" -> uiText(R.string.toolbox_error_denied)
        "not_connected",
        "adb_not_connected",
        "connection_replaced",
        "agent_not_deployed",
        "screen_not_deployed" -> uiText(R.string.toolbox_error_disconnected)
        "source_short_read",
        "source_empty_read",
        "source_size_mismatch" -> uiText(R.string.toolbox_error_source)
        "toolbox_unavailable",
        "dnd_sync_unavailable" -> uiText(R.string.toolbox_error_unavailable)
        "not_found" -> uiText(R.string.toolbox_error_not_found)
        "already_exists" -> uiText(R.string.toolbox_error_exists)
        "connection_closed" -> uiText(R.string.toolbox_error_disconnected)
        "invalid_request" -> uiText(R.string.error_invalid_input)
        "screen_decoder_failed" -> uiText(R.string.toolbox_error_decoder)
        "screen_encoder_unavailable" -> uiText(R.string.toolbox_error_encoder)
        "screen_audio_failed" -> uiText(R.string.toolbox_error_audio)
        "screen_udp_unavailable" -> uiText(R.string.screen_udp_wireless_only)
        "screen_udp_encryption_mode_unavailable" ->
            uiText(R.string.screen_udp_encryption_mode_unavailable)
        "screen_udp_tls_required" -> uiText(R.string.screen_udp_tls_required)
        "screen_udp_frame_too_large" -> uiText(R.string.screen_udp_frame_too_large)
        "screen_udp_recovery_failed" -> uiText(R.string.screen_udp_recovery_failed)
        "screen_udp_connect_failed" -> uiText(R.string.screen_udp_connect_failed)
        "screen_video_no_frames" -> uiText(R.string.screen_video_no_frames)
        "screen_udp_disconnected" -> uiText(R.string.screen_udp_disconnected)
        "screen_udp_protocol_mismatch",
        "screen_udp_stream_failed" -> uiText(R.string.screen_udp_stream_failed)
        "bridge_bundle_missing" -> uiText(R.string.toolbox_error_bundle)
        "package_encrypted" -> uiText(R.string.toolbox_error_package_encrypted)
        "package_wrong_abi" -> uiText(R.string.toolbox_error_package_wrong_abi)
        "package_unreadable" -> uiText(R.string.toolbox_error_package_unreadable)
        "package_not_recognised" -> uiText(R.string.toolbox_error_package_not_recognised)
        "package_many_apps" -> uiText(R.string.toolbox_error_package_many_apps)
        "package_bad_path" -> uiText(R.string.toolbox_error_package_bad_path)
        "package_incompatible" -> uiText(R.string.toolbox_error_package_incompatible)
        "package_watch_unknown" -> uiText(R.string.toolbox_error_package_watch_unknown)
        "package_no_space" -> uiText(R.string.toolbox_error_package_no_space)
        "file_size_mismatch" -> uiText(R.string.toolbox_error_package_incomplete)
        else -> uiText(R.string.toolbox_error_code, code)
    }

/** The backend's timeouts are cancellations, which a command would swallow without a word. */
internal suspend fun <T> reportingTimeouts(block: suspend () -> T): T =
    try {
        block()
    } catch (timeout: TimeoutCancellationException) {
        throw ToolboxException("timeout", timeout)
    }
