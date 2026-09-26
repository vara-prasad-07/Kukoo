// JNI bridge between com.example.kukoo.ai.NativeLlamaEngine and llama.cpp.
// Every failure is reported as a Java RuntimeException so the Kotlin side can fall back.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "KukooLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

void throwJava(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

void freeSession(Session *s) {
    if (s == nullptr) return;
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_kukoo_ai_NativeLlamaEngine_nativeLoad(JNIEnv *env, jobject, jstring modelPath, jint contextSize) {
    static bool backendReady = false;
    if (!backendReady) {
        llama_backend_init();
        backendReady = true;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) {
        throwJava(env, "Failed to load GGUF model");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(contextSize);
    cp.n_batch = static_cast<uint32_t>(contextSize);
    cp.n_threads = 4;
    cp.n_threads_batch = 4;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        throwJava(env, "Failed to create llama context");
        return 0;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    LOGI("Model loaded (ctx=%d)", contextSize);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_kukoo_ai_NativeLlamaEngine_nativeComplete(JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens, jstring grammar) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        throwJava(env, "Model not loaded");
        return nullptr;
    }

    const char *cprompt = env->GetStringUTFChars(prompt, nullptr);
    std::string text(cprompt);
    env->ReleaseStringUTFChars(prompt, cprompt);

    // Each request is independent: start from an empty KV cache.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(s->ctx));
    std::vector<llama_token> tokens(text.size() + 8);
    int32_t n = llama_tokenize(s->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                               tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(s->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (n <= 0 || n + maxTokens > nCtx) {
        throwJava(env, "Prompt does not fit the context window");
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(n));

    // Optional GBNF grammar: the grammar sampler masks every token that would break the grammar, so the
    // output is always syntactically valid. (llama_sampler_init_grammar replaces the removed
    // llama_grammar_init / llama_sample_grammar pair.)
    std::string grammarText;
    if (grammar != nullptr) {
        const char *g = env->GetStringUTFChars(grammar, nullptr);
        grammarText = g;
        env->ReleaseStringUTFChars(grammar, g);
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammarText.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(s->vocab, grammarText.c_str(), "root");
        if (g == nullptr) {
            llama_sampler_free(sampler);
            throwJava(env, "Invalid GBNF grammar");
            return nullptr;
        }
        llama_sampler_chain_add(sampler, g);
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string out;
    int depth = 0;
    bool started = false;
    bool inString = false;
    bool escaped = false;
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));

    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(s->ctx, batch) != 0) {
            llama_sampler_free(sampler);
            throwJava(env, "llama_decode failed");
            return nullptr;
        }
        llama_token tok = llama_sampler_sample(sampler, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;

        char buf[256];
        int32_t len = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf), 0, false);
        if (len > 0) {
            for (int32_t k = 0; k < len; k++) {
                char c = buf[k];
                // Braces inside a JSON string (a task title) must not end the object early.
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                    started = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            out.append(buf, static_cast<size_t>(len));
        }
        // The reply is a single JSON object: stop as soon as it closes.
        if (started && depth <= 0) break;

        batch = llama_batch_get_one(&tok, 1);
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_kukoo_ai_NativeLlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    freeSession(reinterpret_cast<Session *>(handle));
}
