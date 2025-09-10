setup.sh
#!/bin/bash
set -e

SDK_DIR="/tmp/android_sdk_jules"

# Clean up previous attempts and create a fresh directory
rm -rf $SDK_DIR
mkdir -p $SDK_DIR

# 1. Download and set up Android command-line tools
cd /tmp # Use a neutral directory for download and unzip
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -o -q commandlinetools-linux-11076708_latest.zip
rm commandlinetools-linux-11076708_latest.zip

# Now, move the extracted 'cmdline-tools' directory to its final destination
mkdir -p $SDK_DIR/cmdline-tools
mv cmdline-tools $SDK_DIR/cmdline-tools/latest

# Return to the project directory
cd /app

# 2. Set environment variables
export ANDROID_HOME=$SDK_DIR
export ANDROID_SDK_ROOT=$SDK_DIR
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 3. Accept licenses and install SDK packages
yes | sdkmanager --licenses --sdk_root=$ANDROID_HOME > /dev/null
sdkmanager "platforms;android-34" "build-tools;34.0.0" --sdk_root=$ANDROID_HOME > /dev/null

echo "Android SDK setup complete in $SDK_DIR"

# 4. Create local.properties to point to the temp SDK
echo "sdk.dir=$SDK_DIR" > local.properties

# 5. Build the project
./gradlew -Dorg.gradle.jvmargs=-Xmx4g clean build
