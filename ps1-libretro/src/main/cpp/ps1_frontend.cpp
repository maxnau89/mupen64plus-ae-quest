// Minimal libretro frontend for the PS1 spike (Beetle PSX HW core).
//
// One emulation thread owns everything: it creates an EGL GLES3 context on the given Surface
// (in XR mode that is QuestXr's QUAD_GAME surface, the same one the N64 renderer draws into),
// dlopen()s the core, provides the HW render FBO, runs retro_run() paced to the core's frame rate
// and blits each frame onto the window surface. Audio goes through AAudio, input comes from Java.
//
// Deliberately small: no savestates, no rewind, no disk swapping, no shaders, no core option UI.

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <aaudio/AAudio.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include "libretro.h"

#define TAG "Ps1Frontend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------------------------
// Core symbols
// ---------------------------------------------------------------------------------------------
struct Core {
    void *handle = nullptr;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    unsigned (*api_version)() = nullptr;
    void (*get_system_info)(retro_system_info *) = nullptr;
    void (*get_system_av_info)(retro_system_av_info *) = nullptr;
    void (*set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*run)() = nullptr;
    bool (*load_game)(const retro_game_info *) = nullptr;
    void (*unload_game)() = nullptr;
};

template<typename T>
bool loadSym(void *handle, T &out, const char *name)
{
    out = reinterpret_cast<T>(dlsym(handle, name));
    if (!out) {
        LOGE("Core is missing %s", name);
    }
    return out != nullptr;
}

bool loadCore(Core &core, const std::string &path)
{
    core.handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!core.handle) {
        // Fall back to the soname, the app's lib dir is on the linker namespace search path
        core.handle = dlopen("libbeetle_psx_hw.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (!core.handle) {
        LOGE("dlopen(%s) failed: %s", path.c_str(), dlerror());
        return false;
    }
    bool ok = true;
    ok &= loadSym(core.handle, core.set_environment, "retro_set_environment");
    ok &= loadSym(core.handle, core.set_video_refresh, "retro_set_video_refresh");
    ok &= loadSym(core.handle, core.set_audio_sample, "retro_set_audio_sample");
    ok &= loadSym(core.handle, core.set_audio_sample_batch, "retro_set_audio_sample_batch");
    ok &= loadSym(core.handle, core.set_input_poll, "retro_set_input_poll");
    ok &= loadSym(core.handle, core.set_input_state, "retro_set_input_state");
    ok &= loadSym(core.handle, core.init, "retro_init");
    ok &= loadSym(core.handle, core.deinit, "retro_deinit");
    ok &= loadSym(core.handle, core.api_version, "retro_api_version");
    ok &= loadSym(core.handle, core.get_system_info, "retro_get_system_info");
    ok &= loadSym(core.handle, core.get_system_av_info, "retro_get_system_av_info");
    ok &= loadSym(core.handle, core.set_controller_port_device, "retro_set_controller_port_device");
    ok &= loadSym(core.handle, core.run, "retro_run");
    ok &= loadSym(core.handle, core.load_game, "retro_load_game");
    ok &= loadSym(core.handle, core.unload_game, "retro_unload_game");
    return ok;
}

// ---------------------------------------------------------------------------------------------
// Audio: single producer (emu thread) / single consumer (AAudio callback) ring of stereo frames
// ---------------------------------------------------------------------------------------------
class AudioRing {
public:
    explicit AudioRing(size_t frames) : mBuffer(frames * 2), mFrames(frames) {}

    size_t write(const int16_t *data, size_t frames)
    {
        const size_t head = mHead.load(std::memory_order_relaxed);
        const size_t tail = mTail.load(std::memory_order_acquire);
        const size_t space = mFrames - (head - tail);
        const size_t count = std::min(frames, space); // Overflow drops the newest samples
        for (size_t i = 0; i < count; ++i) {
            const size_t slot = ((head + i) % mFrames) * 2;
            mBuffer[slot] = data[i * 2];
            mBuffer[slot + 1] = data[i * 2 + 1];
        }
        mHead.store(head + count, std::memory_order_release);
        return count;
    }

    void read(int16_t *out, size_t frames)
    {
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t head = mHead.load(std::memory_order_acquire);
        const size_t count = std::min(frames, head - tail);
        for (size_t i = 0; i < count; ++i) {
            const size_t slot = ((tail + i) % mFrames) * 2;
            out[i * 2] = mBuffer[slot];
            out[i * 2 + 1] = mBuffer[slot + 1];
        }
        // Underflow plays silence
        std::memset(out + count * 2, 0, (frames - count) * 2 * sizeof(int16_t));
        mTail.store(tail + count, std::memory_order_release);
    }

private:
    std::vector<int16_t> mBuffer;
    const size_t mFrames;
    std::atomic<size_t> mHead{0};
    std::atomic<size_t> mTail{0};
};

// ---------------------------------------------------------------------------------------------
// Frontend state. libretro callbacks are plain C function pointers, so there is one global instance.
// ---------------------------------------------------------------------------------------------
struct Frontend {
    std::string corePath, contentPath, systemDir, saveDir;
    ANativeWindow *window = nullptr;

    Core core;
    retro_system_av_info av{};
    retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    retro_hw_render_callback hw{};
    bool hwRequested = false;

    // Core options: the core's defaults, then our spike overrides on top
    std::map<std::string, std::string> variables;
    std::map<std::string, std::string> overrides;

    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    int windowWidth = 0, windowHeight = 0;

    GLuint fbo = 0, fboColor = 0, fboDepth = 0;
    unsigned fboWidth = 0, fboHeight = 0;
    GLuint swFbo = 0, swTexture = 0;
    unsigned swWidth = 0, swHeight = 0;
    std::vector<uint32_t> swPixels;

    // What the last presented frame came from, re-presented when the core dupes a frame
    enum class Source { None, Hardware, Software } lastSource = Source::None;
    unsigned lastWidth = 0, lastHeight = 0;

    AAudioStream *audio = nullptr;
    AudioRing audioRing{8192};

    std::atomic<bool> running{false};
    std::thread thread;
};

Frontend *g = nullptr;
std::atomic<bool> gStopRequested{false};
std::atomic<bool> gPaused{false};
// Input is written from the XR frame thread, so it lives outside the Frontend that start/stop replace
std::atomic<uint32_t> gButtons{0};
std::atomic<int32_t> gAnalog[4] = {{0}, {0}, {0}, {0}}; // lx, ly, rx, ry

// ---------------------------------------------------------------------------------------------
// GL helpers
// ---------------------------------------------------------------------------------------------
bool initEgl(Frontend &f)
{
    f.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (f.display == EGL_NO_DISPLAY || !eglInitialize(f.display, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }
    const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(f.display, configAttribs, &config, 1, &count) || count < 1) {
        LOGE("eglChooseConfig failed");
        return false;
    }
    EGLint format = 0;
    eglGetConfigAttrib(f.display, config, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(f.window, 0, 0, format);

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    f.context = eglCreateContext(f.display, config, EGL_NO_CONTEXT, contextAttribs);
    if (f.context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext(GLES3) failed: 0x%x", eglGetError());
        return false;
    }
    f.surface = eglCreateWindowSurface(f.display, config, f.window, nullptr);
    if (f.surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }
    if (!eglMakeCurrent(f.display, f.surface, f.surface, f.context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }
    // Pacing comes from the frame clock in the run loop, never block on the SurfaceTexture queue
    eglSwapInterval(f.display, 0);
    eglQuerySurface(f.display, f.surface, EGL_WIDTH, &f.windowWidth);
    eglQuerySurface(f.display, f.surface, EGL_HEIGHT, &f.windowHeight);
    LOGI("EGL ready: %s | %s | %dx%d", glGetString(GL_RENDERER), glGetString(GL_VERSION),
         f.windowWidth, f.windowHeight);
    return true;
}

void destroyEgl(Frontend &f)
{
    if (f.display == EGL_NO_DISPLAY) {
        return;
    }
    eglMakeCurrent(f.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (f.surface != EGL_NO_SURFACE) {
        eglDestroySurface(f.display, f.surface);
    }
    if (f.context != EGL_NO_CONTEXT) {
        eglDestroyContext(f.display, f.context);
    }
    eglTerminate(f.display);
    f.display = EGL_NO_DISPLAY;
    f.context = EGL_NO_CONTEXT;
    f.surface = EGL_NO_SURFACE;
}

void destroyFbo(Frontend &f)
{
    if (f.fbo) glDeleteFramebuffers(1, &f.fbo);
    if (f.fboColor) glDeleteTextures(1, &f.fboColor);
    if (f.fboDepth) glDeleteRenderbuffers(1, &f.fboDepth);
    f.fbo = f.fboColor = f.fboDepth = 0;
    f.fboWidth = f.fboHeight = 0;
}

// The FBO the HW core renders into (retro_hw_render_callback::get_current_framebuffer)
bool ensureFbo(Frontend &f, unsigned width, unsigned height)
{
    GLint maxSize = 4096;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
    width = std::min<unsigned>(std::max(width, 1u), static_cast<unsigned>(maxSize));
    height = std::min<unsigned>(std::max(height, 1u), static_cast<unsigned>(maxSize));
    if (f.fbo && width <= f.fboWidth && height <= f.fboHeight) {
        return true;
    }
    destroyFbo(f);

    glGenTextures(1, &f.fboColor);
    glBindTexture(GL_TEXTURE_2D, f.fboColor);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindTexture(GL_TEXTURE_2D, 0);

    glGenFramebuffers(1, &f.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, f.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, f.fboColor, 0);
    if (f.hw.depth || f.hw.stencil) {
        glGenRenderbuffers(1, &f.fboDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, f.fboDepth);
        glRenderbufferStorage(GL_RENDERBUFFER, f.hw.stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24,
                              width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, f.hw.stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT,
                                  GL_RENDERBUFFER, f.fboDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("HW render FBO incomplete: 0x%x", status);
        destroyFbo(f);
        return false;
    }
    f.fboWidth = width;
    f.fboHeight = height;
    LOGI("HW render FBO %ux%u (depth=%d stencil=%d)", width, height, f.hw.depth, f.hw.stencil);
    return true;
}

// Blit a frame from readFbo onto the window, letterboxed to the core's aspect ratio
void present(Frontend &f, GLuint readFbo, unsigned width, unsigned height, bool flipY)
{
    eglQuerySurface(f.display, f.surface, EGL_WIDTH, &f.windowWidth);
    eglQuerySurface(f.display, f.surface, EGL_HEIGHT, &f.windowHeight);

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(0, 0, f.windowWidth, f.windowHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    float aspect = f.av.geometry.aspect_ratio;
    if (aspect <= 0.0f) {
        aspect = static_cast<float>(width) / static_cast<float>(height);
    }
    int dstW = f.windowWidth;
    int dstH = static_cast<int>(static_cast<float>(dstW) / aspect);
    if (dstH > f.windowHeight) {
        dstH = f.windowHeight;
        dstW = static_cast<int>(static_cast<float>(dstH) * aspect);
    }
    const int x0 = (f.windowWidth - dstW) / 2;
    const int y0 = (f.windowHeight - dstH) / 2;
    if (flipY) {
        glBlitFramebuffer(0, 0, width, height, x0, y0 + dstH, x0 + dstW, y0, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    } else {
        glBlitFramebuffer(0, 0, width, height, x0, y0, x0 + dstW, y0 + dstH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    eglSwapBuffers(f.display, f.surface);
}

// Software frames (the core's SW renderer fallback) are converted to RGBA8 and uploaded
void uploadSoftwareFrame(Frontend &f, const void *data, unsigned width, unsigned height, size_t pitch)
{
    if (!f.swTexture || width > f.swWidth || height > f.swHeight) {
        if (f.swFbo) glDeleteFramebuffers(1, &f.swFbo);
        if (f.swTexture) glDeleteTextures(1, &f.swTexture);
        f.swWidth = std::max(width, 1024u);
        f.swHeight = std::max(height, 512u);
        glGenTextures(1, &f.swTexture);
        glBindTexture(GL_TEXTURE_2D, f.swTexture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, f.swWidth, f.swHeight);
        glGenFramebuffers(1, &f.swFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, f.swFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, f.swTexture, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    f.swPixels.resize(static_cast<size_t>(width) * height);
    const auto *bytes = static_cast<const uint8_t *>(data);
    for (unsigned y = 0; y < height; ++y) {
        uint32_t *dst = &f.swPixels[static_cast<size_t>(y) * width];
        const uint8_t *row = bytes + y * pitch;
        for (unsigned x = 0; x < width; ++x) {
            uint32_t r, gr, b;
            if (f.pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
                const uint32_t p = reinterpret_cast<const uint32_t *>(row)[x];
                r = (p >> 16) & 0xff; gr = (p >> 8) & 0xff; b = p & 0xff;
            } else if (f.pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
                const uint16_t p = reinterpret_cast<const uint16_t *>(row)[x];
                r = ((p >> 11) & 0x1f) << 3; gr = ((p >> 5) & 0x3f) << 2; b = (p & 0x1f) << 3;
            } else {
                const uint16_t p = reinterpret_cast<const uint16_t *>(row)[x];
                r = ((p >> 10) & 0x1f) << 3; gr = ((p >> 5) & 0x1f) << 3; b = (p & 0x1f) << 3;
            }
            dst[x] = r | (gr << 8) | (b << 16) | 0xff000000u; // RGBA in memory order
        }
    }
    glBindTexture(GL_TEXTURE_2D, f.swTexture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, f.swPixels.data());
    glBindTexture(GL_TEXTURE_2D, 0);
}

// ---------------------------------------------------------------------------------------------
// libretro callbacks
// ---------------------------------------------------------------------------------------------
void coreLog(enum retro_log_level level, const char *fmt, ...)
{
    static const int priorities[] = {ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR};
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(priorities[std::min<int>(level, 3)], "Ps1Core", fmt, args);
    va_end(args);
}

uintptr_t hwGetCurrentFramebuffer()
{
    return g ? g->fbo : 0;
}

retro_proc_address_t hwGetProcAddress(const char *sym)
{
    auto proc = reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(sym));
    if (!proc) {
        static void *gles = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
        if (gles) {
            proc = reinterpret_cast<retro_proc_address_t>(dlsym(gles, sym));
        }
    }
    return proc;
}

// First value of a legacy "Description; a|b|c" SET_VARIABLES entry
std::string legacyDefault(const char *value)
{
    const char *semi = value ? std::strchr(value, ';') : nullptr;
    if (!semi) {
        return {};
    }
    const char *p = semi + 1;
    while (*p == ' ') ++p;
    const char *end = std::strchr(p, '|');
    return end ? std::string(p, end - p) : std::string(p);
}

bool environment(unsigned cmd, void *data)
{
    Frontend &f = *g;
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *static_cast<const char **>(data) = f.systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char **>(data) = f.saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback *>(data)->log = coreLog;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool *>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *static_cast<unsigned *>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            const auto format = *static_cast<const retro_pixel_format *>(data);
            if (format != RETRO_PIXEL_FORMAT_0RGB1555 && format != RETRO_PIXEL_FORMAT_XRGB8888 &&
                format != RETRO_PIXEL_FORMAT_RGB565) {
                return false;
            }
            f.pixelFormat = format;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto *cb = static_cast<retro_hw_render_callback *>(data);
            const bool gles3 = cb->context_type == RETRO_HW_CONTEXT_OPENGLES3 ||
                               cb->context_type == RETRO_HW_CONTEXT_OPENGLES2 ||
                               (cb->context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION && cb->version_major <= 3);
            if (!gles3) {
                // Desktop GL and Vulkan are not available on our EGL/GLES chain
                LOGW("Rejecting HW context type %d (%u.%u)", cb->context_type, cb->version_major,
                     cb->version_minor);
                return false;
            }
            cb->get_current_framebuffer = hwGetCurrentFramebuffer;
            cb->get_proc_address = hwGetProcAddress;
            f.hw = *cb;
            f.hwRequested = true;
            LOGI("Accepted HW context type %d, bottom_left_origin=%d", cb->context_type, cb->bottom_left_origin);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned *>(data) = 2;
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            for (auto *v = static_cast<const retro_variable *>(data); v && v->key; ++v) {
                f.variables.emplace(v->key, legacyDefault(v->value));
            }
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            const retro_core_options_v2 *options = cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL
                    ? static_cast<const retro_core_options_v2_intl *>(data)->us
                    : static_cast<const retro_core_options_v2 *>(data);
            if (options) {
                for (auto *d = options->definitions; d && d->key; ++d) {
                    if (d->default_value) {
                        f.variables.emplace(d->key, d->default_value);
                    }
                }
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto *var = static_cast<retro_variable *>(data);
            auto it = f.overrides.find(var->key);
            if (it == f.overrides.end()) {
                it = f.variables.find(var->key);
                if (it == f.variables.end()) {
                    var->value = nullptr;
                    return false;
                }
            }
            var->value = it->second.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool *>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            f.av.geometry = *static_cast<const retro_game_geometry *>(data);
            return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            f.av = *static_cast<const retro_system_av_info *>(data);
            if (f.hwRequested && f.fbo) {
                // Called from retro_run on this thread, so the context is current
                ensureFbo(f, f.av.geometry.max_width, f.av.geometry.max_height);
            }
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE:
            LOGI("Core message: %s", static_cast<const retro_message *>(data)->msg);
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
            LOGI("Core message: %s", static_cast<const retro_message_ext *>(data)->msg);
            return true;
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            *static_cast<unsigned *>(data) = 1;
            return true;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
            return true;
        default:
            // Everything else (disk control, rumble, VFS, LED, ...) is unsupported in the spike
            return false;
    }
}

void videoRefresh(const void *data, unsigned width, unsigned height, size_t pitch)
{
    Frontend &f = *g;
    if (!data) {
        // Duped frame: EGL buffers are not preserved across swaps, so present the last source again
        if (f.lastSource == Frontend::Source::Hardware) {
            present(f, f.fbo, f.lastWidth, f.lastHeight, !f.hw.bottom_left_origin);
        } else if (f.lastSource == Frontend::Source::Software) {
            present(f, f.swFbo, f.lastWidth, f.lastHeight, true);
        }
        return;
    }
    if (width == 0 || height == 0) {
        return;
    }
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        f.lastSource = Frontend::Source::Hardware;
        f.lastWidth = std::min(width, f.fboWidth);
        f.lastHeight = std::min(height, f.fboHeight);
        present(f, f.fbo, f.lastWidth, f.lastHeight, !f.hw.bottom_left_origin);
    } else {
        uploadSoftwareFrame(f, data, width, height, pitch);
        f.lastSource = Frontend::Source::Software;
        f.lastWidth = width;
        f.lastHeight = height;
        present(f, f.swFbo, width, height, true);
    }
}

void audioSample(int16_t left, int16_t right)
{
    const int16_t frame[2] = {left, right};
    g->audioRing.write(frame, 1);
}

size_t audioSampleBatch(const int16_t *data, size_t frames)
{
    g->audioRing.write(data, frames);
    return frames;
}

void inputPoll()
{
}

int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id)
{
    if (port != 0) {
        return 0;
    }
    Frontend &f = *g;
    switch (device & RETRO_DEVICE_MASK) {
        case RETRO_DEVICE_JOYPAD: {
            const uint32_t mask = gButtons.load(std::memory_order_relaxed);
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
                return static_cast<int16_t>(mask & 0xffff);
            }
            return id < 16 && (mask & (1u << id)) ? 1 : 0;
        }
        case RETRO_DEVICE_ANALOG:
            if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON) {
                // Analog triggers: report fully pressed/released from the digital state
                return id < 16 && (gButtons.load(std::memory_order_relaxed) & (1u << id)) ? 0x7fff : 0;
            }
            if (index <= RETRO_DEVICE_INDEX_ANALOG_RIGHT && id <= RETRO_DEVICE_ID_ANALOG_Y) {
                return static_cast<int16_t>(gAnalog[index * 2 + id].load(std::memory_order_relaxed));
            }
            return 0;
        default:
            return 0;
    }
}

// ---------------------------------------------------------------------------------------------
// Audio output
// ---------------------------------------------------------------------------------------------
aaudio_data_callback_result_t audioCallback(AAudioStream *, void *user, void *audioData, int32_t numFrames)
{
    static_cast<AudioRing *>(user)->read(static_cast<int16_t *>(audioData), static_cast<size_t>(numFrames));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void startAudio(Frontend &f)
{
    AAudioStreamBuilder *builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGE("AAudio_createStreamBuilder failed");
        return;
    }
    const int32_t rate = f.av.timing.sample_rate > 0 ? static_cast<int32_t>(f.av.timing.sample_rate + 0.5) : 44100;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setDataCallback(builder, audioCallback, &f.audioRing);
    const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &f.audio);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        LOGE("AAudio open failed: %s", AAudio_convertResultToText(result));
        f.audio = nullptr;
        return;
    }
    AAudioStream_requestStart(f.audio);
    LOGI("AAudio started at %d Hz", rate);
}

void stopAudio(Frontend &f)
{
    if (f.audio) {
        AAudioStream_requestStop(f.audio);
        AAudioStream_close(f.audio);
        f.audio = nullptr;
    }
}

// ---------------------------------------------------------------------------------------------
// Emulation thread
// ---------------------------------------------------------------------------------------------
void emulationThread(Frontend *frontend)
{
    Frontend &f = *frontend;
    bool gameLoaded = false;
    bool coreInitialized = false;

    if (!initEgl(f) || !loadCore(f.core, f.corePath)) {
        f.running = false;
    }

    if (f.running) {
        LOGI("Core API version %u", f.core.api_version());
        f.core.set_environment(environment);
        f.core.set_video_refresh(videoRefresh);
        f.core.set_audio_sample(audioSample);
        f.core.set_audio_sample_batch(audioSampleBatch);
        f.core.set_input_poll(inputPoll);
        f.core.set_input_state(inputState);
        f.core.init();
        coreInitialized = true;

        retro_system_info info{};
        f.core.get_system_info(&info);
        LOGI("Core: %s %s (need_fullpath=%d)", info.library_name, info.library_version, info.need_fullpath);

        retro_game_info game{};
        game.path = f.contentPath.c_str();
        // Beetle needs the path (it opens the .cue/.bin itself), so no data buffer is passed
        gameLoaded = f.core.load_game(&game);
        if (!gameLoaded) {
            LOGE("retro_load_game(%s) failed (missing BIOS in %s?)", game.path, f.systemDir.c_str());
            f.running = false;
        }
    }

    if (f.running) {
        f.core.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
        f.core.get_system_av_info(&f.av);
        LOGI("AV: base %ux%u max %ux%u aspect %.3f fps %.3f rate %.0f", f.av.geometry.base_width,
             f.av.geometry.base_height, f.av.geometry.max_width, f.av.geometry.max_height,
             f.av.geometry.aspect_ratio, f.av.timing.fps, f.av.timing.sample_rate);
        if (f.hwRequested) {
            if (ensureFbo(f, f.av.geometry.max_width, f.av.geometry.max_height)) {
                if (f.hw.context_reset) {
                    f.hw.context_reset();
                }
            } else {
                f.running = false;
            }
        } else {
            LOGW("Core did not request a HW context, software frames will be uploaded");
        }
    }

    if (f.running) {
        startAudio(f);
        const double fps = f.av.timing.fps > 1.0 ? f.av.timing.fps : 60.0;
        const auto period = std::chrono::duration_cast<std::chrono::steady_clock::duration>(
                std::chrono::duration<double>(1.0 / fps));
        auto deadline = std::chrono::steady_clock::now();
        while (f.running && !gStopRequested) {
            if (gPaused) {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                deadline = std::chrono::steady_clock::now();
                continue;
            }
            f.core.run();
            deadline += period;
            const auto now = std::chrono::steady_clock::now();
            if (deadline > now) {
                std::this_thread::sleep_until(deadline);
            } else if (now - deadline > period * 4) {
                // Running slow, don't try to catch up with a burst of frames
                deadline = now;
            }
        }
        stopAudio(f);
    }

    if (coreInitialized) {
        if (f.hwRequested && f.hw.context_destroy) {
            f.hw.context_destroy();
        }
        if (gameLoaded) {
            f.core.unload_game();
        }
        f.core.deinit();
    }
    if (f.core.handle) {
        dlclose(f.core.handle);
        f.core.handle = nullptr;
    }
    if (f.display != EGL_NO_DISPLAY) {
        destroyFbo(f);
        if (f.swFbo) glDeleteFramebuffers(1, &f.swFbo);
        if (f.swTexture) glDeleteTextures(1, &f.swTexture);
    }
    destroyEgl(f);
    f.running = false;
    LOGI("Emulation thread finished");
}

std::string toString(JNIEnv *env, jstring value)
{
    if (!value) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void stopFrontend()
{
    if (!g) {
        return;
    }
    gStopRequested = true;
    if (g->thread.joinable()) {
        g->thread.join();
    }
    if (g->window) {
        ANativeWindow_release(g->window);
    }
    delete g;
    g = nullptr;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_mupen64plusae_ps1_LibretroFrontend_nativeStart(JNIEnv *env, jclass, jobject surface, jstring corePath,
                                                       jstring contentPath, jstring systemDir, jstring saveDir)
{
    stopFrontend();
    ANativeWindow *window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (!window) {
        LOGE("No window for the given Surface");
        return JNI_FALSE;
    }
    g = new Frontend();
    g->window = window;
    g->corePath = toString(env, corePath);
    g->contentPath = toString(env, contentPath);
    g->systemDir = toString(env, systemDir);
    g->saveDir = toString(env, saveDir);

    // Spike defaults: force the GLES renderer (auto would try Vulkan first), PGXP on
    g->overrides["beetle_psx_hw_renderer"] = "hardware_gl";
    g->overrides["beetle_psx_hw_pgxp_mode"] = "memory only";
    g->overrides["beetle_psx_hw_pgxp_texture"] = "enabled";
    g->overrides["beetle_psx_hw_pgxp_2d_tol"] = "disabled";
    g->overrides["beetle_psx_hw_internal_resolution"] = "2x";
    g->overrides["beetle_psx_hw_dither_mode"] = "internal resolution";
    g->overrides["beetle_psx_hw_filter"] = "nearest";
    // CPU stays on the core default (Beetle interpreter) for the first boot; the Lightrec dynarec
    // ("execute") is the obvious next knob if the interpreter is too slow on the headset
    // g->overrides["beetle_psx_hw_cpu_dynarec"] = "execute";

    gStopRequested = false;
    gPaused = false;
    g->running = true;
    g->thread = std::thread(emulationThread, g);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_mupen64plusae_ps1_LibretroFrontend_nativeStop(JNIEnv *, jclass)
{
    stopFrontend();
}

JNIEXPORT jboolean JNICALL
Java_org_mupen64plusae_ps1_LibretroFrontend_nativeIsRunning(JNIEnv *, jclass)
{
    return g && g->running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_mupen64plusae_ps1_LibretroFrontend_nativeSetPaused(JNIEnv *, jclass, jboolean paused)
{
    gPaused = paused == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_mupen64plusae_ps1_LibretroFrontend_nativeSetInput(JNIEnv *, jclass, jint buttons, jint leftX, jint leftY,
                                                          jint rightX, jint rightY)
{
    gButtons.store(static_cast<uint32_t>(buttons), std::memory_order_relaxed);
    gAnalog[0].store(leftX, std::memory_order_relaxed);
    gAnalog[1].store(leftY, std::memory_order_relaxed);
    gAnalog[2].store(rightX, std::memory_order_relaxed);
    gAnalog[3].store(rightY, std::memory_order_relaxed);
}

} // extern "C"
