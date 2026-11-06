#include <jni.h>
#include <llama.h>
#include <atomic>
#include <mutex>
#include <memory>
#include <vector>
#include <string>
#include <stdexcept>
#include <chrono>
#include <algorithm>

namespace {
constexpr int CONTEXT = 1024, BATCH = 128, OUTPUT = 128;
std::atomic<bool> cancelled{false};
std::atomic<uint64_t> epoch{0};
std::mutex lock;
using Clock = std::chrono::steady_clock;
struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_batch_ext * batch = nullptr;
    llama_sampler * sampler = nullptr;
    int position = 0, tokens = 0;
    int64_t nanos = 0;
    uint64_t ticket = 0;
    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (batch) llama_batch_ext_free(batch);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
std::unique_ptr<Session> session;
bool abort_cpu(void * data) { return cancelled.load() || static_cast<Session *>(data)->ticket != epoch.load(); }
void error(JNIEnv * env, const char * message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
std::string bytes(JNIEnv * env, jbyteArray value) {
    const auto count = env->GetArrayLength(value);
    std::string result(count, '\0');
    env->GetByteArrayRegion(value, 0, count, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
Session & ready() { if (!session) throw std::runtime_error("Load a verified model first."); return *session; }
void process(Session & s, const llama_token * tokens, int count) {
    llama_batch_ext_clear(s.batch);
    for (int i = 0; i < count; ++i) {
        const int idx = llama_batch_ext_add_token(s.batch, 0, tokens[i]);
        const llama_pos pos = s.position + i;
        if (idx < 0 || !llama_batch_ext_set_pos(s.batch, idx, &pos)) throw std::runtime_error("Native batch rejected.");
    }
    llama_batch_ext_set_output_logits(s.batch, count - 1, true);
    if (llama_process(s.context, LLAMA_PROCESS_TYPE_DECODE, s.batch) != 0) throw std::runtime_error(cancelled ? "Response stopped." : "Native decode failed.");
    s.position += count;
}
}

extern "C" JNIEXPORT jstring JNICALL Java_app_lfm3_probe_inference_NativeLlama_info(JNIEnv * env, jobject) {
    static std::once_flag once;
    std::call_once(once, [] { llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr); llama_backend_init(); });
    return env->NewStringUTF("llama.cpp v0.8.0 / 8642541b2 / ARM64 CPU");
}
extern "C" JNIEXPORT void JNICALL Java_app_lfm3_probe_inference_NativeLlama_cancel(JNIEnv *, jobject) { ++epoch; cancelled = true; }
extern "C" JNIEXPORT jlong JNICALL Java_app_lfm3_probe_inference_NativeLlama_prepare(JNIEnv *, jobject) { cancelled = false; return ++epoch; }
extern "C" JNIEXPORT void JNICALL Java_app_lfm3_probe_inference_NativeLlama_unload(JNIEnv *, jobject) { std::lock_guard<std::mutex> guard(lock); session.reset(); }

extern "C" JNIEXPORT void JNICALL Java_app_lfm3_probe_inference_NativeLlama_load(JNIEnv * env, jobject, jstring path) {
    std::lock_guard<std::mutex> guard(lock);
    try {
        session.reset(); cancelled = false;
        auto s = std::make_unique<Session>();
        auto parameters = llama_model_default_params();
        parameters.n_gpu_layers = 0;
        parameters.load_mode = LLAMA_LOAD_MODE_MMAP;
        parameters.progress_callback = [](float, void *) { return !cancelled.load(); };
        const char * file = env->GetStringUTFChars(path, nullptr);
        s->model = llama_model_load_from_file(file, parameters);
        env->ReleaseStringUTFChars(path, file);
        if (!s->model) throw std::runtime_error("Model loading failed. Check free memory and model compatibility.");
        char arch[64] = {};
        llama_model_meta_val_str(s->model, "general.architecture", arch, sizeof(arch));
        if (std::string(arch) != "lfm3") throw std::runtime_error("This adapter requires an LFM3 text checkpoint.");
        auto params = llama_context_default_params();
        params.n_ctx = CONTEXT; params.n_batch = BATCH; params.n_ubatch = BATCH; params.n_seq_max = 1;
        params.n_threads = 4; params.n_threads_batch = 4;
        params.abort_callback = abort_cpu; params.abort_callback_data = s.get(); params.offload_kqv = false; params.op_offload = false;
        s->context = llama_init_from_model(s->model, params);
        if (!s->context) throw std::runtime_error("Unable to allocate the model context.");
        s->batch = llama_batch_ext_init(s->context);
        s->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (!s->batch || !s->sampler) throw std::runtime_error("Unable to allocate native generation state.");
        // Greedy sampling is intentional for the first reproducible correctness probe.
        llama_sampler_chain_add(s->sampler, llama_sampler_init_greedy());
        session = std::move(s);
    } catch (const std::exception & e) { error(env, e.what()); }
}

extern "C" JNIEXPORT void JNICALL Java_app_lfm3_probe_inference_NativeLlama_begin(JNIEnv * env, jobject, jobjectArray contents, jobjectArray roles, jlong ticket) {
    std::lock_guard<std::mutex> guard(lock);
    try {
        auto & s = ready();
        s.ticket = ticket;
        if (abort_cpu(&s)) throw std::runtime_error("Response stopped.");
        // Every turn is reconstructed. Aborted hybrid state is never reused implicitly.
        llama_memory_clear(llama_get_memory(s.context), true);
        llama_sampler_reset(s.sampler); s.position = 0; s.tokens = 0; s.nanos = 0;
        int count = env->GetArrayLength(contents);
        if (count != env->GetArrayLength(roles) || count < 1 || count > 201) throw std::runtime_error("Invalid chat history.");
        std::vector<std::string> text, role;
        for (int i = 0; i < count; ++i) {
            auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            text.push_back(bytes(env, item)); env->DeleteLocalRef(item);
            auto r = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            const char * str = env->GetStringUTFChars(r, nullptr); role.emplace_back(str);
            env->ReleaseStringUTFChars(r, str); env->DeleteLocalRef(r);
        }
        std::vector<llama_chat_message> messages;
        for (int i = 0; i < count; ++i) messages.push_back({role[i].c_str(), text[i].c_str()});
        const char * tmpl = llama_model_chat_template(s.model, nullptr);
        if (!tmpl) throw std::runtime_error("The checkpoint has no chat template.");
        int size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
        if (size <= 0 || size > 1024 * 1024) throw std::runtime_error("Unsupported chat template.");
        std::string prompt(size, '\0');
        llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, prompt.data(), size);
        auto vocab = llama_model_get_vocab(s.model);
        int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (n <= 0 || n + OUTPUT > CONTEXT) throw std::runtime_error("Context limit reached (1024 tokens including output). Start a new chat.");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, true, true) != n) throw std::runtime_error("Tokenization failed.");
        for (int i = 0; i < n; i += BATCH) { if (cancelled) throw std::runtime_error("Response stopped."); process(s, tokens.data() + i, std::min(BATCH, n - i)); }
    } catch (const std::exception & e) { error(env, e.what()); }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_lfm3_probe_inference_NativeLlama_next(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    try {
        auto & s = ready();
        if (abort_cpu(&s) || s.tokens >= OUTPUT) return nullptr;
        auto start = Clock::now();
        auto vocab = llama_model_get_vocab(s.model);
        auto token = llama_sampler_sample(s.sampler, s.context, -1);
        if (llama_vocab_is_eog(vocab, token)) return nullptr;
        std::vector<char> piece(256);
        int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
        if (n < 0) throw std::runtime_error("Token decoding failed.");
        process(s, &token, 1);
        ++s.tokens;
        s.nanos += std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
        auto out = env->NewByteArray(n);
        env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(piece.data()));
        return out;
    } catch (const std::exception & e) { error(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_lfm3_probe_inference_NativeLlama_usage(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    jlong stats[2] = {session ? session->tokens : 0, session ? session->nanos : 0};
    auto out = env->NewLongArray(2); env->SetLongArrayRegion(out, 0, 2, stats); return out;
}
