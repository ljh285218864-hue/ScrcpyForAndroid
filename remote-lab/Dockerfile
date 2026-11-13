FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_AVD_HOME=/data/avd \
    DISPLAY=:0 \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip openjdk-17-jre-headless \
    xvfb x11vnc openbox novnc websockify xdotool x11-utils \
    libgl1 libglu1-mesa libpulse0 libasound2t64 libnss3 libdbus-1-3 \
    libx11-xcb1 libxkbcommon-x11-0 libxcb-cursor0 libxcb-xinerama0 \
    libxcb-shape0 libxcb-render-util0 libxcb-randr0 libxcb-xfixes0 \
    libxcursor1 libxcomposite1 libxi6 libxrender1 libxdamage1 \
    libxss1 libxtst6 libgtk-3-0 socat procps \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/android-sdk/cmdline-tools /tmp/android-tools \
 && curl -fL --retry 3 -o /tmp/commandlinetools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
 && unzip -q /tmp/commandlinetools.zip -d /tmp/android-tools \
 && mv /tmp/android-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
 && rm -rf /tmp/android-tools /tmp/commandlinetools.zip \
 && yes | sdkmanager --sdk_root=/opt/android-sdk --licenses >/dev/null || true

RUN sdkmanager --sdk_root=/opt/android-sdk --install \
      'platform-tools' 'emulator' 'platforms;android-35' \
      'system-images;android-35;google_apis;x86_64'

COPY start-emulator.sh /usr/local/bin/start-emulator.sh
RUN chmod +x /usr/local/bin/start-emulator.sh && mkdir -p /data/avd

EXPOSE 6080 5558
ENTRYPOINT ["/usr/local/bin/start-emulator.sh"]
