package paulscode.android.mupen64plusae.game.xr;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Surface;

import java.util.Arrays;

import paulscode.android.mupen64plusae.input.AbstractController;

/**
 * Draws a slightly transparent N64 controller into the controller quad's Surface. Pressed buttons
 * glow. Must be used from a single thread.
 */
public class QuestN64Overlay
{
    private static final String TAG = "QuestN64Overlay";

    /** 3D controller between the hands */
    public static final int MODE_HANDS = 0;
    /** This 2D overlay below the game screen */
    public static final int MODE_SCREEN = 1;
    public static final int MODE_OFF = 2;
    public static final int MODE_COUNT = 3;

    // Logical drawing size, scaled to the surface
    private static final float LOGICAL_WIDTH = 768;
    private static final float LOGICAL_HEIGHT = 512;

    private static final int BODY = Color.argb(150, 70, 72, 80);
    private static final int BODY_EDGE = Color.argb(190, 150, 152, 160);
    private static final int BLUE = Color.rgb(40, 90, 230);
    private static final int GREEN = Color.rgb(40, 170, 80);
    private static final int YELLOW = Color.rgb(240, 200, 30);
    private static final int RED = Color.rgb(220, 40, 40);
    private static final int GRAY = Color.rgb(120, 122, 130);

    private final Surface mSurface;
    private final int mWidth;
    private final int mHeight;

    private final boolean[] mButtons = new boolean[AbstractController.NUM_N64_BUTTONS];
    private float mAxisX = 0;
    private float mAxisY = 0;
    private boolean mDrawn = false;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mBodyPath = new Path();

    public QuestN64Overlay(Surface surface, int width, int height)
    {
        mSurface = surface;
        mWidth = width;
        mHeight = height;

        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(4);
        mStroke.setColor(BODY_EDGE);
        mGlow.setMaskFilter(new BlurMaskFilter(22, BlurMaskFilter.Blur.NORMAL));
        mLabel.setColor(Color.WHITE);
        mLabel.setTextSize(30);
        mLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mLabel.setTextAlign(Paint.Align.CENTER);

        // Body: wide top part with three prongs
        mBodyPath.addRoundRect(new RectF(40, 110, 728, 320), 90, 90, Path.Direction.CW);
        mBodyPath.addRoundRect(new RectF(60, 200, 230, 470), 80, 80, Path.Direction.CW);
        mBodyPath.addRoundRect(new RectF(304, 220, 464, 490), 70, 70, Path.Direction.CW);
        mBodyPath.addRoundRect(new RectF(538, 200, 708, 470), 80, 80, Path.Direction.CW);
    }

    /** Redraws if the state differs from what was drawn last. */
    public void update(boolean[] buttons, float axisX, float axisY)
    {
        if (mDrawn && Arrays.equals(buttons, mButtons) && axisX == mAxisX && axisY == mAxisY) {
            return;
        }
        System.arraycopy(buttons, 0, mButtons, 0, mButtons.length);
        mAxisX = axisX;
        mAxisY = axisY;
        draw();
    }

    private void draw()
    {
        if (mSurface == null || !mSurface.isValid()) {
            return;
        }

        final Canvas canvas;
        try {
            // Software canvas: BlurMaskFilter is not supported by hardware canvases
            canvas = mSurface.lockCanvas(null);
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Unable to lock controller surface", e);
            return;
        }

        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.save();
            canvas.scale(mWidth / LOGICAL_WIDTH, mHeight / LOGICAL_HEIGHT);

            // Shoulder buttons and Z behind the body
            roundButton(canvas, new RectF(80, 70, 260, 130), GRAY, mButtons[AbstractController.BTN_L], "L");
            roundButton(canvas, new RectF(508, 70, 688, 130), GRAY, mButtons[AbstractController.BTN_R], "R");

            mFill.setColor(BODY);
            canvas.drawPath(mBodyPath, mFill);
            canvas.drawPath(mBodyPath, mStroke);

            roundButton(canvas, new RectF(334, 430, 434, 480), GRAY, mButtons[AbstractController.BTN_Z], "Z");

            // D-pad
            final float dx = 165, dy = 215, arm = 36, len = 58;
            dpadArm(canvas, new RectF(dx - arm / 2, dy - len - arm / 2, dx + arm / 2, dy - arm / 2), mButtons[AbstractController.DPD_U]);
            dpadArm(canvas, new RectF(dx - arm / 2, dy + arm / 2, dx + arm / 2, dy + len + arm / 2), mButtons[AbstractController.DPD_D]);
            dpadArm(canvas, new RectF(dx - len - arm / 2, dy - arm / 2, dx - arm / 2, dy + arm / 2), mButtons[AbstractController.DPD_L]);
            dpadArm(canvas, new RectF(dx + arm / 2, dy - arm / 2, dx + len + arm / 2, dy + arm / 2), mButtons[AbstractController.DPD_R]);
            mFill.setColor(Color.argb(220, 40, 40, 46));
            canvas.drawRect(dx - arm / 2, dy - arm / 2, dx + arm / 2, dy + arm / 2, mFill);

            // Start
            circleButton(canvas, 384, 190, 22, RED, mButtons[AbstractController.START], null);

            // Analog stick: well plus a knob that follows the stick
            mFill.setColor(Color.argb(200, 30, 30, 36));
            canvas.drawCircle(384, 300, 58, mFill);
            final boolean stickActive = mAxisX != 0 || mAxisY != 0;
            circleButton(canvas, 384 + mAxisX * 26, 300 - mAxisY * 26, 36, GRAY, stickActive, null);

            // A and B
            circleButton(canvas, 560, 275, 32, BLUE, mButtons[AbstractController.BTN_A], "A");
            circleButton(canvas, 500, 215, 32, GREEN, mButtons[AbstractController.BTN_B], "B");

            // C buttons
            final float cx = 650, cy = 205, spread = 48, radius = 24;
            circleButton(canvas, cx, cy - spread, radius, YELLOW, mButtons[AbstractController.CPD_U], null);
            circleButton(canvas, cx, cy + spread, radius, YELLOW, mButtons[AbstractController.CPD_D], null);
            circleButton(canvas, cx - spread, cy, radius, YELLOW, mButtons[AbstractController.CPD_L], null);
            circleButton(canvas, cx + spread, cy, radius, YELLOW, mButtons[AbstractController.CPD_R], null);

            canvas.restore();
            mDrawn = true;
        } finally {
            mSurface.unlockCanvasAndPost(canvas);
        }
    }

    private void circleButton(Canvas canvas, float x, float y, float radius, int color, boolean pressed, String label)
    {
        if (pressed) {
            mGlow.setColor(brighten(color));
            canvas.drawCircle(x, y, radius + 14, mGlow);
        }
        mFill.setColor(pressed ? brighten(color) : withAlpha(color, 190));
        canvas.drawCircle(x, y, radius, mFill);
        if (label != null) {
            canvas.drawText(label, x, y + 11, mLabel);
        }
    }

    private void roundButton(Canvas canvas, RectF rect, int color, boolean pressed, String label)
    {
        if (pressed) {
            mGlow.setColor(brighten(color));
            canvas.drawRoundRect(new RectF(rect.left - 12, rect.top - 12, rect.right + 12, rect.bottom + 12), 30, 30, mGlow);
        }
        mFill.setColor(pressed ? brighten(color) : withAlpha(color, 190));
        canvas.drawRoundRect(rect, 24, 24, mFill);
        canvas.drawText(label, rect.centerX(), rect.centerY() + 11, mLabel);
    }

    private void dpadArm(Canvas canvas, RectF rect, boolean pressed)
    {
        if (pressed) {
            mGlow.setColor(Color.WHITE);
            canvas.drawRect(new RectF(rect.left - 10, rect.top - 10, rect.right + 10, rect.bottom + 10), mGlow);
        }
        mFill.setColor(pressed ? Color.WHITE : Color.argb(220, 40, 40, 46));
        canvas.drawRect(rect, mFill);
    }

    private static int withAlpha(int color, int alpha)
    {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int brighten(int color)
    {
        return Color.rgb(Math.min(255, Color.red(color) + 90), Math.min(255, Color.green(color) + 90),
                Math.min(255, Color.blue(color) + 90));
    }
}
