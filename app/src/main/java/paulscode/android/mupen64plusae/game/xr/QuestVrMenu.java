package paulscode.android.mupen64plusae.game.xr;

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
import java.util.Locale;

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

    public static final float MIN_SCREEN_SIZE = 0.8f;
    public static final float MAX_SCREEN_SIZE = 6.0f;
    public static final float MIN_SCREEN_DISTANCE = 0.8f;
    public static final float MAX_SCREEN_DISTANCE = 6.0f;
    private static final float SCREEN_STEP = 0.1f;

    public interface Host
    {
        void onVrMenuClosed();

        void onVrMenuExitGame();

        void onVrMenuScreenshot();

        boolean isPassthroughSupported();

        boolean isPassthroughEnabled();

        void setPassthroughEnabled(boolean enabled);

        float getScreenSize();

        float getScreenDistance();

        void setScreenGeometry(float size, float distance);
    }

    private enum Item
    {
        RESUME, SAVE, LOAD, SLOT, SCREEN_SIZE, SCREEN_DISTANCE, PASSTHROUGH, SPEED, FRAME_LIMITER,
        SCREENSHOT, RESET, EXIT
    }

    private final Surface mSurface;
    private final int mWidth;
    private final int mHeight;
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

    public QuestVrMenu(Surface surface, int width, int height, CoreFragment coreFragment, Host host, String title)
    {
        mSurface = surface;
        mWidth = width;
        mHeight = height;
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
        final Item item = mItems.get(mSelected);
        switch (item) {
            case SLOT:
                if (direction > 0) {
                    mCoreFragment.incrementSlot();
                } else {
                    mCoreFragment.decrementSlot();
                }
                break;
            case SCREEN_SIZE:
                mHost.setScreenGeometry(clamp(mHost.getScreenSize() + direction * SCREEN_STEP,
                        MIN_SCREEN_SIZE, MAX_SCREEN_SIZE), mHost.getScreenDistance());
                break;
            case SCREEN_DISTANCE:
                mHost.setScreenGeometry(mHost.getScreenSize(), clamp(mHost.getScreenDistance() + direction * SCREEN_STEP,
                        MIN_SCREEN_DISTANCE, MAX_SCREEN_DISTANCE));
                break;
            case PASSTHROUGH:
            case SPEED:
            case FRAME_LIMITER:
                activate();
                return;
            default:
                return;
        }
        draw();
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
            case SPEED:
                mCoreFragment.toggleSpeed();
                break;
            case FRAME_LIMITER:
                mCoreFragment.toggleFramelimiter();
                break;
            case SCREENSHOT:
                mHost.onVrMenuScreenshot();
                mStatus = "Screenshot gespeichert";
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

    private String label(Item item)
    {
        switch (item) {
            case RESUME:
                return "Weiterspielen";
            case SAVE:
                return String.format(Locale.GERMANY, "Speichern (Slot %d)", mCoreFragment.getSlot());
            case LOAD:
                return String.format(Locale.GERMANY, "Laden (Slot %d)", mCoreFragment.getSlot());
            case SLOT:
                return String.format(Locale.GERMANY, "Slot:  ◀ %d ▶", mCoreFragment.getSlot());
            case SCREEN_SIZE:
                return String.format(Locale.GERMANY, "Bildgröße:  ◀ %.1f m ▶", mHost.getScreenSize());
            case SCREEN_DISTANCE:
                return String.format(Locale.GERMANY, "Abstand:  ◀ %.1f m ▶", mHost.getScreenDistance());
            case PASSTHROUGH:
                return "Passthrough: " + (mHost.isPassthroughEnabled() ? "An" : "Aus");
            case SPEED:
                return String.format(Locale.GERMANY, "Geschwindigkeit: %d %%", mCoreFragment.getCurrentSpeed());
            case FRAME_LIMITER:
                return "Frame-Limiter: " + (mCoreFragment.getFramelimiter() ? "An" : "Aus");
            case SCREENSHOT:
                return "Screenshot";
            case RESET:
                return mPendingConfirm == item ? "Zurücksetzen? A zum Bestätigen" : "Spiel zurücksetzen";
            case EXIT:
                return mPendingConfirm == item ? "Beenden? A zum Bestätigen" : "Spiel beenden";
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
                canvas.drawText(label(mItems.get(i)), padding, top + rowHeight - 30, mTextPaint);
            }

            float footer = mHeight - padding;
            canvas.drawText("Stick: Auswahl   ◀▶: ändern   A: OK   B: zurück", padding, footer, mHintPaint);
            if (!mStatus.isEmpty()) {
                canvas.drawText(mStatus, padding, footer - 44, mHintPaint);
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

    private static float clamp(float value, float min, float max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
