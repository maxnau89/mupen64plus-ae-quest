package paulscode.android.mupen64plusae.input;

import android.os.Handler;
import android.os.Looper;

import paulscode.android.mupen64plusae.game.xr.QuestXr;
import paulscode.android.mupen64plusae.jni.CoreFragment;

/**
 * Maps Meta Quest Touch controllers (read through OpenXR) to player 1's N64 controller.
 * <p>
 * Left stick: analog stick. Right stick: C buttons, or D-pad while Y is held.
 * A/B: A/B. Left trigger: Z. Right trigger or right grip: R. Left grip: L. Menu: Start.
 * Y + Menu: exit the game.
 */
public class QuestTouchController extends AbstractController implements QuestXr.Listener
{
    /** Called on the main thread. */
    public interface SessionListener
    {
        void onXrFocusChanged(boolean focused);

        void onXrExitRequested();
    }

    private static final float STICK_DEADZONE = 0.15f;
    private static final float DIRECTION_THRESHOLD = 0.5f;
    private static final float TRIGGER_THRESHOLD = 0.4f;

    private final SessionListener mSessionListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mFocused = false;
    private boolean mExitComboHeld = false;

    public QuestTouchController(CoreFragment coreFragment, SessionListener sessionListener)
    {
        super(coreFragment);
        mSessionListener = sessionListener;
    }

    @Override
    public void onXrInput(float leftX, float leftY, float rightX, float rightY,
                          float leftTrigger, float rightTrigger, float leftGrip, float rightGrip, int buttons)
    {
        final boolean[] b = mState.buttons;
        final boolean yHeld = (buttons & QuestXr.BTN_Y) != 0;
        final boolean menu = (buttons & QuestXr.BTN_MENU) != 0;

        final boolean exitCombo = yHeld && menu;
        if (exitCombo && !mExitComboHeld) {
            mMainHandler.post(mSessionListener::onXrExitRequested);
        }
        mExitComboHeld = exitCombo;

        b[BTN_A] = (buttons & QuestXr.BTN_A) != 0;
        b[BTN_B] = (buttons & QuestXr.BTN_B) != 0;
        b[START] = menu && !yHeld;
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

    @Override
    public void onXrSessionState(int state)
    {
        final boolean focused = state == QuestXr.STATE_FOCUSED;
        if (focused != mFocused) {
            mFocused = focused;
            if (!focused) {
                releaseAll();
            }
            mMainHandler.post(() -> mSessionListener.onXrFocusChanged(focused));
        }

        if (state == QuestXr.STATE_EXITING || state == QuestXr.STATE_LOSS_PENDING) {
            mMainHandler.post(mSessionListener::onXrExitRequested);
        }
    }

    private void releaseAll()
    {
        java.util.Arrays.fill(mState.buttons, false);
        mState.axisFractionX = 0;
        mState.axisFractionY = 0;
        notifyChanged(false);
    }

    private static float applyDeadzone(float value)
    {
        if (Math.abs(value) <= STICK_DEADZONE) {
            return 0;
        }
        return Math.signum(value) * (Math.abs(value) - STICK_DEADZONE) / (1.0f - STICK_DEADZONE);
    }
}
