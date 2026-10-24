# TV Home - Apple TV style launcher

Plain Android (Java, no dependencies, no blur/video/polling). Pitch black, dock band, glass highlight,
glow sampled from the focused dock app, Inputs/Wi-Fi/Settings pills, greeting + clock + date.

## Build (GitHub, no local tools)
1. Upload this folder to a GitHub repository (Add file > Upload files).
2. Make sure `.github/workflows/build.yml` exists (Add file > Create new file, type the path, paste the contents).
3. Actions tab > "Build APK" > Run workflow. When it is green, download the `tvhome-apk` artifact and unzip it.

## Install
    sh install.sh app-debug.apk
The stock launcher is not disabled by this script. Disable it only after Home works:
    adb shell pm disable-user --user 0 com.google.android.tvlauncher
Undo:
    adb shell pm enable com.google.android.tvlauncher

## Change the dock
Edit `app/src/main/res/values/config.xml`. Entries are package names, left to right, and
`special:inputs` for the HDMI/Live TV picker. 4 to 7 entries; the count is also the grid column count.
Rebuild and reinstall.

## Wallpaper
This version is pitch black by design and has no wallpaper support.

## Re-enable a disabled app
    sh restore.sh com.example.package      (single app)
    sh restore.sh                          (everything logged by disable.sh)

## Remove the launcher
    adb shell pm enable com.google.android.tvlauncher
    adb shell pm uninstall dev.tvhome.launcher
