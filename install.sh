#!/bin/sh
# Usage: sh install.sh [path/to/app-debug.apk]
# Optional: TV=192.168.1.15:5555 sh install.sh
# Installs the launcher and makes it the default Home. It does NOT disable the stock launcher.
APK="${1:-app-debug.apk}"
ADB="adb"
[ -n "$TV" ] && ADB="adb -s $TV"

$ADB install -r "$APK" || { echo "Install failed - paste the error to Claude."; exit 1; }
$ADB shell pm grant dev.tvhome.launcher android.permission.ACCESS_FINE_LOCATION || true
$ADB shell cmd package set-home-activity dev.tvhome.launcher/.HomeActivity
$ADB shell input keyevent KEYCODE_HOME
sleep 3
echo "--- focused app (should mention dev.tvhome.launcher) ---"
$ADB shell dumpsys activity activities | grep -E "mFocusedApp|topResumedActivity"
