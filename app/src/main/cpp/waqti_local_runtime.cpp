#include <jni.h>

#include <sys/stat.h>

#include <cerrno>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>

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
