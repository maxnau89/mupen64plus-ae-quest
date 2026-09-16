// OpenXR bridge for Meta Quest: shows the emulator output and an in-VR menu on quad
// layers, optionally over passthrough, and forwards Touch controller input to Java.
//
// Each quad is fed by an android.graphics.SurfaceTexture owned by Java. The emulator
// (or the menu) draws into a Surface created from it; the frame thread copies the
// latest buffer into an OpenGL ES swapchain every frame. Meta's runtime rejects
// XR_KHR_android_surface_swapchain, so there is no zero-copy path.

#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <vector>

#define TAG "QuestXr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Must match QuestXr.java
enum Buttons {
    BTN_A = 1 << 0,
    BTN_B = 1 << 1,
    BTN_X = 1 << 2,
    BTN_Y = 1 << 3,
    BTN_MENU = 1 << 4,
    BTN_LSTICK = 1 << 5,
    BTN_RSTICK = 1 << 6,
};

// Must match QuestXr.java
enum QuadId {
    QUAD_GAME = 0,
    QUAD_MENU = 1,
    QUAD_COUNT
};

enum ActionId {
    ACT_LSTICK, ACT_RSTICK,
    ACT_LTRIGGER, ACT_RTRIGGER, ACT_LGRIP, ACT_RGRIP,
    ACT_A, ACT_B, ACT_X, ACT_Y, ACT_MENU, ACT_LSTICK_CLICK, ACT_RSTICK_CLICK,
    ACT_COUNT
};

struct ActionDef {
    const char* name;
    XrActionType type;
    const char* path;
};

const ActionDef kActions[ACT_COUNT] = {
    {"left_stick", XR_ACTION_TYPE_VECTOR2F_INPUT, "/user/hand/left/input/thumbstick"},
    {"right_stick", XR_ACTION_TYPE_VECTOR2F_INPUT, "/user/hand/right/input/thumbstick"},
    {"left_trigger", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/left/input/trigger/value"},
    {"right_trigger", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/right/input/trigger/value"},
    {"left_grip", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/left/input/squeeze/value"},
    {"right_grip", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/right/input/squeeze/value"},
    {"button_a", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/right/input/a/click"},
    {"button_b", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/right/input/b/click"},
    {"button_x", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/x/click"},
    {"button_y", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/y/click"},
    {"button_menu", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/menu/click"},
    {"left_stick_click", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/thumbstick/click"},
    {"right_stick_click", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/right/input/thumbstick/click"},
};

struct QuadSettings {
    bool visible = false;
    float width = 2.0f;     // meters
    float distance = 2.0f;  // meters in front of the LOCAL space origin
    float offsetY = 0.0f;   // meters
    bool blendAlpha = false;
};

struct Quad {
    int32_t width = 0;
    int32_t height = 0;
    XrSwapchain swapchain = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageOpenGLESKHR> images;

    jobject sourceTexture = nullptr;  // android.graphics.SurfaceTexture
    GLuint oesTexture = 0;
    bool attached = false;
    bool hasImage = false;  // a quad may only be submitted after its first image was released

    QuadSettings settings;  // guarded by XrState::quadMutex
};

struct PassthroughFns {
    XrResult (*create)(XrSession, const XrPassthroughCreateInfoFB*, XrPassthroughFB*) = nullptr;
    XrResult (*destroy)(XrPassthroughFB) = nullptr;
    XrResult (*start)(XrPassthroughFB) = nullptr;
    XrResult (*pause)(XrPassthroughFB) = nullptr;
    XrResult (*createLayer)(XrSession, const XrPassthroughLayerCreateInfoFB*, XrPassthroughLayerFB*) = nullptr;
    XrResult (*destroyLayer)(XrPassthroughLayerFB) = nullptr;
    XrResult (*pauseLayer)(XrPassthroughLayerFB) = nullptr;
    XrResult (*resumeLayer)(XrPassthroughLayerFB) = nullptr;
};

struct XrState {
    jobject activity = nullptr;
    jobject listener = nullptr;
    jmethodID onInput = nullptr;
    jmethodID onSessionState = nullptr;

    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLConfig eglConfig = nullptr;
    EGLContext eglContext = EGL_NO_CONTEXT;
    EGLSurface eglSurface = EGL_NO_SURFACE;

    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction actions[ACT_COUNT] = {};

    std::mutex quadMutex;
    Quad quads[QUAD_COUNT];
    int64_t swapchainFormat = 0;
    bool decodeSrgb = false;

    // SurfaceTexture methods and copy program, created on the frame thread
    jmethodID attachToGLContext = nullptr;
    jmethodID detachFromGLContext = nullptr;
    jmethodID updateTexImage = nullptr;
    jmethodID getTransformMatrix = nullptr;
    jobject transformArray = nullptr;  // float[16]
    GLuint program = 0;
    GLuint framebuffer = 0;
    GLint texMatrixLocation = -1;
    GLint decodeSrgbLocation = -1;
    bool glReady = false;

    bool passthroughSupported = false;
    PassthroughFns passthroughFns;
    XrPassthroughFB passthrough = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer = XR_NULL_HANDLE;
    std::atomic<bool> passthroughWanted{false};
    bool passthroughActive = false;

    XrSessionState sessionState = XR_SESSION_STATE_UNKNOWN;
    bool sessionRunning = false;
    std::atomic<bool> stopRequested{false};
    std::thread frameThread;
};

JavaVM* gVm = nullptr;
bool gLoaderInitialized = false;
XrState* gXr = nullptr;

// The loader only exports core functions; extension functions are looked up at runtime.
template <typename Fn>
bool getProc(XrInstance instance, const char* name, Fn& fn) {
    PFN_xrVoidFunction proc = nullptr;
    if (XR_FAILED(xrGetInstanceProcAddr(instance, name, &proc)) || proc == nullptr) {
        LOGE("xrGetInstanceProcAddr(%s) failed", name);
        return false;
    }
    fn = reinterpret_cast<Fn>(proc);
    return true;
}

bool check(XrResult result, const char* what) {
    if (XR_FAILED(result)) {
        LOGE("%s failed: %d", what, result);
        return false;
    }
    return true;
}

bool clearException(JNIEnv* env, const char* what) {
    if (env->ExceptionCheck()) {
        LOGE("Java exception in %s", what);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

bool initEgl(XrState& xr) {
    xr.eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (xr.eglDisplay == EGL_NO_DISPLAY || !eglInitialize(xr.eglDisplay, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, 0x0040 /* EGL_OPENGL_ES3_BIT_KHR */,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(xr.eglDisplay, configAttribs, &xr.eglConfig, 1, &numConfigs) || numConfigs == 0) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    xr.eglContext = eglCreateContext(xr.eglDisplay, xr.eglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (xr.eglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }

    const EGLint pbufferAttribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    xr.eglSurface = eglCreatePbufferSurface(xr.eglDisplay, xr.eglConfig, pbufferAttribs);
    if (xr.eglSurface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed");
        return false;
    }
    return true;
}

bool createActions(XrState& xr) {
    XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
    strncpy(setInfo.actionSetName, "gameplay", XR_MAX_ACTION_SET_NAME_SIZE);
    strncpy(setInfo.localizedActionSetName, "Gameplay", XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE);
    if (!check(xrCreateActionSet(xr.instance, &setInfo, &xr.actionSet), "xrCreateActionSet")) {
        return false;
    }

    XrActionSuggestedBinding bindings[ACT_COUNT];
    for (int i = 0; i < ACT_COUNT; ++i) {
        XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
        info.actionType = kActions[i].type;
        strncpy(info.actionName, kActions[i].name, XR_MAX_ACTION_NAME_SIZE);
        strncpy(info.localizedActionName, kActions[i].name, XR_MAX_LOCALIZED_ACTION_NAME_SIZE);
        if (!check(xrCreateAction(xr.actionSet, &info, &xr.actions[i]), kActions[i].name)) {
            return false;
        }
        bindings[i].action = xr.actions[i];
        if (!check(xrStringToPath(xr.instance, kActions[i].path, &bindings[i].binding), kActions[i].path)) {
            return false;
        }
    }

    XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    if (!check(xrStringToPath(xr.instance, "/interaction_profiles/oculus/touch_controller",
                              &suggested.interactionProfile), "touch_controller path")) {
        return false;
    }
    suggested.suggestedBindings = bindings;
    suggested.countSuggestedBindings = ACT_COUNT;
    if (!check(xrSuggestInteractionProfileBindings(xr.instance, &suggested), "xrSuggestInteractionProfileBindings")) {
        return false;
    }

    XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attach.countActionSets = 1;
    attach.actionSets = &xr.actionSet;
    return check(xrAttachSessionActionSets(xr.session, &attach), "xrAttachSessionActionSets");
}

void destroyXr(JNIEnv* env, XrState* xr) {
    for (Quad& quad : xr->quads) {
        if (quad.swapchain != XR_NULL_HANDLE) xrDestroySwapchain(quad.swapchain);
        if (quad.sourceTexture != nullptr) env->DeleteGlobalRef(quad.sourceTexture);
    }
    if (xr->passthroughLayer != XR_NULL_HANDLE) xr->passthroughFns.destroyLayer(xr->passthroughLayer);
    if (xr->passthrough != XR_NULL_HANDLE) xr->passthroughFns.destroy(xr->passthrough);
    if (xr->space != XR_NULL_HANDLE) xrDestroySpace(xr->space);
    if (xr->actionSet != XR_NULL_HANDLE) xrDestroyActionSet(xr->actionSet);
    if (xr->session != XR_NULL_HANDLE) xrDestroySession(xr->session);
    if (xr->instance != XR_NULL_HANDLE) xrDestroyInstance(xr->instance);

    if (xr->eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(xr->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (xr->eglSurface != EGL_NO_SURFACE) eglDestroySurface(xr->eglDisplay, xr->eglSurface);
        if (xr->eglContext != EGL_NO_CONTEXT) eglDestroyContext(xr->eglDisplay, xr->eglContext);
        // No eglTerminate: the display is shared with the emulator's render thread.
    }

    if (xr->transformArray != nullptr) env->DeleteGlobalRef(xr->transformArray);
    if (xr->listener != nullptr) env->DeleteGlobalRef(xr->listener);
    if (xr->activity != nullptr) env->DeleteGlobalRef(xr->activity);
    delete xr;
}

void handleSessionStateChanged(JNIEnv* env, XrState& xr, XrSessionState state) {
    LOGI("Session state %d -> %d", xr.sessionState, state);
    xr.sessionState = state;

    switch (state) {
        case XR_SESSION_STATE_READY: {
            XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO};
            beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            xr.sessionRunning = check(xrBeginSession(xr.session, &beginInfo), "xrBeginSession");
            break;
        }
        case XR_SESSION_STATE_STOPPING:
            check(xrEndSession(xr.session), "xrEndSession");
            xr.sessionRunning = false;
            break;
        default:
            break;
    }

    env->CallVoidMethod(xr.listener, xr.onSessionState, static_cast<jint>(state));
    clearException(env, "onXrSessionState");
}

void pollEvents(JNIEnv* env, XrState& xr) {
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(xr.instance, &event) == XR_SUCCESS) {
        switch (event.type) {
            case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED: {
                auto* changed = reinterpret_cast<XrEventDataSessionStateChanged*>(&event);
                handleSessionStateChanged(env, xr, changed->state);
                break;
            }
            case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
                LOGE("Instance loss pending");
                env->CallVoidMethod(xr.listener, xr.onSessionState, static_cast<jint>(XR_SESSION_STATE_LOSS_PENDING));
                clearException(env, "onXrSessionState");
                break;
            default:
                break;
        }
        event = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}

XrVector2f getVector2(XrState& xr, ActionId id) {
    XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = xr.actions[id];
    XrActionStateVector2f state{XR_TYPE_ACTION_STATE_VECTOR2F};
    if (XR_SUCCEEDED(xrGetActionStateVector2f(xr.session, &info, &state)) && state.isActive) {
        return state.currentState;
    }
    return {0.0f, 0.0f};
}

float getFloat(XrState& xr, ActionId id) {
    XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = xr.actions[id];
    XrActionStateFloat state{XR_TYPE_ACTION_STATE_FLOAT};
    if (XR_SUCCEEDED(xrGetActionStateFloat(xr.session, &info, &state)) && state.isActive) {
        return state.currentState;
    }
    return 0.0f;
}

bool getBool(XrState& xr, ActionId id) {
    XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = xr.actions[id];
    XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
    return XR_SUCCEEDED(xrGetActionStateBoolean(xr.session, &info, &state)) && state.isActive && state.currentState;
}

void syncInput(JNIEnv* env, XrState& xr) {
    XrActiveActionSet active{xr.actionSet, XR_NULL_PATH};
    XrActionsSyncInfo syncInfo{XR_TYPE_ACTIONS_SYNC_INFO};
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &active;
    if (XR_FAILED(xrSyncActions(xr.session, &syncInfo))) {
        return;
    }

    XrVector2f left = getVector2(xr, ACT_LSTICK);
    XrVector2f right = getVector2(xr, ACT_RSTICK);

    jint buttons = 0;
    if (getBool(xr, ACT_A)) buttons |= BTN_A;
    if (getBool(xr, ACT_B)) buttons |= BTN_B;
    if (getBool(xr, ACT_X)) buttons |= BTN_X;
    if (getBool(xr, ACT_Y)) buttons |= BTN_Y;
    if (getBool(xr, ACT_MENU)) buttons |= BTN_MENU;
    if (getBool(xr, ACT_LSTICK_CLICK)) buttons |= BTN_LSTICK;
    if (getBool(xr, ACT_RSTICK_CLICK)) buttons |= BTN_RSTICK;

    env->CallVoidMethod(xr.listener, xr.onInput,
                        left.x, left.y, right.x, right.y,
                        getFloat(xr, ACT_LTRIGGER), getFloat(xr, ACT_RTRIGGER),
                        getFloat(xr, ACT_LGRIP), getFloat(xr, ACT_RGRIP),
                        buttons);
    clearException(env, "onXrInput");
}

// ---------------------------------------------------------------------------------------------
// Passthrough (XR_FB_passthrough)

bool loadPassthroughFns(XrState& xr) {
    PassthroughFns& fns = xr.passthroughFns;
    return getProc(xr.instance, "xrCreatePassthroughFB", fns.create) &&
           getProc(xr.instance, "xrDestroyPassthroughFB", fns.destroy) &&
           getProc(xr.instance, "xrPassthroughStartFB", fns.start) &&
           getProc(xr.instance, "xrPassthroughPauseFB", fns.pause) &&
           getProc(xr.instance, "xrCreatePassthroughLayerFB", fns.createLayer) &&
           getProc(xr.instance, "xrDestroyPassthroughLayerFB", fns.destroyLayer) &&
           getProc(xr.instance, "xrPassthroughLayerPauseFB", fns.pauseLayer) &&
           getProc(xr.instance, "xrPassthroughLayerResumeFB", fns.resumeLayer);
}

bool createPassthrough(XrState& xr) {
    XrPassthroughCreateInfoFB info{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
    if (!check(xr.passthroughFns.create(xr.session, &info, &xr.passthrough), "xrCreatePassthroughFB")) {
        return false;
    }
    XrPassthroughLayerCreateInfoFB layerInfo{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
    layerInfo.passthrough = xr.passthrough;
    layerInfo.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
    return check(xr.passthroughFns.createLayer(xr.session, &layerInfo, &xr.passthroughLayer),
                 "xrCreatePassthroughLayerFB");
}

// Called on the frame thread
void updatePassthrough(XrState& xr) {
    if (!xr.passthroughSupported) {
        return;
    }
    const bool wanted = xr.passthroughWanted;
    if (wanted == xr.passthroughActive) {
        return;
    }
    if (wanted) {
        xr.passthroughActive = check(xr.passthroughFns.start(xr.passthrough), "xrPassthroughStartFB") &&
                               check(xr.passthroughFns.resumeLayer(xr.passthroughLayer), "xrPassthroughLayerResumeFB");
        if (!xr.passthroughActive) {
            xr.passthroughWanted = false;
        }
    } else {
        xr.passthroughFns.pauseLayer(xr.passthroughLayer);
        xr.passthroughFns.pause(xr.passthrough);
        xr.passthroughActive = false;
    }
    LOGI("Passthrough %s", xr.passthroughActive ? "on" : "off");
}

// ---------------------------------------------------------------------------------------------
// Copying SurfaceTextures into swapchains

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

// Must run on the frame thread with the XR context current.
bool initCopyGl(JNIEnv* env, XrState& xr) {
    static const char* kVertex = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    vec2 uv = aPosition * 0.5 + 0.5;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPosition, 0.0, 1.0);
})";
    static const char* kFragment = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform float uDecodeSrgb;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec4 color = texture(uTexture, vUv);
    // sRGB swapchains re-encode on write, so undo the gamma the source already applied
    if (uDecodeSrgb > 0.5) color.rgb = pow(color.rgb, vec3(2.2));
    outColor = color;
})";

    GLuint vertex = compileShader(GL_VERTEX_SHADER, kVertex);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, kFragment);
    if (vertex == 0 || fragment == 0) {
        return false;
    }
    xr.program = glCreateProgram();
    glAttachShader(xr.program, vertex);
    glAttachShader(xr.program, fragment);
    glLinkProgram(xr.program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = 0;
    glGetProgramiv(xr.program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Program link failed");
        return false;
    }
    xr.texMatrixLocation = glGetUniformLocation(xr.program, "uTexMatrix");
    xr.decodeSrgbLocation = glGetUniformLocation(xr.program, "uDecodeSrgb");
    glGenFramebuffers(1, &xr.framebuffer);

    jclass textureClass = env->FindClass("android/graphics/SurfaceTexture");
    if (clearException(env, "FindClass SurfaceTexture")) {
        return false;
    }
    xr.attachToGLContext = env->GetMethodID(textureClass, "attachToGLContext", "(I)V");
    xr.detachFromGLContext = env->GetMethodID(textureClass, "detachFromGLContext", "()V");
    xr.updateTexImage = env->GetMethodID(textureClass, "updateTexImage", "()V");
    xr.getTransformMatrix = env->GetMethodID(textureClass, "getTransformMatrix", "([F)V");
    env->DeleteLocalRef(textureClass);
    if (clearException(env, "SurfaceTexture lookup")) {
        return false;
    }

    jfloatArray array = env->NewFloatArray(16);
    xr.transformArray = env->NewGlobalRef(array);
    env->DeleteLocalRef(array);

    LOGI("Copy program ready");
    return true;
}

void destroyCopyGl(JNIEnv* env, XrState& xr) {
    for (Quad& quad : xr.quads) {
        if (quad.attached) {
            env->CallVoidMethod(quad.sourceTexture, xr.detachFromGLContext);
            clearException(env, "detachFromGLContext");
            quad.attached = false;
        }
        if (quad.oesTexture != 0) {
            glDeleteTextures(1, &quad.oesTexture);
            quad.oesTexture = 0;
        }
    }
    if (xr.glReady) {
        glDeleteFramebuffers(1, &xr.framebuffer);
        glDeleteProgram(xr.program);
        xr.glReady = false;
    }
}

// Copies the latest source frame into the next swapchain image of the quad.
void copyQuad(JNIEnv* env, XrState& xr, Quad& quad) {
    if (quad.sourceTexture == nullptr || quad.swapchain == XR_NULL_HANDLE) {
        return;
    }
    if (!quad.attached) {
        glGenTextures(1, &quad.oesTexture);
        env->CallVoidMethod(quad.sourceTexture, xr.attachToGLContext, static_cast<jint>(quad.oesTexture));
        if (clearException(env, "attachToGLContext")) {
            return;
        }
        quad.attached = true;
    }

    env->CallVoidMethod(quad.sourceTexture, xr.updateTexImage);
    if (clearException(env, "updateTexImage")) {
        return;
    }
    env->CallVoidMethod(quad.sourceTexture, xr.getTransformMatrix, static_cast<jfloatArray>(xr.transformArray));
    jfloat matrix[16];
    env->GetFloatArrayRegion(static_cast<jfloatArray>(xr.transformArray), 0, 16, matrix);

    uint32_t index = 0;
    XrSwapchainImageAcquireInfo acquireInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    if (!check(xrAcquireSwapchainImage(quad.swapchain, &acquireInfo, &index), "xrAcquireSwapchainImage")) {
        return;
    }
    XrSwapchainImageWaitInfo waitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    waitInfo.timeout = XR_INFINITE_DURATION;
    check(xrWaitSwapchainImage(quad.swapchain, &waitInfo), "xrWaitSwapchainImage");

    static const GLfloat kQuad[] = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    glBindFramebuffer(GL_FRAMEBUFFER, xr.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, quad.images[index].image, 0);
    glViewport(0, 0, quad.width, quad.height);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glUseProgram(xr.program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(0x8D65 /* GL_TEXTURE_EXTERNAL_OES */, quad.oesTexture);
    glUniformMatrix4fv(xr.texMatrixLocation, 1, GL_FALSE, matrix);
    glUniform1f(xr.decodeSrgbLocation, xr.decodeSrgb ? 1.0f : 0.0f);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, kQuad);
    glEnableVertexAttribArray(0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    XrSwapchainImageReleaseInfo releaseInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    if (check(xrReleaseSwapchainImage(quad.swapchain, &releaseInfo), "xrReleaseSwapchainImage")) {
        quad.hasImage = true;
    }
}

void renderFrame(XrState& xr, JNIEnv* env) {
    XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState frameState{XR_TYPE_FRAME_STATE};
    if (!check(xrWaitFrame(xr.session, &waitInfo, &frameState), "xrWaitFrame")) {
        usleep(10000);
        return;
    }

    XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
    if (!check(xrBeginFrame(xr.session, &beginInfo), "xrBeginFrame")) {
        return;
    }

    if (xr.sessionState == XR_SESSION_STATE_FOCUSED) {
        syncInput(env, xr);
    }
    updatePassthrough(xr);

    QuadSettings settings[QUAD_COUNT];
    {
        std::lock_guard<std::mutex> lock(xr.quadMutex);
        for (int i = 0; i < QUAD_COUNT; ++i) {
            settings[i] = xr.quads[i].settings;
        }
    }

    XrCompositionLayerPassthroughFB passthroughLayer{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
    XrCompositionLayerQuad quadLayers[QUAD_COUNT];
    const XrCompositionLayerBaseHeader* layers[QUAD_COUNT + 1];
    uint32_t layerCount = 0;

    if (frameState.shouldRender) {
        if (!xr.glReady) {
            xr.glReady = initCopyGl(env, xr);
        }

        if (xr.passthroughActive) {
            passthroughLayer.layerHandle = xr.passthroughLayer;
            layers[layerCount++] = reinterpret_cast<XrCompositionLayerBaseHeader*>(&passthroughLayer);
        }

        for (int i = 0; i < QUAD_COUNT; ++i) {
            Quad& quad = xr.quads[i];
            if (!settings[i].visible) {
                continue;
            }
            if (xr.glReady) {
                copyQuad(env, xr, quad);
            }
            if (!quad.hasImage) {
                continue;
            }

            XrCompositionLayerQuad& layer = quadLayers[i];
            layer = {XR_TYPE_COMPOSITION_LAYER_QUAD};
            layer.layerFlags = settings[i].blendAlpha ? XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT : 0;
            layer.space = xr.space;
            layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
            layer.subImage.swapchain = quad.swapchain;
            layer.subImage.imageRect = {{0, 0}, {quad.width, quad.height}};
            layer.subImage.imageArrayIndex = 0;
            layer.pose = {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, settings[i].offsetY, -settings[i].distance}};
            layer.size = {settings[i].width,
                          settings[i].width * static_cast<float>(quad.height) / static_cast<float>(quad.width)};
            layers[layerCount++] = reinterpret_cast<XrCompositionLayerBaseHeader*>(&layer);
        }
    }

    XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
    endInfo.displayTime = frameState.predictedDisplayTime;
    endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    endInfo.layerCount = layerCount;
    endInfo.layers = layers;
    check(xrEndFrame(xr.session, &endInfo), "xrEndFrame");
}

void frameLoop(XrState* xr) {
    JNIEnv* env = nullptr;
    gVm->AttachCurrentThread(&env, nullptr);
    eglMakeCurrent(xr->eglDisplay, xr->eglSurface, xr->eglSurface, xr->eglContext);

    while (!xr->stopRequested) {
        pollEvents(env, *xr);
        if (xr->sessionRunning) {
            renderFrame(*xr, env);
        } else {
            usleep(10000);
        }
    }

    if (xr->sessionRunning) {
        xrRequestExitSession(xr->session);
    }
    destroyCopyGl(env, *xr);
    eglMakeCurrent(xr->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    gVm->DetachCurrentThread();
}

// ---------------------------------------------------------------------------------------------
// Setup

bool hasInstanceExtension(const char* name) {
    uint32_t count = 0;
    if (XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr))) {
        return false;
    }
    std::vector<XrExtensionProperties> properties(count, {XR_TYPE_EXTENSION_PROPERTIES});
    if (XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, properties.data()))) {
        return false;
    }
    for (const auto& property : properties) {
        if (strcmp(property.extensionName, name) == 0) {
            return true;
        }
    }
    return false;
}

bool chooseSwapchainFormat(XrState& xr) {
    uint32_t count = 0;
    if (!check(xrEnumerateSwapchainFormats(xr.session, 0, &count, nullptr), "xrEnumerateSwapchainFormats")) {
        return false;
    }
    std::vector<int64_t> formats(count);
    if (!check(xrEnumerateSwapchainFormats(xr.session, count, &count, formats.data()), "xrEnumerateSwapchainFormats")) {
        return false;
    }
    for (int64_t format : formats) {
        if (format == 0x8058 /* GL_RGBA8 */) {
            xr.swapchainFormat = format;
        } else if (format == 0x8C43 /* GL_SRGB8_ALPHA8 */ && xr.swapchainFormat == 0) {
            xr.swapchainFormat = format;
        }
    }
    if (xr.swapchainFormat == 0 && !formats.empty()) {
        xr.swapchainFormat = formats[0];
    }
    xr.decodeSrgb = xr.swapchainFormat == 0x8C43;
    return xr.swapchainFormat != 0;
}

bool createQuadSwapchain(XrState& xr, Quad& quad, int32_t width, int32_t height) {
    quad.width = width;
    quad.height = height;

    XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    info.format = xr.swapchainFormat;
    info.sampleCount = 1;
    info.width = width;
    info.height = height;
    info.faceCount = 1;
    info.arraySize = 1;
    info.mipCount = 1;
    if (!check(xrCreateSwapchain(xr.session, &info, &quad.swapchain), "xrCreateSwapchain")) {
        return false;
    }

    uint32_t imageCount = 0;
    if (!check(xrEnumerateSwapchainImages(quad.swapchain, 0, &imageCount, nullptr), "xrEnumerateSwapchainImages")) {
        return false;
    }
    quad.images.assign(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    if (!check(xrEnumerateSwapchainImages(quad.swapchain, imageCount, &imageCount,
                                          reinterpret_cast<XrSwapchainImageBaseHeader*>(quad.images.data())),
               "xrEnumerateSwapchainImages")) {
        return false;
    }
    LOGI("Swapchain format=0x%llx %dx%d, %u images", static_cast<long long>(xr.swapchainFormat), width, height, imageCount);
    return true;
}

// Creates instance, session, input actions, passthrough and the quad swapchains.
// Leaves the XR EGL context current on the calling thread once initEgl() ran.
bool createXrSession(XrState& xr, jint gameWidth, jint gameHeight, jint menuWidth, jint menuHeight) {
    if (!gLoaderInitialized) {
        PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
        if (!getProc(XR_NULL_HANDLE, "xrInitializeLoaderKHR", initializeLoader)) {
            return false;
        }
        XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loaderInfo.applicationVM = gVm;
        loaderInfo.applicationContext = xr.activity;
        if (!check(initializeLoader(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)),
                   "xrInitializeLoaderKHR")) {
            return false;
        }
        gLoaderInitialized = true;
    }

    xr.passthroughSupported = hasInstanceExtension(XR_FB_PASSTHROUGH_EXTENSION_NAME);
    LOGI("%s available: %d", XR_FB_PASSTHROUGH_EXTENSION_NAME, xr.passthroughSupported);

    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = gVm;
    androidInfo.applicationActivity = xr.activity;

    std::vector<const char*> extensions = {XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
    if (xr.passthroughSupported) {
        extensions.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);
    }

    XrInstanceCreateInfo instanceInfo{XR_TYPE_INSTANCE_CREATE_INFO};
    instanceInfo.next = &androidInfo;
    instanceInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    instanceInfo.enabledExtensionNames = extensions.data();
    strncpy(instanceInfo.applicationInfo.applicationName, "M64Plus Quest", XR_MAX_APPLICATION_NAME_SIZE);
    instanceInfo.applicationInfo.applicationVersion = 1;
    strncpy(instanceInfo.applicationInfo.engineName, "mupen64plus-ae", XR_MAX_ENGINE_NAME_SIZE);
    instanceInfo.applicationInfo.engineVersion = 1;
    instanceInfo.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    if (!check(xrCreateInstance(&instanceInfo, &xr.instance), "xrCreateInstance")) {
        return false;
    }

    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    if (!check(xrGetSystem(xr.instance, &systemInfo, &xr.systemId), "xrGetSystem")) {
        return false;
    }

    PFN_xrGetOpenGLESGraphicsRequirementsKHR getGraphicsRequirements = nullptr;
    if (!getProc(xr.instance, "xrGetOpenGLESGraphicsRequirementsKHR", getGraphicsRequirements)) {
        return false;
    }
    if (xr.passthroughSupported && !loadPassthroughFns(xr)) {
        xr.passthroughSupported = false;
    }

    // Required before xrCreateSession
    XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    if (!check(getGraphicsRequirements(xr.instance, xr.systemId, &requirements),
               "xrGetOpenGLESGraphicsRequirementsKHR")) {
        return false;
    }

    // Meta's runtime needs the context current for session and swapchain calls
    if (!initEgl(xr)) {
        return false;
    }
    eglMakeCurrent(xr.eglDisplay, xr.eglSurface, xr.eglSurface, xr.eglContext);

    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    binding.display = xr.eglDisplay;
    binding.config = xr.eglConfig;
    binding.context = xr.eglContext;

    XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO};
    sessionInfo.next = &binding;
    sessionInfo.systemId = xr.systemId;
    if (!check(xrCreateSession(xr.instance, &sessionInfo, &xr.session), "xrCreateSession")) {
        return false;
    }

    XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace = {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    if (!check(xrCreateReferenceSpace(xr.session, &spaceInfo, &xr.space), "xrCreateReferenceSpace")) {
        return false;
    }

    if (!createActions(xr)) {
        return false;
    }

    if (xr.passthroughSupported && !createPassthrough(xr)) {
        xr.passthroughSupported = false;
    }

    return chooseSwapchainFormat(xr) &&
           createQuadSwapchain(xr, xr.quads[QUAD_GAME], gameWidth, gameHeight) &&
           createQuadSwapchain(xr, xr.quads[QUAD_MENU], menuWidth, menuHeight);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeCreate(JNIEnv* env, jclass,
                                                                  jobject activity, jobject listener,
                                                                  jint gameWidth, jint gameHeight,
                                                                  jint menuWidth, jint menuHeight) {
    if (gXr != nullptr) {
        LOGE("XR already created");
        return JNI_FALSE;
    }

    auto* xr = new XrState();
    xr->activity = env->NewGlobalRef(activity);
    xr->listener = env->NewGlobalRef(listener);
    jclass listenerClass = env->GetObjectClass(listener);
    xr->onInput = env->GetMethodID(listenerClass, "onXrInput", "(FFFFFFFFI)V");
    xr->onSessionState = env->GetMethodID(listenerClass, "onXrSessionState", "(I)V");
    if (xr->onInput == nullptr || xr->onSessionState == nullptr) {
        env->ExceptionClear();
        LOGE("Listener is missing callback methods");
        destroyXr(env, xr);
        return JNI_FALSE;
    }

    const bool created = createXrSession(*xr, gameWidth, gameHeight, menuWidth, menuHeight);
    // The frame thread makes the context current again
    if (xr->eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(xr->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (!created) {
        destroyXr(env, xr);
        return JNI_FALSE;
    }

    gXr = xr;
    return JNI_TRUE;
}

// Must be called before nativeStart
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetSourceTexture(JNIEnv* env, jclass, jint quad,
                                                                            jobject texture) {
    if (gXr != nullptr && quad >= 0 && quad < QUAD_COUNT && gXr->quads[quad].sourceTexture == nullptr) {
        gXr->quads[quad].sourceTexture = env->NewGlobalRef(texture);
    }
}

JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetQuad(JNIEnv*, jclass, jint quad, jboolean visible,
                                                                   jfloat width, jfloat distance, jfloat offsetY,
                                                                   jboolean blendAlpha) {
    if (gXr == nullptr || quad < 0 || quad >= QUAD_COUNT) {
        return;
    }
    std::lock_guard<std::mutex> lock(gXr->quadMutex);
    QuadSettings& settings = gXr->quads[quad].settings;
    settings.visible = visible;
    settings.width = width;
    settings.distance = distance;
    settings.offsetY = offsetY;
    settings.blendAlpha = blendAlpha;
}

// Returns false if passthrough is not supported
JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetPassthrough(JNIEnv*, jclass, jboolean enabled) {
    if (gXr == nullptr || !gXr->passthroughSupported) {
        return JNI_FALSE;
    }
    gXr->passthroughWanted = enabled;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeStart(JNIEnv*, jclass) {
    if (gXr == nullptr || gXr->frameThread.joinable()) {
        return;
    }
    gXr->stopRequested = false;
    gXr->frameThread = std::thread(frameLoop, gXr);
}

JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeDestroy(JNIEnv* env, jclass) {
    if (gXr == nullptr) {
        return;
    }
    gXr->stopRequested = true;
    if (gXr->frameThread.joinable()) {
        gXr->frameThread.join();
    }
    destroyXr(env, gXr);
    gXr = nullptr;
}

}  // extern "C"
