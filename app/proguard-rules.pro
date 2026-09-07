# Vireo — R8 rules for the release build.

# --- JNI: native symbols are linked by name (Java_com_vireo_llm_NativeLlm_*),
#     and native code looks up GenerationCallback.onToken/onDone by name. ---
-keep class com.vireo.llm.NativeLlm { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep interface com.vireo.llm.GenerationCallback { *; }
-keepclassmembers class * implements com.vireo.llm.GenerationCallback { <methods>; }
-keep interface com.vireo.llm.TokenPacer { *; }

# --- PdfBox-Android / fontbox: reflection + bundled resources ---
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.**
-dontwarn javax.**
-dontwarn java.awt.**

# --- OkHttp / Okio (they ship consumer rules; silence platform-only refs) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep enum values used across the app
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
