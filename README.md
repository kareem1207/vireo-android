# Vireo — Android (offline on-device AI study assistant)

Native Kotlin + Jetpack Compose. llama.cpp (git submodule) via JNI. arm64-v8a only.
No Android Studio: headless Gradle + NDK.

## Pinned toolchain (installed at `D:\Android\Sdk`)

| Package | Version |
|---|---|
| cmdline-tools | 12.0 (commandlinetools-win-11076708) |
| platform-tools | 37.0.1 |
| platforms;android-35 | r02 |
| build-tools;35.0.0 | 35.0.0 |
| cmake;3.22.1 | 3.22.1 |
| ndk | 27.1.12297006 |
| Gradle (wrapper) | 8.10.2 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| JDK | Temurin 21 |

## Build & install

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
./gradlew :app:assembleDebug
D:/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
D:/Android/Sdk/platform-tools/adb.exe shell am start -n com.vireo/.MainActivity
D:/Android/Sdk/platform-tools/adb.exe logcat -s Vireo:V vireo_llm:V llama:V "*:E"
```

## Target device (verified)

realme narzo 60 5G / RMX3750 · MediaTek MT6833 (Dimensity 700/6020) · 2×A76 + 6×A55 ·
`asimddp` yes, `i8mm` no · 8 GB RAM (~2.6 GB free) · Android 15 (SDK 35) · arm64-v8a.

## Milestones

See `../Major Project/docs/PLAN.md` and `docs/ANTIGRAVITY_PROMPTS.md`.
M0 scaffold (this) → M1 llama.cpp JNI → M2 chat UI → M3 thermal governor →
M4 model manager → M5 RAG → M6 polish.
