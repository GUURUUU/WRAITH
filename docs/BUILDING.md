# Building WRAITH

The project has no Gradle build and no third-party dependencies: it is compiled
with the Android SDK build tools directly. That keeps the build reproducible on
a machine with nothing but the SDK installed.

## What you need

- Android SDK build-tools **35.0.0** (or newer) and `platforms/android-35`
- a JDK (11 or newer)
- a keystore; a throwaway debug key is fine

## Local build

From the project root, with `SDK` pointing at your Android SDK:

```bash
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"

rm -rf build/gen build/classes
mkdir -p build/gen build/classes

# resources and R.java
"$BT/aapt" package -f -M AndroidManifest.xml -S res -I "$JAR" \
    -J build/gen -F build/base.apk
mkdir -p build/gen/com/brycestrosoft/liber80211
mv build/gen/R.java build/gen/com/brycestrosoft/liber80211/R.java

# java
javac --release 11 -nowarn -cp "build/gen:$JAR" -d build/classes \
    src/com/brycestrosoft/liber80211/*.java

# dex
(cd build && jar cf classes.jar -C classes .)
"$BT/d8" --min-api 23 --lib "$JAR" --output build build/classes.jar

# package, align, sign
cp build/base.apk build/app.apk
jar uf build/app.apk -C build classes.dex
"$BT/zipalign" -f -p 4 build/app.apk build/aligned.apk
"$BT/apksigner" sign --ks build/debug.keystore --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out wraith.apk build/aligned.apk
```

On Windows with Git Bash, call the `.bat` wrappers (`d8.bat`, `apksigner.bat`)
and use `;` as the classpath separator.

Create the debug key once:

```bash
keytool -genkeypair -v -keystore build/debug.keystore -storetype PKCS12 \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
```

## Install and run

```bash
adb install -r wraith.apk
adb shell am start -n com.brycestrosoft.liber80211/.MainActivity
```

Grant the runtime permissions the first time, then plug the card in:

```bash
adb shell pm grant com.brycestrosoft.liber80211 android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.brycestrosoft.liber80211 android.permission.WRITE_EXTERNAL_STORAGE
```

## Checking it works

```bash
adb shell "ls -l /sdcard/wraith"
adb pull /sdcard/wraith/capture.pcap
tshark -r capture.pcap -Y "wlan.fc.type_subtype == 8"
```

A deauth flood of one frame proves the transmit path:

```bash
adb shell "printf '\005' | nc 127.0.0.1 8765"     # INFO
```

## Continuous integration

`.github/workflows/build-apk.yml` runs the same steps on a runner and uploads
`wraith.apk` as a build artifact. It needs `platforms/android-35` and
build-tools 35.0.0 to match the local build; if it drifts, the local recipe
above is the reference.

## Common build problems

| Symptom | Cause |
| --- | --- |
| `INSTALL_FAILED_DEPRECATED_SDK_VERSION` | `targetSdkVersion` below 24; the manifest sets 24 |
| `d8` rejects flags or directories | older d8; feed it `classes.jar`, not `build/classes` |
| `error: package R does not exist` | `R.java` was not moved under the package directory |
| spaces in the SDK path break `javac` | quote the classpath, or copy `android.jar` into `build/` |
| `keytool` asks for a different key algorithm | use `-genkeypair -storetype PKCS12` |
