package paulscode.android.mupen64plusae.game.xr;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Thin wrapper around the native OpenXR session (quest-xr module).
 * <p>
 * The session shows quad layers for the game, the VR menu and a 2D N64 controller, and a 3D N64
 * controller in the projection layer, optionally over passthrough.
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
    public static final int QUAD_CONTROLLER = 2;
    public static final int QUAD_DOCK = 3;
    private static final int QUAD_COUNT = 4;

    // Quad attach modes, must match quest_xr.cpp
    /** Position and yaw in the tracking space */
    public static final int ATTACH_WORLD = 0;
    /** Position and yaw relative to the game quad */
    public static final int ATTACH_GAME = 1;
    /** Between the controllers, raised by the y offset, facing the head */
    public static final int ATTACH_HANDS = 2;

    /** Callbacks are invoked on the XR frame thread. */
    public interface Listener
    {
        void onXrInput(float leftX, float leftY, float rightX, float rightY,
                       float leftTrigger, float rightTrigger, float leftGrip, float rightGrip, int buttons);

        void onXrSessionState(int state);

        /** The game screen was grabbed and released at a new pose. */
        void onXrScreenMoved(float x, float y, float z, float yaw, float width);

        /**
         * A controller is aiming at a pointable quad. u and v are 0..1 from its top left corner;
         * quad is -1 with u and v at -1 when nothing is hit.
         */
        void onXrPointer(int quad, float u, float v, boolean pressed);
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
     * Create the XR session with game, menu, controller and dock quads of the given pixel sizes.
     * @return False if XR is unavailable
     */
    public static boolean create(Activity activity, Listener listener, int gameWidth, int gameHeight,
                                 int menuWidth, int menuHeight, int controllerWidth, int controllerHeight,
                                 int dockWidth, int dockHeight)
    {
        if (!isQuestDevice() || !loadLibrary()) {
            return false;
        }
        if (!nativeCreate(activity, listener, gameWidth, gameHeight, menuWidth, menuHeight,
                controllerWidth, controllerHeight, dockWidth, dockHeight)) {
            return false;
        }

        final int[][] sizes = {{gameWidth, gameHeight}, {menuWidth, menuHeight},
                {controllerWidth, controllerHeight}, {dockWidth, dockHeight}};
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

    /**
     * Load the 3D controller mesh and texture from assets. Must be called before {@link #start()};
     * without it the 3D controller falls back to a simple procedural model.
     */
    public static void loadControllerModel(AssetManager assets)
    {
        if (!sLibraryLoaded) {
            return;
        }
        try (InputStream meshStream = assets.open("quest/n64_controller.bin");
             InputStream textureStream = assets.open("quest/n64_controller.png")) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = meshStream.read(chunk)) > 0) {
                bytes.write(chunk, 0, read);
            }
            final ByteBuffer mesh = ByteBuffer.allocateDirect(bytes.size());
            mesh.put(bytes.toByteArray());

            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPremultiplied = false;
            final Bitmap texture = BitmapFactory.decodeStream(textureStream, null, options);
            if (texture == null || !nativeSetControllerModel(mesh, texture)) {
                Log.w(TAG, "Unable to use the controller model");
            }
            if (texture != null) {
                texture.recycle();
            }
        } catch (IOException e) {
            Log.w(TAG, "Controller model not found", e);
        }
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
     * Place a quad. The height follows the pixel aspect ratio.
     * @param attach One of the ATTACH_ constants, defines what x/y/z and yaw are relative to
     * @param yaw Rotation around the vertical axis in radians, 0 faces the initial head position
     * @param width Width in meters
     * @param blendAlpha Whether the quad's alpha channel is used for blending
     * @param cornerRadius Rounds the corners by this many meters, needs blendAlpha
     * @param opaqueSource Treat the drawn content as opaque, e.g. the emulator output
     */
    public static void setQuad(int quad, boolean visible, int attach, float x, float y, float z, float yaw,
                               float width, boolean blendAlpha, float cornerRadius, boolean opaqueSource)
    {
        if (sLibraryLoaded) {
            nativeSetQuad(quad, visible, attach, x, y, z, yaw, width, blendAlpha, cornerRadius, opaqueSource);
        }
    }

    /** While enabled, either thumbstick resizes the game screen without grabbing it. */
    public static void setStickResizeEnabled(boolean enabled)
    {
        if (sLibraryLoaded) {
            nativeSetStickResizeEnabled(enabled);
        }
    }

    /** While enabled, aiming a controller at the menu quad is reported through onXrPointer. */
    public static void setPointerEnabled(boolean enabled)
    {
        if (sLibraryLoaded) {
            nativeSetPointerEnabled(enabled);
        }
    }

    /** The emulator draws both eyes into the game quad side by side; each eye gets its half. */
    public static void setStereoGame(boolean enabled)
    {
        if (sLibraryLoaded) {
            nativeSetStereoGame(enabled);
        }
    }

    /** While enabled, holding a grip moves the game screen with that controller. */
    public static void setGrabEnabled(boolean enabled)
    {
        if (sLibraryLoaded) {
            nativeSetGrabEnabled(enabled);
        }
    }

    /**
     * State shown by the 3D controller. Safe to call from any thread.
     * @param buttons Bit i set when N64 button i (AbstractController indices) is pressed
     */
    public static void setN64State(int buttons, float axisX, float axisY)
    {
        if (sLibraryLoaded) {
            nativeSetN64State(buttons, axisX, axisY);
        }
    }

    /** Show the 3D N64 controller between the hands. */
    public static void setController3dVisible(boolean visible)
    {
        if (sLibraryLoaded) {
            nativeSetController3dVisible(visible);
        }
    }

    /** Move the game screen straight ahead of the current head direction; reported via onXrScreenMoved. */
    public static void recenterScreen(float distance, float width)
    {
        if (sLibraryLoaded) {
            nativeRecenterScreen(distance, width);
        }
    }

    /** @return False if passthrough is not supported */
    public static boolean setPassthrough(boolean enabled)
    {
        return sLibraryLoaded && nativeSetPassthrough(enabled);
    }

    private static native boolean nativeCreate(Activity activity, Listener listener, int gameWidth, int gameHeight,
                                               int menuWidth, int menuHeight, int controllerWidth,
                                               int controllerHeight, int dockWidth, int dockHeight);
    private static native void nativeSetSourceTexture(int quad, SurfaceTexture texture);
    private static native void nativeSetQuad(int quad, boolean visible, int attach, float x, float y, float z,
                                             float yaw, float width, boolean blendAlpha, float cornerRadius,
                                             boolean opaqueSource);
    private static native void nativeSetStickResizeEnabled(boolean enabled);
    private static native void nativeSetGrabEnabled(boolean enabled);
    private static native void nativeSetPointerEnabled(boolean enabled);
    private static native void nativeSetStereoGame(boolean enabled);
    private static native boolean nativeSetControllerModel(ByteBuffer mesh, Bitmap texture);
    private static native void nativeSetN64State(int buttons, float axisX, float axisY);
    private static native void nativeSetController3dVisible(boolean visible);
    private static native void nativeRecenterScreen(float distance, float width);
    private static native boolean nativeSetPassthrough(boolean enabled);
    private static native void nativeStart();
    private static native void nativeDestroy();
}
