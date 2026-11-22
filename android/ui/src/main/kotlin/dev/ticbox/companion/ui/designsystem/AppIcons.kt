package dev.ticbox.companion.ui.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import dev.ticbox.companion.ui.R

/**
 * Material Symbols (Outlined) imported as vector drawables by tools/import-material-symbols.mjs.
 * Add an icon there, rerun the script, then reference it here. Paste and Send are the older
 * Material Icons drawings: the symbols are shorter and thinner, which shows at button size.
 */
object AppIcons {
    val Account = R.drawable.ic_account_circle
    val Add = R.drawable.ic_add
    val Breath = R.drawable.ic_air
    val InstallApk = R.drawable.ic_android
    val Alarm = R.drawable.ic_alarm
    val Apps = R.drawable.ic_apps
    val Archive = R.drawable.ic_archive
    val Back = R.drawable.ic_arrow_back
    val Dropdown = R.drawable.ic_arrow_drop_down
    val ArrowUp = R.drawable.ic_arrow_upward
    val KeyboardTab = R.drawable.ic_keyboard_tab
    val Suggest = R.drawable.ic_auto_awesome
    val Backspace = R.drawable.ic_backspace
    val Battery = R.drawable.ic_battery_full
    val Sleep = R.drawable.ic_bedtime
    val SleepFilled = R.drawable.ic_bedtime_filled
    val Block = R.drawable.ic_block
    val Bluetooth = R.drawable.ic_bluetooth
    val BluetoothOff = R.drawable.ic_bluetooth_disabled
    val Energy = R.drawable.ic_bolt
    val Diagnostics = R.drawable.ic_bug_report
    val Calendar = R.drawable.ic_calendar_today
    val Call = R.drawable.ic_call
    val SplitApk = R.drawable.ic_call_split
    val Screen = R.drawable.ic_cast
    val Check = R.drawable.ic_check
    val CheckCircle = R.drawable.ic_check_circle_filled
    val ChevronLeft = R.drawable.ic_chevron_left
    val ChevronRight = R.drawable.ic_chevron_right
    val Close = R.drawable.ic_close
    val Cloud = R.drawable.ic_cloud
    val Terminal = R.drawable.ic_code
    val Advanced = R.drawable.ic_construction
    val Copy = R.drawable.ic_content_copy
    val Paste = R.drawable.ic_content_paste
    val NewFolder = R.drawable.ic_create_new_folder
    val Database = R.drawable.ic_database
    val Delete = R.drawable.ic_delete
    val SkinTemperature = R.drawable.ic_device_thermostat
    val Cycling = R.drawable.ic_directions_bike
    val Running = R.drawable.ic_directions_run
    val Walking = R.drawable.ic_directions_walk
    val DoNotDisturb = R.drawable.ic_do_not_disturb_on
    val Download = R.drawable.ic_download
    val File = R.drawable.ic_draft
    val Edit = R.drawable.ic_edit
    val Error = R.drawable.ic_error
    val Extension = R.drawable.ic_extension
    val ExpandMore = R.drawable.ic_expand_more
    val Heart = R.drawable.ic_favorite
    val HeartFilled = R.drawable.ic_favorite_filled
    val Filter = R.drawable.ic_filter_list
    val Strength = R.drawable.ic_fitness_center
    val Goal = R.drawable.ic_flag
    val Folder = R.drawable.ic_folder
    val Fullscreen = R.drawable.ic_fullscreen
    val FullscreenExit = R.drawable.ic_fullscreen_exit
    val Hearing = R.drawable.ic_hearing
    val History = R.drawable.ic_history
    val Home = R.drawable.ic_home
    val Info = R.drawable.ic_info
    val Key = R.drawable.ic_key
    val Keyboard = R.drawable.ic_keyboard
    val Enter = R.drawable.ic_keyboard_return
    val Language = R.drawable.ic_language
    val Link = R.drawable.ic_link
    val Disconnect = R.drawable.ic_link_off
    val Calories = R.drawable.ic_local_fire_department
    val Location = R.drawable.ic_location_on
    val Lock = R.drawable.ic_lock
    val Login = R.drawable.ic_login
    val Logout = R.drawable.ic_logout
    val HeartMonitor = R.drawable.ic_monitor_heart
    val Mood = R.drawable.ic_mood
    val More = R.drawable.ic_more_vert
    val MoreHoriz = R.drawable.ic_more_horiz
    val Music = R.drawable.ic_music_note
    val Notifications = R.drawable.ic_notifications
    val OpenExternal = R.drawable.ic_open_in_new
    val Palette = R.drawable.ic_palette
    val Paused = R.drawable.ic_pause_circle
    val Person = R.drawable.ic_person
    val FindDevices = R.drawable.ic_phonelink_ring
    val Camera = R.drawable.ic_photo_camera
    val Play = R.drawable.ic_play_arrow
    val Playing = R.drawable.ic_play_circle
    val Swimming = R.drawable.ic_pool
    val Power = R.drawable.ic_power_settings_new
    val Refresh = R.drawable.ic_refresh
    val Restart = R.drawable.ic_restart_alt
    val Schedule = R.drawable.ic_schedule
    val Demo = R.drawable.ic_science
    val ScreenShare = R.drawable.ic_screen_share
    val Search = R.drawable.ic_search
    val Stress = R.drawable.ic_self_improvement
    val Send = R.drawable.ic_send
    val Settings = R.drawable.ic_settings
    val SettingsFilled = R.drawable.ic_settings_filled
    val Privacy = R.drawable.ic_shield
    val Phone = R.drawable.ic_smartphone
    val SimCard = R.drawable.ic_sim_card
    val Sms = R.drawable.ic_sms
    val Sos = R.drawable.ic_sos
    val Sort = R.drawable.ic_sort
    val SortByName = R.drawable.ic_sort_by_alpha
    val Speed = R.drawable.ic_speed
    val Oxygen = R.drawable.ic_spo2
    val Sports = R.drawable.ic_sports
    val Stairs = R.drawable.ic_stairs
    val Steps = R.drawable.ic_steps
    val Stop = R.drawable.ic_stop
    val Storage = R.drawable.ic_storage
    val Distance = R.drawable.ic_straighten
    val Sync = R.drawable.ic_sync
    val Temperature = R.drawable.ic_thermometer
    val Timer = R.drawable.ic_timer
    val TouchControl = R.drawable.ic_touch_app
    val Tune = R.drawable.ic_tune
    val Upload = R.drawable.ic_upload
    val Vibration = R.drawable.ic_vibration
    val Visible = R.drawable.ic_visibility
    val Hidden = R.drawable.ic_visibility_off
    val VolumeDown = R.drawable.ic_volume_down
    val VolumeOff = R.drawable.ic_volume_off
    val VolumeUp = R.drawable.ic_volume_up
    val Warning = R.drawable.ic_warning
    val Watch = R.drawable.ic_watch
    val WatchFilled = R.drawable.ic_watch_filled
    val WatchFaces = R.drawable.ic_watch_screentime
    val Drop = R.drawable.ic_water_drop
    val Wifi = R.drawable.ic_wifi
}

@Composable
fun AppIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon), contentDescription, modifier, tint)
}
