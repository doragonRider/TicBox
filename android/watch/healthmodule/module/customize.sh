#!/system/bin/sh
# Fixed install checks only. Never invoke vendor code, grant permissions, or change system policy.
[ "$MAGISK_VER_CODE" -ge 26000 ] || abort "Magisk 26+ is required"
[ "$API" -ge 30 ] || abort "Android 11+ is required"
case "$ARCH" in arm|arm64) ;; *) abort "Only arm/arm64 are supported" ;; esac
set_perm_recursive "$MODPATH" 0 0 0755 0644
# The notification sound and vibration control is on unless it was turned off, which leaves this marker. An update keeps
# it: Magisk replaces the whole directory at the next boot. Anything under that name counts, as the native loader has it.
# No settings, DenyList or Zygisk flags are changed.
OLD_OFF="$NVBASE/modules/ticwatch_health_settings/notification-alerts.disabled"
if [ "$BOOTMODE" = true ] && { [ -e "$OLD_OFF" ] || [ -L "$OLD_OFF" ]; }; then
  touch "$MODPATH/notification-alerts.disabled" && set_perm "$MODPATH/notification-alerts.disabled" 0 0 0600
  ui_print "- Notification sound and vibration control stays off"
else
  ui_print "- Notification sound and vibration control is on"
fi
ui_print "Enable Zygisk and reboot to activate the module."
ui_print "The TicHealth main process must be excluded from Magisk's DenyList."
ui_print "Turn the notification control off on the TicBox module page, or create notification-alerts.disabled in this module's directory."
ui_print "Disable this module in Magisk and reboot to remove the runtime extension."
