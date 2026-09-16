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
 * A/B: A/B. Left trigger: Z. Right trigger or right grip: R. Left grip: L. Menu: Start.
 * X + Menu: VR menu. Y + Menu: exit the game. Left stick click: toggle passthrough.
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

        void onXrPassthroughToggleRequested();

        void onXrMenuNavigate(int direction);

        void onXrMenuAdjust(int direction);

        void onXrMenuActivate();

        void onXrMenuBack();
    }

    private static final float STICK_DEADZONE = 0.15f;
    private static final float DIRECTION_THRESHOLD = 0.5f;
    private static final float MENU_THRESHOLD = 0.6f;
    private static final float TRIGGER_THRESHOLD = 0.4f;
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 150;

    private final SessionListener mSessionListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mMenuOpen = false;
    private boolean mFocused = false;
    private int mLastButtons = 0;

    // Menu stick repeat state, per axis
    private int mVerticalDirection = 0;
    private long mVerticalNextRepeat = 0;
    private int mHorizontalDirection = 0;
    private long mHorizontalNextRepeat = 0;

    public QuestTouchController(CoreFragment coreFragment, SessionListener sessionListener)
    {
        super(coreFragment);
        mSessionListener = sessionListener;
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
        final int pressed = buttons & ~mLastButtons;
        mLastButtons = buttons;

        final boolean menu = (buttons & QuestXr.BTN_MENU) != 0;
        final boolean xHeld = (buttons & QuestXr.BTN_X) != 0;
        final boolean yHeld = (buttons & QuestXr.BTN_Y) != 0;
        final boolean menuPressed = (pressed & QuestXr.BTN_MENU) != 0;

        // Combos trigger when the second button goes down
        final boolean menuCombo = xHeld && menu && (menuPressed || (pressed & QuestXr.BTN_X) != 0);
        final boolean exitCombo = yHeld && menu && (menuPressed || (pressed & QuestXr.BTN_Y) != 0);

        if (mMenuOpen) {
            if (menuCombo) {
                post(mSessionListener::onXrMenuToggleRequested);
            } else {
                handleMenuInput(leftX, leftY, rightX, rightY, pressed);
            }
            return;
        }

        if (menuCombo) {
            releaseAll();
            post(mSessionListener::onXrMenuToggleRequested);
            return;
        }
        if (exitCombo) {
            post(mSessionListener::onXrExitRequested);
        }
        if ((pressed & QuestXr.BTN_LSTICK) != 0) {
            post(mSessionListener::onXrPassthroughToggleRequested);
        }

        final boolean[] b = mState.buttons;
        b[BTN_A] = (buttons & QuestXr.BTN_A) != 0;
        b[BTN_B] = (buttons & QuestXr.BTN_B) != 0;
        b[START] = menu && !xHeld && !yHeld;
        b[BTN_Z] = leftTrigger > TRIGGER_THRESHOLD;
        b[BTN_R] = rightTrigger > TRIGGER_THRESHOLD || rightGrip > TRIGGER_THRESHOLD;
        b[BTN_L] = leftGrip > TRIGGER_THRESHOLD;

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
    }

    private void handleMenuInput(float leftX, float leftY, float rightX, float rightY, int pressed)
    {
        final long now = SystemClock.uptimeMillis();

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

    /** Release all N64 buttons and center the stick. */
    public void releaseAll()
    {
        Arrays.fill(mState.buttons, false);
        mState.axisFractionX = 0;
        mState.axisFractionY = 0;
        notifyChanged(false);
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
