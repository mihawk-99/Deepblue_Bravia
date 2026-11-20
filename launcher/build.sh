#!/usr/bin/env bash
# Build build/DeepBlue.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs ../android-sdk (platform 34, build-tools 35.0.0: 34's d8 crashes on JDK 21 inner classes) and a JDK.
set -euo pipefail
cd "$(dirname "$0")"
BT=../android-sdk/build-tools/35.0.0
JAR=../android-sdk/platforms/android-34/android.jar

rm -rf build && mkdir -p build/gen build/classes
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml -A assets \
  --java build/gen --min-sdk-version 31 --target-sdk-version 31 --version-code 1 --version-name 1.0 build/res.zip
javac --release 11 -Xlint:-options -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 31 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# debug key, gitignored; if it is ever lost, uninstall the app before installing a build signed with a new one
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname CN=DeepBlue
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out build/DeepBlue.apk build/aligned.apk
echo "built $(pwd)/build/DeepBlue.apk"
