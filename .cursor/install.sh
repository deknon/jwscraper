#!/usr/bin/env bash
# Idempotent Cloud Agent setup for the saha Video Downloader Android app.
# Installs JDK 17 + the Android SDK, pins Gradle's JVM, and warms the build cache.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA17_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "==> Ensuring JDK 17 is installed"
if [ ! -x "$JAVA17_HOME/bin/java" ]; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless
fi
export JAVA_HOME="$JAVA17_HOME"

echo "==> Ensuring Android command-line tools are installed"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdtools.zip" "$CMDLINE_TOOLS_URL"
  unzip -q "$tmp/cmdtools.zip" -d "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

echo "==> Accepting licenses and installing SDK packages"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null

echo "==> Pinning Gradle to JDK 17"
mkdir -p "$HOME/.gradle"
GRADLE_PROPS="$HOME/.gradle/gradle.properties"
touch "$GRADLE_PROPS"
if grep -q '^org.gradle.java.home=' "$GRADLE_PROPS"; then
  sed -i "s#^org.gradle.java.home=.*#org.gradle.java.home=$JAVA17_HOME#" "$GRADLE_PROPS"
else
  echo "org.gradle.java.home=$JAVA17_HOME" >> "$GRADLE_PROPS"
fi

echo "==> Writing local.properties (Android SDK location)"
echo "sdk.dir=$ANDROID_HOME" > "$REPO_DIR/local.properties"

echo "==> Persisting toolchain env for interactive shells"
sudo tee /etc/profile.d/android-sdk.sh >/dev/null <<EOF
export JAVA_HOME=$JAVA17_HOME
export ANDROID_HOME=$ANDROID_HOME
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=\$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin
EOF

echo "==> Warming Gradle cache and verifying the build"
cd "$REPO_DIR"
./gradlew --no-daemon assembleDebug

echo "==> Environment setup complete"
