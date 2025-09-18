#!/bin/bash
set -e
SDK_DIR="/tmp/android_sdk_jules"
export ANDROID_HOME=$SDK_DIR
export ANDROID_SDK_ROOT=$SDK_DIR
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
./gradlew testDebugUnitTest
