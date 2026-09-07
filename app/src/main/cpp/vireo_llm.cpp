// Vireo JNI bridge to llama.cpp.
// M1 step 1: prove the NDK + CMake + llama.cpp toolchain links and loads.
// Model load / generation / embeddings are added in the next step.

#include <jni.h>
#include <string>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "vireo_llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_vireo_llm_NativeLlm_nativePing(JNIEnv *env, jobject /*thiz*/) {
    const char *sys = llama_print_system_info();
    LOGI("llama system info: %s", sys ? sys : "(null)");
    std::string msg = "llm-ok | ";
    msg += (sys ? sys : "");
    return env->NewStringUTF(msg.c_str());
}
