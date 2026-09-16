package paulscode.android.mupen64plusae.game.xr;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

/**
 * Thin wrapper around the native OpenXR session (quest-xr module).
 * <p>
 * The session renders a quad layer whose content comes from an Android surface swapchain;
 * the game surface draws into {@link #create}'s returned Surface instead of its SurfaceView.
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

    /** Callbacks are invoked on the XR frame thread. */
    public interface Listener
    {
        void onXrInput(float leftX, float leftY, float rightX, float rightY,
                       float leftTrigger, float rightTrigger, float leftGrip, float rightGrip, int buttons);

        void onXrSessionState(int state);
    }

    private static boolean sLibraryLoaded = false;

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
     * Create the XR session and a surface swapchain of the given size.
     * @return The surface to render the game into, or null if XR is unavailable
     */
    public static Surface create(Activity activity, Listener listener, int width, int height)
    {
        if (!isQuestDevice() || !loadLibrary()) {
            return null;
        }
        return nativeCreate(activity, listener, width, height);
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
    }

    /** Set the virtual screen width and distance, in meters. */
    public static void setQuad(float width, float distance)
    {
        if (sLibraryLoaded) {
            nativeSetQuad(width, distance);
        }
    }

    private static native Surface nativeCreate(Activity activity, Listener listener, int width, int height);
    private static native void nativeStart();
    private static native void nativeDestroy();
    private static native void nativeSetQuad(float width, float distance);
}
