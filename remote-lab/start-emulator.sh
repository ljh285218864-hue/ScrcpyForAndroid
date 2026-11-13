#!/usr/bin/env bash
set -euo pipefail

AVD_NAME="${AVD_NAME:-phone}"
export DISPLAY=:0
export ANDROID_AVD_HOME=/data/avd
mkdir -p "$ANDROID_AVD_HOME" /data/android_home

Xvfb :0 -screen 0 1200x1120x24 -nolisten tcp +extension GLX &
sleep 1
openbox --display :0 >/tmp/openbox.log 2>&1 &
x11vnc -display :0 -rfbport 5900 -localhost -forever -shared -nopw -noxdamage -viewonly >/tmp/x11vnc.log 2>&1 &
websockify --web=/usr/share/novnc 6080 127.0.0.1:5900 >/tmp/websockify.log 2>&1 &

if [[ ! -f "$ANDROID_AVD_HOME/${AVD_NAME}.ini" ]]; then
  echo "Creating Android 15 AVD: ${AVD_NAME}"
  echo no | avdmanager create avd --force --name "$AVD_NAME" \
    --package 'system-images;android-35;google_apis;x86_64' \
    --path "$ANDROID_AVD_HOME/${AVD_NAME}.avd" --device pixel_5
  {
    echo 'hw.lcd.width=480'
    echo 'hw.lcd.height=960'
    echo 'hw.lcd.density=240'
    echo 'hw.ramSize=2048'
    echo 'hw.cpu.ncore=2'
    echo 'showDeviceFrame=no'
  } >> "$ANDROID_AVD_HOME/${AVD_NAME}.avd/config.ini"
fi

# Private Docker-network bridge. No public ADB port is published.
if [[ "${ADB_BRIDGE:-false}" == "true" ]]; then
  socat TCP-LISTEN:5558,bind=0.0.0.0,reuseaddr,fork TCP:127.0.0.1:5555 >/tmp/adb-bridge.log 2>&1 &
fi

exec emulator -avd "$AVD_NAME" -port 5554 -gpu swiftshader_indirect \
  -noaudio -no-snapshot -no-boot-anim -camera-back none -accel on
