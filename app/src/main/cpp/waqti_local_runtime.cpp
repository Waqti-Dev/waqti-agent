#include <jni.h>

#include <sys/stat.h>

#include <cerrno>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>
#include <llama.h>

// llama.cpp's own chat layer. This is the same code path llama-server and
// `llama-cli` use, so the model's OWN chat template decides how tools are
// described, how the assistant turn is laid out, and how tool calls are parsed
// back. Nothing about Qwen's format is reimplemented here — no hardcoded
// markers, no hardcoded token ids, no substring search for "<|tool_call|>".
#include "chat.h"
#include "common.h"

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

// Parsed chat templates for the loaded model. Owned here because the parser and
// the grammar the model is constrained with both stay valid for as long as the
// model does; rebuilding them per turn would re-run the Jinja analysis every
// message. Guarded by g_model_mutex: created on load, destroyed on unload.
common_chat_templates_ptr g_tmpls;

// Renders a string for logcat so the bytes survive the trip through logcat and
// any shell/terminal pipeline: '<', newlines and control bytes are escaped.
// Without this, a literal "<|im_end|>" in a log line is indistinguishable from
// the harness eating angle brackets, which is exactly how a real token leak got
// mistaken for a display artifact once already.
std::string log_escape(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 16);
    static const char * kHex = "0123456789abcdef";
    for (unsigned char c : s) {
        if (c == '<') {
            out += "\\x3c";
        } else if (c == '>') {
            out += "\\x3e";
        } else if (c == '\n') {
            out += "\\n";
        } else if (c == '\r') {
            out += "\\r";
        } else if (c < 0x20 || c == 0x7f) {
            out += "\\x";
            out += kHex[c >> 4];
            out += kHex[c & 0xf];
        } else {
            out += static_cast<char>(c);
        }
    }
    return out;
}

// Clears the KV cache before a prefill. The formatted prompt always contains
// the whole conversation from the first turn, so the cache from a previous
// generateChat must not be reused — otherwise every turn re-prefills the entire
// history on top of the previous turn's KV, duplicating context and eventually
// overflowing n_ctx. This is the same call examples/embedding and common.cpp
// make: llama_memory_clear(llama_get_memory(ctx), true).
void reset_memory() {
    if (g_ctx != nullptr) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
    }
}

// Tokenizes text the way every llama.cpp example does (common_tokenize):
// add_special and parse_special both true. parse_special=true is what lets
// tokenizer_st_partition() recognise "<|im_start|>" / "<|im_end|>" in the
// chat-formatted prompt and map them to the model's real control token ids
// instead of splitting them into ordinary text pieces. With parse_special=false
// the ChatML markers reach the model as literal text, the prompt is
// off-distribution, and the model answers with a literal "<|im_end|>".
// add_special=true is safe here: for this vocab add_bos/add_eos are both false
// (read from the GGUF), so it only affects special-token parsing.
std::vector<llama_token> tokenize_prompt(const llama_vocab * vocab,
                                         const std::string & text) {
    std::vector<llama_token> out(text.size() + 2);
    int n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                           out.data(), (int32_t) out.size(),
                           /* add_special */ true, /* parse_special */ true);
    if (n < 0) {
        out.resize((size_t) (-n));
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                           out.data(), (int32_t) out.size(),
                           /* add_special */ true, /* parse_special */ true);
    }
    out.resize(n > 0 ? (size_t) n : 0);
    return out;
}

// ---------------------------------------------------------------------------
// Task 7: tools. Everything below is generic plumbing. The actual tool syntax
// comes from the model's own Jinja chat template via common_chat_templates_apply
// and is parsed back with common_chat_parse.
// ---------------------------------------------------------------------------

// Minimal recursive-descent JSON reader. Enough for the envelope Kotlin sends
// and strict enough to reject anything malformed, so bad input becomes a
// controlled error string instead of a crash or a silently empty conversation.
class JsonReader {
  public:
    explicit JsonReader(const std::string & src) : s(src) {}

    void ws() {
        while (i < s.size() &&
               (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) {
            ++i;
        }
    }

    bool at_end() {
        ws();
        return i >= s.size();
    }

    bool peek(char c) {
        ws();
        return i < s.size() && s[i] == c;
    }

    bool take(char c) {
        ws();
        if (i < s.size() && s[i] == c) {
            ++i;
            return true;
        }
        return false;
    }

    bool read_string(std::string & out) {
        ws();
        if (i >= s.size() || s[i] != '"') return false;
        ++i;
        out.clear();
        while (i < s.size()) {
            const char c = s[i];
            if (c == '"') {
                ++i;
                return true;
            }
            if (c == '\\') {
                ++i;
                if (i >= s.size()) return false;
                const char e = s[i++];
                switch (e) {
                    case '"':  out += '"';  break;
                    case '\\': out += '\\'; break;
                    case '/':  out += '/';  break;
                    case 'b':  out += '\b'; break;
                    case 'f':  out += '\f'; break;
                    case 'n':  out += '\n'; break;
                    case 'r':  out += '\r'; break;
                    case 't':  out += '\t'; break;
                    case 'u': {
                        uint32_t cp = 0;
                        if (!hex4(cp)) return false;
                        // High surrogate: pair it with the following escape.
                        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < s.size() &&
                            s[i] == '\\' && s[i + 1] == 'u') {
                            const size_t save = i;
                            i += 2;
                            uint32_t lo = 0;
                            if (!hex4(lo)) return false;
                            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                            } else {
                                i = save;  // not a pair; keep the lone surrogate
                            }
                        }
                        append_utf8(out, cp);
                        break;
                    }
                    default: return false;
                }
                continue;
            }
            out += c;
            ++i;
        }
        return false;
    }

    // Advances past one JSON value of any type.
    bool skip_value() {
        ws();
        if (i >= s.size()) return false;
        const char c = s[i];
        if (c == '"') {
            std::string ignored;
            return read_string(ignored);
        }
        if (c == '{' || c == '[') {
            const char open = c;
            const char close = (open == '{') ? '}' : ']';
            int depth = 0;
            while (i < s.size()) {
                if (s[i] == '"') {
                    std::string ignored;
                    if (!read_string(ignored)) return false;
                    continue;
                }
                if (s[i] == open) ++depth;
                else if (s[i] == close) {
                    --depth;
                    if (depth == 0) {
                        ++i;
                        return true;
                    }
                }
                ++i;
            }
            return false;
        }
        // number / true / false / null
        const size_t start = i;
        while (i < s.size() && s[i] != ',' && s[i] != '}' && s[i] != ']') ++i;
        return i > start;
    }

    size_t pos() const { return i; }
    void   seek(size_t p) { i = p; }

  private:
    bool hex4(uint32_t & out) {
        if (i + 4 > s.size()) return false;
        out = 0;
        for (int k = 0; k < 4; ++k) {
            const char c = s[i + (size_t) k];
            uint32_t d;
            if (c >= '0' && c <= '9') d = (uint32_t) (c - '0');
            else if (c >= 'a' && c <= 'f') d = (uint32_t) (c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') d = (uint32_t) (c - 'A' + 10);
            else return false;
            out = (out << 4) | d;
        }
        i += 4;
        return true;
    }

    static void append_utf8(std::string & out, uint32_t cp) {
        if (cp <= 0x7f) {
            out += (char) cp;
        } else if (cp <= 0x7ff) {
            out += (char) (0xc0 | (cp >> 6));
            out += (char) (0x80 | (cp & 0x3f));
        } else if (cp <= 0xffff) {
            out += (char) (0xe0 | (cp >> 12));
            out += (char) (0x80 | ((cp >> 6) & 0x3f));
            out += (char) (0x80 | (cp & 0x3f));
        } else {
            out += (char) (0xf0 | (cp >> 18));
            out += (char) (0x80 | ((cp >> 12) & 0x3f));
            out += (char) (0x80 | ((cp >> 6) & 0x3f));
            out += (char) (0x80 | (cp & 0x3f));
        }
    }

    const std::string & s;
    size_t i = 0;
};

// A tool call as it appears in the transcript (assistant turn) or in the reply.
struct ChatToolCall {
    std::string id;
    std::string name;
    std::string arguments;
};

// Reads {"messages":[...], "tools":[...]}. common_chat_msg owns its strings, so
// the vectors are handed over by value and stay valid for the whole call.
bool parse_chat_envelope(const std::string & json,
                         std::vector<common_chat_msg> & msgs,
                         std::vector<common_chat_tool> & tools,
                         std::string & err) {
    JsonReader r(json);
    if (!r.take('{')) { err = "expected object"; return false; }

    bool saw_messages = false;
    if (!r.peek('}')) {
        while (true) {
            std::string key;
            if (!r.read_string(key)) { err = "bad key"; return false; }
            if (!r.take(':')) { err = "expected ':'"; return false; }

            if (key == "messages") {
                if (!r.take('[')) { err = "messages not an array"; return false; }
                saw_messages = true;
                if (!r.peek(']')) {
                    while (true) {
                        common_chat_msg m;
                        if (!r.take('{')) { err = "message not an object"; return false; }
                        std::vector<ChatToolCall> calls;
                        bool have_role = false;
                        if (!r.peek('}')) {
                            while (true) {
                                std::string field;
                                if (!r.read_string(field)) { err = "bad field"; return false; }
                                if (!r.take(':')) { err = "expected ':'"; return false; }
                                if (field == "role") {
                                    if (!r.read_string(m.role)) { err = "bad role"; return false; }
                                    have_role = true;
                                } else if (field == "content") {
                                    if (!r.read_string(m.content)) { err = "bad content"; return false; }
                                } else if (field == "tool_name") {
                                    if (!r.read_string(m.tool_name)) { err = "bad tool_name"; return false; }
                                } else if (field == "tool_call_id") {
                                    if (!r.read_string(m.tool_call_id)) { err = "bad tool_call_id"; return false; }
                                } else if (field == "tool_calls") {
                                    if (!r.take('[')) { err = "tool_calls not an array"; return false; }
                                    if (!r.peek(']')) {
                                        while (true) {
                                            ChatToolCall c;
                                            if (!r.take('{')) { err = "tool_call not an object"; return false; }
                                            if (!r.peek('}')) {
                                                while (true) {
                                                    std::string cf;
                                                    if (!r.read_string(cf)) { err = "bad tool_call field"; return false; }
                                                    if (!r.take(':')) { err = "expected ':'"; return false; }
                                                    if (cf == "id") {
                                                        if (!r.read_string(c.id)) { err = "bad id"; return false; }
                                                    } else if (cf == "name") {
                                                        if (!r.read_string(c.name)) { err = "bad name"; return false; }
                                                    } else if (cf == "arguments") {
                                                        if (!r.read_string(c.arguments)) { err = "bad arguments"; return false; }
                                                    } else if (!r.skip_value()) {
                                                        err = "bad tool_call value"; return false;
                                                    }
                                                    if (r.take(',')) continue;
                                                    if (r.take('}')) break;
                                                    err = "bad tool_call object"; return false;
                                                }
                                            }
                                            if (c.name.empty()) { err = "tool_call without a name"; return false; }
                                            calls.push_back(c);
                                            if (r.take(',')) continue;
                                            if (r.take(']')) break;
                                            err = "bad tool_calls array"; return false;
                                        }
                                    } else {
                                        r.take(']');
                                    }
                                    for (auto & c : calls) {
                                        common_chat_tool_call tc;
                                        tc.id      = c.id;
                                        tc.name    = c.name;
                                        tc.arguments = c.arguments;
                                        m.tool_calls.push_back(tc);
                                    }
                                } else if (!r.skip_value()) {
                                    err = "bad value"; return false;
                                }
                                if (r.take(',')) continue;
                                if (r.take('}')) break;
                                err = "bad message object"; return false;
                            }
                        } else {
                            r.take('}');
                        }
                        if (!have_role || m.role.empty()) { err = "message without a role"; return false; }
                        msgs.push_back(m);
                        if (r.take(',')) continue;
                        if (r.take(']')) break;
                        err = "bad messages array"; return false;
                    }
                } else {
                    r.take(']');
                }
            } else if (key == "tools") {
                if (!r.take('[')) { err = "tools not an array"; return false; }
                if (!r.peek(']')) {
                    while (true) {
                        common_chat_tool t;
                        bool have_name = false;
                        if (!r.take('{')) { err = "tool not an object"; return false; }
                        if (!r.peek('}')) {
                            while (true) {
                                std::string field;
                                if (!r.read_string(field)) { err = "bad tool field"; return false; }
                                if (!r.take(':')) { err = "expected ':'"; return false; }
                                if (field == "name") {
                                    if (!r.read_string(t.name)) { err = "bad tool name"; return false; }
                                    have_name = true;
                                } else if (field == "description") {
                                    if (!r.read_string(t.description)) { err = "bad tool description"; return false; }
                                } else if (field == "parameters") {
                                    if (!r.read_string(t.parameters)) { err = "bad tool parameters"; return false; }
                                } else if (!r.skip_value()) {
                                    err = "bad tool value"; return false;
                                }
                                if (r.take(',')) continue;
                                if (r.take('}')) break;
                                err = "bad tool object"; return false;
                            }
                        } else {
                            r.take('}');
                        }
                        if (!have_name || t.name.empty()) { err = "tool without a name"; return false; }
                        tools.push_back(t);
                        if (r.take(',')) continue;
                        if (r.take(']')) break;
                        err = "bad tools array"; return false;
                    }
                } else {
                    r.take(']');
                }
            } else if (!r.skip_value()) {
                err = "bad top level value"; return false;
            }

            // Like the inner loops, this one only separates members. The object's
            // own '}' is closed once, below, which is also what closes the empty
            // case that peek('}') skipped over. Taking it here as well asked for a
            // second '}' and rejected every non-empty envelope as "unterminated
            // object".
            if (r.take(',')) continue;
            if (!r.peek('}')) { err = "bad object"; return false; }
            break;
        }
    }

    if (!r.take('}')) { err = "unterminated object"; return false; }
    if (!r.at_end()) { err = "trailing data"; return false; }
    if (!saw_messages) { err = "no messages"; return false; }

    return true;
}

void json_escape_into(std::string & out, const std::string & s) {
    static const char * kHex = "0123456789abcdef";
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b";  break;
            case '\f': out += "\\f";  break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    out += "\\u00";
                    out += kHex[c >> 4];
                    out += kHex[c & 0xf];
                } else {
                    out += (char) c;
                }
        }
    }
}

std::string base64_encode(const std::string & in) {
    static const char * tbl =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((in.size() + 2) / 3) * 4);
    size_t i = 0;
    while (i + 2 < in.size()) {
        const uint32_t v = ((uint32_t) (unsigned char) in[i] << 16) |
                           ((uint32_t) (unsigned char) in[i + 1] << 8) |
                           ((uint32_t) (unsigned char) in[i + 2]);
        out += tbl[(v >> 18) & 0x3f];
        out += tbl[(v >> 12) & 0x3f];
        out += tbl[(v >> 6) & 0x3f];
        out += tbl[v & 0x3f];
        i += 3;
    }
    if (i + 1 == in.size()) {
        const uint32_t v = ((uint32_t) (unsigned char) in[i] << 16);
        out += tbl[(v >> 18) & 0x3f];
        out += tbl[(v >> 12) & 0x3f];
        out += "==";
    } else if (i + 2 == in.size()) {
        const uint32_t v = ((uint32_t) (unsigned char) in[i] << 16) |
                           ((uint32_t) (unsigned char) in[i + 1] << 8);
        out += tbl[(v >> 18) & 0x3f];
        out += tbl[(v >> 12) & 0x3f];
        out += tbl[(v >> 6) & 0x3f];
        out += "=";
    }
    return out;
}

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

    // Chat templates (and therefore tool formatting + tool-call parsing) are a
    // property of the model, so they are built once per load and reused for
    // every turn. common_chat_templates_init() throws if the model has no usable
    // template at all.
    //
    // chat_template_override is a const std::string&, so "no override" is an EMPTY
    // string, never nullptr. Passing nullptr selected std::string(const char*),
    // which measures its argument with strlen() and dereferenced NULL: SIGSEGV
    // with fault addr 0x0 inside loadModel. An empty override is also what the
    // declaration's own default (= "") means, and it takes the
    // chat_template_override.empty() branch that reads the model's template.
    try {
        g_tmpls = common_chat_templates_init(model, /* chat_template_override */ std::string());
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "chat templates initialised (explicit=%d)",
                            (int) common_chat_templates_was_explicit(g_tmpls.get()));
    } catch (const std::exception & e) {
        g_tmpls.reset();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "common_chat_templates_init failed: %s", e.what());
    }

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

    // Tokenize exactly the way every llama.cpp example does (common_tokenize):
    // add_special=true, parse_special=true. For this GGUF add_bos/add_eos are
    // both false, so this only affects how special-token strings are matched.
    std::vector<llama_token> tokens = tokenize_prompt(vocab, prompt);
    if (tokens.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "generate: tokenize produced no tokens");
        return env->NewStringUTF("error|tokenize produced no tokens");
    }
    const int n_tokens = (int) tokens.size();
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "generate: prompt tokenized to %d tokens (add_bos=%d add_eos=%d)",
                        n_tokens, (int) llama_vocab_get_add_bos(vocab),
                        (int) llama_vocab_get_add_eos(vocab));

    // Create batch using official llama.cpp pattern: let llama.cpp auto-manage positions
    // llama_batch_get_one returns pos=nullptr, logits=nullptr (defaults to last token logits)
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    // Note: llama_batch_get_one returns logits=nullptr which defaults to "only last token logits"

    // Each call is an independent completion: start from an empty KV cache
    reset_memory();

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
    const char *stop_reason = "n_predict";

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: starting generation loop, n_predict=%d", n_predict);

    for (int i = 0; i < n_predict; ++i) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "generate: step %d, sampling...", i);
        llama_token token = llama_sampler_sample(smpl, g_ctx, -1);
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "generate: step %d, sampled token=%d", i, token);
        if (token == -1) {
            stop_reason = "sampler_eof";
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generate: sampler returned -1, stopping");
            break;
        }
        llama_sampler_accept(smpl, token);

        // EOG is the generation boundary: stop before rendering it
        if (llama_vocab_is_eog(vocab, token)) {
            stop_reason = "eog";
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generate: EOG id=%d after %d token(s)", token, n_generated);
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
            stop_reason = "decode_error";
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
        "|stop=" + stop_reason + "|text=" + generated_text;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generation done: %s", log_escape(result).c_str());
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

    g_tmpls.reset();

    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    const std::string result = "ok|model unloaded";
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// Gets the chat template from the loaded model.
// Returns a '|' separated status string:
//   ok|<template>
//   error|<reason>
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_getChatTemplate(JNIEnv *env, jobject /*runtime*/) {
    if (g_model == nullptr) {
        return env->NewStringUTF("error|no model loaded");
    }

    const char *template_str = llama_model_chat_template(g_model, nullptr);
    if (template_str == nullptr) {
        return env->NewStringUTF("error|no chat template available");
    }

    const std::string result = "ok|" + std::string(template_str);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "chat template retrieved, length=%zu", std::string(template_str).size());
    return env->NewStringUTF(result.c_str());
}

// Runs one chat turn and returns either assistant text or tool calls.
//
// jchatJson envelope:
//   {"messages":[{"role":"system|user|assistant|tool","content":"...",
//                 "tool_calls":[{"id","name","arguments"}],
//                 "tool_name":"...","tool_call_id":"..."}],
//    "tools":[{"name":"...","description":"...","parameters":"{...}"}]}
//
// The prompt, the tool-call grammar and the reply parser are all produced by
// llama.cpp from the model's own chat template (common_chat_templates_apply +
// common_chat_parse). Waqti adds no format knowledge of its own.
//
// Result string (text is last so it can carry '|'):
//   ok|<ms>|tokens=<n>|stop=<reason>|tool_calls_b64=<base64 JSON array>|text=<text>
//   error|<reason>
extern "C" JNIEXPORT jstring JNICALL
Java_com_waqti_agent_runtime_NativeRuntime_generateChat(JNIEnv *env, jobject /*runtime*/,
                                                         jstring jchatJson, jint n_predict,
                                                         jfloat temperature, jint top_k,
                                                         jfloat top_p, jint seed) {
    if (g_model == nullptr) {
        return env->NewStringUTF("error|no model loaded");
    }
    if (g_ctx == nullptr) {
        return env->NewStringUTF("error|no context created");
    }
    if (g_tmpls == nullptr) {
        return env->NewStringUTF("error|chat templates not initialised");
    }

    std::lock_guard<std::mutex> guard(g_ctx_mutex);

    if (jchatJson == nullptr) {
        return env->NewStringUTF("error|chat json is null");
    }
    const char *chars = env->GetStringUTFChars(jchatJson, nullptr);
    if (chars == nullptr) {
        return env->NewStringUTF("error|GetStringUTFChars failed");
    }
    const std::string chat_json(chars);
    env->ReleaseStringUTFChars(jchatJson, chars);

    std::vector<common_chat_msg> msgs;
    std::vector<common_chat_tool> tools;
    std::string err;
    if (!parse_chat_envelope(chat_json, msgs, tools, err)) {
        const std::string result = "error|malformed chat json: " + err;
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s (json=%s)",
                            result.c_str(), log_escape(chat_json).c_str());
        return env->NewStringUTF(result.c_str());
    }
    if (msgs.empty()) {
        return env->NewStringUTF("error|no valid messages in chat");
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "generateChat: parsed %zu message(s), %zu tool(s)",
                        msgs.size(), tools.size());

    // The model's own template does the formatting. With tools present it also
    // emits the tool-call grammar plus the lazy triggers that switch the
    // constraint on only once the model starts a tool call.
    common_chat_templates_inputs inputs;
    inputs.messages             = msgs;
    inputs.tools                = tools;
    inputs.add_generation_prompt = true;
    inputs.use_jinja            = true;
    inputs.parallel_tool_calls  = false;
    inputs.enable_thinking      = false;   // Qwen2.5 instruct: no thinking block
    inputs.reasoning_format     = COMMON_REASONING_FORMAT_NONE;
    inputs.tool_choice          = tools.empty() ? COMMON_CHAT_TOOL_CHOICE_NONE
                                               : COMMON_CHAT_TOOL_CHOICE_AUTO;

    common_chat_params params;
    try {
        params = common_chat_templates_apply(g_tmpls.get(), inputs);
    } catch (const std::exception & e) {
        const std::string result = std::string("error|chat template apply failed: ") + e.what();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", result.c_str());
        return env->NewStringUTF(result.c_str());
    }

    const std::string prompt = params.prompt;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "generateChat: formatted prompt (%zu bytes) = %s",
                        prompt.size(), log_escape(prompt).c_str());
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "generateChat: grammar=%s triggers=%zu additional_stops=%zu",
                        params.grammar.empty() ? "<none>" : "yes",
                        params.grammar_triggers.size(), params.additional_stops.size());
    for (const auto & stop : params.additional_stops) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "generateChat: additional stop = %s", log_escape(stop).c_str());
    }

    const struct llama_vocab *vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        return env->NewStringUTF("error|failed to get vocab");
    }

    // add_special=true, parse_special=true — same call every llama.cpp example
    // makes (common_tokenize). parse_special=true is what makes
    // tokenizer_st_partition() map the ChatML markers to the model's real
    // control ids instead of splitting them into ordinary text.
    std::vector<llama_token> tokens = tokenize_prompt(vocab, prompt);
    if (tokens.empty()) {
        return env->NewStringUTF("error|tokenize produced no tokens");
    }
    const int n_tokens = (int) tokens.size();
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "generateChat: prompt tokenized to %d tokens (add_bos=%d add_eos=%d)",
                        n_tokens, (int) llama_vocab_get_add_bos(vocab),
                        (int) llama_vocab_get_add_eos(vocab));

    // Evidence that parse_special=true really mapped the ChatML markers to the
    // model's own control ids instead of splitting them into text pieces.
    {
        std::string ids;
        int n_special = 0;
        for (int t = 0; t < n_tokens; ++t) {
            if (llama_vocab_is_control(vocab, tokens[t])) {
                ++n_special;
            }
            if (t < 32) {
                ids += std::to_string(tokens[t]);
                ids += " ";
            }
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "generateChat: prompt ids = %s| control tokens in prompt = %d",
                            ids.c_str(), n_special);
    }

    // Each call re-prefills the complete conversation, so start from an empty
    // KV cache rather than on top of the previous turn's.
    reset_memory();

    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);

    const auto gen_started = std::chrono::steady_clock::now();
    int ret = llama_decode(g_ctx, batch);
    if (ret != 0) {
        const std::string result = "error|llama_decode prefill failed with code " + std::to_string(ret);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", result.c_str());
        return env->NewStringUTF(result.c_str());
    }

    // Same chain llama.cpp's own common_sampler builds: top_k -> top_p -> temp
    // -> dist(seed).
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));

    // Tool-call constraint. llama.cpp derives both the grammar and the trigger
    // from the template's own tool format, so this works for any model whose
    // template supports tools, with no per-model code here. Triggers are regex
    // escaped exactly like common/sampling.cpp does before being handed to the
    // lazy sampler, so a literal marker is not treated as a regex.
    llama_sampler *grmr = nullptr;
    if (!params.grammar.empty()) {
        const std::string &grammar_str = params.grammar;
        // Trigger handling mirrors common/sampling.cpp exactly: a WORD trigger is
        // a literal marker and must be regex-escaped before it reaches the lazy
        // sampler, a PATTERN is already a regex, a PATTERN_FULL is anchored, and a
        // TOKEN trigger is matched on the token id. Treating a literal marker as
        // a regex is how a trigger like {"type": "function", silently never
        // matches and the grammar never engages.
        std::vector<std::string> trigger_patterns;
        std::vector<const char *> trigger_patterns_c;
        std::vector<llama_token> trigger_tokens;
        for (const auto & trigger : params.grammar_triggers) {
            switch (trigger.type) {
                case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:
                    trigger_patterns.push_back(regex_escape(trigger.value));
                    break;
                case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
                    trigger_patterns.push_back(trigger.value);
                    break;
                case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL: {
                    const auto & pattern = trigger.value;
                    if (pattern.empty()) break;
                    std::string anchored = "^*$";
                    anchored = (pattern.front() != '^' ? "^" : "")
                             + pattern
                             + (pattern.back() != '$' ? "$" : "");
                    trigger_patterns.push_back(anchored);
                    break;
                }
                case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                    trigger_tokens.push_back(trigger.token);
                    break;
                default:
                    break;
            }
        }
        for (const auto & p : trigger_patterns) trigger_patterns_c.push_back(p.c_str());
        for (auto t : trigger_tokens) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generateChat: grammar token trigger id=%d", (int) t);
        }
        for (const auto & p : trigger_patterns) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generateChat: grammar trigger pattern = %s", log_escape(p).c_str());
        }

        try {
            if (params.grammar_lazy) {
                grmr = llama_sampler_init_grammar_lazy_patterns(
                    vocab, grammar_str.c_str(), "root",
                    trigger_patterns_c.data(), trigger_patterns_c.size(),
                    trigger_tokens.data(), trigger_tokens.size());
            } else {
                grmr = llama_sampler_init_grammar(vocab, grammar_str.c_str(), "root");
            }
        } catch (const std::exception & e) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                "generateChat: grammar init failed: %s", e.what());
            grmr = nullptr;
        }
        if (grmr != nullptr) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generateChat: tool grammar active, lazy=%d, %zu trigger(s)",
                                (int) params.grammar_lazy, trigger_patterns_c.size());
            // The grammar only constrains tokens once a trigger matches, so a
            // plain-text answer is still free to be plain text.
            if (!params.grammar_lazy) {
                llama_sampler_chain_add(smpl, grmr);
                grmr = nullptr;  // owned by the chain now
            }
        }
    }

    // The candidate set the chain samples from. Built from the raw logits the
    // same way llama.cpp's own common_sampler does, so the lazy grammar sampler
    // is applied to exactly the same candidates the chain would have picked
    // from. `float *` here because llama_get_logits_ith() is the logit row API,
    // not the candidate-array API.
    std::vector<llama_token_data> candidates_buf;
    candidates_buf.resize(llama_vocab_n_tokens(vocab));
    llama_token_data_array candidates = { nullptr, 0, -1, false };

    auto refresh_candidates = [&]() {
        const float *logits = llama_get_logits_ith(g_ctx, -1);
        if (logits == nullptr) {
            candidates.data = nullptr;
            candidates.size = 0;
            candidates.selected = -1;
            return;
        }
        for (size_t t = 0; t < candidates_buf.size(); ++t) {
            candidates_buf[t] = llama_token_data{ (llama_token) t, logits[t], 0.0f };
        }
        candidates.data = candidates_buf.data();
        candidates.size = (int32_t) candidates_buf.size();
        candidates.selected = -1;
    };
    refresh_candidates();

    std::string generated_text;
    int n_generated = 0;
    const char *stop_reason = "n_predict";

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "generateChat: starting generation loop, n_predict=%d", n_predict);

    for (int i = 0; i < n_predict; ++i) {
        llama_token token;
        if (grmr != nullptr) {
            // Grammar-constrained path, mirroring common_sampler_sample(): the
            // grammar is applied to the candidate set first so an invalid token
            // can never be selected, then the sampling chain picks among what
            // survived.
            if (candidates.data == nullptr || candidates.size == 0) {
                stop_reason = "sampler_eof";
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                    "generateChat: no candidates for grammar-constrained step");
                break;
            }
            candidates.selected = -1;
            llama_sampler_apply(grmr, &candidates);
            llama_sampler_apply(smpl, &candidates);
            if (candidates.selected < 0) {
                stop_reason = "sampler_eof";
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                    "generateChat: grammar + chain selected no token");
                break;
            }
            token = candidates.data[candidates.selected].id;
        } else {
            token = llama_sampler_sample(smpl, g_ctx, -1);
        }
        if (token == -1) {
            stop_reason = "sampler_eof";
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generateChat: sampler returned -1, stopping");
            break;
        }
        llama_sampler_accept(smpl, token);

        // EOG is the generation boundary. Stop *before* rendering it, so the
        // control token never becomes user-visible text. This is why no
        // post-hoc string stripping of the end-of-turn marker is needed.
        if (llama_vocab_is_eog(vocab, token)) {
            char eog_buf[64] = {};
            const int eog_len = llama_token_to_piece(vocab, token, eog_buf,
                                                     (int32_t) sizeof(eog_buf), 0, true);
            stop_reason = "eog";
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generateChat: EOG id=%d piece=%s after %d token(s)",
                                token,
                                log_escape(std::string(eog_buf, eog_len > 0 ? eog_len : 0)).c_str(),
                                n_generated);
            break;
        }

        // special=false: llama.cpp renders 0 chars for CONTROL/UNKNOWN tokens,
        // so a sampled special token cannot leak into user text at all.
        char piece_buf[128];
        const int n_chars = llama_token_to_piece(vocab, token, piece_buf,
                                                 (int32_t) sizeof(piece_buf), 0, false);
        if (n_chars > 0) {
            generated_text.append(piece_buf, n_chars);
        } else {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generateChat: token id=%d rendered as 0 chars (special, withheld)",
                                token);
        }

        if (grmr != nullptr) {
            llama_sampler_accept(grmr, token);
        }

        // The template's own additional stops (for example the end of a tool
        // call block). Checked against what was actually rendered, never by
        // searching the text for a hardcoded marker.
        bool hit_stop = false;
        for (const auto & stop : params.additional_stops) {
            if (!stop.empty() && generated_text.size() >= stop.size() &&
                generated_text.compare(generated_text.size() - stop.size(), stop.size(), stop) == 0) {
                generated_text.resize(generated_text.size() - stop.size());
                hit_stop = true;
                break;
            }
        }

        llama_batch next_batch = llama_batch_get_one(&token, 1);

        ret = llama_decode(g_ctx, next_batch);
        if (ret != 0) {
            stop_reason = "decode_error";
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                "llama_decode generation step failed: %d", ret);
            break;
        }

        n_generated++;

        if (hit_stop) {
            stop_reason = "stop_string";
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generateChat: additional stop hit after %d token(s)", n_generated);
            break;
        }

        refresh_candidates();
    }

    if (grmr != nullptr) {
        llama_sampler_free(grmr);
    }
    llama_sampler_free(smpl);

    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - gen_started).count();

    // Parse the reply with llama.cpp's own parser for THIS template, so a tool
    // call comes back as a structured {id, name, arguments} triple rather than
    // text we would have to pattern match.
    //
    // The parser is a PEG arena that common_chat_templates_apply() serialised
    // into params.parser; common_chat_parser_params' constructor copies only the
    // format and the generation prompt, NOT the arena. Calling common_chat_parse
    // with that would hit the empty-arena fallback — a parser that accepts any
    // text as content — and silently return zero tool calls for every reply.
    // tests/test-chat.cpp (make_peg_parser) loads the arena explicitly for the
    // same reason; this mirrors it.
    common_peg_arena arena;
    common_chat_parser_params parser_params(params);
    parser_params.parse_tool_calls = !tools.empty();
    std::string parse_error;
    try {
        if (!params.parser.empty()) {
            arena.load(params.parser);
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                "generateChat: template produced no PEG parser; "
                                "the reply will be parsed as plain content only");
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "generateChat: parser arena %s (%zu bytes), parse_tool_calls=%d, "
                            "generation_prompt=%s",
                            arena.empty() ? "empty" : "loaded", params.parser.size(),
                            (int) parser_params.parse_tool_calls,
                            log_escape(parser_params.generation_prompt).c_str());
    } catch (const std::exception & e) {
        parse_error = e.what();
    }

    common_chat_msg parsed;
    if (parse_error.empty()) {
        try {
            parsed = common_chat_peg_parse(arena, generated_text, /*is_partial*/ false, parser_params);
        } catch (const std::exception & e) {
            parse_error = e.what();
        }
    }

    std::string tool_calls_json = "[";
    if (!parse_error.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "generateChat: chat parse failed: %s", parse_error.c_str());
        stop_reason = "parse_error";
    } else {
        for (size_t k = 0; k < parsed.tool_calls.size(); ++k) {
            if (k > 0) tool_calls_json += ",";
            const auto & tc = parsed.tool_calls[k];
            tool_calls_json += "{\"id\":\"";
            json_escape_into(tool_calls_json, tc.id);
            tool_calls_json += "\",\"name\":\"";
            json_escape_into(tool_calls_json, tc.name);
            tool_calls_json += "\",\"arguments\":\"";
            json_escape_into(tool_calls_json, tc.arguments);
            tool_calls_json += "\"}";
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "generateChat: parsed reply: content=%zu chars, %zu tool call(s)",
                            parsed.content.size(), parsed.tool_calls.size());
        for (const auto & tc : parsed.tool_calls) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "generateChat: tool call name=%s id=%s arguments=%s",
                                log_escape(tc.name).c_str(), log_escape(tc.id).c_str(),
                                log_escape(tc.arguments).c_str());
        }
    }
    tool_calls_json += "]";

    // With tool calls present the model's prose is usually empty; content is
    // still carried through so a mixed reply (text + call) survives intact.
    const std::string final_text =
        parsed.tool_calls.empty() ? generated_text : parsed.content;

    const std::string result =
        "ok|" + std::to_string(elapsed_ms) + " ms|tokens=" + std::to_string(n_generated) +
        "|stop=" + stop_reason +
        "|tool_calls_b64=" + base64_encode(tool_calls_json) +
        "|text=" + final_text;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "generation done: %s", log_escape(result).c_str());
    return env->NewStringUTF(result.c_str());
}
