package paulscode.android.mupen64plusae.input;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Arrays;

import paulscode.android.mupen64plusae.game.xr.QuestXr;
import paulscode.android.mupen64plusae.jni.CoreFragment;

/**
 * Maps Meta Quest Touch controllers (read through OpenXR) to player 1's N64 controller.
 * <p>
 * Left stick: analog stick. Right stick: C buttons, or D-pad while Y is held.
 * A/B: A/B. Left trigger: L. Left grip: Z. Right trigger or right grip: R.
 * Menu: Start when tapped, VR menu when held.
 * <p>
 * While the VR menu is open, input navigates the menu instead of reaching the game.
 */
public class QuestTouchController extends AbstractController implements QuestXr.Listener
{
    /** Called on the main thread. */
    public interface SessionListener
    {
        void onXrFocusChanged(boolean focused);

        void onXrExitRequested();

        void onXrMenuToggleRequested();

        void onXrMenuNavigate(int direction);

        void onXrMenuAdjust(int direction);

        void onXrMenuActivate();

        void onXrMenuBack();

        /** A, B or a Menu tap while adjusting the screen. */
        void onXrAdjustFinished();

        void onXrScreenMoved(float x, float y, float z, float yaw, float width);

        /** The N64 controller state changed, arrays are copies. */
        void onN64StateChanged(boolean[] buttons, float axisX, float axisY);
    }

    private static final float STICK_DEADZONE = 0.15f;
    private static final float DIRECTION_THRESHOLD = 0.5f;
    private static final float MENU_THRESHOLD = 0.6f;
    private static final float TRIGGER_THRESHOLD = 0.4f;
    private static final long MENU_HOLD_MS = 600;
    private static final long START_PULSE_MS = 120;
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 150;

    private final SessionListener mSessionListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mMenuOpen = false;
    private volatile boolean mAdjusting = false;
    private boolean mFocused = false;
    private int mLastButtons = 0;

    // Menu button: tap = Start pulse, hold = VR menu
    private long mMenuDownTime = 0;
    private boolean mMenuHoldConsumed = false;
    private long mStartPulseUntil = 0;

    // Menu stick repeat state, per axis
    private int mVerticalDirection = 0;
    private long mVerticalNextRepeat = 0;
    private int mHorizontalDirection = 0;
    private long mHorizontalNextRepeat = 0;

    // Last state sent to the overlay
    private final boolean[] mReportedButtons = new boolean[NUM_N64_BUTTONS];
    private float mReportedX = 0;
    private float mReportedY = 0;

    public QuestTouchController(CoreFragment coreFragment, SessionListener sessionListener)
    {
        super(coreFragment);
        mSessionListener = sessionListener;
    }

    /** While adjusting the screen, input only ends the adjust mode. May be called from any thread. */
    public void setAdjusting(boolean adjusting)
    {
        mAdjusting = adjusting;
    }

    /** Route input to the menu instead of the game. May be called from any thread. */
    public void setMenuOpen(boolean open)
    {
        mMenuOpen = open;
    }

    @Override
    public void onXrInput(float leftX, float leftY, float rightX, float rightY,
                          float leftTrigger, float rightTrigger, float leftGrip, float rightGrip, int buttons)
    {
        final long now = SystemClock.uptimeMillis();
        final int pressed = buttons & ~mLastButtons;
        final int released = ~buttons & mLastButtons;
        mLastButtons = buttons;

        boolean menuTapped = false;
        if ((pressed & QuestXr.BTN_MENU) != 0) {
            mMenuDownTime = now;
            mMenuHoldConsumed = false;
        } else if ((buttons & QuestXr.BTN_MENU) != 0 && !mMenuHoldConsumed && now - mMenuDownTime >= MENU_HOLD_MS) {
            mMenuHoldConsumed = true;
            releaseAll();
            post(mSessionListener::onXrMenuToggleRequested);
            return;
        } else if ((released & QuestXr.BTN_MENU) != 0 && !mMenuHoldConsumed) {
            menuTapped = true;
        }

        if (mAdjusting) {
            if (menuTapped || (pressed & (QuestXr.BTN_A | QuestXr.BTN_B)) != 0) {
                post(mSessionListener::onXrAdjustFinished);
            }
            return;
        }

        if (mMenuOpen) {
            if (menuTapped) {
                post(mSessionListener::onXrMenuToggleRequested);
            } else {
                handleMenuInput(leftX, leftY, rightX, rightY, pressed, now);
            }
            return;
        }

        if (menuTapped) {
            mStartPulseUntil = now + START_PULSE_MS;
        }

        final boolean yHeld = (buttons & QuestXr.BTN_Y) != 0;
        final boolean[] b = mState.buttons;
        b[BTN_A] = (buttons & QuestXr.BTN_A) != 0;
        b[BTN_B] = (buttons & QuestXr.BTN_B) != 0;
        b[START] = now < mStartPulseUntil;
        b[BTN_L] = leftTrigger > TRIGGER_THRESHOLD;
        b[BTN_Z] = leftGrip > TRIGGER_THRESHOLD;
        b[BTN_R] = rightTrigger > TRIGGER_THRESHOLD || rightGrip > TRIGGER_THRESHOLD;

        final boolean right = rightX > DIRECTION_THRESHOLD;
        final boolean left = rightX < -DIRECTION_THRESHOLD;
        final boolean up = rightY > DIRECTION_THRESHOLD;
        final boolean down = rightY < -DIRECTION_THRESHOLD;

        b[CPD_R] = !yHeld && right;
        b[CPD_L] = !yHeld && left;
        b[CPD_U] = !yHeld && up;
        b[CPD_D] = !yHeld && down;
        b[DPD_R] = yHeld && right;
        b[DPD_L] = yHeld && left;
        b[DPD_U] = yHeld && up;
        b[DPD_D] = yHeld && down;

        mState.axisFractionX = applyDeadzone(leftX);
        mState.axisFractionY = applyDeadzone(leftY);

        notifyChanged(false);
        reportState();
    }

    private void handleMenuInput(float leftX, float leftY, float rightX, float rightY, int pressed, long now)
    {
        // Either stick navigates; stick up means previous item
        final float vertical = Math.abs(leftY) > Math.abs(rightY) ? leftY : rightY;
        final float horizontal = Math.abs(leftX) > Math.abs(rightX) ? leftX : rightX;

        final int verticalDirection = vertical > MENU_THRESHOLD ? -1 : vertical < -MENU_THRESHOLD ? 1 : 0;
        if (verticalDirection != mVerticalDirection) {
            mVerticalDirection = verticalDirection;
            mVerticalNextRepeat = now + REPEAT_DELAY_MS;
            if (verticalDirection != 0) {
                post(() -> mSessionListener.onXrMenuNavigate(verticalDirection));
            }
        } else if (verticalDirection != 0 && now >= mVerticalNextRepeat) {
            mVerticalNextRepeat = now + REPEAT_INTERVAL_MS;
            post(() -> mSessionListener.onXrMenuNavigate(verticalDirection));
        }

        final int horizontalDirection = horizontal > MENU_THRESHOLD ? 1 : horizontal < -MENU_THRESHOLD ? -1 : 0;
        if (horizontalDirection != mHorizontalDirection) {
            mHorizontalDirection = horizontalDirection;
            mHorizontalNextRepeat = now + REPEAT_DELAY_MS;
            if (horizontalDirection != 0) {
                post(() -> mSessionListener.onXrMenuAdjust(horizontalDirection));
            }
        } else if (horizontalDirection != 0 && now >= mHorizontalNextRepeat) {
            mHorizontalNextRepeat = now + REPEAT_INTERVAL_MS;
            post(() -> mSessionListener.onXrMenuAdjust(horizontalDirection));
        }

        if ((pressed & QuestXr.BTN_A) != 0) {
            post(mSessionListener::onXrMenuActivate);
        }
        if ((pressed & QuestXr.BTN_B) != 0) {
            post(mSessionListener::onXrMenuBack);
        }
    }

    @Override
    public void onXrSessionState(int state)
    {
        final boolean focused = state == QuestXr.STATE_FOCUSED;
        if (focused != mFocused) {
            mFocused = focused;
            if (!focused) {
                mLastButtons = 0;
                releaseAll();
            }
            post(() -> mSessionListener.onXrFocusChanged(focused));
        }

        if (state == QuestXr.STATE_EXITING || state == QuestXr.STATE_LOSS_PENDING) {
            post(mSessionListener::onXrExitRequested);
        }
    }

    @Override
    public void onXrScreenMoved(float x, float y, float z, float yaw, float width)
    {
        post(() -> mSessionListener.onXrScreenMoved(x, y, z, yaw, width));
    }

    /** Release all N64 buttons and center the stick. */
    public void releaseAll()
    {
        Arrays.fill(mState.buttons, false);
        mState.axisFractionX = 0;
        mState.axisFractionY = 0;
        mStartPulseUntil = 0;
        notifyChanged(false);
        reportState();
    }

    private void reportState()
    {
        if (Arrays.equals(mState.buttons, mReportedButtons) && mState.axisFractionX == mReportedX
                && mState.axisFractionY == mReportedY) {
            return;
        }
        System.arraycopy(mState.buttons, 0, mReportedButtons, 0, NUM_N64_BUTTONS);
        mReportedX = mState.axisFractionX;
        mReportedY = mState.axisFractionY;

        int mask = 0;
        for (int i = 0; i < NUM_N64_BUTTONS; ++i) {
            if (mReportedButtons[i]) {
                mask |= 1 << i;
            }
        }
        QuestXr.setN64State(mask, mReportedX, mReportedY);

        final boolean[] buttons = mReportedButtons.clone();
        final float x = mReportedX;
        final float y = mReportedY;
        post(() -> mSessionListener.onN64StateChanged(buttons, x, y));
    }

    private void post(Runnable runnable)
    {
        mMainHandler.post(runnable);
    }

    private static float applyDeadzone(float value)
    {
        if (Math.abs(value) <= STICK_DEADZONE) {
            return 0;
        }
        return Math.signum(value) * (Math.abs(value) - STICK_DEADZONE) / (1.0f - STICK_DEADZONE);
    }
}
