#!/system/bin/sh
# Fixed install checks only. Never invoke vendor code, grant permissions, or change system policy.
[ "$MAGISK_VER_CODE" -ge 26000 ] || abort "Magisk 26+ is required"
[ "$API" -ge 30 ] || abort "Android 11+ is required"
case "$ARCH" in arm|arm64) ;; *) abort "Only arm/arm64 are supported" ;; esac
set_perm_recursive "$MODPATH" 0 0 0755 0644
# An update preserves a prior explicit opt-in, using the same file checks as the native loader.
# Fresh installations never create this marker. No settings, DenyList or Zygisk flags are changed.
OLD_FLAG="$NVBASE/modules/ticwatch_health_settings/notification-alerts.enabled"
if [ "$BOOTMODE" = true ] && [ -f "$OLD_FLAG" ] && [ ! -L "$OLD_FLAG" ]; then
  FLAG_UID=$(stat -c '%u' "$OLD_FLAG")
  FLAG_MODE=$(stat -c '%a' "$OLD_FLAG")
  FLAG_SIZE=$(stat -c '%s' "$OLD_FLAG")
  if [ "$FLAG_UID" = 0 ] && [ "$FLAG_SIZE" -le 16 ] && [ $((0$FLAG_MODE & 0022)) -eq 0 ]; then
    cp -f "$OLD_FLAG" "$MODPATH/notification-alerts.enabled"
    set_perm "$MODPATH/notification-alerts.enabled" 0 0 0644
  fi
fi
ui_print "Enable Zygisk and reboot to activate the module."
ui_print "The TicHealth main process must be excluded from Magisk's DenyList."
ui_print "The optional SystemUI notification patch is disabled by default; see its separate opt-in instructions."
ui_print "Disable this module in Magisk and reboot to remove the runtime extension."
