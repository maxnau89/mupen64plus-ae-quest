package paulscode.android.mupen64plusae.ps1;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import org.mupen64plusae.ps1.LibretroFrontend;

import java.io.File;

import paulscode.android.mupen64plusae.game.xr.QuestXr;

/**
 * PS1 spike, debug builds only. Runs the Beetle PSX HW core on the QUAD_GAME surface of the Quest
 * XR shell (or a plain SurfaceView off-headset). Nothing here touches the N64 path.
 *
 * adb shell am start -n org.mupen64plusae.v3.quest.debug/paulscode.android.mupen64plusae.ps1.Ps1SpikeActivity \
 *     --es content /sdcard/Android/data/org.mupen64plusae.v3.quest.debug/files/ps1/games/game.cue
 *
 * Extras: content (required, .cue/.chd/.pbp), system_dir (BIOS, default files/ps1/system),
 * save_dir (default files/ps1/saves).
 */
public class Ps1SpikeActivity extends Activity implements QuestXr.Listener, SurfaceHolder.Callback
{
    private static final String TAG = "Ps1SpikeActivity";

    public static final String EXTRA_CONTENT = "content";
    public static final String EXTRA_SYSTEM_DIR = "system_dir";
    public static final String EXTRA_SAVE_DIR = "save_dir";

    // 4:3 like the PS1 output; the frontend letterboxes to the core's aspect ratio inside it
    private static final int XR_GAME_WIDTH = 1280;
    private static final int XR_GAME_HEIGHT = 960;
    private static final int XR_SMALL = 64;
    private static final float XR_SCREEN_DISTANCE = 2.0f;
    private static final float XR_SCREEN_SIZE = 2.0f;

    private static final float TRIGGER_THRESHOLD = 0.5f;
    private static final float DPAD_THRESHOLD = 0.5f;

    private String mContent;
    private String mSystemDir;
    private String mSaveDir;
    private boolean mXrMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final File base = new File(getExternalFilesDir(null), "ps1");
        mContent = getIntent().getStringExtra(EXTRA_CONTENT);
        mSystemDir = stringExtra(EXTRA_SYSTEM_DIR, new File(base, "system").getAbsolutePath());
        mSaveDir = stringExtra(EXTRA_SAVE_DIR, new File(base, "saves").getAbsolutePath());
        //noinspection ResultOfMethodCallIgnored
        new File(mSystemDir).mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(mSaveDir).mkdirs();

        if (mContent == null) {
            Log.e(TAG, "No content given, pass --es " + EXTRA_CONTENT + " /path/to/game.cue");
            finish();
            return;
        }
        Log.i(TAG, "content=" + mContent + " system=" + mSystemDir + " saves=" + mSaveDir);

        if (QuestXr.isQuestDevice() && QuestXr.create(this, this, XR_GAME_WIDTH, XR_GAME_HEIGHT,
                XR_SMALL, XR_SMALL, XR_SMALL, XR_SMALL, XR_SMALL, XR_SMALL)) {
            mXrMode = true;
            // Only the game screen, straight ahead; menu, controller and dock quads stay hidden
            QuestXr.setQuad(QuestXr.QUAD_GAME, true, QuestXr.ATTACH_WORLD, 0.0f, 0.0f, -XR_SCREEN_DISTANCE,
                    0.0f, XR_SCREEN_SIZE, false, 0.0f, true);
            QuestXr.setQuad(QuestXr.QUAD_MENU, false, QuestXr.ATTACH_GAME, 0, 0, 0, 0, 0.1f, true, 0, false);
            QuestXr.setQuad(QuestXr.QUAD_CONTROLLER, false, QuestXr.ATTACH_GAME, 0, 0, 0, 0, 0.1f, true, 0, false);
            QuestXr.setQuad(QuestXr.QUAD_DOCK, false, QuestXr.ATTACH_GAME, 0, 0, 0, 0, 0.1f, true, 0, false);
            QuestXr.setController3dVisible(false);
            QuestXr.start();
            startCore(QuestXr.getSurface(QuestXr.QUAD_GAME));
        } else {
            // Off-headset smoke test: a plain fullscreen SurfaceView, no input
            final SurfaceView view = new SurfaceView(this);
            view.getHolder().addCallback(this);
            setContentView(view);
        }
    }

    private String stringExtra(String name, String fallback)
    {
        final String value = getIntent().getStringExtra(name);
        return value != null ? value : fallback;
    }

    private void startCore(Surface surface)
    {
        final String core = LibretroFrontend.getCorePath(this);
        if (!new File(core).exists()) {
            Log.w(TAG, "Core not found at " + core + ", trying the soname");
        }
        if (!LibretroFrontend.start(surface, core, mContent, mSystemDir, mSaveDir)) {
            Log.e(TAG, "Unable to start the PS1 frontend");
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        LibretroFrontend.setPaused(true);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        LibretroFrontend.setPaused(false);
    }

    @Override
    protected void onDestroy()
    {
        // The core must stop drawing before the XR session releases the surface
        LibretroFrontend.stop();
        if (mXrMode) {
            QuestXr.destroy();
        }
        super.onDestroy();
    }

    // SurfaceHolder.Callback, 2D fallback only

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
        startCore(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
        LibretroFrontend.stop();
    }

    // QuestXr.Listener, called on the XR frame thread

    @Override
    public void onXrInput(float leftX, float leftY, float rightX, float rightY, float leftTrigger,
                          float rightTrigger, float leftGrip, float rightGrip, int buttons)
    {
        // Touch controllers to a PS1 pad: face buttons by position (A=Cross, B=Circle, X=Square,
        // Y=Triangle), triggers L1/R1, grips L2/R2, menu Start, left stick click Select,
        // left stick D-pad, right stick click R3
        int ps = 0;
        if ((buttons & QuestXr.BTN_A) != 0) ps |= LibretroFrontend.PS_CROSS;
        if ((buttons & QuestXr.BTN_B) != 0) ps |= LibretroFrontend.PS_CIRCLE;
        if ((buttons & QuestXr.BTN_X) != 0) ps |= LibretroFrontend.PS_SQUARE;
        if ((buttons & QuestXr.BTN_Y) != 0) ps |= LibretroFrontend.PS_TRIANGLE;
        if ((buttons & QuestXr.BTN_MENU) != 0) ps |= LibretroFrontend.PS_START;
        if ((buttons & QuestXr.BTN_LSTICK) != 0) ps |= LibretroFrontend.PS_SELECT;
        if ((buttons & QuestXr.BTN_RSTICK) != 0) ps |= LibretroFrontend.PS_R3;
        if (leftTrigger > TRIGGER_THRESHOLD) ps |= LibretroFrontend.PS_L1;
        if (rightTrigger > TRIGGER_THRESHOLD) ps |= LibretroFrontend.PS_R1;
        if (leftGrip > TRIGGER_THRESHOLD) ps |= LibretroFrontend.PS_L2;
        if (rightGrip > TRIGGER_THRESHOLD) ps |= LibretroFrontend.PS_R2;
        if (leftY > DPAD_THRESHOLD) ps |= LibretroFrontend.PS_UP;
        if (leftY < -DPAD_THRESHOLD) ps |= LibretroFrontend.PS_DOWN;
        if (leftX < -DPAD_THRESHOLD) ps |= LibretroFrontend.PS_LEFT;
        if (leftX > DPAD_THRESHOLD) ps |= LibretroFrontend.PS_RIGHT;

        // OpenXR y is up, libretro y is down
        LibretroFrontend.setInput(ps, toAxis(leftX), toAxis(-leftY), toAxis(rightX), toAxis(-rightY));
    }

    private static int toAxis(float value)
    {
        return Math.round(Math.max(-1.0f, Math.min(1.0f, value)) * 32767.0f);
    }

    @Override
    public void onXrSessionState(int state)
    {
        LibretroFrontend.setPaused(state != QuestXr.STATE_FOCUSED && state != QuestXr.STATE_VISIBLE);
        if (state == QuestXr.STATE_EXITING || state == QuestXr.STATE_LOSS_PENDING) {
            runOnUiThread(this::finish);
        }
    }

    @Override
    public void onXrScreenMoved(float x, float y, float z, float yaw, float width)
    {
    }

    @Override
    public void onXrPointer(int quad, float u, float v, boolean pressed)
    {
    }
}
