package org.mupen64plusae.ps1;

import android.content.Context;
import android.view.Surface;

import java.io.File;

/**
 * PS1 spike: JNI wrapper around the minimal libretro frontend in libps1frontend.so.
 * The frontend runs the Beetle PSX HW core (libbeetle_psx_hw.so) on its own thread and renders
 * with GLES3 onto the Surface passed to {@link #start}.
 */
public final class LibretroFrontend
{
    /** File name the Beetle PSX HW core is packaged under, see ps1-libretro/build.gradle */
    public static final String CORE_LIBRARY = "libbeetle_psx_hw.so";

    // libretro RETRO_DEVICE_ID_JOYPAD_* bit positions, with the PlayStation names
    public static final int PS_CROSS = 1;          // B
    public static final int PS_SQUARE = 1 << 1;    // Y
    public static final int PS_SELECT = 1 << 2;
    public static final int PS_START = 1 << 3;
    public static final int PS_UP = 1 << 4;
    public static final int PS_DOWN = 1 << 5;
    public static final int PS_LEFT = 1 << 6;
    public static final int PS_RIGHT = 1 << 7;
    public static final int PS_CIRCLE = 1 << 8;    // A
    public static final int PS_TRIANGLE = 1 << 9;  // X
    public static final int PS_L1 = 1 << 10;
    public static final int PS_R1 = 1 << 11;
    public static final int PS_L2 = 1 << 12;
    public static final int PS_R2 = 1 << 13;
    public static final int PS_L3 = 1 << 14;
    public static final int PS_R3 = 1 << 15;

    private static boolean sLoaded = false;

    private LibretroFrontend()
    {
    }

    private static boolean loadLibrary()
    {
        if (!sLoaded) {
            System.loadLibrary("ps1frontend");
            sLoaded = true;
        }
        return sLoaded;
    }

    /** Absolute path of the packaged core in the app's native library directory. */
    public static String getCorePath(Context context)
    {
        return new File(context.getApplicationInfo().nativeLibraryDir, CORE_LIBRARY).getAbsolutePath();
    }

    /**
     * Start the core on its own thread, rendering into the given surface.
     * @param contentPath Path to a .cue/.chd/.pbp the app can read
     * @param systemDir Directory with the PS1 BIOS (e.g. scph5501.bin)
     * @param saveDir Directory for memory cards
     * @return False if the surface was unusable; core errors only show in logcat (tags Ps1Frontend/Ps1Core)
     */
    public static boolean start(Surface surface, String corePath, String contentPath, String systemDir, String saveDir)
    {
        return loadLibrary() && nativeStart(surface, corePath, contentPath, systemDir, saveDir);
    }

    /** Stop the emulation thread and release the core. Blocks until it has finished. */
    public static void stop()
    {
        if (sLoaded) {
            nativeStop();
        }
    }

    public static boolean isRunning()
    {
        return sLoaded && nativeIsRunning();
    }

    public static void setPaused(boolean paused)
    {
        if (sLoaded) {
            nativeSetPaused(paused);
        }
    }

    /**
     * @param buttons PS_* bits
     * @param leftX Analog values -32768..32767, y down is positive (libretro convention)
     */
    public static void setInput(int buttons, int leftX, int leftY, int rightX, int rightY)
    {
        if (sLoaded) {
            nativeSetInput(buttons, leftX, leftY, rightX, rightY);
        }
    }

    private static native boolean nativeStart(Surface surface, String corePath, String contentPath,
                                              String systemDir, String saveDir);
    private static native void nativeStop();
    private static native boolean nativeIsRunning();
    private static native void nativeSetPaused(boolean paused);
    private static native void nativeSetInput(int buttons, int leftX, int leftY, int rightX, int rightY);
}
