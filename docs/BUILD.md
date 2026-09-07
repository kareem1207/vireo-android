# Building Vireo without Android Studio

Everything here runs from a terminal. You need **JDK 17+** and about **4 GB** of disk for
the Android SDK + NDK.

Reference environment (what the project was built and tested with):

| Component | Version |
|---|---|
| JDK | Temurin 21 |
| Android cmdline‑tools | 12.0 (`commandlinetools-*-11076708`) |
| platform‑tools (`adb`) | 37.0.1 |
| `platforms;android-35` | r02 |
| `build-tools;35.0.0` | 35.0.0 |
| `ndk` | `27.1.12297006` |
| `cmake` | `3.22.1` |
| Gradle (wrapper) | 8.10.2 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |

---

## 1. Install the Android command‑line tools

1. Download **"Command line tools only"** from
   <https://developer.android.com/studio#command-line-tools-only>.
2. Unzip so the path becomes `…/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`
   (the extra `latest/` level is required).
3. Add to your shell for the session:

   ```bash
   export ANDROID_HOME="$HOME/Android/Sdk"          # wherever you put it
   export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
   ```

## 2. Install the SDK packages

```bash
yes | sdkmanager --licenses
sdkmanager \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "ndk;27.1.12297006" \
  "cmake;3.22.1"
```

`ndk` + `cmake` are what Gradle's `externalNativeBuild` uses to compile llama.cpp
(~2 GB download, one time).

## 3. Point the project at the SDK

In the repo root, create **`local.properties`** (git‑ignored):

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

`app/build.gradle.kts` pins `ndkVersion = "27.1.12297006"` and the CMake version, so no
other configuration is needed.

## 4. Get the source (with the submodule)

```bash
git clone --recurse-submodules https://github.com/kareem1207/vireo-android
cd vireo-android
# if you already cloned without --recurse-submodules:
git submodule update --init --recursive
```

## 5. Build & install

```bash
export JAVA_HOME="/path/to/jdk-21"

# debug (fast to iterate)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# release (R8 + resource shrink + stripped native lib -> 15.5 MB)
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

adb shell am start -n com.vireo/.MainActivity
adb logcat -s Vireo:V vireo_llm:V llama:V "*:E"
```

> The **first** native build compiles all of llama.cpp/ggml for arm64 — a few minutes.
> Later builds are incremental.
>
> The native side is always compiled **optimised**, even for the debug APK
> (`CMakeLists.txt` forces `CMAKE_BUILD_TYPE=Release`) — an unoptimised ggml runs ~30–60×
> slower on‑device.

### Release signing

`assembleRelease` expects a keystore at the repo root named `vireo-release.jks`
(git‑ignored). Generate a throwaway one:

```bash
keytool -genkeypair -keystore vireo-release.jks -alias vireo -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass <pass> -keypass <pass> -dname "CN=Vireo"
```

then either export `VIREO_KS_PASS` / `VIREO_KEY_PASS` or edit the defaults in
`app/build.gradle.kts`.

## 6. Run the tests

```bash
./gradlew :app:testDebugUnitTest        # ThermalPolicyTest — the governor decision fn
```

## 7. Get a model onto the phone

Two options:

- **In the app (normal path):** ⋮ → **Models** → Download → Activate.
- **Sideload for a debug build:**
  ```bash
  adb push some-model-q4_0.gguf /data/local/tmp/m.gguf
  adb shell run-as com.vireo cp /data/local/tmp/m.gguf files/models/<catalog-id>.gguf
  ```
  (`<catalog-id>` must match an `id` in `app/src/main/assets/model_catalog.json`.)

## Notes / gotchas

- **arm64 only.** `abiFilters = ["arm64-v8a"]`. Fine for any modern phone; won't run on an
  x86 emulator.
- **realme / ColorOS** blocks `adb install` unless "Install via USB" is on *or* you run
  `adb shell settings put global verifier_verify_adb_installs 0` once.
- **Windows + Git Bash:** disable MSYS path mangling for `adb` shell paths with
  `export MSYS_NO_PATHCONV=1` and pass Windows‑style local paths to `adb push`.
- `kotlin.incremental=false` is set in `gradle.properties` on purpose (see METHODOLOGY §6).
