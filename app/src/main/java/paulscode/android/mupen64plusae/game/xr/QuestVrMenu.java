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
 * In-game menu for VR, drawn with a Canvas into the menu quad's Surface in the Quest design system
 * style. It is driven either by Touch controller navigation events or by pointing a controller at
 * it. All methods must be called on the main thread.
 * <p>
 * The regular game sidebar can't be used in VR because the activity window is not shown and
 * most of its actions open dialogs.
 */
public class QuestVrMenu
{
    private static final String TAG = "QuestVrMenu";

    // Design system tokens at menu scale (1 design unit = 1.9 px on the 1024 px wide quad)
    private static final int PADDING = 56;
    private static final int TITLE_BASELINE = 116;
    private static final int SUBTITLE_BASELINE = 162;
    private static final int DIVIDER_Y = 198;
    private static final int LIST_TOP = 226;
    private static final int ROW_HEIGHT = 88;
    private static final int ROW_RADIUS = 16;
    private static final int ACCENT = 0xFF00DFDF;
    private static final int TEXT_PRIMARY = 0xFFEFEFEF;
    private static final int TEXT_SECONDARY = 0xFF9C9897;
    private static final int TEXT_TERTIARY = 0xFF747273;
    private static final int SLAB_FILL = 0xF0121212;
    private static final int SLAB_EDGE = 0x38EFEFEF;
    private static final int ROW_SELECTED = 0x2600DFDF;
    private static final int ROW_HOVER = 0x1AFFFFFF;

    public interface Host
    {
        void onVrMenuClosed();

        void onVrMenuExitGame();

        void onVrMenuScreenshot();

        void onVrMenuResetScreen();

        /** Hide the menu and let the player move and resize the screen with the controllers. */
        void onVrMenuAdjustScreen();

        boolean isPassthroughSupported();

        boolean isPassthroughEnabled();

        void setPassthroughEnabled(boolean enabled);

        /** One of the QuestN64Overlay.MODE_ constants */
        int getControllerMode();

        void setControllerMode(int mode);
    }

    private enum Item
    {
        RESUME, SAVE, LOAD, SLOT, ADJUST_SCREEN, RESET_SCREEN, PASSTHROUGH, CONTROLLER, SPEED, FRAME_LIMITER,
        SCREENSHOT, RESET, EXIT
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
    private int mHovered = -1;
    private Item mPendingConfirm = null;
    private String mStatus = "";
    private boolean mOpen = false;

    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

        final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        mBackgroundPaint.setColor(SLAB_FILL);
        mEdgePaint.setColor(SLAB_EDGE);
        mRowPaint.setColor(ROW_SELECTED);
        mMarkerPaint.setColor(ACCENT);
        mTitlePaint.setColor(TEXT_PRIMARY);
        mTitlePaint.setTextSize(54);
        mTitlePaint.setTypeface(condensed);
        mTitlePaint.setLetterSpacing(0.06f);
        mSubtitlePaint.setColor(TEXT_SECONDARY);
        mSubtitlePaint.setTextSize(30);
        mTextPaint.setColor(TEXT_PRIMARY);
        mTextPaint.setTextSize(38);
        mTextPaint.setTypeface(medium);
        mValuePaint.setColor(ACCENT);
        mValuePaint.setTextSize(36);
        mValuePaint.setTypeface(medium);
        mValuePaint.setTextAlign(Paint.Align.RIGHT);
        mHintPaint.setColor(TEXT_TERTIARY);
        mHintPaint.setTextSize(28);
        mCreditsPaint.setColor(TEXT_TERTIARY);
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
        mHovered = -1;
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
            mHovered = -1;
            mHost.onVrMenuClosed();
        }
    }

    public void moveSelection(int direction)
    {
        mSelected = (mSelected + direction + mItems.size()) % mItems.size();
        mHovered = -1;
        mPendingConfirm = null;
        draw();
    }

    /**
     * A controller is aiming at the menu. u and v are 0..1 from the top left corner, or -1 when the
     * ray left the menu.
     */
    public void pointAt(float u, float v)
    {
        final int row = rowAt(u, v);
        if (row == mHovered) {
            return;
        }
        mHovered = row;
        if (row >= 0) {
            mSelected = row;
            mPendingConfirm = null;
        }
        draw();
    }

    /** The trigger was pressed while aiming at the menu. Returns false if the ray missed a row. */
    public boolean clickAt(float u, float v)
    {
        final int row = rowAt(u, v);
        if (row < 0) {
            return false;
        }
        mSelected = row;
        activate();
        return true;
    }

    /** The item the given menu coordinates fall on, or -1. */
    private int rowAt(float u, float v)
    {
        if (u < 0.0f || v < 0.0f) {
            return -1;
        }
        final float x = u * mWidth;
        final float y = v * mHeight;
        if (x < PADDING - 16 || x > mWidth - PADDING + 16 || y < LIST_TOP) {
            return -1;
        }
        final int row = (int) ((y - LIST_TOP) / ROW_HEIGHT);
        return row >= 0 && row < mItems.size() ? row : -1;
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
            case ADJUST_SCREEN:
                mOpen = false;
                mHost.onVrMenuAdjustScreen();
                return;
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
                return mResources.getString(R.string.questMenu_labelSave);
            case LOAD:
                return mResources.getString(R.string.questMenu_labelLoad);
            case SLOT:
                return mResources.getString(R.string.questMenu_labelSlot);
            case PASSTHROUGH:
                return mResources.getString(R.string.questMenu_labelPassthrough);
            case CONTROLLER:
                return mResources.getString(R.string.questMenu_labelController);
            case SPEED:
                return mResources.getString(R.string.questMenu_labelSpeed);
            case FRAME_LIMITER:
                return mResources.getString(R.string.questMenu_labelFrameLimiter);
            case SCREENSHOT:
                return mResources.getString(R.string.questMenu_screenshot);
            case ADJUST_SCREEN:
                return mResources.getString(R.string.questMenu_adjustScreen);
            case RESET_SCREEN:
                return mResources.getString(R.string.questMenu_resetScreen);
            case RESET:
                return mResources.getString(mPendingConfirm == item
                        ? R.string.questMenu_resetConfirm : R.string.questMenu_reset);
            case EXIT:
                return mResources.getString(mPendingConfirm == item
                        ? R.string.questMenu_exitConfirm : R.string.questMenu_exit);
            default:
                return item.name();
        }
    }

    /** The right hand side of a row, empty when the item has no value. */
    private String value(Item item)
    {
        switch (item) {
            case SAVE:
            case LOAD:
            case SLOT:
                return String.valueOf(mCoreFragment.getSlot());
            case PASSTHROUGH:
                return onOff(mHost.isPassthroughEnabled());
            case CONTROLLER:
                return controllerModeLabel(mHost.getControllerMode());
            case SPEED:
                return mCoreFragment.getCurrentSpeed() + " %";
            case FRAME_LIMITER:
                return onOff(mCoreFragment.getFramelimiter());
            default:
                return "";
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
            canvas.drawRoundRect(new RectF(0, 0, mWidth, mHeight), 28, 28, mBackgroundPaint);

            final float textRight = mWidth - PADDING;
            canvas.drawText(ellipsize(mResources.getString(R.string.questMenu_title).toUpperCase(),
                    mTitlePaint, mWidth - 2 * PADDING), PADDING, TITLE_BASELINE, mTitlePaint);
            canvas.drawText(ellipsize(mTitle, mSubtitlePaint, mWidth - 2 * PADDING),
                    PADDING, SUBTITLE_BASELINE, mSubtitlePaint);
            canvas.drawRect(PADDING, DIVIDER_Y, textRight, DIVIDER_Y + 1, mEdgePaint);

            for (int i = 0; i < mItems.size(); ++i) {
                final Item item = mItems.get(i);
                final float top = LIST_TOP + i * ROW_HEIGHT;
                if (i == mSelected) {
                    mRowPaint.setColor(ROW_SELECTED);
                    canvas.drawRoundRect(new RectF(PADDING - 16, top + 3, textRight + 16, top + ROW_HEIGHT - 3),
                            ROW_RADIUS, ROW_RADIUS, mRowPaint);
                    canvas.drawRoundRect(new RectF(PADDING - 16, top + 14, PADDING - 11, top + ROW_HEIGHT - 14),
                            3, 3, mMarkerPaint);
                } else if (i == mHovered) {
                    mRowPaint.setColor(ROW_HOVER);
                    canvas.drawRoundRect(new RectF(PADDING - 16, top + 3, textRight + 16, top + ROW_HEIGHT - 3),
                            ROW_RADIUS, ROW_RADIUS, mRowPaint);
                }

                final String valueText = value(item);
                final float valueWidth = valueText.isEmpty() ? 0 : mValuePaint.measureText(valueText) + 24;
                final float baseline = top + ROW_HEIGHT / 2.0f + 13;
                canvas.drawText(ellipsize(label(item), mTextPaint, textRight - PADDING - valueWidth),
                        PADDING + 8, baseline, mTextPaint);
                if (!valueText.isEmpty()) {
                    canvas.drawText(valueText, textRight - 8, baseline, mValuePaint);
                }
            }

            float footer = mHeight - PADDING;
            if (mHost.getControllerMode() == QuestN64Overlay.MODE_HANDS) {
                canvas.drawText(ellipsize(mResources.getString(R.string.questMenu_modelCredits), mCreditsPaint,
                        mWidth - 2 * PADDING), PADDING, mHeight - 18, mCreditsPaint);
            }
            canvas.drawText(ellipsize(mResources.getString(R.string.questMenu_hintNavigate), mHintPaint,
                    mWidth - 2 * PADDING), PADDING, footer, mHintPaint);
            if (!mStatus.isEmpty()) {
                canvas.drawText(mStatus, PADDING, footer - 42, mHintPaint);
            }
        } finally {
            mSurface.unlockCanvasAndPost(canvas);
        }
    }

    /** Draws only a hint banner at the bottom of the menu surface, used while adjusting the screen. */
    public void drawAdjustHint()
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
            final float top = mHeight - 210;
            canvas.drawRoundRect(new RectF(0, top, mWidth, mHeight), 28, 28, mBackgroundPaint);
            canvas.drawText(mResources.getString(R.string.questAdjust_title).toUpperCase(),
                    PADDING, top + 80, mTitlePaint);
            canvas.drawText(ellipsize(mResources.getString(R.string.questAdjust_hint), mHintPaint,
                    mWidth - 2 * PADDING), PADDING, top + 142, mHintPaint);
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
