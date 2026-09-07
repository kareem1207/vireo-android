// Vireo JNI bridge to llama.cpp.
//
// Surface (all under com.vireo.llm.NativeLlm):
//   nativePing()                                   -> String
//   nativeLoadModel(path,nCtx,nThreads,nBatch)     -> long handle (0 = failure)
//   nativeGenerate(handle,prompt,maxTokens,temp,topP,topK,minP,seed,callback)
//   nativeCancel(handle)
//   nativeFree(handle)
//
// callback (com.vireo.llm.GenerationCallback):
//   void onToken(String piece)
//   void onDone(float tokPerSec, int nTokens, float promptEvalTokPerSec)

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "vireo_llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct VireoLlm {
    llama_model        *model = nullptr;
    llama_context      *ctx   = nullptr;
    const llama_vocab  *vocab = nullptr;
    std::string         chatTemplate;   // may be empty
    int                 nCtx  = 2048;
    std::atomic<bool>   cancel{false};
};

std::atomic<bool> g_backendReady{false};

void ensureBackend() {
    if (!g_backendReady.exchange(true)) {
        llama_backend_init();
        llama_log_set([](ggml_log_level level, const char *text, void *) {
            int prio = ANDROID_LOG_INFO;
            if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
            else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
            __android_log_print(prio, "llama", "%s", text);
        }, nullptr);
    }
}

// Copy the largest valid-UTF-8 prefix of `pending` into `out`, keep the rest.
void flushUtf8(std::string &pending, std::string &out) {
    size_t good = pending.size();
    // walk back over an incomplete trailing multi-byte sequence
    size_t i = pending.size();
    while (i > 0) {
        unsigned char c = pending[i - 1];
        if ((c & 0x80) == 0x00) { break; }                 // ASCII: complete
        if ((c & 0xC0) == 0x80) { i--; continue; }          // continuation byte
        // lead byte: how many bytes does this sequence need?
        size_t need = ((c & 0xE0) == 0xC0) ? 2 : ((c & 0xF0) == 0xE0) ? 3 : ((c & 0xF8) == 0xF0) ? 4 : 1;
        size_t have = pending.size() - (i - 1);
        if (have < need) good = i - 1;                      // truncated -> hold back
        break;
    }
    out.append(pending, 0, good);
    pending.erase(0, good);
}

std::string applyChatTemplate(VireoLlm *s, const std::vector<llama_chat_message> &msgs) {
    const char *tmpl = s->chatTemplate.empty() ? nullptr : s->chatTemplate.c_str();
    size_t approx = 512;
    for (auto &m : msgs) approx += strlen(m.role) + strlen(m.content) + 32;
    std::vector<char> buf(approx * 2);
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), /*add_ass=*/true,
                                          buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (n <= 0) {
        LOGW("chat template failed (n=%d); concatenating raw", n);
        std::string raw;
        for (auto &m : msgs) { raw += m.role; raw += ": "; raw += m.content; raw += "\n"; }
        return raw;
    }
    return std::string(buf.data(), n);
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text,
                                  bool addSpecial, bool parseSpecial) {
    int32_t n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                nullptr, 0, addSpecial, parseSpecial);
    std::vector<llama_token> out(n);
    int32_t got = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                 out.data(), (int32_t) out.size(), addSpecial, parseSpecial);
    if (got < 0) { out.clear(); return out; }
    out.resize(got);
    return out;
}

std::string pieceToString(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) return {};
    return std::string(buf, n);
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_vireo_llm_NativeLlm_nativePing(JNIEnv *env, jobject) {
    ensureBackend();
    const char *sys = llama_print_system_info();
    std::string msg = "llm-ok | ";
    msg += (sys ? sys : "");
    return env->NewStringUTF(msg.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_vireo_llm_NativeLlm_nativeLoadModel(JNIEnv *env, jobject, jstring jpath,
                                            jint nCtx, jint nThreads, jint nBatch) {
    ensureBackend();
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("loading model: %s (nCtx=%d nThreads=%d nBatch=%d)", path, nCtx, nThreads, nBatch);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                        // CPU only
    // NOTE: mmap over Android FUSE (/sdcard, getExternalFilesDir) stalls indefinitely.
    // Read the whole file instead. The model manager (M4) downloads to internal
    // storage where mmap is safe and can re-enable it.
    mp.load_mode    = LLAMA_LOAD_MODE_NONE;

    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("llama_model_load_from_file failed"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) nCtx;
    cp.n_batch         = (uint32_t) nBatch;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    // KV cache left at default (f16) for the M1 baseline. Quantized KV (needs flash
    // attention) is revisited in M3 alongside the thermal work.

    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("llama_init_from_model failed"); llama_model_free(model); return 0; }

    auto *s = new VireoLlm();
    s->model = model;
    s->ctx   = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->nCtx  = (int) llama_n_ctx(ctx);
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl) s->chatTemplate = tmpl;

    LOGI("model ready. n_ctx=%d chat_template=%s", s->nCtx, tmpl ? "builtin" : "(none)");
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_vireo_llm_NativeLlm_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<VireoLlm *>(handle);
    if (s) s->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_vireo_llm_NativeLlm_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<VireoLlm *>(handle);
    if (!s) return;
    if (s->ctx)   llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
    LOGI("model freed");
}

JNIEXPORT void JNICALL
Java_com_vireo_llm_NativeLlm_nativeGenerate(JNIEnv *env, jobject, jlong handle,
                                           jobjectArray jRoles, jobjectArray jContents,
                                           jint maxTokens, jfloat temp, jfloat topP, jint topK,
                                           jfloat minP, jint seed, jobject cb) {
    auto *s = reinterpret_cast<VireoLlm *>(handle);
    if (!s) { LOGE("nativeGenerate: null handle"); return; }
    s->cancel.store(false);

    jclass cbCls    = env->GetObjectClass(cb);
    jmethodID mTok  = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");
    jmethodID mDone = env->GetMethodID(cbCls, "onDone", "(FIF)V");

    // marshal the message list
    const jsize nMsg = env->GetArrayLength(jRoles);
    std::vector<std::string> roleStr(nMsg), contentStr(nMsg);
    std::vector<llama_chat_message> msgs(nMsg);
    for (jsize i = 0; i < nMsg; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(jRoles, i);
        auto c = (jstring) env->GetObjectArrayElement(jContents, i);
        const char *rc = env->GetStringUTFChars(r, nullptr);
        const char *cc = env->GetStringUTFChars(c, nullptr);
        roleStr[i] = rc; contentStr[i] = cc;
        env->ReleaseStringUTFChars(r, rc); env->ReleaseStringUTFChars(c, cc);
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
        msgs[i] = llama_chat_message{roleStr[i].c_str(), contentStr[i].c_str()};
    }

    // fresh KV each call: we re-send the (already trimmed) transcript every turn.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const std::string prompt = applyChatTemplate(s, msgs);
    std::vector<llama_token> tokens = tokenize(s->vocab, prompt, /*addSpecial=*/true, /*parseSpecial=*/true);
    if (tokens.empty()) { LOGE("tokenize produced 0 tokens"); return; }
    if ((int) tokens.size() >= s->nCtx - 4) {
        tokens.resize(s->nCtx - 64);   // crude guard for M1
    }

    using clock = std::chrono::steady_clock;
    auto t0 = clock::now();

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(s->ctx, batch) != 0) { LOGE("llama_decode(prompt) failed"); return; }

    auto tPrompt = clock::now();
    float promptTps = tokens.size() /
        std::max(1e-4f, std::chrono::duration<float>(tPrompt - t0).count());

    // sampler chain
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (topK > 0)      llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        if (topP < 1.0f)   llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        if (minP > 0.0f)   llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed));
    }

    std::string pending, flushed;
    int nGen = 0;
    auto tGenStart = clock::now();

    for (int i = 0; i < maxTokens; ++i) {
        if (s->cancel.load()) { LOGI("generation cancelled at %d tokens", nGen); break; }

        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;

        pending += pieceToString(s->vocab, id);
        flushed.clear();
        flushUtf8(pending, flushed);
        if (!flushed.empty()) {
            jstring js = env->NewStringUTF(flushed.c_str());
            env->CallVoidMethod(cb, mTok, js);
            env->DeleteLocalRef(js);
        }
        nGen++;

        llama_batch nb = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, nb) != 0) { LOGE("llama_decode(step) failed"); break; }
    }

    if (!pending.empty()) {   // emit whatever remains
        jstring js = env->NewStringUTF(pending.c_str());
        env->CallVoidMethod(cb, mTok, js);
        env->DeleteLocalRef(js);
    }

    float genTps = nGen /
        std::max(1e-4f, std::chrono::duration<float>(clock::now() - tGenStart).count());
    LOGI("done: %d tok, gen %.2f tok/s, prompt-eval %.2f tok/s", nGen, genTps, promptTps);

    llama_sampler_free(smpl);
    env->CallVoidMethod(cb, mDone, genTps, nGen, promptTps);
}

} // extern "C"
