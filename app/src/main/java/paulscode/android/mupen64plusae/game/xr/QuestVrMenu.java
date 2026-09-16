package paulscode.android.mupen64plusae.game.xr;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import paulscode.android.mupen64plusae.R;
import paulscode.android.mupen64plusae.jni.CoreFragment;

/**
 * In-game menu for VR. It is drawn with a Canvas into the menu quad's Surface and driven by
 * Touch controller navigation events. All methods must be called on the main thread.
 * <p>
 * The regular game sidebar can't be used in VR because the activity window is not shown and
 * most of its actions open dialogs.
 */
public class QuestVrMenu
{
    private static final String TAG = "QuestVrMenu";

    public interface Host
    {
        void onVrMenuClosed();

        void onVrMenuExitGame();

        void onVrMenuScreenshot();

        void onVrMenuResetScreen();

        boolean isPassthroughSupported();

        boolean isPassthroughEnabled();

        void setPassthroughEnabled(boolean enabled);

        /** One of the QuestN64Overlay.MODE_ constants */
        int getControllerMode();

        void setControllerMode(int mode);
    }

    private enum Item
    {
        RESUME, SAVE, LOAD, SLOT, PASSTHROUGH, CONTROLLER, SPEED, FRAME_LIMITER, SCREENSHOT, RESET_SCREEN,
        RESET, EXIT
    }

    private final Surface mSurface;
    private final int mWidth;
    private final int mHeight;
    private final Resources mResources;
    private final CoreFragment mCoreFragment;
    private final Host mHost;
    private final String mTitle;

    private final List<Item> mItems = new ArrayList<>();
    private int mSelected = 0;
    private Item mPendingConfirm = null;
    private String mStatus = "";
    private boolean mOpen = false;

    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCreditsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public QuestVrMenu(Surface surface, int width, int height, Resources resources, CoreFragment coreFragment,
                       Host host, String title)
    {
        mSurface = surface;
        mWidth = width;
        mHeight = height;
        mResources = resources;
        mCoreFragment = coreFragment;
        mHost = host;
        mTitle = title != null ? title : "";

        mBackgroundPaint.setColor(Color.argb(235, 20, 22, 28));
        mHighlightPaint.setColor(Color.argb(255, 60, 110, 200));
        mTitlePaint.setColor(Color.WHITE);
        mTitlePaint.setTextSize(52);
        mTitlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(42);
        mHintPaint.setColor(Color.argb(255, 170, 175, 185));
        mHintPaint.setTextSize(30);
        mCreditsPaint.setColor(Color.argb(255, 120, 124, 132));
        mCreditsPaint.setTextSize(22);
    }

    public boolean isOpen()
    {
        return mOpen;
    }

    public void open()
    {
        mItems.clear();
        for (Item item : Item.values()) {
            if (item != Item.PASSTHROUGH || mHost.isPassthroughSupported()) {
                mItems.add(item);
            }
        }
        mSelected = 0;
        mPendingConfirm = null;
        mStatus = "";
        mOpen = true;
        draw();
    }

    /** Close the menu and notify the host. */
    public void close()
    {
        if (mOpen) {
            mOpen = false;
            mHost.onVrMenuClosed();
        }
    }

    public void moveSelection(int direction)
    {
        mSelected = (mSelected + direction + mItems.size()) % mItems.size();
        mPendingConfirm = null;
        draw();
    }

    public void adjust(int direction)
    {
        switch (mItems.get(mSelected)) {
            case SLOT:
                if (direction > 0) {
                    mCoreFragment.incrementSlot();
                } else {
                    mCoreFragment.decrementSlot();
                }
                draw();
                break;
            case CONTROLLER:
                mHost.setControllerMode((mHost.getControllerMode() + direction + QuestN64Overlay.MODE_COUNT)
                        % QuestN64Overlay.MODE_COUNT);
                draw();
                break;
            case PASSTHROUGH:
            case SPEED:
            case FRAME_LIMITER:
                activate();
                break;
            default:
                break;
        }
    }

    public void activate()
    {
        final Item item = mItems.get(mSelected);
        Log.i(TAG, "activate " + item);

        switch (item) {
            case RESUME:
                close();
                return;
            case SAVE:
                mCoreFragment.saveSlot();
                close();
                return;
            case LOAD:
                mCoreFragment.loadSlot();
                close();
                return;
            case SLOT:
                mCoreFragment.incrementSlot();
                break;
            case PASSTHROUGH:
                mHost.setPassthroughEnabled(!mHost.isPassthroughEnabled());
                break;
            case CONTROLLER:
                mHost.setControllerMode((mHost.getControllerMode() + 1) % QuestN64Overlay.MODE_COUNT);
                break;
            case SPEED:
                mCoreFragment.toggleSpeed();
                break;
            case FRAME_LIMITER:
                mCoreFragment.toggleFramelimiter();
                break;
            case SCREENSHOT:
                mHost.onVrMenuScreenshot();
                mStatus = mResources.getString(R.string.questMenu_screenshotSaved);
                break;
            case RESET_SCREEN:
                mHost.onVrMenuResetScreen();
                break;
            case RESET:
            case EXIT:
                if (mPendingConfirm != item) {
                    mPendingConfirm = item;
                    break;
                }
                mPendingConfirm = null;
                if (item == Item.RESET) {
                    mCoreFragment.restartEmulator();
                    close();
                } else {
                    mOpen = false;
                    mHost.onVrMenuExitGame();
                }
                return;
            default:
                break;
        }
        draw();
    }

    /** B button: cancel a pending confirmation, otherwise close the menu. */
    public void back()
    {
        if (mPendingConfirm != null) {
            mPendingConfirm = null;
            draw();
        } else {
            close();
        }
    }

    private String onOff(boolean on)
    {
        return mResources.getString(on ? R.string.questMenu_on : R.string.questMenu_off);
    }

    private String controllerModeLabel(int mode)
    {
        switch (mode) {
            case QuestN64Overlay.MODE_HANDS:
                return mResources.getString(R.string.questMenu_controllerHands);
            case QuestN64Overlay.MODE_SCREEN:
                return mResources.getString(R.string.questMenu_controllerScreen);
            default:
                return mResources.getString(R.string.questMenu_controllerOff);
        }
    }

    private String label(Item item)
    {
        switch (item) {
            case RESUME:
                return mResources.getString(R.string.questMenu_resume);
            case SAVE:
                return mResources.getString(R.string.questMenu_save, mCoreFragment.getSlot());
            case LOAD:
                return mResources.getString(R.string.questMenu_load, mCoreFragment.getSlot());
            case SLOT:
                return mResources.getString(R.string.questMenu_slot, mCoreFragment.getSlot());
            case PASSTHROUGH:
                return mResources.getString(R.string.questMenu_passthrough, onOff(mHost.isPassthroughEnabled()));
            case CONTROLLER:
                return mResources.getString(R.string.questMenu_controller, controllerModeLabel(mHost.getControllerMode()));
            case SPEED:
                return mResources.getString(R.string.questMenu_speed, mCoreFragment.getCurrentSpeed());
            case FRAME_LIMITER:
                return mResources.getString(R.string.questMenu_frameLimiter, onOff(mCoreFragment.getFramelimiter()));
            case SCREENSHOT:
                return mResources.getString(R.string.questMenu_screenshot);
            case RESET_SCREEN:
                return mResources.getString(R.string.questMenu_resetScreen);
            case RESET:
                return mResources.getString(mPendingConfirm == item ? R.string.questMenu_resetConfirm : R.string.questMenu_reset);
            case EXIT:
                return mResources.getString(mPendingConfirm == item ? R.string.questMenu_exitConfirm : R.string.questMenu_exit);
            default:
                return item.name();
        }
    }

    private void draw()
    {
        if (mSurface == null || !mSurface.isValid()) {
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
            canvas.drawText(ellipsize(mTitle, mTitlePaint, mWidth - 2 * padding), padding, y, mTitlePaint);
            y += 36;

            final float rowHeight = 74;
            for (int i = 0; i < mItems.size(); ++i) {
                final float top = y + i * rowHeight;
                if (i == mSelected) {
                    canvas.drawRoundRect(new RectF(padding - 16, top, mWidth - padding + 16, top + rowHeight - 8),
                            18, 18, mHighlightPaint);
                }
                canvas.drawText(ellipsize(label(mItems.get(i)), mTextPaint, mWidth - 2 * padding),
                        padding, top + rowHeight - 30, mTextPaint);
            }

            final float footer = mHeight - padding;
            canvas.drawText(mResources.getString(R.string.questMenu_hintNavigate), padding, footer, mHintPaint);
            canvas.drawText(mResources.getString(R.string.questMenu_hintGrab), padding, footer - 44, mHintPaint);
            if (!mStatus.isEmpty()) {
                canvas.drawText(mStatus, padding, footer - 88, mHintPaint);
            }
            if (mHost.getControllerMode() == QuestN64Overlay.MODE_HANDS) {
                canvas.drawText(ellipsize(mResources.getString(R.string.questMenu_modelCredits), mCreditsPaint,
                        mWidth - 2 * padding), padding, mHeight - 14, mCreditsPaint);
            }
        } finally {
            mSurface.unlockCanvasAndPost(canvas);
        }
    }

    private static String ellipsize(String text, Paint paint, float maxWidth)
    {
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        final int count = paint.breakText(text, true, maxWidth - paint.measureText("…"), null);
        return text.substring(0, count) + "…";
    }
}
