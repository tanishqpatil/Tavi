#include <jni.h>
#include <llama.h>
#include <mtmd.h>
#include <mtmd-helper.h>
#include <atomic>
#include <mutex>
#include <memory>
#include <vector>
#include <string>
#include <stdexcept>
#include <chrono>
#include <algorithm>
#include <cstring>

namespace {
constexpr int BATCH = 128;
std::atomic<bool> cancelled{false};
std::atomic<uint64_t> epoch{0};
std::mutex lock;
using Clock = std::chrono::steady_clock;
struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_batch_ext * batch = nullptr;
    llama_sampler * sampler = nullptr;
    mtmd_context * media = nullptr;
    mtmd_helper_gen_audio * speech = nullptr;
    std::vector<float> hidden;
    bool finished = false, limited = false;
    int audio_rate = 24000, steps = 0;
    int64_t audio_samples = 0;
    int position = 0, tokens = 0;
    int64_t nanos = 0;
    uint64_t ticket = 0;
    int context_limit = 1024, output_limit = 128;
    ~Session() {
        if (speech) mtmd_helper_gen_audio_free(speech);
        if (media) mtmd_free(media);
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
    if (llama_process(s.context, LLAMA_PROCESS_TYPE_DECODE, s.batch) != 0) throw std::runtime_error(abort_cpu(&s) ? "Response stopped." : "Native decode failed.");
    s.position += count;
}
}

extern "C" JNIEXPORT jstring JNICALL Java_app_lfm3_probe_inference_NativeLlama_info(JNIEnv * env, jobject) {
    static std::once_flag once;
    std::call_once(once, [] { llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr); mtmd_helper_log_set([](ggml_log_level, const char *, void *) {}, nullptr); llama_backend_init(); });
    return env->NewStringUTF("llama.cpp v0.8.0 / 8642541b2 / ARM64 CPU");
}
extern "C" JNIEXPORT void JNICALL Java_app_lfm3_probe_inference_NativeLlama_cancel(JNIEnv *, jobject) { ++epoch; cancelled = true; }
extern "C" JNIEXPORT jlong JNICALL Java_app_lfm3_probe_inference_NativeLlama_prepare(JNIEnv *, jobject) { cancelled = false; return ++epoch; }
extern "C" JNIEXPORT void JNICALL Java_app_lfm3_probe_inference_NativeLlama_unload(JNIEnv *, jobject) { std::lock_guard<std::mutex> guard(lock); session.reset(); }

extern "C" JNIEXPORT void JNICALL Java_app_lfm3_probe_inference_NativeLlama_load(JNIEnv * env, jobject, jstring path, jint context_tokens, jstring projector) {
    std::lock_guard<std::mutex> guard(lock);
    try {
        session.reset(); cancelled = false;
        auto s = std::make_unique<Session>();
        s->ticket = epoch.load();
        if (context_tokens < 1024 || context_tokens > 8192) throw std::runtime_error("Invalid context size.");
        s->context_limit = context_tokens;
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
        if (std::string(arch) != "lfm3") throw std::runtime_error("This adapter requires an LFM3 checkpoint.");
        const char * proj = env->GetStringUTFChars(projector, nullptr);
        std::string proj_file(proj); env->ReleaseStringUTFChars(projector, proj);
        if (!proj_file.empty()) {
            auto mp = mtmd_context_params_default(); mp.use_gpu = false; mp.n_threads = 4; mp.warmup = false;
            mp.progress_callback = [](float, void *) { return !cancelled.load(); };
            mp.cb_eval = [](ggml_tensor *, bool ask, void * data) { return ask || !abort_cpu(data); };
            mp.cb_eval_user_data = s.get();
            s->media = mtmd_init_from_file(proj_file.c_str(), s->model, mp);
            if (!s->media) throw std::runtime_error("Unable to load the matching multimodal component.");
        }
        auto params = llama_context_default_params();
        params.n_ctx = s->context_limit; params.n_batch = BATCH; params.n_ubatch = BATCH; params.n_seq_max = 1;
        params.n_threads = 4; params.n_threads_batch = 4;
        params.abort_callback = abort_cpu; params.abort_callback_data = s.get(); params.offload_kqv = false; params.op_offload = false;
        params.embeddings = s->media && mtmd_gen_audio_get_info(s->media).type != MTMD_GEN_AUDIO_TYPE_NONE;
        s->context = llama_init_from_model(s->model, params);
        if (!s->context) throw std::runtime_error("Unable to allocate the model context.");
        s->batch = llama_batch_ext_init(s->context);
        s->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (!s->batch || !s->sampler) throw std::runtime_error("Unable to allocate native generation state.");
        // Greedy sampling is intentional for the first reproducible correctness probe.
        llama_sampler_chain_add(s->sampler, llama_sampler_init_greedy());
        if (params.embeddings) {
            s->speech = mtmd_helper_gen_audio_init(s->context, s->media);
            if (!s->speech) throw std::runtime_error("Speech codec initialization failed.");
        }
        session = std::move(s);
    } catch (const std::exception & e) { error(env, e.what()); }
}

extern "C" JNIEXPORT void JNICALL Java_app_lfm3_probe_inference_NativeLlama_begin(JNIEnv * env, jobject, jobjectArray contents, jobjectArray roles, jlong ticket, jint output_limit, jobjectArray media_files, jboolean speak) {
    std::lock_guard<std::mutex> guard(lock);
    try {
        auto & s = ready();
        s.ticket = ticket;
        if (output_limit < 1 || output_limit > s.context_limit / 2) throw std::runtime_error("Output limit exceeds half the loaded context. Adjust settings or reload the model.");
        s.output_limit = output_limit;
        s.finished = false; s.limited = false; s.steps = 0; s.audio_samples = 0; s.hidden.clear();
        if (s.speech) mtmd_helper_gen_audio_reset(s.speech);
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
        std::string prefix;
        if (s.speech) {
            const int base_size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), false, nullptr, 0);
            if (base_size <= 0 || base_size >= size) throw std::runtime_error("Unsupported audio chat template.");
            std::string base(base_size, '\0');
            llama_chat_apply_template(tmpl, messages.data(), messages.size(), false, base.data(), base_size);
            if (prompt.compare(0, base.size(), base) != 0) throw std::runtime_error("Audio template continuation is unsupported.");
            prefix = prompt.substr(base.size()); prompt = std::move(base);
        }
        auto vocab = llama_model_get_vocab(s.model);
        const int n_media = env->GetArrayLength(media_files);
        if (n_media > 8) throw std::runtime_error("This session exceeds eight media attachments. Start a new chat.");
        if (s.media) {
            using Bitmap = std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>;
            std::vector<Bitmap> owned;
            std::vector<const mtmd_bitmap *> bitmaps;
            for (int i = 0; i < n_media; ++i) {
                if (abort_cpu(&s)) throw std::runtime_error("Response stopped.");
                auto value = static_cast<jstring>(env->GetObjectArrayElement(media_files, i));
                const char * name = env->GetStringUTFChars(value, nullptr);
                auto wrapper = mtmd_helper_bitmap_init_from_file(s.media, name, false, mtmd_helper_init_opt_default());
                env->ReleaseStringUTFChars(value, name); env->DeleteLocalRef(value);
                if (!wrapper.bitmap) throw std::runtime_error("Attachment decoding failed.");
                owned.emplace_back(wrapper.bitmap, mtmd_bitmap_free); bitmaps.push_back(wrapper.bitmap);
            }
            auto chunks = std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)>(mtmd_input_chunks_init(), mtmd_input_chunks_free);
            mtmd_input_text input{prompt.c_str(), prompt.size(), true, true};
            if (mtmd_tokenize(s.media, chunks.get(), &input, bitmaps.data(), bitmaps.size()) != 0) throw std::runtime_error("Multimodal preprocessing failed.");
            const int reserve = s.output_limit + (s.speech ? (int) prefix.size() + 2 : 0);
            if (mtmd_helper_get_n_pos(chunks.get()) + reserve > s.context_limit) throw std::runtime_error("Media and output exceed context. Increase context, shorten audio, or start a new chat.");
            if (mtmd_helper_eval_chunks(s.media, s.context, chunks.get(), 0, 0, BATCH, true, &s.position) != 0) throw std::runtime_error(abort_cpu(&s) ? "Response stopped." : "Multimodal encoding failed.");
        } else {
        if (n_media) throw std::runtime_error("Load a multimodal pack for attachments.");
        int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (n <= 0 || n + s.output_limit > s.context_limit) throw std::runtime_error("Context limit reached including output. Start a new chat or increase context and reload.");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, true, true) != n) throw std::runtime_error("Tokenization failed.");
        for (int i = 0; i < n; i += BATCH) { if (cancelled) throw std::runtime_error("Response stopped."); process(s, tokens.data() + i, std::min(BATCH, n - i)); }
        }
        if (s.speech) {
            auto input = mtmd_helper_gen_audio_inp_default();
            input.prompt = prefix.c_str(); input.prompt_len = prefix.size(); input.n_past = s.position;
            input.stream = true; input.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
            input.out_mode = speak ? MTMD_HELPER_GEN_AUDIO_OUT_MIXED : MTMD_HELPER_GEN_AUDIO_OUT_TEXT;
            if (mtmd_helper_gen_audio_set_input(s.speech, &input) != 0) throw std::runtime_error("Audio response initialization failed.");
            int ret;
            while ((ret = mtmd_helper_gen_audio_step_prompt(s.speech, BATCH)) > 0) { if (abort_cpu(&s)) throw std::runtime_error("Response stopped."); }
            if (ret < 0) throw std::runtime_error("Audio response prefill failed.");
            const float * h = llama_get_embeddings_ith(s.context, -1);
            if (!h) throw std::runtime_error("Missing hidden state for speech generation.");
            s.hidden.assign(h, h + llama_model_n_embd(s.model));
            llama_sampler_free(s.sampler); s.sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
            size_t n_bias = 0; const auto bias = mtmd_helper_gen_audio_get_logit_bias(s.speech, &n_bias);
            if (n_bias) llama_sampler_chain_add(s.sampler, llama_sampler_init_logit_bias(llama_vocab_n_tokens(vocab), n_bias, bias));
            llama_sampler_chain_add(s.sampler, llama_sampler_init_greedy());
        }
    } catch (const std::exception & e) { error(env, e.what()); }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_lfm3_probe_inference_NativeLlama_next(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    try {
        auto & s = ready();
        if (s.finished) return nullptr;
        if (abort_cpu(&s)) throw std::runtime_error("Response stopped.");
        if (s.tokens >= s.output_limit || s.steps >= s.context_limit || llama_memory_seq_pos_max(llama_get_memory(s.context), 0) + 2 >= s.context_limit || s.audio_samples >= s.audio_rate * 30LL) {
            s.finished = true; s.limited = true;
            if (s.speech) mtmd_helper_gen_audio_flush(s.speech);
            return nullptr;
        }
        auto start = Clock::now();
        auto vocab = llama_model_get_vocab(s.model);
        auto token = (!s.speech || mtmd_helper_gen_audio_next_is_text(s.speech)) ? llama_sampler_sample(s.sampler, s.context, -1) : LLAMA_TOKEN_NULL;
        bool stop = false;
        if (s.speech) {
            const float * next_hidden = nullptr;
            if (mtmd_helper_gen_audio_step_gen(s.speech, token, s.hidden.data(), &next_hidden, &stop) != 0) throw std::runtime_error(abort_cpu(&s) ? "Response stopped." : "Speech generation failed.");
            if (next_hidden) s.hidden.assign(next_hidden, next_hidden + llama_model_n_embd(s.model));
        } else stop = llama_vocab_is_eog(vocab, token);
        if (stop) { s.finished = true; if (s.speech) mtmd_helper_gen_audio_flush(s.speech); return nullptr; }
        std::vector<char> piece(256);
        const char * spelling = token == LLAMA_TOKEN_NULL ? "" : llama_vocab_get_text(vocab, token);
        const bool preserve_thinking = spelling && (std::strcmp(spelling, "<think>") == 0 || std::strcmp(spelling, "</think>") == 0);
        int n = token == LLAMA_TOKEN_NULL ? 0 : llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, preserve_thinking);
        if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, preserve_thinking); }
        if (n < 0) throw std::runtime_error("Token decoding failed.");
        if (!s.speech) process(s, &token, 1);
        if (token != LLAMA_TOKEN_NULL) ++s.tokens;
        ++s.steps;
        s.nanos += std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
        auto out = env->NewByteArray(n);
        env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(piece.data()));
        return out;
    } catch (const std::exception & e) { error(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_lfm3_probe_inference_NativeLlama_usage(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    jlong stats[3] = {session ? session->tokens : 0, session ? session->nanos : 0, session && session->limited ? 1 : 0};
    auto out = env->NewLongArray(3); env->SetLongArrayRegion(out, 0, 3, stats); return out;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_lfm3_probe_inference_NativeLlama_audio(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    try {
        auto & s = ready();
        if (!s.speech) return nullptr;
        const char * data = nullptr; size_t size = 0; int64_t samples = 0;
        if (mtmd_helper_gen_audio_get_output(s.speech, &s.audio_rate, &data, &size, &samples) != 0) throw std::runtime_error("Audio codec output failed.");
        if (size != samples * sizeof(float) || samples > 240000) throw std::runtime_error("Audio chunk exceeds buffer limit.");
        s.audio_samples += samples;
        auto result = env->NewFloatArray(samples);
        if (samples) env->SetFloatArrayRegion(result, 0, samples, reinterpret_cast<const float *>(data));
        return result;
    } catch (const std::exception & e) { error(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT jint JNICALL Java_app_lfm3_probe_inference_NativeLlama_audioRate(JNIEnv *, jobject) { std::lock_guard<std::mutex> guard(lock); return ready().audio_rate; }
extern "C" JNIEXPORT jint JNICALL Java_app_lfm3_probe_inference_NativeLlama_capabilities(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    if (!session) return 0;
    return 1 | (session->media && mtmd_support_vision(session->media) ? 2 : 0)
        | (session->media && mtmd_support_audio(session->media) ? 4 : 0) | (session->speech ? 8 : 0);
}
