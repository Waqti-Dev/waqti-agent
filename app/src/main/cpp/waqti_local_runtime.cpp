#include <jni.h>

#include <sys/stat.h>

#include <cerrno>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>
#include <llama.h>

// The single JNI entry point of the in-process runtime. Everything above this
// file (Kotlin, UI, agent) speaks application-level types only; nothing here is
// reachable from AgentLoop or the Compose UI.

#define LOG_TAG "waqti-native"

namespace {

// llama.cpp logs to stderr by default, which Android discards — route every
// message to logcat so a load failure can be diagnosed straight from the device.
void log_to_logcat(ggml_log_level level, const char *text, void * /*user_data*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_VERBOSE; break;
    }
    std::string line(text != nullptr ? text : "");
    if (!line.empty() && line.back() == '\n') {
        line.pop_back();  // logcat already terminates lines
    }
    __android_log_print(prio, LOG_TAG, "%s", line.c_str());
}

// Loading a 2 GiB model is the slow part of startup: report it in 10% steps so
// the device log shows real progress. Loads are serialised by g_model_mutex,
// so the static state here is single-user.
bool log_progress(float progress, void * /*user_data*/) {
    static int last_bucket = -1;
    const int pct = static_cast<int>(progress * 100.0f);
    const int bucket = pct / 10;
    if (bucket != last_bucket) {
        last_bucket = bucket;
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "model load progress: %d%%", pct);
    }
    return true;
}

std::mutex g_model_mutex;        // serialises load/free of the process-wide model
llama_model *g_model = nullptr;  // the one model this process holds
std::once_flag g_backend_once;

// Context for generation (one at a time for simplicity)
std::mutex g_ctx_mutex;
llama_context *g_ctx = nullptr;

}  // namespace

// Kotlin `object` members are instance methods (on NativeRuntime.INSTANCE),
// so JNI hands us the instance — a jobject, not a jclass.
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_versionInfo(JNIEnv *env, jobject /*runtime*/) {
    const std::string info =
        std::string(llama_version()) + " | " + llama_print_system_info();

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "llama.cpp linked: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

// Loads a GGUF from this app's own storage into *this* process — no server,
// no socket, no other process involved.
//
// Returns a '|' separated status string, never throws:
//   ok|<load ms>|<architecture>|name=<general.name>|bytes=<file size>
//   error|<reason>
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_loadModel(JNIEnv *env, jobject /*runtime*/,
                                                      jstring jpath) {
    if (jpath == nullptr) {
        return env->NewStringUTF("error|path is null");
    }
    const char *chars = env->GetStringUTFChars(jpath, nullptr);
    if (chars == nullptr) {
        return env->NewStringUTF("error|GetStringUTFChars failed");
    }
    const std::string path(chars);
    env->ReleaseStringUTFChars(jpath, chars);

    // Fail fast with a precise reason: a missing or half-copied file is the
    // most likely development mistake.
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) {
        const std::string result = "error|cannot stat file: " + std::string(std::strerror(errno));
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", result.c_str());
        return env->NewStringUTF(result.c_str());
    }

    std::lock_guard<std::mutex> guard(g_model_mutex);

    std::call_once(g_backend_once, [] {
        llama_log_set(log_to_logcat, nullptr);
        llama_backend_init();
    });

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "loading model: %s (%lld bytes), llama.cpp %s",
                        path.c_str(), static_cast<long long>(st.st_size), llama_version());

    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;  // CPU only: no accelerators until correctness is proven
    params.progress_callback = log_progress;

    const auto started = std::chrono::steady_clock::now();
    llama_model *model = llama_model_load_from_file(path.c_str(), params);
    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();

    if (model == nullptr) {
        const std::string result =
            "error|llama_model_load_from_file returned null after " +
            std::to_string(elapsed_ms) + " ms";
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", result.c_str());
        return env->NewStringUTF(result.c_str());
    }

    g_model = model;

    char desc[256] = {};
    llama_model_desc(model, desc, sizeof(desc));
    char name[256] = {};
    llama_model_meta_val_str(model, "general.name", name, sizeof(name));

    const std::string result =
        "ok|" + std::to_string(elapsed_ms) + " ms|" + desc +
        "|name=" + (name[0] != '\0' ? name : "?") +
        "|bytes=" + std::to_string(static_cast<long long>(st.st_size));
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "model loaded: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// Creates a context for generation with the loaded model.
// Returns a '|' separated status string:
//   ok|<init ms>|ctx=<ctx size>|n_batch=<batch>
//   error|<reason>
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_createContext(JNIEnv *env, jobject /*runtime*/,
                                                          jint n_ctx, jint n_batch) {
    if (g_model == nullptr) {
        return env->NewStringUTF("error|no model loaded");
    }

    std::lock_guard<std::mutex> guard(g_ctx_mutex);

    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = (uint32_t) n_batch;
    cparams.n_threads = 4;  // Conservative: device has 8 cores, leave headroom
    cparams.n_threads_batch = 4;
    cparams.offload_kqv = false;  // CPU only
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cparams.no_perf = true;

    const auto started = std::chrono::steady_clock::now();
    llama_context *ctx = llama_init_from_model(g_model, cparams);
    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();

    if (ctx == nullptr) {
        const std::string result =
            "error|llama_init_from_model returned null after " +
            std::to_string(elapsed_ms) + " ms";
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", result.c_str());
        return env->NewStringUTF(result.c_str());
    }

    g_ctx = ctx;

    const std::string result =
        "ok|" + std::to_string(elapsed_ms) + " ms|ctx=" + std::to_string(n_ctx) +
        "|n_batch=" + std::to_string(n_batch);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "context created: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// Generates text from a prompt using the loaded model and context.
// Returns a '|' separated status string:
//   ok|<gen ms>|text=<generated text>
//   error|<reason>
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_generate(JNIEnv *env, jobject /*runtime*/,
                                                     jstring jprompt, jint n_predict,
                                                     jfloat temperature, jint top_k,
                                                     jfloat top_p, jint seed) {
    if (g_model == nullptr) {
        return env->NewStringUTF("error|no model loaded");
    }
    if (g_ctx == nullptr) {
        return env->NewStringUTF("error|no context created");
    }

    std::lock_guard<std::mutex> guard(g_ctx_mutex);

    if (jprompt == nullptr) {
        return env->NewStringUTF("error|prompt is null");
    }
    const char *chars = env->GetStringUTFChars(jprompt, nullptr);
    if (chars == nullptr) {
        return env->NewStringUTF("error|GetStringUTFChars failed");
    }
    const std::string prompt(chars);
    env->ReleaseStringUTFChars(jprompt, chars);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: prompt='%s', len=%zu", prompt.c_str(), prompt.size());

    // Tokenize the prompt
    const struct llama_vocab *vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "generate: failed to get vocab");
        return env->NewStringUTF("error|failed to get vocab");
    }

    // Check vocab properties
    int32_t n_vocab = llama_vocab_n_tokens(vocab);
    enum llama_vocab_type vocab_type = llama_vocab_type(vocab);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: vocab type=%d, n_tokens=%d", vocab_type, n_vocab);

    // First pass: get required token count - try without add_special first
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, false, false);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: tokenize sizing (no special) returned %d", n_tokens);
    if (n_tokens < 0) {
        // Negative means buffer too small; absolute value is the required size
        n_tokens = -n_tokens;
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: need %d tokens (no special)", n_tokens);
    } else {
        // Try with add_special=true in case it's needed
        int n_tokens_special = llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, false);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: tokenize sizing (with special) returned %d", n_tokens_special);
        if (n_tokens_special < 0) {
            n_tokens_special = -n_tokens_special;
        }
        // Use the larger of the two (special tokens might add BOS/EOS)
        if (n_tokens_special > n_tokens) {
            n_tokens = n_tokens_special;
        }
    }

    // Allocate buffer and tokenize
    std::vector<llama_token> tokens(n_tokens);
    int actual_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), n_tokens, false, false);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: tokenize actual returned %d", actual_tokens);
    if (actual_tokens < 0) {
        // Buffer still too small? Try with the exact size needed
        int needed = -actual_tokens;
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "generate: buffer too small, need %d, retrying", needed);
        tokens.resize(needed);
        actual_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), needed, false, false);
        if (actual_tokens < 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "generate: tokenize failed even after resize: %d", actual_tokens);
            return env->NewStringUTF("error|tokenize failed");
        }
    }
    n_tokens = actual_tokens;

    // Create batch using official llama.cpp pattern: let llama.cpp auto-manage positions
    // llama_batch_get_one returns pos=nullptr, logits=nullptr (defaults to last token logits)
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    // Note: llama_batch_get_one returns logits=nullptr which defaults to "only last token logits"

    // Decode the prompt (prefill)
    const auto gen_started = std::chrono::steady_clock::now();
    int ret = llama_decode(g_ctx, batch);
    if (ret != 0) {
        const std::string result = "error|llama_decode prefill failed with code " + std::to_string(ret);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", result.c_str());
        return env->NewStringUTF(result.c_str());
    }

    // Create sampler chain
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));

    // Generate tokens
    std::string generated_text;
    int n_generated = 0;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: starting generation loop, n_predict=%d", n_predict);

    for (int i = 0; i < n_predict; ++i) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "generate: step %d, sampling...", i);
        llama_token token = llama_sampler_sample(smpl, g_ctx, -1);
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "generate: step %d, sampled token=%d", i, token);
        if (token == -1) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: sampler returned -1, stopping");
            break;
        }
        llama_sampler_accept(smpl, token);

        // Check for EOS
        if (llama_vocab_is_eog(vocab, token)) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: EOS token reached, stopping");
            break;
        }

        // Convert token to text
        char buf[128];
        int n_chars = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
        if (n_chars > 0) {
            generated_text.append(buf, n_chars);
        }

        // Prepare next batch with the new token - use official pattern: llama_batch_get_one with pos=nullptr
        llama_batch next_batch = llama_batch_get_one(&token, 1);
        // llama_batch_get_one returns pos=nullptr (auto-managed), logits=nullptr (last token logits)

        ret = llama_decode(g_ctx, next_batch);
        if (ret != 0) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "llama_decode generation step failed: %d", ret);
            break;
        }

        n_generated++;
    }

    llama_sampler_free(smpl);

    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - gen_started).count();

    const std::string result =
        "ok|" + std::to_string(elapsed_ms) + " ms|tokens=" + std::to_string(n_generated) +
        "|text=" + generated_text;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generation done: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// Releases the generation context (frees KV cache etc.)
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_releaseContext(JNIEnv *env, jobject /*runtime*/) {
    std::lock_guard<std::mutex> guard(g_ctx_mutex);

    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    const std::string result = "ok|context released";
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// Unloads the model entirely
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_unloadModel(JNIEnv *env, jobject /*runtime*/) {
    std::lock_guard<std::mutex> guard(g_model_mutex);

    // Release context first if any
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    const std::string result = "ok|model unloaded";
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", result.c_str());
    return env->NewStringUTF(result.c_str());
}