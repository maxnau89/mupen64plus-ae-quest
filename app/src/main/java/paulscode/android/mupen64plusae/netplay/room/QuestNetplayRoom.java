package paulscode.android.mupen64plusae.netplay.room;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import paulscode.android.mupen64plusae.R;
import paulscode.android.mupen64plusae.util.DeviceUtil;

/**
 * Netplay room logic of NetplayServerSetupDialog and NetplayClientSetupDialog without the UI, for the
 * VR menu on Meta Quest. All listener callbacks run on the main thread.
 */
public class QuestNetplayRoom
{
    private static final String TAG = "QuestNetplayRoom";
    private static final String ONLINE_SERVER = "np.zurita.me";
    private static final int ONLINE_SERVER_PORT = 37520;

    public interface Listener
    {
        /** Connect the core to the netplay server, see OnClientDialogActionListener.connect */
        void onConnect(int regId, int player, String videoPlugin, String rspPlugin, InetAddress address, int port);

        /** Start the emulation, see OnClientDialogActionListener.start */
        void onStart();

        /** Players (server) or found servers (client) changed */
        void onRoomChanged();

        /** A message to show, as a string resource */
        void onMessage(int messageResId);
    }

    public static class Player
    {
        public final int number;
        public final String name;

        Player(int number, String name)
        {
            this.number = number;
            this.name = name;
        }
    }

    public static class Server
    {
        public final int netplayVersion;
        public final int id;
        public final String name;
        public final String romMd5;

        Server(int netplayVersion, int id, String name, String romMd5)
        {
            this.netplayVersion = netplayVersion;
            this.id = id;
            this.name = name;
            this.romMd5 = romMd5;
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Listener mListener;
    private final boolean mIsServer;
    private final String mRomMd5;

    private final List<Player> mPlayers = new ArrayList<>();
    private final List<Server> mServers = new ArrayList<>();
    private NetplayRoomServer mRoomServer = null;
    private NetplayRoomClient mRoomClient = null;
    private OnlineNetplayHandler mOnlineHandler = null;
    private InetAddress mAddress = null;
    private long mRoomCode = -1;
    private boolean mRequestingCode = false;
    private boolean mRegistered = false;

    private QuestNetplayRoom(Listener listener, boolean isServer, String romMd5)
    {
        mListener = listener;
        mIsServer = isServer;
        mRomMd5 = romMd5 != null ? romMd5 : "";
    }

    /** Hosts a room and connects player one, like NetplayServerSetupDialog. */
    public static QuestNetplayRoom createServer(Context context, String romMd5, String videoPlugin, String rspPlugin,
                                                int serverPort, Listener listener)
    {
        final QuestNetplayRoom room = new QuestNetplayRoom(listener, true, romMd5);
        final String deviceName = DeviceUtil.getDeviceName(context, context.getContentResolver());
        room.mAddress = DeviceUtil.getIPAddress();
        room.mPlayers.add(new Player(1, deviceName));

        room.mRoomServer = new NetplayRoomServer(context.getApplicationContext(), deviceName, romMd5, videoPlugin,
                rspPlugin, serverPort, new NetplayRoomServer.OnClientFound() {
            @Override
            public void onClientRegistration(int playerNumber, String name) {
                room.post(() -> {
                    room.mPlayers.add(new Player(playerNumber, name));
                    listener.onRoomChanged();
                });
            }

            @Override
            public void onClienLeave(int playerNumber) {
                room.post(() -> {
                    final ListIterator<Player> iterator = room.mPlayers.listIterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().number == playerNumber) {
                            iterator.remove();
                        }
                    }
                    listener.onRoomChanged();
                });
            }

            @Override
            public void onRoomCode(long roomCode) {
                room.post(() -> {
                    room.mRoomCode = roomCode;
                    room.mRequestingCode = false;
                    listener.onRoomChanged();
                });
            }
        });

        final int registrationId = room.mRoomServer.registerPlayerOne();
        InetAddress connectAddress = room.mAddress;
        if (connectAddress == null) {
            try {
                connectAddress = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            } catch (UnknownHostException e) {
                Log.e(TAG, "No local address", e);
            }
        }
        if (connectAddress != null) {
            final InetAddress address = connectAddress;
            room.post(() -> listener.onConnect(registrationId, 1, videoPlugin, rspPlugin, address, serverPort));
        }
        return room;
    }

    /** Searches for rooms on the local network, like NetplayClientSetupDialog. */
    public static QuestNetplayRoom createClient(Context context, String romMd5, Listener listener)
    {
        final QuestNetplayRoom room = new QuestNetplayRoom(listener, false, romMd5);
        final String deviceName = DeviceUtil.getDeviceName(context, context.getContentResolver());
        room.mAddress = DeviceUtil.getIPAddress();

        room.mRoomClient = new NetplayRoomClient(context, deviceName, new NetplayRoomClient.OnServerFound() {
            @Override
            public void onValidServerFound(int netplayVersion, int serverId, String serverName, String serverRomMd5) {
                room.post(() -> {
                    room.mServers.add(new Server(netplayVersion, serverId, serverName, serverRomMd5));
                    listener.onRoomChanged();
                });
            }

            @Override
            public void onServerRegistration(int regId, int player, String videoPlugin, String rspPlugin,
                                             InetAddress address, int port) {
                room.post(() -> {
                    room.mRegistered = true;
                    listener.onConnect(regId, player, videoPlugin, rspPlugin, address, port);
                    listener.onRoomChanged();
                });
            }

            @Override
            public void onServerStart() {
                if (room.mOnlineHandler != null) {
                    room.mOnlineHandler.notifyGameStartedAsync();
                }
                room.post(listener::onStart);
            }
        });
        return room;
    }

    public boolean isServer()
    {
        return mIsServer;
    }

    /** This device's address on the local network, or null */
    public InetAddress getAddress()
    {
        return mAddress;
    }

    // ------------------------------------------------------------------------------------------
    // Server

    public List<Player> getPlayers()
    {
        return mPlayers;
    }

    /** Room code for online play, -1 if none yet */
    public long getRoomCode()
    {
        return mRoomCode;
    }

    public boolean isRequestingCode()
    {
        return mRequestingCode;
    }

    /** Port of the room server, to be mapped with UPnP before requesting a code */
    public int getRoomPort()
    {
        return mRoomServer != null ? mRoomServer.getServerPort() : -1;
    }

    /** Call after starting the UPnP port mapping; the code arrives through onRoomChanged. */
    public void onCodeRequested()
    {
        mRequestingCode = true;
        mListener.onRoomChanged();
        // Same timeout as the dialog
        mHandler.postDelayed(() -> {
            if (mRequestingCode && mRoomCode == -1) {
                mRequestingCode = false;
                mListener.onMessage(R.string.netplay_codeRetrieveFailure);
                mListener.onRoomChanged();
            }
        }, 20000);
    }

    /** Forwarded UPnP result, requests an online room code. */
    public void onUpnpPortsObtained(int tcpPort1, int tcpPort2, int udpPort2)
    {
        if (!mIsServer) {
            return;
        }
        if (tcpPort1 == -1) {
            mRequestingCode = false;
            mListener.onMessage(R.string.netplay_codeRetrieveFailure);
            mListener.onRoomChanged();
            return;
        }
        if (mOnlineHandler == null) {
            final Thread thread = new Thread(() -> {
                try {
                    mOnlineHandler = new OnlineNetplayHandler(InetAddress.getByName(ONLINE_SERVER), ONLINE_SERVER_PORT,
                            tcpPort1, -1, new OnlineNetplayHandler.OnOnlineNetplayData() {
                        @Override
                        public void onInitSessionResponse(boolean success) {
                            if (!success) {
                                post(() -> mListener.onMessage(R.string.netplay_serverVersionMismatch));
                            }
                        }

                        @Override
                        public void onRoomData(InetAddress address, int port) {
                            // Nothing to do for the server
                        }
                    });
                    mOnlineHandler.connectAndRequestCode();
                } catch (UnknownHostException e) {
                    Log.e(TAG, "Online netplay server not found", e);
                }
            });
            thread.setDaemon(true);
            thread.start();
            mRoomServer.updateServerPort(tcpPort2);
        }
    }

    /** Server only: tell the clients to start, then start locally through onStart. */
    public void startGame()
    {
        if (mRoomServer == null) {
            return;
        }
        mRoomServer.start();
        if (mOnlineHandler != null) {
            mOnlineHandler.notifyGameStartedAsync();
        }
        mListener.onStart();
    }

    // ------------------------------------------------------------------------------------------
    // Client

    public List<Server> getServers()
    {
        return mServers;
    }

    /** Client: joined a room and waiting for the host to start */
    public boolean isRegistered()
    {
        return mRegistered;
    }

    public void join(Server server)
    {
        if (server.netplayVersion != NetplayRoomClientHandler.NETPLAY_VERSION) {
            mListener.onMessage(R.string.netplay_serverVersionMismatch);
        } else if (!server.romMd5.equals(mRomMd5)) {
            mListener.onMessage(R.string.netplay_romMd5Mismatch);
        } else if (mRoomClient != null) {
            mRoomClient.registerServer(server.id);
        }
    }

    public void joinAddress(String hostname, int port)
    {
        if (mRoomClient != null) {
            mRoomClient.connectToServer(hostname, port);
        }
    }

    public void joinCode(long code)
    {
        final Thread thread = new Thread(() -> {
            try {
                if (mOnlineHandler != null) {
                    mOnlineHandler.disconnect();
                }
                mOnlineHandler = new OnlineNetplayHandler(InetAddress.getByName(ONLINE_SERVER), ONLINE_SERVER_PORT,
                        -1, code, new OnlineNetplayHandler.OnOnlineNetplayData() {
                    @Override
                    public void onInitSessionResponse(boolean success) {
                        if (!success) {
                            post(() -> mListener.onMessage(R.string.netplay_serverVersionMismatch));
                        }
                    }

                    @Override
                    public void onRoomData(InetAddress address, int port) {
                        if (port == -1) {
                            post(() -> mListener.onMessage(R.string.netplay_codeNotFound));
                        } else if (mRoomClient != null) {
                            mRoomClient.connectToServer(address.getHostName(), port);
                        }
                        mOnlineHandler.disconnect();
                    }
                });
                mOnlineHandler.connectAndGetDataFromCode();
            } catch (UnknownHostException e) {
                Log.e(TAG, "Online netplay server not found", e);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------------------------------

    /** Leave the room when netplay is cancelled. */
    public void close()
    {
        mHandler.removeCallbacksAndMessages(null);
        if (mRoomClient != null) {
            mRoomClient.leaveServer();
            mRoomClient.stopListening();
        }
        if (mRoomServer != null) {
            mRoomServer.stopServer();
        }
        if (mOnlineHandler != null) {
            mOnlineHandler.disconnect();
        }
    }

    private void post(Runnable runnable)
    {
        mHandler.post(runnable);
    }
}
