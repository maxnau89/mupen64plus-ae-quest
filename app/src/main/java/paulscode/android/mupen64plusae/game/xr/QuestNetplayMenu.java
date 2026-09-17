package paulscode.android.mupen64plusae.game.xr;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Surface;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import paulscode.android.mupen64plusae.R;
import paulscode.android.mupen64plusae.netplay.room.QuestNetplayRoom;

/**
 * Netplay room setup inside the VR menu quad, replacing the netplay dialogs that are invisible in VR.
 * Pages and their items are kept separate from drawing. All methods must be called on the main thread.
 */
public class QuestNetplayMenu implements QuestNetplayRoom.Listener
{
    private static final String TAG = "QuestNetplayMenu";
    private static final int CODE_DIGITS = 10;

    /** Implemented by GameActivity */
    public interface Host
    {
        void connect(int regId, int player, String videoPlugin, String rspPlugin, InetAddress address, int port);

        void start();

        void cancel();

        void mapPorts(int roomPort);

        /** The netplay menu was closed because the game starts or netplay was cancelled */
        void onNetplayMenuClosed();

        int getNetplayRoomPort();
    }

    private enum Page { SERVER, CLIENT_LIST, CLIENT_CODE, CLIENT_ADDRESS, CLIENT_WAITING }

    private enum Action { START, GET_CODE, JOIN_SERVER, ENTER_CODE, ENTER_ADDRESS, CANCEL }

    private static class Item
    {
        final Action action;
        final String label;
        final QuestNetplayRoom.Server server;

        Item(Action action, String label, QuestNetplayRoom.Server server)
        {
            this.action = action;
            this.label = label;
            this.server = server;
        }
    }

    private final Surface mSurface;
    private final int mWidth;
    private final int mHeight;
    private final Resources mResources;
    private final Host mHost;
    private QuestNetplayRoom mRoom = null;

    private Page mPage = Page.SERVER;
    private final List<Item> mItems = new ArrayList<>();
    private int mSelected = 0;
    private boolean mConfirmCancel = false;
    private String mMessage = "";
    private boolean mOpen = false;

    // Digit editor for the room code and the address
    private final StringBuilder mEditText = new StringBuilder();
    private int mEditCursor = 0;

    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEditPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public QuestNetplayMenu(Surface surface, int width, int height, Resources resources, Host host)
    {
        mSurface = surface;
        mWidth = width;
        mHeight = height;
        mResources = resources;
        mHost = host;

        mBackgroundPaint.setColor(Color.argb(235, 20, 22, 28));
        mHighlightPaint.setColor(Color.argb(255, 60, 110, 200));
        mTitlePaint.setColor(Color.WHITE);
        mTitlePaint.setTextSize(52);
        mTitlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(42);
        mHintPaint.setColor(Color.argb(255, 170, 175, 185));
        mHintPaint.setTextSize(30);
        mEditPaint.setColor(Color.WHITE);
        mEditPaint.setTextSize(64);
        mEditPaint.setTypeface(Typeface.MONOSPACE);
    }

    public boolean isOpen()
    {
        return mOpen;
    }

    public void openServer(Context context, String romMd5, String videoPlugin, String rspPlugin, int serverPort)
    {
        mRoom = QuestNetplayRoom.createServer(context, romMd5, videoPlugin, rspPlugin, serverPort, this);
        mOpen = true;
        showPage(Page.SERVER);
    }

    public void openClient(Context context, String romMd5)
    {
        mRoom = QuestNetplayRoom.createClient(context, romMd5, this);
        mOpen = true;
        showPage(Page.CLIENT_LIST);
    }

    public void onUpnpPortsObtained(int tcpPort1, int tcpPort2, int udpPort2)
    {
        if (mRoom != null) {
            mRoom.onUpnpPortsObtained(tcpPort1, tcpPort2, udpPort2);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Room callbacks

    @Override
    public void onConnect(int regId, int player, String videoPlugin, String rspPlugin, InetAddress address, int port)
    {
        mHost.connect(regId, player, videoPlugin, rspPlugin, address, port);
        if (mRoom != null && !mRoom.isServer()) {
            showPage(Page.CLIENT_WAITING);
        }
    }

    @Override
    public void onStart()
    {
        Log.i(TAG, "Netplay starts");
        mOpen = false;
        mHost.start();
        mHost.onNetplayMenuClosed();
    }

    @Override
    public void onRoomChanged()
    {
        if (mOpen && (mPage == Page.SERVER || mPage == Page.CLIENT_LIST)) {
            rebuildItems();
        }
        draw();
    }

    @Override
    public void onMessage(int messageResId)
    {
        mMessage = mResources.getString(messageResId);
        draw();
    }

    // ------------------------------------------------------------------------------------------
    // Navigation

    public void moveSelection(int direction)
    {
        if (isEditing()) {
            // Stick up increases the digit under the cursor
            changeDigit(-direction);
        } else if (!mItems.isEmpty()) {
            mSelected = (mSelected + direction + mItems.size()) % mItems.size();
            mConfirmCancel = false;
        }
        draw();
    }

    public void adjust(int direction)
    {
        if (isEditing()) {
            moveCursor(direction);
            draw();
        }
    }

    public void activate()
    {
        if (isEditing()) {
            submitEdit();
            return;
        }
        if (mItems.isEmpty()) {
            return;
        }
        final Item item = mItems.get(mSelected);
        switch (item.action) {
            case START:
                mRoom.startGame();
                return;
            case GET_CODE:
                mHost.mapPorts(mRoom.getRoomPort());
                mRoom.onCodeRequested();
                return;
            case JOIN_SERVER:
                mMessage = "";
                mRoom.join(item.server);
                break;
            case ENTER_CODE:
                startEditing(Page.CLIENT_CODE, repeat('0', CODE_DIGITS));
                return;
            case ENTER_ADDRESS:
                startEditing(Page.CLIENT_ADDRESS, addressTemplate());
                return;
            case CANCEL:
                if (!mConfirmCancel) {
                    mConfirmCancel = true;
                    break;
                }
                cancelNetplay();
                return;
            default:
                break;
        }
        draw();
    }

    /** B: leave an editor, otherwise ask to cancel netplay */
    public void back()
    {
        if (isEditing()) {
            showPage(Page.CLIENT_LIST);
            return;
        }
        for (int i = 0; i < mItems.size(); ++i) {
            if (mItems.get(i).action == Action.CANCEL) {
                if (mSelected == i && mConfirmCancel) {
                    cancelNetplay();
                    return;
                }
                mSelected = i;
                mConfirmCancel = true;
            }
        }
        draw();
    }

    private void cancelNetplay()
    {
        Log.i(TAG, "Netplay cancelled");
        mOpen = false;
        if (mRoom != null) {
            mRoom.close();
        }
        mHost.onNetplayMenuClosed();
        mHost.cancel();
    }

    // ------------------------------------------------------------------------------------------
    // Pages and items

    private void showPage(Page page)
    {
        mPage = page;
        mSelected = 0;
        mConfirmCancel = false;
        rebuildItems();
        draw();
    }

    private void rebuildItems()
    {
        final Action selectedAction = mSelected < mItems.size() ? mItems.get(mSelected).action : null;
        mItems.clear();
        switch (mPage) {
            case SERVER:
                mItems.add(new Item(Action.START, mResources.getString(R.string.questNetplay_start), null));
                if (mRoom.getRoomCode() == -1 && !mRoom.isRequestingCode()) {
                    mItems.add(new Item(Action.GET_CODE, mResources.getString(R.string.questNetplay_getCode), null));
                }
                mItems.add(new Item(Action.CANCEL, cancelLabel(), null));
                break;
            case CLIENT_LIST:
                for (QuestNetplayRoom.Server server : mRoom.getServers()) {
                    mItems.add(new Item(Action.JOIN_SERVER, server.name, server));
                }
                mItems.add(new Item(Action.ENTER_CODE, mResources.getString(R.string.questNetplay_enterCode), null));
                mItems.add(new Item(Action.ENTER_ADDRESS, mResources.getString(R.string.questNetplay_enterAddress), null));
                mItems.add(new Item(Action.CANCEL, cancelLabel(), null));
                break;
            case CLIENT_WAITING:
                mItems.add(new Item(Action.CANCEL, cancelLabel(), null));
                break;
            default:
                break;
        }
        // Keep the selection on the same action when the list changes
        for (int i = 0; i < mItems.size(); ++i) {
            if (mItems.get(i).action == selectedAction && selectedAction != Action.JOIN_SERVER) {
                mSelected = i;
                return;
            }
        }
        mSelected = Math.min(mSelected, Math.max(0, mItems.size() - 1));
    }

    private String cancelLabel()
    {
        return mResources.getString(mConfirmCancel ? R.string.questNetplay_cancelConfirm : R.string.questNetplay_cancel);
    }

    // ------------------------------------------------------------------------------------------
    // Digit editor

    private boolean isEditing()
    {
        return mPage == Page.CLIENT_CODE || mPage == Page.CLIENT_ADDRESS;
    }

    private void startEditing(Page page, String template)
    {
        mEditText.setLength(0);
        mEditText.append(template);
        mEditCursor = 0;
        mMessage = "";
        showPage(page);
    }

    /** Address template prefilled with this device's network, e.g. 192.168.001.000:43821 */
    private String addressTemplate()
    {
        final InetAddress address = mRoom.getAddress();
        final byte[] bytes = address != null ? address.getAddress() : new byte[]{(byte) 192, (byte) 168, 0, 0};
        final int b0 = bytes.length == 4 ? bytes[0] & 0xff : 192;
        final int b1 = bytes.length == 4 ? bytes[1] & 0xff : 168;
        final int b2 = bytes.length == 4 ? bytes[2] & 0xff : 0;
        return String.format(Locale.US, "%03d.%03d.%03d.%03d:%05d", b0, b1, b2, 0, mHost.getNetplayRoomPort());
    }

    private void moveCursor(int direction)
    {
        int position = mEditCursor;
        do {
            position += direction;
        } while (position >= 0 && position < mEditText.length() && !Character.isDigit(mEditText.charAt(position)));
        if (position >= 0 && position < mEditText.length()) {
            mEditCursor = position;
        }
    }

    private void changeDigit(int delta)
    {
        final int digit = mEditText.charAt(mEditCursor) - '0';
        mEditText.setCharAt(mEditCursor, (char) ('0' + (digit + delta + 10) % 10));
    }

    private void submitEdit()
    {
        final String text = mEditText.toString();
        try {
            if (mPage == Page.CLIENT_CODE) {
                mRoom.joinCode(Long.parseLong(text));
            } else {
                final String[] hostAndPort = text.split(":");
                final String[] octets = hostAndPort[0].split("\\.");
                final String hostname = String.format(Locale.US, "%d.%d.%d.%d", Integer.parseInt(octets[0]),
                        Integer.parseInt(octets[1]), Integer.parseInt(octets[2]), Integer.parseInt(octets[3]));
                mRoom.joinAddress(hostname, Integer.parseInt(hostAndPort[1]));
            }
            mMessage = mResources.getString(R.string.questNetplay_connecting);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            Log.e(TAG, "Invalid input " + text, e);
        }
        showPage(Page.CLIENT_LIST);
    }

    private static String repeat(char c, int count)
    {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; ++i) {
            builder.append(c);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Drawing

    private List<String> infoLines()
    {
        final List<String> lines = new ArrayList<>();
        final InetAddress address = mRoom.getAddress();
        switch (mPage) {
            case SERVER:
                // The room server picks its own port when UPnP is used, show the one it listens on
                lines.add(mResources.getString(R.string.questNetplay_address,
                        address != null ? address.getHostAddress() : "-", mRoom.getRoomPort()));
                if (mRoom.getRoomCode() != -1) {
                    lines.add(mResources.getString(R.string.questNetplay_code, mRoom.getRoomCode()));
                } else if (mRoom.isRequestingCode()) {
                    lines.add(mResources.getString(R.string.questNetplay_requestingCode));
                }
                lines.add(mResources.getString(R.string.questNetplay_players));
                for (QuestNetplayRoom.Player player : mRoom.getPlayers()) {
                    lines.add("   " + player.number + ": " + player.name);
                }
                break;
            case CLIENT_LIST:
                lines.add(mResources.getString(mRoom.getServers().isEmpty() ?
                        R.string.questNetplay_searching : R.string.questNetplay_selectServer));
                break;
            case CLIENT_WAITING:
                lines.add(mResources.getString(R.string.questNetplay_waiting));
                break;
            case CLIENT_CODE:
                lines.add(mResources.getString(R.string.questNetplay_enterCode));
                break;
            case CLIENT_ADDRESS:
                lines.add(mResources.getString(R.string.questNetplay_enterAddress));
                break;
        }
        return lines;
    }

    private void draw()
    {
        if (!mOpen || mSurface == null || !mSurface.isValid() || mRoom == null) {
            return;
        }
        final Canvas canvas;
        try {
            canvas = mSurface.lockHardwareCanvas();
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Unable to lock menu surface", e);
            return;
        }

        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.drawRoundRect(new RectF(0, 0, mWidth, mHeight), 36, 36, mBackgroundPaint);

            final float padding = 48;
            float y = padding + 52;
            canvas.drawText(mResources.getString(mRoom.isServer() ? R.string.questNetplay_titleHost
                    : R.string.questNetplay_titleJoin), padding, y, mTitlePaint);
            y += 70;

            for (String line : infoLines()) {
                canvas.drawText(line, padding, y, mTextPaint);
                y += 56;
            }
            y += 20;

            if (isEditing()) {
                drawEditor(canvas, padding, y + 60);
            } else {
                final float rowHeight = 74;
                for (int i = 0; i < mItems.size(); ++i) {
                    final float top = y + i * rowHeight;
                    if (i == mSelected) {
                        canvas.drawRoundRect(new RectF(padding - 16, top, mWidth - padding + 16, top + rowHeight - 8),
                                18, 18, mHighlightPaint);
                    }
                    final Item item = mItems.get(i);
                    canvas.drawText(item.action == Action.CANCEL ? cancelLabel() : item.label, padding,
                            top + rowHeight - 30, mTextPaint);
                }
            }

            final float footer = mHeight - padding;
            canvas.drawText(mResources.getString(isEditing() ? R.string.questNetplay_hintEdit
                    : R.string.questMenu_hintNavigate), padding, footer, mHintPaint);
            if (!mMessage.isEmpty()) {
                canvas.drawText(mMessage, padding, footer - 44, mHintPaint);
            }
        } finally {
            mSurface.unlockCanvasAndPost(canvas);
        }
    }

    private void drawEditor(Canvas canvas, float x, float baseline)
    {
        final float charWidth = mEditPaint.measureText("0");
        for (int i = 0; i < mEditText.length(); ++i) {
            final float left = x + i * charWidth;
            if (i == mEditCursor) {
                canvas.drawRoundRect(new RectF(left - 4, baseline - 64, left + charWidth + 4, baseline + 16), 10, 10,
                        mHighlightPaint);
            }
            canvas.drawText(String.valueOf(mEditText.charAt(i)), left, baseline, mEditPaint);
        }
    }
}
