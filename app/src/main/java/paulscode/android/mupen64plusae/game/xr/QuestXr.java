package paulscode.android.mupen64plusae.game.xr;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

/**
 * Thin wrapper around the native OpenXR session (quest-xr module).
 * <p>
 * The session shows quad layers for the game and the VR menu, optionally over passthrough.
 * Each quad gets a Surface backed by a SurfaceTexture; the native frame thread copies whatever
 * is drawn into it into an OpenXR swapchain.
 */
public class QuestXr
{
    private static final String TAG = "QuestXr";

    // Button bits, must match quest_xr.cpp
    public static final int BTN_A = 1;
    public static final int BTN_B = 1 << 1;
    public static final int BTN_X = 1 << 2;
    public static final int BTN_Y = 1 << 3;
    public static final int BTN_MENU = 1 << 4;
    public static final int BTN_LSTICK = 1 << 5;
    public static final int BTN_RSTICK = 1 << 6;

    // XrSessionState values
    public static final int STATE_IDLE = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_SYNCHRONIZED = 3;
    public static final int STATE_VISIBLE = 4;
    public static final int STATE_FOCUSED = 5;
    public static final int STATE_STOPPING = 6;
    public static final int STATE_LOSS_PENDING = 7;
    public static final int STATE_EXITING = 8;

    // Quad ids, must match quest_xr.cpp
    public static final int QUAD_GAME = 0;
    public static final int QUAD_MENU = 1;
    private static final int QUAD_COUNT = 2;

    /** Callbacks are invoked on the XR frame thread. */
    public interface Listener
    {
        void onXrInput(float leftX, float leftY, float rightX, float rightY,
                       float leftTrigger, float rightTrigger, float leftGrip, float rightGrip, int buttons);

        void onXrSessionState(int state);
    }

    private static boolean sLibraryLoaded = false;
    private static final SurfaceTexture[] sTextures = new SurfaceTexture[QUAD_COUNT];
    private static final Surface[] sSurfaces = new Surface[QUAD_COUNT];

    public static boolean isQuestDevice()
    {
        return "Oculus".equalsIgnoreCase(Build.MANUFACTURER) || "Meta".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private static boolean loadLibrary()
    {
        if (!sLibraryLoaded) {
            try {
                System.loadLibrary("questxr");
                sLibraryLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Unable to load questxr", e);
            }
        }
        return sLibraryLoaded;
    }

    /**
     * Create the XR session with a game and a menu quad of the given pixel sizes.
     * @return False if XR is unavailable
     */
    public static boolean create(Activity activity, Listener listener, int gameWidth, int gameHeight,
                                 int menuWidth, int menuHeight)
    {
        if (!isQuestDevice() || !loadLibrary()) {
            return false;
        }
        if (!nativeCreate(activity, listener, gameWidth, gameHeight, menuWidth, menuHeight)) {
            return false;
        }

        final int[][] sizes = {{gameWidth, gameHeight}, {menuWidth, menuHeight}};
        for (int quad = 0; quad < QUAD_COUNT; ++quad) {
            // Created detached; the native frame thread attaches it to its own GL context
            sTextures[quad] = new SurfaceTexture(false);
            sTextures[quad].setDefaultBufferSize(sizes[quad][0], sizes[quad][1]);
            nativeSetSourceTexture(quad, sTextures[quad]);
            sSurfaces[quad] = new Surface(sTextures[quad]);
        }
        return true;
    }

    /** The surface to draw the given quad's content into. */
    public static Surface getSurface(int quad)
    {
        return sSurfaces[quad];
    }

    /** Start the frame loop thread. */
    public static void start()
    {
        nativeStart();
    }

    /** Stop the frame loop and destroy the session. Safe to call if never created. */
    public static void destroy()
    {
        if (sLibraryLoaded) {
            nativeDestroy();
        }
        for (int quad = 0; quad < QUAD_COUNT; ++quad) {
            if (sSurfaces[quad] != null) {
                sSurfaces[quad].release();
                sSurfaces[quad] = null;
            }
            if (sTextures[quad] != null) {
                sTextures[quad].release();
                sTextures[quad] = null;
            }
        }
    }

    /**
     * Place a quad straight ahead of the initial head position.
     * @param width Width in meters, the height follows the pixel aspect ratio
     * @param distance Distance in meters
     * @param offsetY Vertical offset in meters
     * @param blendAlpha Whether the quad's alpha channel is used for blending
     */
    public static void setQuad(int quad, boolean visible, float width, float distance, float offsetY, boolean blendAlpha)
    {
        if (sLibraryLoaded) {
            nativeSetQuad(quad, visible, width, distance, offsetY, blendAlpha);
        }
    }

    /** @return False if passthrough is not supported */
    public static boolean setPassthrough(boolean enabled)
    {
        return sLibraryLoaded && nativeSetPassthrough(enabled);
    }

    private static native boolean nativeCreate(Activity activity, Listener listener, int gameWidth, int gameHeight,
                                               int menuWidth, int menuHeight);
    private static native void nativeSetSourceTexture(int quad, SurfaceTexture texture);
    private static native void nativeSetQuad(int quad, boolean visible, float width, float distance, float offsetY,
                                             boolean blendAlpha);
    private static native boolean nativeSetPassthrough(boolean enabled);
    private static native void nativeStart();
    private static native void nativeDestroy();
}
