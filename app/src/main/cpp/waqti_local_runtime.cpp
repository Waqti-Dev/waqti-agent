#include <jni.h>

#include <string>

#include <android/log.h>
#include <llama.h>

// The single JNI entry point of the in-process runtime. Everything above this
// file (Kotlin, UI, agent) speaks application-level types only; nothing here is
// reachable from AgentLoop or the Compose UI.

#define LOG_TAG "waqti-native"

// Kotlin `object` members are instance methods (on NativeRuntime.INSTANCE),
// so JNI hands us the instance — a jobject, not a jclass.
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_versionInfo(JNIEnv *env, jobject /*runtime*/) {
    const std::string info =
        std::string(llama_version()) + " | " + llama_print_system_info();

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "llama.cpp linked: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}
