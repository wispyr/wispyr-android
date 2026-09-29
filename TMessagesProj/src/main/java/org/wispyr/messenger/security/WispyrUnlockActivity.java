package org.wispyr.messenger.security;

import static org.wispyr.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import org.wispyr.messenger.AndroidUtilities;
import org.wispyr.messenger.ApplicationLoader;
import org.wispyr.messenger.LocaleController;
import org.wispyr.messenger.R;
import org.wispyr.messenger.SharedConfig;
import org.wispyr.ui.Components.CubicBezierInterpolator;
import org.wispyr.ui.Components.LayoutHelper;
import org.wispyr.ui.Components.MotionBackgroundDrawable;
import org.wispyr.ui.Components.PasscodeView;
import org.wispyr.ui.Components.RLottieImageView;
import org.wispyr.ui.Components.RadialProgressView;
import org.wispyr.ui.Components.voip.VoIpGradientLayout;

import java.util.Arrays;

/**
 * Standalone passcode screen shown while the data tier is locked. It is built from Telegram's call and
 * passcode components but touches only device-tier state (vault verifier, retry counters), so nothing
 * account-related starts before the passcode actually decrypts the data key. After unlocking it forwards
 * the original intent.
 *
 * For a call that rang while locked it shows the caller over Telegram's call background and asks for the
 * passcode; Telegram's own incoming-call screen (answer / decline) opens once the account is online.
 */
public class WispyrUnlockActivity extends Activity {

    public static final String EXTRA_TARGET = "wispyr_unlock_target";
    public static final String EXTRA_CALL_ID = "wispyr_call_id";
    public static final String EXTRA_CALLER_NAME = "wispyr_caller_name";
    public static final String EXTRA_CALL_DEADLINE = "wispyr_call_deadline";
    public static final String EXTRA_RELOCK = "wispyr_relock";

    private static final int AVATAR_SIZE = 88;
    private static final int LOCK_SIZE = 58;
    private static final int LOCK_CLOSED_FRAME = 37;
    private static final int LOCK_OPEN_FRAME = 71;

    private long callId;
    private boolean callEnded;
    private final Runnable endCall = this::onCallEnded;
    private final LockedPushNotifier.CallEndedListener callEndedListener = id -> {
        if (id == 0 || id == callId) {
            onCallEnded();
        }
    };

    private MotionBackgroundDrawable background;
    private CallerAvatarView avatarView;
    private RLottieImageView lockView;
    private TextView subtitleView;
    private CharSequence subtitleText;
    private FrameLayout entryView;
    private PinDotsView dotsView;
    private EditText passwordInput;
    private RadialProgressView progress;
    private final StringBuilder pinInput = new StringBuilder();
    private boolean checking;
    private boolean pin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ApplicationLoader.postInitApplication();
        final boolean relock = !WispyrVault.isLocked() && getIntent() != null && getIntent().getBooleanExtra(EXTRA_RELOCK, false);
        if (!WispyrVault.isLocked() && !relock) {
            proceed();
            return;
        }
        pin = WispyrVault.getPasscodeType() != SharedConfig.PASSCODE_TYPE_PASSWORD;
        final Intent intent = getIntent();
        final long callDeadline = intent != null ? intent.getLongExtra(EXTRA_CALL_DEADLINE, 0) : 0;
        final long ringRemaining = callDeadline - System.currentTimeMillis();
        callId = intent != null ? intent.getLongExtra(EXTRA_CALL_ID, 0) : 0;
        if (callDeadline != 0 && ringRemaining <= 0) {
            callId = 0;
        }
        final boolean incomingCall = callId != 0;
        if (incomingCall) {
            LockedPushNotifier.setCallEndedListener(callEndedListener);
            if (callDeadline != 0) {
                AndroidUtilities.runOnUIThread(endCall, ringRemaining);
            }
        }
        if (incomingCall && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        AndroidUtilities.setLightStatusBar(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        background = VoIpGradientLayout.createIncomingCallBackground();
        background.setIndeterminateAnimation(incomingCall);
        background.setParentView(root);
        root.setBackground(new LayerDrawable(new Drawable[]{background, new ColorDrawable(0x33000000)}));
        root.setFitsSystemWindows(true);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // Scrolls instead of squeezing the header when the keyboard (password mode) shrinks the window.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));

        content.addView(new Space(this), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        String callerName = intent != null ? intent.getStringExtra(EXTRA_CALLER_NAME) : null;
        if (TextUtils.isEmpty(callerName)) {
            callerName = LocaleController.getString(R.string.NotificationHiddenName);
        }
        if (incomingCall) {
            avatarView = new CallerAvatarView(this, callerName);
            content.addView(avatarView, LayoutHelper.createLinear(AVATAR_SIZE + 64, AVATAR_SIZE + 64, Gravity.CENTER_HORIZONTAL));
        } else {
            lockView = new RLottieImageView(this);
            lockView.setAnimation(R.raw.passcode_lock, LOCK_SIZE, LOCK_SIZE);
            lockView.setAutoRepeat(false);
            content.addView(lockView, LayoutHelper.createLinear(LOCK_SIZE, LOCK_SIZE, Gravity.CENTER_HORIZONTAL));
        }

        TextView titleView = new TextView(this);
        titleView.setText(incomingCall ? callerName : LocaleController.getString(R.string.WispyrUnlockTitle));
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, incomingCall ? 26 : 20);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, incomingCall ? 4 : 20, 32, 0));

        subtitleText = LocaleController.getString(incomingCall ? R.string.WispyrUnlockIncomingCall : pin ? R.string.WispyrUnlockPin : R.string.WispyrUnlockPassword);
        subtitleView = new TextView(this);
        subtitleView.setText(subtitleText);
        subtitleView.setTextColor(0xccffffff);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        // Fixed two-line slot: errors replace the hint here without moving anything.
        subtitleView.setLines(2);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 8, 32, 0));

        entryView = new FrameLayout(this);
        content.addView(entryView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, pin ? 36 : 52, Gravity.CENTER_HORIZONTAL, pin ? 0 : 40, 8, pin ? 0 : 40, 0));
        if (pin) {
            dotsView = new PinDotsView(this, SharedConfig.PASSCODE_PIN_LENGTH);
            entryView.addView(dotsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        } else {
            entryView.addView(createPasswordInput(), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        progress = new RadialProgressView(this);
        progress.setSize(dp(24));
        progress.setProgressColor(Color.WHITE);
        progress.setAlpha(0f);
        progress.setVisibility(View.INVISIBLE);
        entryView.addView(progress, LayoutHelper.createFrame(32, 32, Gravity.CENTER));

        content.addView(new Space(this), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        if (pin) {
            content.addView(createKeypad(), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 40));
        }
        setContentView(root);
        if (relock) {
            startRelock();
            return;
        }
        if (lockView != null) {
            lockView.getAnimatedDrawable().setCustomEndFrame(LOCK_CLOSED_FRAME);
            lockView.playAnimation();
        }
        if (passwordInput != null) {
            passwordInput.requestFocus();
            AndroidUtilities.showKeyboard(passwordInput);
        }
    }

    private EditText createPasswordInput() {
        GradientDrawable field = new GradientDrawable();
        field.setColor(0x26ffffff);
        field.setCornerRadius(dp(14));

        passwordInput = new EditText(this);
        passwordInput.setBackground(field);
        passwordInput.setPadding(dp(16), 0, dp(16), 0);
        passwordInput.setTextColor(Color.WHITE);
        passwordInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        passwordInput.setGravity(Gravity.CENTER);
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            submit(passwordInput.getText().toString());
            return true;
        });
        passwordInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (count > 0) {
                    restoreSubtitle();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        return passwordInput;
    }

    private void startRelock() {
        checking = true;
        subtitleView.setText(LocaleController.getString(R.string.WispyrLocking));
        setBusy(true, false);
        SharedConfig.appLocked = true;
        SharedConfig.saveConfig();
        new Thread(() -> {
            ApplicationLoader.prepareForRelock();
            // stateNotNeeded keeps this screen in the task, so the system recreates it in a fresh, locked process.
            Process.killProcess(Process.myPid());
        }, "WispyrRelock").start();
    }

    /** Telegram passcode keypad: 1-9, then [empty] 0 backspace. */
    private LinearLayout createKeypad() {
        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        int[][] rows = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}, {-1, 0, -2}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                int key = rows[r][c];
                View button;
                if (key >= 0) {
                    button = PasscodeView.PasscodeButton.createNumber(this, key);
                    button.setOnClickListener(v -> onDigit(key));
                } else if (key == -2) {
                    button = PasscodeView.PasscodeButton.createIcon(this, R.drawable.filled_clear, LocaleController.getString(R.string.AccDescrBackspace));
                    button.setOnClickListener(v -> onBackspace());
                    button.setOnLongClickListener(v -> {
                        if (checking) {
                            return false;
                        }
                        pinInput.setLength(0);
                        dotsView.setFilled(0);
                        return true;
                    });
                } else {
                    button = new View(this);
                }
                row.addView(button, LayoutHelper.createLinear(PasscodeView.BUTTON_SIZE, PasscodeView.BUTTON_SIZE,
                        c == 0 ? 0 : PasscodeView.BUTTON_X_MARGIN, 0, 0, 0));
            }
            keypad.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, r == 0 ? 0 : PasscodeView.BUTTON_Y_MARGIN, 0, 0));
        }
        return keypad;
    }

    private void onDigit(int digit) {
        if (checking || pinInput.length() >= SharedConfig.PASSCODE_PIN_LENGTH) {
            return;
        }
        restoreSubtitle();
        pinInput.append(digit);
        dotsView.setFilled(pinInput.length());
        if (callId == 0) {
            background.switchToNextPosition(true);
        }
        if (pinInput.length() == SharedConfig.PASSCODE_PIN_LENGTH) {
            String passcode = pinInput.toString();
            pinInput.setLength(0);
            submit(passcode);
        }
    }

    private void onBackspace() {
        if (checking || pinInput.length() == 0) {
            return;
        }
        pinInput.setLength(pinInput.length() - 1);
        dotsView.setFilled(pinInput.length());
        if (callId == 0) {
            background.switchToPrevPosition(true);
        }
    }

    private void restoreSubtitle() {
        if (!TextUtils.equals(subtitleView.getText(), subtitleText)) {
            subtitleView.setText(subtitleText);
        }
    }

    /** Swaps the entry (dots / field) for the spinner in the same slot, so nothing around it moves. */
    private void setBusy(boolean busy, boolean animated) {
        final View input = dotsView != null ? dotsView : passwordInput;
        input.animate().cancel();
        progress.animate().cancel();
        if (passwordInput != null) {
            passwordInput.setEnabled(!busy);
        }
        if (busy) {
            progress.setVisibility(View.VISIBLE);
        }
        if (!animated) {
            input.setAlpha(busy ? 0f : 1f);
            progress.setAlpha(busy ? 1f : 0f);
            progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        input.animate().alpha(busy ? 0f : 1f).setDuration(180).start();
        progress.animate().alpha(busy ? 1f : 0f).setDuration(180).withEndAction(() -> {
            if (progress.getAlpha() == 0f) {
                progress.setVisibility(View.INVISIBLE);
            }
        }).start();
    }

    private long retryRemainingMs() {
        if (SharedConfig.passcodeRetryInMs <= 0) {
            return 0;
        }
        long elapsed = SystemClock.elapsedRealtime() - SharedConfig.lastUptimeMillis;
        if (elapsed < 0) {
            elapsed = 0;
        }
        return Math.max(0, SharedConfig.passcodeRetryInMs - elapsed);
    }

    private String tooManyTriesText(long waitMs) {
        return LocaleController.formatString(R.string.WispyrTooManyTries, (int) Math.ceil(waitMs / 1000.0));
    }

    private void submit(String passcode) {
        if (checking) {
            return;
        }
        long wait = retryRemainingMs();
        if (wait > 0) {
            showError(tooManyTriesText(wait));
            return;
        }
        if (passcode.isEmpty() || pin && passcode.length() != SharedConfig.PASSCODE_PIN_LENGTH) {
            return;
        }
        final String verifier = WispyrVault.getPasscodeVerifier();
        if (verifier == null) {
            proceed();
            return;
        }
        checking = true;
        setBusy(true, true);
        final long pendingCallId = callId;
        PasscodeHasher.executor().execute(() -> {
            byte[] secret = PasscodeHasher.deriveSecret(passcode, verifier);
            if (secret != null) {
                // Registered before unlocking: the call may be acknowledged as soon as the network starts.
                LockedCallBridge.request(pendingCallId, LockedCallBridge.ACTION_OPEN);
            }
            boolean ok = secret != null && WispyrVault.unlock(secret);
            if (!ok) {
                LockedCallBridge.clear(pendingCallId);
            }
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
            AndroidUtilities.runOnUIThread(() -> onChecked(ok));
        });
    }

    private void onChecked(boolean ok) {
        if (ok) {
            SharedConfig.badPasscodeTries = 0;
            SharedConfig.passcodeRetryInMs = 0;
            SharedConfig.appLocked = false;
            SharedConfig.lastPauseTime = 0;
            SharedConfig.isWaitingForPasscodeEnter = false;
            SharedConfig.saveConfig();
            if (callId != 0) {
                LockedPushNotifier.cancelCall();
            }
            if (lockView != null) {
                setBusy(false, true);
                lockView.getAnimatedDrawable().setCustomEndFrame(LOCK_OPEN_FRAME);
                lockView.getAnimatedDrawable().setCurrentFrame(LOCK_CLOSED_FRAME, false);
                lockView.playAnimation();
                AndroidUtilities.runOnUIThread(this::proceed, 250);
            } else {
                proceed();
            }
            return;
        }
        checking = false;
        setBusy(false, true);
        SharedConfig.increaseBadPasscodeTries();
        if (callEnded) {
            onCallEnded();
            return;
        }
        long wait = retryRemainingMs();
        showError(wait > 0 ? tooManyTriesText(wait) : LocaleController.getString(R.string.WispyrWrongPasscode));
    }

    private void showError(String text) {
        subtitleView.setText(text);
        if (passwordInput != null) {
            passwordInput.setText("");
        }
        if (dotsView != null) {
            dotsView.setFilled(0);
        }
        AndroidUtilities.shakeViewSpring(entryView, 8);
        entryView.performHapticFeedback(HapticFeedbackConstants.REJECT);
        background.switchToNextPosition(true);
    }

    private void onCallEnded() {
        if (callId == 0 || isFinishing()) {
            return;
        }
        if (checking) {
            callEnded = true;
            return;
        }
        LockedCallBridge.clear(callId);
        LockedPushNotifier.cancelCall();
        moveTaskToBack(true);
        finish();
        overridePendingTransition(0, 0);
    }

    private void proceed() {
        if (isFinishing()) {
            return;
        }
        Intent target = getIntent() != null ? getIntent().getParcelableExtra(EXTRA_TARGET) : null;
        if (target == null) {
            target = getPackageManager().getLaunchIntentForPackage(getPackageName());
        }
        if (target != null) {
            target.addFlags(getIntent().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            try {
                startActivity(target);
            } catch (Exception ignore) {
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AndroidUtilities.cancelRunOnUIThread(endCall);
        LockedPushNotifier.removeCallEndedListener(callEndedListener);
        if (avatarView != null) {
            avatarView.stopPulse();
        }
    }

    /** Placeholder avatar with Telegram-like pulsing rings (no photo: the roster is encrypted). */
    private static final class CallerAvatarView extends View {
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String initials;
        private final ValueAnimator animator;
        private float phase;

        CallerAvatarView(Context context, String name) {
            super(context);
            initials = initials(name);
            circlePaint.setColor(0x33ffffff);
            ringPaint.setColor(Color.WHITE);
            textPaint.setColor(Color.WHITE);
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setTextAlign(Paint.Align.CENTER);
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2000);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void stopPulse() {
            animator.cancel();
        }

        private static String initials(String name) {
            StringBuilder result = new StringBuilder();
            for (String part : name.trim().split("\\s+")) {
                if (!part.isEmpty() && result.length() < 4) {
                    result.appendCodePoint(part.codePointAt(0));
                }
                if (result.codePointCount(0, result.length()) >= 2) {
                    break;
                }
            }
            return result.toString().toUpperCase();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = dp(AVATAR_SIZE) / 2f;
            for (int i = 0; i < 2; i++) {
                float p = (phase + i * 0.5f) % 1f;
                ringPaint.setAlpha((int) (60 * (1f - p)));
                canvas.drawCircle(cx, cy, radius + dp(32) * p, ringPaint);
            }
            canvas.drawCircle(cx, cy, radius, circlePaint);
            textPaint.setTextSize(radius * 0.8f);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            canvas.drawText(initials, cx, cy - (metrics.ascent + metrics.descent) / 2f, textPaint);
        }
    }

    /** Passcode dots: outlined slots that fill with a small bounce as digits are entered. */
    private static final class PinDotsView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] scale;
        private final float[] fromScale;
        private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        private int filled;

        PinDotsView(Context context, int count) {
            super(context);
            scale = new float[count];
            fromScale = new float[count];
            fillPaint.setColor(Color.WHITE);
            ringPaint.setColor(0x80ffffff);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1.5f));
            animator.setDuration(220);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK);
            animator.addUpdateListener(a -> {
                final float t = (float) a.getAnimatedValue();
                for (int i = 0; i < scale.length; i++) {
                    scale[i] = AndroidUtilities.lerp(fromScale[i], i < filled ? 1f : 0f, t);
                }
                invalidate();
            });
        }

        void setFilled(int filled) {
            this.filled = filled;
            animator.cancel();
            System.arraycopy(scale, 0, fromScale, 0, scale.length);
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float radius = dp(7);
            final float gap = dp(26);
            final float start = (getWidth() - (scale.length - 1) * gap) / 2f;
            final float cy = getHeight() / 2f;
            for (int i = 0; i < scale.length; i++) {
                final float cx = start + i * gap;
                canvas.drawCircle(cx, cy, radius - ringPaint.getStrokeWidth() / 2f, ringPaint);
                if (scale[i] > 0f) {
                    canvas.drawCircle(cx, cy, radius * scale[i], fillPaint);
                }
            }
        }
    }
}
