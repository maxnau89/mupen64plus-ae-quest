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

import paulscode.android.mupen64plusae.R;

/**
 * The bar below the game screen in VR: the game title and save slot on the left, the actions that
 * are needed most often on the right. It is pointed at with a controller, so every button is its
 * own hit area. All methods must be called on the main thread.
 */
public class QuestSpatialDock
{
    private static final String TAG = "QuestSpatialDock";

    public enum Action { SAVE, LOAD, SCREENSHOT, MENU, EXIT }

    public interface Host
    {
        void onDockAction(Action action);

        /** Save slot shown on the left */
        int getSlot();
    }

    // Design system tokens at dock scale
    private static final int PADDING = 34;
    private static final int BUTTON_SIZE = 84;
    private static final int BUTTON_GAP = 14;
    private static final int ACCENT = 0xFF00DFDF;
    private static final int TEXT_PRIMARY = 0xFFEFEFEF;
    private static final int TEXT_SECONDARY = 0xFF9C9897;
    private static final int SLAB_FILL = 0xE6121212;
    private static final int SLAB_EDGE = 0x38EFEFEF;
    private static final int BUTTON_FILL = 0xFF242424;
    private static final int BUTTON_HOVER = 0x3300DFDF;

    private final Surface mSurface;
    private final int mWidth;
    private final int mHeight;
    private final Resources mResources;
    private final Host mHost;
    private final String mTitle;
    private final Action[] mActions = Action.values();

    private int mHovered = -1;
    private boolean mVisible = false;

    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mButtonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSlotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public QuestSpatialDock(Surface surface, int width, int height, Resources resources, Host host, String title)
    {
        mSurface = surface;
        mWidth = width;
        mHeight = height;
        mResources = resources;
        mHost = host;
        mTitle = title != null ? title : "";

        mBackgroundPaint.setColor(SLAB_FILL);
        mEdgePaint.setColor(SLAB_EDGE);
        mTitlePaint.setColor(TEXT_PRIMARY);
        mTitlePaint.setTextSize(30);
        mTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSlotPaint.setColor(TEXT_SECONDARY);
        mSlotPaint.setTextSize(24);
        mLabelPaint.setColor(TEXT_PRIMARY);
        mLabelPaint.setTextSize(24);
        mLabelPaint.setTextAlign(Paint.Align.CENTER);
        mLabelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public boolean isVisible()
    {
        return mVisible;
    }

    public void setVisible(boolean visible)
    {
        if (mVisible == visible) {
            return;
        }
        mVisible = visible;
        mHovered = -1;
        if (visible) {
            draw();
        }
    }

    /** A controller is aiming at the dock, or u is negative when the ray left it. */
    public void pointAt(float u, float v)
    {
        final int button = buttonAt(u, v);
        if (button != mHovered) {
            mHovered = button;
            draw();
        }
    }

    /** The trigger was pressed while aiming at the dock. Returns false if no button was hit. */
    public boolean clickAt(float u, float v)
    {
        final int button = buttonAt(u, v);
        if (button < 0) {
            return false;
        }
        Log.i(TAG, "dock action " + mActions[button]);
        mHost.onDockAction(mActions[button]);
        return true;
    }

    /** Redraw, e.g. after the slot changed. */
    public void refresh()
    {
        if (mVisible) {
            draw();
        }
    }

    private float buttonLeft(int index)
    {
        final int count = mActions.length;
        final float right = mWidth - PADDING;
        return right - (count - index) * BUTTON_SIZE - (count - 1 - index) * BUTTON_GAP;
    }

    private int buttonAt(float u, float v)
    {
        if (u < 0.0f || v < 0.0f) {
            return -1;
        }
        final float x = u * mWidth;
        final float y = v * mHeight;
        final float top = (mHeight - BUTTON_SIZE) / 2.0f;
        if (y < top || y > top + BUTTON_SIZE) {
            return -1;
        }
        for (int i = 0; i < mActions.length; ++i) {
            final float left = buttonLeft(i);
            if (x >= left && x <= left + BUTTON_SIZE) {
                return i;
            }
        }
        return -1;
    }

    private String label(Action action)
    {
        switch (action) {
            case SAVE:
                return mResources.getString(R.string.questDock_save);
            case LOAD:
                return mResources.getString(R.string.questDock_load);
            case SCREENSHOT:
                return mResources.getString(R.string.questDock_screenshot);
            case MENU:
                return mResources.getString(R.string.questDock_menu);
            case EXIT:
            default:
                return mResources.getString(R.string.questDock_exit);
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
            Log.e(TAG, "Unable to lock dock surface", e);
            return;
        }

        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.drawRoundRect(new RectF(0, 0, mWidth, mHeight), 22, 22, mBackgroundPaint);

            final float top = (mHeight - BUTTON_SIZE) / 2.0f;
            final float titleMax = buttonLeft(0) - PADDING - 24;
            canvas.drawText(ellipsize(mTitle, mTitlePaint, titleMax), PADDING, mHeight / 2.0f - 4, mTitlePaint);
            canvas.drawText(mResources.getString(R.string.questDock_slot, mHost.getSlot()),
                    PADDING, mHeight / 2.0f + 28, mSlotPaint);

            for (int i = 0; i < mActions.length; ++i) {
                final float left = buttonLeft(i);
                final RectF rect = new RectF(left, top, left + BUTTON_SIZE, top + BUTTON_SIZE);
                mButtonPaint.setColor(i == mHovered ? BUTTON_HOVER : BUTTON_FILL);
                canvas.drawRoundRect(rect, 14, 14, mButtonPaint);
                mEdgePaint.setStyle(Paint.Style.STROKE);
                mEdgePaint.setStrokeWidth(1);
                mEdgePaint.setColor(i == mHovered ? ACCENT : SLAB_EDGE);
                canvas.drawRoundRect(rect, 14, 14, mEdgePaint);
                canvas.drawText(ellipsize(label(mActions[i]), mLabelPaint, BUTTON_SIZE - 8),
                        rect.centerX(), rect.centerY() + 9, mLabelPaint);
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
