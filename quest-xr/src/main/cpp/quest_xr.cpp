// OpenXR bridge for Meta Quest: shows the emulator output and an in-VR menu on quad
// layers, optionally over passthrough, and forwards Touch controller input to Java.
//
// Each quad is fed by an android.graphics.SurfaceTexture owned by Java. The emulator
// (or the menu) draws into a Surface created from it; the frame thread copies the
// latest buffer into an OpenGL ES swapchain every frame. Meta's runtime rejects
// XR_KHR_android_surface_swapchain, so there is no zero-copy path.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include "controller_model.h"

#include <algorithm>
#include <atomic>
#include <cmath>
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
    QUAD_CONTROLLER = 2,
    QUAD_DOCK = 3,
    QUAD_COUNT
};

// Back to front; the projection layer with the 3D controller and the ray is submitted after these
const QuadId kQuadOrder[QUAD_COUNT] = {QUAD_GAME, QUAD_CONTROLLER, QUAD_DOCK, QUAD_MENU};

// How a quad's pose is interpreted, must match QuestXr.java
enum Attach {
    ATTACH_WORLD = 0,  // position and yaw in the LOCAL space
    ATTACH_GAME = 1,   // position and yaw relative to the game quad
    ATTACH_HANDS = 2,  // between the controllers, offset upwards by position.y, facing the head
};

enum Hand { HAND_LEFT = 0, HAND_RIGHT = 1, HAND_COUNT };

enum ActionId {
    ACT_LSTICK, ACT_RSTICK,
    ACT_LTRIGGER, ACT_RTRIGGER, ACT_LGRIP, ACT_RGRIP,
    ACT_A, ACT_B, ACT_X, ACT_Y, ACT_MENU, ACT_LSTICK_CLICK, ACT_RSTICK_CLICK,
    ACT_LPOSE, ACT_RPOSE,
    ACT_LAIM, ACT_RAIM,
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
    {"left_pose", XR_ACTION_TYPE_POSE_INPUT, "/user/hand/left/input/grip/pose"},
    {"right_pose", XR_ACTION_TYPE_POSE_INPUT, "/user/hand/right/input/grip/pose"},
    {"left_aim", XR_ACTION_TYPE_POSE_INPUT, "/user/hand/left/input/aim/pose"},
    {"right_aim", XR_ACTION_TYPE_POSE_INPUT, "/user/hand/right/input/aim/pose"},
};

struct QuadSettings {
    bool visible = false;
    int attach = ATTACH_WORLD;
    XrVector3f position = {0.0f, 0.0f, -2.0f};
    float yaw = 0.0f;     // radians around +Y
    float width = 2.0f;   // meters
    bool blendAlpha = false;
    float cornerRadius = 0.0f;  // meters, rounds the corners when blending
    bool opaqueSource = false;  // ignore the source alpha, e.g. for the emulator output
};

struct InputState {
    XrVector2f sticks[HAND_COUNT] = {};
    float triggers[HAND_COUNT] = {};
    float grips[HAND_COUNT] = {};
    int buttons = 0;
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
    jobject loaderContext = nullptr;  // separate reference, outlives this state (see gLoaderContext)
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
    XrSpace handSpaces[HAND_COUNT] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    XrSpace aimSpaces[HAND_COUNT] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    XrSpace viewSpace = XR_NULL_HANDLE;
    jmethodID onScreenMoved = nullptr;
    jmethodID onPointer = nullptr;

    // Pointer ray at the menu quad, only while pointing is enabled
    std::atomic<bool> pointerEnabled{false};
    bool pointerHit = false;          // frame thread only
    XrVector3f pointerFrom = {};
    XrVector3f pointerTo = {};
    int lastPointerQuad = -1;
    float lastPointerU = -1.0f;
    float lastPointerV = -1.0f;
    bool lastPointerPressed = false;

    InputState input;

    // 3D N64 controller in the projection layer
    std::atomic<bool> controller3dVisible{false};
    std::atomic<int> n64Buttons{0};
    std::atomic<float> n64AxisX{0.0f};
    std::atomic<float> n64AxisY{0.0f};
    XrViewConfigurationView eyeConfigs[2] = {{XR_TYPE_VIEW_CONFIGURATION_VIEW}, {XR_TYPE_VIEW_CONFIGURATION_VIEW}};
    XrSwapchain eyeSwapchains[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    std::vector<XrSwapchainImageOpenGLESKHR> eyeImages[2];
    GLuint depthBuffer = 0;
    GLuint rayProgram = 0;
    GLint rayViewProjectionLocation = -1;
    GLint rayColorLocation = -1;
    questxr::ControllerModel controllerModel;
    questxr::ControllerAsset controllerAsset;  // set from Java before the frame thread starts
    bool controllerReady = false;

    // Screen recenter request from Java, handled on the frame thread
    std::atomic<bool> recenterRequested{false};
    float recenterDistance = 2.0f;
    float recenterWidth = 2.0f;
    bool grabBlockedLogged = false;
    // Grabbing the game screen with a grip, only while grabbing is enabled (VR menu open)
    std::atomic<bool> grabEnabled{false};
    // Screen adjust mode: either stick resizes the screen without grabbing
    std::atomic<bool> stickResizeEnabled{false};
    bool stickResizing = false;
    int grabHand = -1;
    XrVector3f grabOffset = {};

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
    GLint opaqueLocation = -1;
    GLint cornerLocation = -1;
    GLint cornerSoftnessLocation = -1;
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
// Activity the OpenXR loader was initialized with. The runtime associates sessions with it and keeps
// using it until the loader is initialized again, so it is only released after the next initialization.
jobject gLoaderContext = nullptr;
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
    if (!check(xrAttachSessionActionSets(xr.session, &attach), "xrAttachSessionActionSets")) {
        return false;
    }

    const ActionId poseActions[HAND_COUNT] = {ACT_LPOSE, ACT_RPOSE};
    const ActionId aimActions[HAND_COUNT] = {ACT_LAIM, ACT_RAIM};
    for (int hand = 0; hand < HAND_COUNT; ++hand) {
        XrActionSpaceCreateInfo spaceInfo{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        spaceInfo.poseInActionSpace = {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
        spaceInfo.action = xr.actions[poseActions[hand]];
        if (!check(xrCreateActionSpace(xr.session, &spaceInfo, &xr.handSpaces[hand]), "xrCreateActionSpace")) {
            return false;
        }
        spaceInfo.action = xr.actions[aimActions[hand]];
        if (!check(xrCreateActionSpace(xr.session, &spaceInfo, &xr.aimSpaces[hand]), "xrCreateActionSpace(aim)")) {
            return false;
        }
    }
    return true;
}

void destroyXr(JNIEnv* env, XrState* xr) {
    for (XrSwapchain eyeSwapchain : xr->eyeSwapchains) {
        if (eyeSwapchain != XR_NULL_HANDLE) xrDestroySwapchain(eyeSwapchain);
    }
    for (Quad& quad : xr->quads) {
        if (quad.swapchain != XR_NULL_HANDLE) xrDestroySwapchain(quad.swapchain);
        if (quad.sourceTexture != nullptr) env->DeleteGlobalRef(quad.sourceTexture);
    }
    if (xr->passthroughLayer != XR_NULL_HANDLE) xr->passthroughFns.destroyLayer(xr->passthroughLayer);
    if (xr->passthrough != XR_NULL_HANDLE) xr->passthroughFns.destroy(xr->passthrough);
    for (XrSpace handSpace : xr->handSpaces) {
        if (handSpace != XR_NULL_HANDLE) xrDestroySpace(handSpace);
    }
    if (xr->viewSpace != XR_NULL_HANDLE) xrDestroySpace(xr->viewSpace);
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

    InputState& input = xr.input;
    input.sticks[HAND_LEFT] = getVector2(xr, ACT_LSTICK);
    input.sticks[HAND_RIGHT] = getVector2(xr, ACT_RSTICK);
    input.triggers[HAND_LEFT] = getFloat(xr, ACT_LTRIGGER);
    input.triggers[HAND_RIGHT] = getFloat(xr, ACT_RTRIGGER);
    input.grips[HAND_LEFT] = getFloat(xr, ACT_LGRIP);
    input.grips[HAND_RIGHT] = getFloat(xr, ACT_RGRIP);

    jint buttons = 0;
    if (getBool(xr, ACT_A)) buttons |= BTN_A;
    if (getBool(xr, ACT_B)) buttons |= BTN_B;
    if (getBool(xr, ACT_X)) buttons |= BTN_X;
    if (getBool(xr, ACT_Y)) buttons |= BTN_Y;
    if (getBool(xr, ACT_MENU)) buttons |= BTN_MENU;
    if (getBool(xr, ACT_LSTICK_CLICK)) buttons |= BTN_LSTICK;
    if (getBool(xr, ACT_RSTICK_CLICK)) buttons |= BTN_RSTICK;
    input.buttons = buttons;

    env->CallVoidMethod(xr.listener, xr.onInput,
                        input.sticks[HAND_LEFT].x, input.sticks[HAND_LEFT].y,
                        input.sticks[HAND_RIGHT].x, input.sticks[HAND_RIGHT].y,
                        input.triggers[HAND_LEFT], input.triggers[HAND_RIGHT],
                        input.grips[HAND_LEFT], input.grips[HAND_RIGHT],
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
out vec2 vQuad;
void main() {
    vec2 uv = aPosition * 0.5 + 0.5;
    vQuad = uv;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPosition, 0.0, 1.0);
})";
    static const char* kFragment = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform float uDecodeSrgb;
uniform float uOpaque;
uniform vec2 uCorner;       // corner radius as a fraction of width and height
uniform vec2 uCornerSoftness;
in vec2 vUv;
in vec2 vQuad;
out vec4 outColor;
void main() {
    vec4 color = texture(uTexture, vUv);
    // sRGB swapchains re-encode on write, so undo the gamma the source already applied
    if (uDecodeSrgb > 0.5) color.rgb = pow(color.rgb, vec3(2.2));
    if (uOpaque > 0.5) color.a = 1.0;
    if (uCorner.x > 0.0) {
        vec2 d = max(abs(vQuad - 0.5) - (0.5 - uCorner), 0.0) / uCorner;
        color.a *= 1.0 - smoothstep(1.0 - uCornerSoftness.x, 1.0, length(d));
    }
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
    xr.opaqueLocation = glGetUniformLocation(xr.program, "uOpaque");
    xr.cornerLocation = glGetUniformLocation(xr.program, "uCorner");
    xr.cornerSoftnessLocation = glGetUniformLocation(xr.program, "uCornerSoftness");
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
    if (xr.controllerReady) {
        xr.controllerModel.destroy();
        glDeleteRenderbuffers(1, &xr.depthBuffer);
        xr.controllerReady = false;
    }
    if (xr.glReady) {
        glDeleteFramebuffers(1, &xr.framebuffer);
        glDeleteProgram(xr.program);
        xr.glReady = false;
    }
}

// Copies the latest source frame into the next swapchain image of the quad.
void copyQuad(JNIEnv* env, XrState& xr, Quad& quad, const QuadSettings& settings) {
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
    glUniform1f(xr.opaqueLocation, settings.opaqueSource ? 1.0f : 0.0f);
    if (settings.cornerRadius > 0.0f && settings.width > 0.0f) {
        const float height = settings.width * static_cast<float>(quad.height) / static_cast<float>(quad.width);
        const float radiusPixels = settings.cornerRadius / settings.width * static_cast<float>(quad.width);
        glUniform2f(xr.cornerLocation, settings.cornerRadius / settings.width, settings.cornerRadius / height);
        // About one and a half pixels of antialiasing
        glUniform2f(xr.cornerSoftnessLocation, std::min(0.5f, 1.5f / std::max(radiusPixels, 1.0f)), 0.0f);
    } else {
        glUniform2f(xr.cornerLocation, 0.0f, 0.0f);
    }
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

// ---------------------------------------------------------------------------------------------
// Poses

bool locate(XrState& xr, XrSpace space, XrTime time, XrPosef& pose) {
    XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
    if (space == XR_NULL_HANDLE || XR_FAILED(xrLocateSpace(space, xr.space, time, &location))) {
        return false;
    }
    const XrSpaceLocationFlags required = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    if ((location.locationFlags & required) != required) {
        return false;
    }
    pose = location.pose;
    return true;
}

XrVector3f add(const XrVector3f& a, const XrVector3f& b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
XrVector3f scale(const XrVector3f& v, float f) { return {v.x * f, v.y * f, v.z * f}; }
float dot(const XrVector3f& a, const XrVector3f& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
XrVector3f sub(const XrVector3f& a, const XrVector3f& b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }

// Rotates v around +Y by yaw
XrVector3f rotateYaw(const XrVector3f& v, float yaw) {
    const float c = std::cos(yaw);
    const float s = std::sin(yaw);
    return {c * v.x + s * v.z, v.y, -s * v.x + c * v.z};
}

// Yaw that turns a quad at `from` (normal +Z) towards `to`
float yawTowards(const XrVector3f& from, const XrVector3f& to) {
    const XrVector3f d = sub(to, from);
    return std::atan2(d.x, d.z);
}

// Pitch (around local X, after yaw) that tilts a quad's normal towards `to`
float pitchTowards(const XrVector3f& from, const XrVector3f& to) {
    const XrVector3f d = sub(to, from);
    return -std::atan2(d.y, std::sqrt(d.x * d.x + d.z * d.z));
}

XrQuaternionf yawPitchQuaternion(float yaw, float pitch) {
    const float cy = std::cos(yaw * 0.5f), sy = std::sin(yaw * 0.5f);
    const float cp = std::cos(pitch * 0.5f), sp = std::sin(pitch * 0.5f);
    // q = qYaw(Y) * qPitch(X)
    return {cy * sp, sy * cp, -sy * sp, cy * cp};
}

// Moves the game screen with the grabbing controller; the stick of that hand scales it.
// Called on the frame thread after input was synced.
void updateGrab(JNIEnv* env, XrState& xr, XrTime time, XrDuration period, const XrPosef* hands[HAND_COUNT],
                const XrPosef* head) {
    const bool enabled = xr.grabEnabled && xr.sessionState == XR_SESSION_STATE_FOCUSED;

    if (xr.grabHand >= 0) {
        const int hand = xr.grabHand;
        QuadSettings released;
        bool finished = false;
        {
            std::lock_guard<std::mutex> lock(xr.quadMutex);
            QuadSettings& screen = xr.quads[QUAD_GAME].settings;
            if (!enabled || xr.input.grips[hand] < 0.5f || hands[hand] == nullptr) {
                finished = true;
                released = screen;
                xr.grabHand = -1;
            } else {
                screen.position = add(hands[hand]->position, xr.grabOffset);
                if (head != nullptr) {
                    screen.yaw = yawTowards(screen.position, head->position);
                }
                const float stick = xr.input.sticks[hand].y;
                if (std::fabs(stick) > 0.2f) {
                    const float seconds = static_cast<float>(period) * 1e-9f;
                    screen.width = std::min(8.0f, std::max(0.5f, screen.width * std::exp(stick * seconds * 1.2f)));
                }
            }
        }
        if (finished) {
            LOGI("Screen released at %.2f %.2f %.2f yaw %.2f width %.2f", released.position.x, released.position.y,
                 released.position.z, released.yaw, released.width);
            env->CallVoidMethod(xr.listener, xr.onScreenMoved, released.position.x, released.position.y,
                                released.position.z, released.yaw, released.width);
            clearException(env, "onXrScreenMoved");
        }
        return;
    }

    if (!enabled) {
        return;
    }

    if (xr.stickResizeEnabled) {
        const float left = xr.input.sticks[HAND_LEFT].y;
        const float right = xr.input.sticks[HAND_RIGHT].y;
        const float stick = std::fabs(left) > std::fabs(right) ? left : right;
        if (std::fabs(stick) > 0.2f) {
            std::lock_guard<std::mutex> lock(xr.quadMutex);
            QuadSettings& screen = xr.quads[QUAD_GAME].settings;
            const float seconds = static_cast<float>(period) * 1e-9f;
            screen.width = std::min(8.0f, std::max(0.3f, screen.width * std::exp(stick * seconds * 1.2f)));
            xr.stickResizing = true;
        } else if (xr.stickResizing) {
            xr.stickResizing = false;
            QuadSettings resized;
            {
                std::lock_guard<std::mutex> lock(xr.quadMutex);
                resized = xr.quads[QUAD_GAME].settings;
            }
            env->CallVoidMethod(xr.listener, xr.onScreenMoved, resized.position.x, resized.position.y,
                                resized.position.z, resized.yaw, resized.width);
            clearException(env, "onXrScreenMoved");
        }
    }

    for (int hand = 0; hand < HAND_COUNT; ++hand) {
        if (xr.input.grips[hand] > 0.7f && hands[hand] == nullptr && !xr.grabBlockedLogged) {
            xr.grabBlockedLogged = true;
            LOGE("Grip %d pressed but the controller pose is not tracked", hand);
        }
        if (xr.input.grips[hand] > 0.7f && hands[hand] != nullptr) {
            std::lock_guard<std::mutex> lock(xr.quadMutex);
            xr.grabHand = hand;
            xr.grabOffset = sub(xr.quads[QUAD_GAME].settings.position, hands[hand]->position);
            LOGI("Screen grabbed with hand %d", hand);
            return;
        }
    }
}

// Places the game screen straight ahead of the current head direction.
void updateRecenter(JNIEnv* env, XrState& xr, const XrPosef* head) {
    if (head == nullptr || !xr.recenterRequested.exchange(false)) {
        return;
    }
    XrVector3f forward = questxr::rotateVector(head->orientation, {0.0f, 0.0f, -1.0f});
    forward.y = 0.0f;
    const float length = std::sqrt(forward.x * forward.x + forward.z * forward.z);
    if (length < 0.01f) {
        forward = {0.0f, 0.0f, -1.0f};
    } else {
        forward = {forward.x / length, 0.0f, forward.z / length};
    }

    QuadSettings moved;
    {
        std::lock_guard<std::mutex> lock(xr.quadMutex);
        QuadSettings& screen = xr.quads[QUAD_GAME].settings;
        screen.position = add(head->position, {forward.x * xr.recenterDistance, 0.0f, forward.z * xr.recenterDistance});
        screen.yaw = yawTowards(screen.position, head->position);
        screen.width = xr.recenterWidth;
        moved = screen;
    }
    LOGI("Screen recentered at %.2f %.2f %.2f", moved.position.x, moved.position.y, moved.position.z);
    env->CallVoidMethod(xr.listener, xr.onScreenMoved, moved.position.x, moved.position.y, moved.position.z,
                        moved.yaw, moved.width);
    clearException(env, "onXrScreenMoved");
}

// Model matrix of the 3D controller held between both hands. Returns false without two tracked hands.
bool controllerMatrix(const XrPosef* hands[HAND_COUNT], float* model) {
    if (hands[HAND_LEFT] == nullptr || hands[HAND_RIGHT] == nullptr) {
        return false;
    }
    const XrVector3f& l = hands[HAND_LEFT]->position;
    const XrVector3f& r = hands[HAND_RIGHT]->position;

    XrVector3f x = sub(r, l);
    float length = std::sqrt(x.x * x.x + x.y * x.y + x.z * x.z);
    if (length < 0.01f) {
        return false;
    }
    x = {x.x / length, x.y / length, x.z / length};

    // Button face points where the tops of both Touch controllers point
    XrVector3f up = add(questxr::rotateVector(hands[HAND_LEFT]->orientation, {0.0f, 1.0f, 0.0f}),
                        questxr::rotateVector(hands[HAND_RIGHT]->orientation, {0.0f, 1.0f, 0.0f}));
    // z = x cross up, then re-orthogonalize up = z cross x
    XrVector3f z = {x.y * up.z - x.z * up.y, x.z * up.x - x.x * up.z, x.x * up.y - x.y * up.x};
    length = std::sqrt(z.x * z.x + z.y * z.y + z.z * z.z);
    if (length < 0.001f) {
        return false;
    }
    z = {z.x / length, z.y / length, z.z / length};
    const XrVector3f y = {z.y * x.z - z.z * x.y, z.z * x.x - z.x * x.z, z.x * x.y - z.y * x.x};

    // Flatter than the Touch controllers: tilt the face towards the far edge (rotate around x)
    const float tilt = 35.0f * static_cast<float>(M_PI) / 180.0f;
    const float c = std::cos(tilt), s = std::sin(tilt);
    const XrVector3f flatY = {y.x * c - z.x * s, y.y * c - z.y * s, y.z * c - z.z * s};
    const XrVector3f flatZ = {z.x * c + y.x * s, z.y * c + y.y * s, z.z * c + y.z * s};

    XrVector3f center = {(l.x + r.x) * 0.5f, (l.y + r.y) * 0.5f, (l.z + r.z) * 0.5f};
    // Down into the hands and slightly away from the player
    center = add(center, {-flatY.x * 0.02f - flatZ.x * 0.03f, -flatY.y * 0.02f - flatZ.y * 0.03f,
                          -flatY.z * 0.02f - flatZ.z * 0.03f});
    questxr::matrixFromBasis(x, flatY, flatZ, center, 1.15f, model);
    return true;
}

// A quad the pointer ray can hit, in world space
struct PointerTarget {
    int quad;
    XrPosef pose;
    float width;
    float height;
};

// Intersects the aim rays with the pointable quads and reports the hit to Java. Targets are tested
// front to back and the right hand goes first, so the menu can be pointed at with either controller.
void updatePointer(JNIEnv* env, XrState& xr, XrTime time, const PointerTarget* targets, int targetCount) {
    xr.pointerHit = false;
    if (!xr.pointerEnabled.load()) {
        targetCount = 0;
    }

    int quad = -1;
    float u = -1.0f, v = -1.0f;
    bool pressed = false;
    for (int i = 0; i < HAND_COUNT && quad < 0; ++i) {
        const int hand = i == 0 ? HAND_RIGHT : HAND_LEFT;
        XrPosef aim;
        if (targetCount == 0 || !locate(xr, xr.aimSpaces[hand], time, aim)) {
            continue;
        }
        const XrVector3f origin = aim.position;
        const XrVector3f direction = questxr::rotateVector(aim.orientation, {0.0f, 0.0f, -1.0f});

        for (int t = 0; t < targetCount; ++t) {
            const PointerTarget& target = targets[t];
            const XrVector3f right = questxr::rotateVector(target.pose.orientation, {1.0f, 0.0f, 0.0f});
            const XrVector3f up = questxr::rotateVector(target.pose.orientation, {0.0f, 1.0f, 0.0f});
            const XrVector3f normal = questxr::rotateVector(target.pose.orientation, {0.0f, 0.0f, 1.0f});
            const float denominator = dot(direction, normal);
            if (std::fabs(denominator) < 1e-4f) {
                continue;
            }
            const float distance = dot(sub(target.pose.position, origin), normal) / denominator;
            if (distance <= 0.0f || distance > 12.0f) {
                continue;
            }
            const XrVector3f point = add(origin, scale(direction, distance));
            const XrVector3f local = sub(point, target.pose.position);
            const float x = dot(local, right) / target.width + 0.5f;
            const float y = 0.5f - dot(local, up) / target.height;
            if (x < 0.0f || x > 1.0f || y < 0.0f || y > 1.0f) {
                continue;
            }
            quad = target.quad;
            u = x;
            v = y;
            pressed = xr.input.triggers[hand] > 0.5f;
            xr.pointerHit = true;
            xr.pointerFrom = origin;
            xr.pointerTo = point;
            break;
        }
    }

    const bool changed = quad != xr.lastPointerQuad || pressed != xr.lastPointerPressed ||
                         std::fabs(u - xr.lastPointerU) > 0.002f || std::fabs(v - xr.lastPointerV) > 0.002f;
    if (!changed) {
        return;
    }
    xr.lastPointerQuad = quad;
    xr.lastPointerPressed = pressed;
    xr.lastPointerU = u;
    xr.lastPointerV = v;
    env->CallVoidMethod(xr.listener, xr.onPointer, quad, u, v, pressed ? JNI_TRUE : JNI_FALSE);
    clearException(env, "onXrPointer");
}

// A thin beam from the controller to the point it aims at, plus a dot on the menu.
bool initRayGl(XrState& xr) {
    if (xr.rayProgram != 0) {
        return true;
    }
    const char* vertex = R"(#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in float aFade;
uniform mat4 uViewProjection;
out float vFade;
void main() {
    gl_Position = uViewProjection * vec4(aPosition, 1.0);
    vFade = aFade;
}
)";
    const char* fragment = R"(#version 300 es
precision mediump float;
in float vFade;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = vec4(uColor.rgb, uColor.a * vFade);
}
)";
    const GLuint vs = compileShader(GL_VERTEX_SHADER, vertex);
    const GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragment);
    if (vs == 0 || fs == 0) {
        return false;
    }
    xr.rayProgram = glCreateProgram();
    glAttachShader(xr.rayProgram, vs);
    glAttachShader(xr.rayProgram, fs);
    glLinkProgram(xr.rayProgram);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint linked = GL_FALSE;
    glGetProgramiv(xr.rayProgram, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        LOGE("Ray program did not link");
        glDeleteProgram(xr.rayProgram);
        xr.rayProgram = 0;
        return false;
    }
    xr.rayViewProjectionLocation = glGetUniformLocation(xr.rayProgram, "uViewProjection");
    xr.rayColorLocation = glGetUniformLocation(xr.rayProgram, "uColor");
    return true;
}

void drawRay(XrState& xr, const float* viewProjection, const XrVector3f& eye) {
    if (!initRayGl(xr)) {
        return;
    }
    // The beam is a ribbon that always faces the eye, so it stays visible from any angle
    XrVector3f along = sub(xr.pointerTo, xr.pointerFrom);
    const float length = std::sqrt(dot(along, along));
    if (length < 0.02f) {
        return;
    }
    along = scale(along, 1.0f / length);
    XrVector3f toEye = sub(eye, xr.pointerFrom);
    XrVector3f side = {along.y * toEye.z - along.z * toEye.y, along.z * toEye.x - along.x * toEye.z,
                       along.x * toEye.y - along.y * toEye.x};
    const float sideLength = std::sqrt(dot(side, side));
    if (sideLength < 1e-5f) {
        return;
    }
    side = scale(side, 1.0f / sideLength);

    const XrVector3f nearSide = scale(side, 0.0015f);
    const XrVector3f farSide = scale(side, 0.004f);
    const XrVector3f& a = xr.pointerFrom;
    const XrVector3f& b = xr.pointerTo;
    const float beam[] = {
        a.x - nearSide.x, a.y - nearSide.y, a.z - nearSide.z, 0.0f,
        a.x + nearSide.x, a.y + nearSide.y, a.z + nearSide.z, 0.0f,
        b.x - farSide.x, b.y - farSide.y, b.z - farSide.z, 1.0f,
        b.x + farSide.x, b.y + farSide.y, b.z + farSide.z, 1.0f,
    };

    // A dot where the beam meets the menu
    const XrVector3f dotSide = scale(side, 0.011f);
    XrVector3f dotUp = {side.y * along.z - side.z * along.y, side.z * along.x - side.x * along.z,
                        side.x * along.y - side.y * along.x};
    dotUp = scale(dotUp, 0.011f);
    const XrVector3f offset = scale(along, -0.004f);  // just in front of the quad
    const XrVector3f c = add(b, offset);
    const float dotStrip[] = {
        c.x - dotSide.x - dotUp.x, c.y - dotSide.y - dotUp.y, c.z - dotSide.z - dotUp.z, 1.0f,
        c.x + dotSide.x - dotUp.x, c.y + dotSide.y - dotUp.y, c.z + dotSide.z - dotUp.z, 1.0f,
        c.x - dotSide.x + dotUp.x, c.y - dotSide.y + dotUp.y, c.z - dotSide.z + dotUp.z, 1.0f,
        c.x + dotSide.x + dotUp.x, c.y + dotSide.y + dotUp.y, c.z + dotSide.z + dotUp.z, 1.0f,
    };

    glUseProgram(xr.rayProgram);
    glUniformMatrix4fv(xr.rayViewProjectionLocation, 1, GL_FALSE, viewProjection);
    glEnable(GL_BLEND);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);

    // Design system accent
    glUniform4f(xr.rayColorLocation, 0.0f, 0.874f, 0.874f, 0.75f);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 4 * sizeof(float), beam);
    glVertexAttribPointer(1, 1, GL_FLOAT, GL_FALSE, 4 * sizeof(float), beam + 3);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glUniform4f(xr.rayColorLocation, 0.0f, 0.874f, 0.874f, 0.95f);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 4 * sizeof(float), dotStrip);
    glVertexAttribPointer(1, 1, GL_FLOAT, GL_FALSE, 4 * sizeof(float), dotStrip + 3);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glDisable(GL_BLEND);
}

// Renders the 3D controller and the pointer ray into both eye swapchains.
// Returns false if nothing was rendered.
bool renderController(XrState& xr, XrTime time, const XrPosef* hands[HAND_COUNT],
                      XrCompositionLayerProjectionView views[2]) {
    float model[16];
    const bool drawController = xr.controller3dVisible && controllerMatrix(hands, model);
    if (xr.eyeSwapchains[0] == XR_NULL_HANDLE || (!drawController && !xr.pointerHit)) {
        return false;
    }

    if (drawController && !xr.controllerReady) {
        if (!xr.controllerModel.init(xr.controllerAsset)) {
            xr.controller3dVisible = false;
            if (!xr.pointerHit) {
                return false;
            }
        }
        glGenRenderbuffers(1, &xr.depthBuffer);
        glBindRenderbuffer(GL_RENDERBUFFER, xr.depthBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24,
                              static_cast<GLsizei>(xr.eyeConfigs[0].recommendedImageRectWidth),
                              static_cast<GLsizei>(xr.eyeConfigs[0].recommendedImageRectHeight));
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        xr.controllerReady = true;
        LOGI("3D controller ready (%s)", xr.controllerAsset.valid() ? "asset" : "procedural");
        // The GPU has its copy now
        xr.controllerAsset = {};
    }

    XrViewLocateInfo locateInfo{XR_TYPE_VIEW_LOCATE_INFO};
    locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locateInfo.displayTime = time;
    locateInfo.space = xr.space;
    XrViewState viewState{XR_TYPE_VIEW_STATE};
    XrView eyeViews[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    uint32_t viewCount = 0;
    if (XR_FAILED(xrLocateViews(xr.session, &locateInfo, &viewState, 2, &viewCount, eyeViews)) || viewCount != 2 ||
        (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
        return false;
    }

    const questxr::N64State state{xr.n64Buttons.load(), xr.n64AxisX.load(), xr.n64AxisY.load()};
    const int32_t width = static_cast<int32_t>(xr.eyeConfigs[0].recommendedImageRectWidth);
    const int32_t height = static_cast<int32_t>(xr.eyeConfigs[0].recommendedImageRectHeight);

    for (int eye = 0; eye < 2; ++eye) {
        uint32_t index = 0;
        XrSwapchainImageAcquireInfo acquireInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        if (!check(xrAcquireSwapchainImage(xr.eyeSwapchains[eye], &acquireInfo, &index), "xrAcquireSwapchainImage(eye)")) {
            return false;
        }
        XrSwapchainImageWaitInfo waitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        waitInfo.timeout = XR_INFINITE_DURATION;
        check(xrWaitSwapchainImage(xr.eyeSwapchains[eye], &waitInfo), "xrWaitSwapchainImage(eye)");

        glBindFramebuffer(GL_FRAMEBUFFER, xr.framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, xr.eyeImages[eye][index].image, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, xr.depthBuffer);
        glViewport(0, 0, width, height);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClearDepthf(1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);

        float view[16], projection[16], viewProjection[16];
        questxr::matrixView(eyeViews[eye].pose, view);
        questxr::matrixProjection(eyeViews[eye].fov, 0.05f, 100.0f, projection);
        questxr::matrixMultiply(projection, view, viewProjection);
        if (drawController && xr.controllerReady) {
            xr.controllerModel.draw(viewProjection, model, state, 0.85f);
        }
        glDisable(GL_DEPTH_TEST);
        if (xr.pointerHit) {
            drawRay(xr, viewProjection, eyeViews[eye].pose.position);
        }

        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        XrSwapchainImageReleaseInfo releaseInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        check(xrReleaseSwapchainImage(xr.eyeSwapchains[eye], &releaseInfo), "xrReleaseSwapchainImage(eye)");

        views[eye] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
        views[eye].pose = eyeViews[eye].pose;
        views[eye].fov = eyeViews[eye].fov;
        views[eye].subImage.swapchain = xr.eyeSwapchains[eye];
        views[eye].subImage.imageRect = {{0, 0}, {width, height}};
        views[eye].subImage.imageArrayIndex = 0;
    }
    return true;
}

// Computes the pose of a quad in the LOCAL space. Returns false if it can't be placed.
bool quadPose(const QuadSettings& settings, const QuadSettings& game, const XrPosef* hands[HAND_COUNT],
              const XrPosef* head, XrPosef& pose) {
    switch (settings.attach) {
        case ATTACH_GAME: {
            const float yaw = game.yaw + settings.yaw;
            pose.position = add(game.position, rotateYaw(settings.position, game.yaw));
            pose.orientation = yawPitchQuaternion(yaw, 0.0f);
            return true;
        }
        case ATTACH_HANDS: {
            XrVector3f center;
            if (hands[HAND_LEFT] != nullptr && hands[HAND_RIGHT] != nullptr) {
                const XrVector3f& l = hands[HAND_LEFT]->position;
                const XrVector3f& r = hands[HAND_RIGHT]->position;
                center = {(l.x + r.x) * 0.5f, (l.y + r.y) * 0.5f, (l.z + r.z) * 0.5f};
            } else if (hands[HAND_LEFT] != nullptr) {
                center = hands[HAND_LEFT]->position;
            } else if (hands[HAND_RIGHT] != nullptr) {
                center = hands[HAND_RIGHT]->position;
            } else {
                return false;
            }
            pose.position = add(center, settings.position);
            if (head != nullptr) {
                pose.orientation = yawPitchQuaternion(yawTowards(pose.position, head->position),
                                                      pitchTowards(pose.position, head->position));
            } else {
                pose.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
            }
            return true;
        }
        case ATTACH_WORLD:
        default:
            pose.position = settings.position;
            pose.orientation = yawPitchQuaternion(settings.yaw, 0.0f);
            return true;
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

    const XrTime time = frameState.predictedDisplayTime;
    XrPosef handPoses[HAND_COUNT];
    const XrPosef* hands[HAND_COUNT] = {nullptr, nullptr};
    for (int hand = 0; hand < HAND_COUNT; ++hand) {
        if (locate(xr, xr.handSpaces[hand], time, handPoses[hand])) {
            hands[hand] = &handPoses[hand];
        }
    }
    XrPosef headPose;
    const XrPosef* head = locate(xr, xr.viewSpace, time, headPose) ? &headPose : nullptr;
    updateGrab(env, xr, time, frameState.predictedDisplayPeriod, hands, head);
    updateRecenter(env, xr, head);

    QuadSettings settings[QUAD_COUNT];
    {
        std::lock_guard<std::mutex> lock(xr.quadMutex);
        for (int i = 0; i < QUAD_COUNT; ++i) {
            settings[i] = xr.quads[i].settings;
        }
    }

    {
        // The quads the ray can hit, front to back; their poses are needed before the layers are built
        const QuadId pointable[] = {QUAD_MENU, QUAD_DOCK};
        PointerTarget targets[2];
        int targetCount = 0;
        for (QuadId id : pointable) {
            if (!settings[id].visible) {
                continue;
            }
            int32_t pixelWidth, pixelHeight;
            {
                std::lock_guard<std::mutex> lock(xr.quadMutex);
                pixelWidth = xr.quads[id].width;
                pixelHeight = xr.quads[id].height;
            }
            PointerTarget& target = targets[targetCount];
            target.quad = id;
            target.width = settings[id].width;
            target.height = pixelWidth > 0
                    ? target.width * static_cast<float>(pixelHeight) / static_cast<float>(pixelWidth)
                    : target.width;
            if (quadPose(settings[id], settings[QUAD_GAME], hands, head, target.pose)) {
                ++targetCount;
            }
        }
        updatePointer(env, xr, time, targets, targetCount);
    }

    XrCompositionLayerPassthroughFB passthroughLayer{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
    XrCompositionLayerQuad quadLayers[QUAD_COUNT];
    XrCompositionLayerProjection projectionLayer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    XrCompositionLayerProjectionView projectionViews[2];
    const XrCompositionLayerBaseHeader* layers[QUAD_COUNT + 2];
    uint32_t layerCount = 0;

    if (frameState.shouldRender) {
        if (!xr.glReady) {
            xr.glReady = initCopyGl(env, xr);
        }

        if (xr.passthroughActive) {
            passthroughLayer.layerHandle = xr.passthroughLayer;
            layers[layerCount++] = reinterpret_cast<XrCompositionLayerBaseHeader*>(&passthroughLayer);
        }

        for (QuadId i : kQuadOrder) {
            Quad& quad = xr.quads[i];
            if (!settings[i].visible) {
                continue;
            }
            if (xr.glReady) {
                copyQuad(env, xr, quad, settings[i]);
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
            if (!quadPose(settings[i], settings[QUAD_GAME], hands, head, layer.pose)) {
                continue;
            }
            layer.size = {settings[i].width,
                          settings[i].width * static_cast<float>(quad.height) / static_cast<float>(quad.width)};
            layers[layerCount++] = reinterpret_cast<XrCompositionLayerBaseHeader*>(&layer);
        }

        // Last, so the pointer dot stays visible on the quad it points at
        if (xr.glReady && renderController(xr, time, hands, projectionViews)) {
            projectionLayer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT |
                                         XR_COMPOSITION_LAYER_UNPREMULTIPLIED_ALPHA_BIT;
            projectionLayer.space = xr.space;
            projectionLayer.viewCount = 2;
            projectionLayer.views = projectionViews;
            layers[layerCount++] = reinterpret_cast<XrCompositionLayerBaseHeader*>(&projectionLayer);
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
bool createEyeSwapchains(XrState& xr) {
    uint32_t count = 0;
    if (!check(xrEnumerateViewConfigurationViews(xr.instance, xr.systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                                                 2, &count, xr.eyeConfigs), "xrEnumerateViewConfigurationViews") ||
        count != 2) {
        return false;
    }
    for (int eye = 0; eye < 2; ++eye) {
        XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        info.format = xr.swapchainFormat;
        info.sampleCount = 1;
        info.width = xr.eyeConfigs[eye].recommendedImageRectWidth;
        info.height = xr.eyeConfigs[eye].recommendedImageRectHeight;
        info.faceCount = 1;
        info.arraySize = 1;
        info.mipCount = 1;
        if (!check(xrCreateSwapchain(xr.session, &info, &xr.eyeSwapchains[eye]), "xrCreateSwapchain(eye)")) {
            return false;
        }
        uint32_t imageCount = 0;
        xrEnumerateSwapchainImages(xr.eyeSwapchains[eye], 0, &imageCount, nullptr);
        xr.eyeImages[eye].assign(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        if (!check(xrEnumerateSwapchainImages(xr.eyeSwapchains[eye], imageCount, &imageCount,
                                              reinterpret_cast<XrSwapchainImageBaseHeader*>(xr.eyeImages[eye].data())),
                   "xrEnumerateSwapchainImages(eye)")) {
            return false;
        }
    }
    LOGI("Eye swapchains %ux%u", xr.eyeConfigs[0].recommendedImageRectWidth, xr.eyeConfigs[0].recommendedImageRectHeight);
    return true;
}

bool createXrSession(XrState& xr, jint gameWidth, jint gameHeight, jint menuWidth, jint menuHeight,
                     jint controllerWidth, jint controllerHeight, jint dockWidth, jint dockHeight) {
    {
        PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
        if (!getProc(XR_NULL_HANDLE, "xrInitializeLoaderKHR", initializeLoader)) {
            return false;
        }
        XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loaderInfo.applicationVM = gVm;
        loaderInfo.applicationContext = xr.loaderContext;
        if (!check(initializeLoader(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)),
                   "xrInitializeLoaderKHR")) {
            return false;
        }
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
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    if (!check(xrCreateReferenceSpace(xr.session, &spaceInfo, &xr.viewSpace), "xrCreateReferenceSpace(VIEW)")) {
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
           createQuadSwapchain(xr, xr.quads[QUAD_MENU], menuWidth, menuHeight) &&
           createQuadSwapchain(xr, xr.quads[QUAD_CONTROLLER], controllerWidth, controllerHeight) &&
           createQuadSwapchain(xr, xr.quads[QUAD_DOCK], dockWidth, dockHeight) &&
           createEyeSwapchains(xr);
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
                                                                  jint menuWidth, jint menuHeight,
                                                                  jint controllerWidth, jint controllerHeight,
                                                                  jint dockWidth, jint dockHeight) {
    if (gXr != nullptr) {
        LOGE("XR already created");
        return JNI_FALSE;
    }

    auto* xr = new XrState();
    xr->activity = env->NewGlobalRef(activity);
    xr->loaderContext = env->NewGlobalRef(activity);
    xr->listener = env->NewGlobalRef(listener);
    jclass listenerClass = env->GetObjectClass(listener);
    xr->onInput = env->GetMethodID(listenerClass, "onXrInput", "(FFFFFFFFI)V");
    xr->onSessionState = env->GetMethodID(listenerClass, "onXrSessionState", "(I)V");
    xr->onScreenMoved = env->GetMethodID(listenerClass, "onXrScreenMoved", "(FFFFF)V");
    xr->onPointer = env->GetMethodID(listenerClass, "onXrPointer", "(IFFZ)V");
    if (xr->onInput == nullptr || xr->onSessionState == nullptr || xr->onScreenMoved == nullptr ||
        xr->onPointer == nullptr) {
        env->ExceptionClear();
        LOGE("Listener is missing callback methods");
        destroyXr(env, xr);
        return JNI_FALSE;
    }

    const bool created = createXrSession(*xr, gameWidth, gameHeight, menuWidth, menuHeight,
                                         controllerWidth, controllerHeight, dockWidth, dockHeight);
    // The loader now uses this activity; the previous one may be released
    if (gLoaderContext != nullptr) {
        env->DeleteGlobalRef(gLoaderContext);
    }
    gLoaderContext = xr->loaderContext;
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
                                                                   jint attach, jfloat x, jfloat y, jfloat z,
                                                                   jfloat yaw, jfloat width, jboolean blendAlpha,
                                                                   jfloat cornerRadius, jboolean opaqueSource) {
    if (gXr == nullptr || quad < 0 || quad >= QUAD_COUNT) {
        return;
    }
    std::lock_guard<std::mutex> lock(gXr->quadMutex);
    QuadSettings& settings = gXr->quads[quad].settings;
    settings.visible = visible;
    settings.attach = attach;
    // Don't fight an active grab or resize of the game screen
    if (!(quad == QUAD_GAME && (gXr->grabHand >= 0 || gXr->stickResizing))) {
        settings.position = {x, y, z};
        settings.yaw = yaw;
        settings.width = width;
    }
    settings.blendAlpha = blendAlpha;
    settings.cornerRadius = cornerRadius;
    settings.opaqueSource = opaqueSource;
}

JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetStickResizeEnabled(JNIEnv*, jclass, jboolean enabled) {
    if (gXr != nullptr) {
        gXr->stickResizeEnabled = enabled;
    }
}

JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetGrabEnabled(JNIEnv*, jclass, jboolean enabled) {
    if (gXr != nullptr) {
        gXr->grabEnabled = enabled;
    }
}

// Aim the controllers at the menu quad and report the hit through onXrPointer
JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetPointerEnabled(JNIEnv*, jclass, jboolean enabled) {
    if (gXr != nullptr) {
        gXr->pointerEnabled = enabled;
    }
}

// Must be called before nativeStart. mesh: direct buffer with the converted model, texture: ARGB_8888 bitmap
JNIEXPORT jboolean JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetControllerModel(JNIEnv* env, jclass, jobject mesh,
                                                                              jobject texture) {
    if (gXr == nullptr) {
        return JNI_FALSE;
    }
    const auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(mesh));
    const jlong size = env->GetDirectBufferCapacity(mesh);
    if (data == nullptr || size < 12 || memcmp(data, "N64M", 4) != 0) {
        LOGE("Invalid controller mesh");
        return JNI_FALSE;
    }
    uint32_t version = 0, vertexCount = 0;
    memcpy(&version, data + 4, 4);
    memcpy(&vertexCount, data + 8, 4);
    if (version != 1 || static_cast<jlong>(12 + vertexCount * 9ull * sizeof(float)) > size) {
        LOGE("Unsupported controller mesh version %u or size", version);
        return JNI_FALSE;
    }

    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, texture, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, texture, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Invalid controller texture");
        return JNI_FALSE;
    }

    questxr::ControllerAsset& asset = gXr->controllerAsset;
    asset.vertices.resize(vertexCount * 9);
    memcpy(asset.vertices.data(), data + 12, asset.vertices.size() * sizeof(float));
    asset.textureWidth = static_cast<int32_t>(info.width);
    asset.textureHeight = static_cast<int32_t>(info.height);
    asset.rgba.resize(static_cast<size_t>(info.width) * info.height * 4);
    for (uint32_t row = 0; row < info.height; ++row) {
        memcpy(asset.rgba.data() + row * info.width * 4, static_cast<uint8_t*>(pixels) + row * info.stride, info.width * 4);
    }
    AndroidBitmap_unlockPixels(env, texture);
    LOGI("Controller model: %u vertices, texture %ux%u", vertexCount, info.width, info.height);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetN64State(JNIEnv*, jclass, jint buttons, jfloat axisX,
                                                                       jfloat axisY) {
    if (gXr != nullptr) {
        gXr->n64Buttons = buttons;
        gXr->n64AxisX = axisX;
        gXr->n64AxisY = axisY;
    }
}

JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeSetController3dVisible(JNIEnv*, jclass, jboolean visible) {
    if (gXr != nullptr) {
        gXr->controller3dVisible = visible;
    }
}

JNIEXPORT void JNICALL
Java_paulscode_android_mupen64plusae_game_xr_QuestXr_nativeRecenterScreen(JNIEnv*, jclass, jfloat distance,
                                                                          jfloat width) {
    if (gXr != nullptr) {
        gXr->recenterDistance = distance;
        gXr->recenterWidth = width;
        gXr->recenterRequested = true;
    }
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
