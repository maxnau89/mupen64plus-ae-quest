package paulscode.android.mupen64plusae.game.xr;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import java.lang.ref.WeakReference;
import java.net.InetAddress;

import paulscode.android.mupen64plusae.game.GameActivity;
import paulscode.android.mupen64plusae.netplay.room.NetplayClientSetupDialog;
import paulscode.android.mupen64plusae.netplay.room.NetplayServerSetupDialog;

/**
 * Hosts the netplay room dialogs in a regular 2D window while the game runs in VR.
 * <p>
 * The dialogs only work inside a visible activity, but GameActivity is immersive on Meta Quest.
 * This activity shows them as a Horizon panel, forwards the room actions to GameActivity and
 * brings the game back to VR once netplay starts. It runs in the emulation process.
 */
public class QuestNetplayActivity extends AppCompatActivity implements
        NetplayClientSetupDialog.OnServerDialogActionListener, NetplayServerSetupDialog.OnClientDialogActionListener
{
    private static final String TAG = "QuestNetplayActivity";
    private static final String EXTRA_SERVER = "server";
    private static final String EXTRA_ROM_MD5 = "romMd5";
    private static final String EXTRA_VIDEO_PLUGIN = "videoPlugin";
    private static final String EXTRA_RSP_PLUGIN = "rspPlugin";
    private static final String EXTRA_SERVER_PORT = "serverPort";
    private static final String EXTRA_GAME_EXTRAS = "gameExtras";
    private static final String EXTRA_GAME_TASK_ID = "gameTaskId";
    private static final String STATE_DIALOG = "STATE_NETPLAY_DIALOG";

    /** Implemented by GameActivity, which owns the core and the netplay server. */
    public interface Host extends NetplayServerSetupDialog.OnClientDialogActionListener
    {
    }

    private static WeakReference<Host> sHost = new WeakReference<>(null);
    private static WeakReference<QuestNetplayActivity> sInstance = new WeakReference<>(null);

    private boolean mFinishedNetplaySetup = false;

    public static void launch(Activity game, Host host, boolean server, String romMd5, String videoPlugin,
                              String rspPlugin, int serverPort)
    {
        sHost = new WeakReference<>(host);
        final Intent intent = new Intent(game, QuestNetplayActivity.class);
        intent.putExtra(EXTRA_SERVER, server);
        intent.putExtra(EXTRA_ROM_MD5, romMd5);
        intent.putExtra(EXTRA_VIDEO_PLUGIN, videoPlugin);
        intent.putExtra(EXTRA_RSP_PLUGIN, rspPlugin);
        intent.putExtra(EXTRA_SERVER_PORT, serverPort);
        intent.putExtra(EXTRA_GAME_EXTRAS, game.getIntent().getExtras());
        intent.putExtra(EXTRA_GAME_TASK_ID, game.getTaskId());
        game.startActivity(intent);
    }

    /** Forwarded from GameActivity once UPnP port mapping finished. */
    public static void onUpnpPortsObtained(int tcpPort1, int tcpPort2, int udpPort2)
    {
        final QuestNetplayActivity activity = sInstance.get();
        if (activity == null) {
            return;
        }
        final FragmentManager fm = activity.getSupportFragmentManager();
        if (fm.findFragmentByTag(STATE_DIALOG) instanceof NetplayServerSetupDialog) {
            ((NetplayServerSetupDialog) fm.findFragmentByTag(STATE_DIALOG)).onUpnpPortsObtained(tcpPort1, tcpPort2, udpPort2);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(new FrameLayout(this));
        sInstance = new WeakReference<>(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                cancel();
            }
        });

        // Dismissing the dialog without a decision cancels netplay instead of leaving an empty window
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentDestroyed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                if (STATE_DIALOG.equals(fragment.getTag()) && !isFinishing() && !isChangingConfigurations()) {
                    cancel();
                }
            }
        }, false);

        if (savedInstanceState == null) {
            final Bundle extras = getIntent().getExtras();
            final String romMd5 = extras != null ? extras.getString(EXTRA_ROM_MD5) : null;
            final FragmentManager fm = getSupportFragmentManager();
            if (extras != null && extras.getBoolean(EXTRA_SERVER)) {
                NetplayServerSetupDialog.newInstance(romMd5, extras.getString(EXTRA_VIDEO_PLUGIN),
                        extras.getString(EXTRA_RSP_PLUGIN), extras.getInt(EXTRA_SERVER_PORT)).show(fm, STATE_DIALOG);
            } else {
                NetplayClientSetupDialog.newInstance(romMd5).show(fm, STATE_DIALOG);
            }
        }
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        if (sInstance.get() == this) {
            sInstance = new WeakReference<>(null);
        }
    }

    @Override
    public void connect(int regId, int player, String videoPlugin, String rspPlugin, InetAddress address, int port)
    {
        final Host host = sHost.get();
        if (host != null) {
            host.connect(regId, player, videoPlugin, rspPlugin, address, port);
        }
    }

    @Override
    public void start()
    {
        Log.i(TAG, "start");
        final Host host = sHost.get();
        if (host != null) {
            host.start();
        }
        mFinishedNetplaySetup = true;
        returnToGame();
        finish();
    }

    @Override
    public void cancel()
    {
        Log.i(TAG, "cancel");
        final Host host = sHost.get();
        if (host != null && !mFinishedNetplaySetup) {
            host.cancel();
        }
        mFinishedNetplaySetup = true;
        finish();
    }

    @Override
    public void mapPorts(int roomPort)
    {
        final Host host = sHost.get();
        if (host != null) {
            host.mapPorts(roomPort);
        }
    }

    private void returnToGame()
    {
        // GameActivity is still alive in its own task. Horizon starts immersive activities in a new
        // task, so starting GameActivity again would create a second instance instead of resuming it.
        final int gameTaskId = getIntent().getIntExtra(EXTRA_GAME_TASK_ID, -1);
        final ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (gameTaskId != -1 && activityManager != null) {
            try {
                activityManager.moveTaskToFront(gameTaskId, 0);
                return;
            } catch (SecurityException e) {
                Log.e(TAG, "Unable to move the game task to the front", e);
            }
        }

        final Intent intent = new Intent(this, GameActivity.class);
        final Bundle gameExtras = getIntent().getBundleExtra(EXTRA_GAME_EXTRAS);
        if (gameExtras != null) {
            intent.putExtras(gameExtras);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}
