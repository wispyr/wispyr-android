package org.wispyr.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;

import androidx.recyclerview.widget.RecyclerView;

import org.wispyr.messenger.AndroidUtilities;
import org.wispyr.messenger.MessageObject;
import org.wispyr.messenger.Utilities;
import org.wispyr.ui.Cells.ChatMessageCell;

import java.util.ArrayList;

public class ChatListPressHelper {

    private static final float PRESSED_SCALE = 0.95f;

    private final RecyclerListView listView;
    private final Utilities.Callback3Return<View, Float, Float, Boolean> canPress;
    private final Runnable onEmptyTap;
    private final Runnable invalidate;
    private final int touchSlop;

    private final ArrayList<View> pressedViews = new ArrayList<>();
    private final ArrayList<View> animatingViews = new ArrayList<>();
    private ValueAnimator animator;
    private boolean pressStarted;
    private boolean emptyTap;
    private float downX, downY;

    private final Runnable startPress = () -> {
        if (pressedViews.isEmpty()) {
            return;
        }
        pressStarted = true;
        final long duration = Math.max(100, ViewConfiguration.getLongPressTimeout() - ViewConfiguration.getTapTimeout());
        animateScale(pressedViews, PRESSED_SCALE, duration, CubicBezierInterpolator.EASE_OUT);
    };

    public ChatListPressHelper(RecyclerListView listView, Utilities.Callback3Return<View, Float, Float, Boolean> canPress, Runnable onEmptyTap, Runnable invalidate) {
        this.listView = listView;
        this.canPress = canPress;
        this.onEmptyTap = onEmptyTap;
        this.invalidate = invalidate;
        this.touchSlop = ViewConfiguration.get(listView.getContext()).getScaledTouchSlop();
    }

    public void onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                release(false);
                downX = e.getX();
                downY = e.getY();
                final View child = listView.findChildViewUnder(downX, downY);
                emptyTap = child == null;
                if (child != null && listView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && canPress.run(child, downX - child.getX(), downY - child.getY())) {
                    collectPressedViews(child);
                    AndroidUtilities.runOnUIThread(startPress, ViewConfiguration.getTapTimeout());
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(e.getX() - downX) > touchSlop || Math.abs(e.getY() - downY) > touchSlop) {
                    emptyTap = false;
                    release(false);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                release(false);
                if (emptyTap) {
                    emptyTap = false;
                    onEmptyTap.run();
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL: {
                emptyTap = false;
                release(false);
                break;
            }
        }
    }

    public void release(boolean bounce) {
        AndroidUtilities.cancelRunOnUIThread(startPress);
        if (pressStarted) {
            animateScale(pressedViews, 1f, bounce ? 320 : 150, bounce ? CubicBezierInterpolator.EASE_OUT_BACK : CubicBezierInterpolator.EASE_OUT);
        }
        pressStarted = false;
        pressedViews.clear();
    }

    private void collectPressedViews(View child) {
        pressedViews.clear();
        if (child instanceof ChatMessageCell) {
            final ChatMessageCell cell = (ChatMessageCell) child;
            final MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
            if (group != null) {
                for (int i = 0, count = listView.getChildCount(); i < count; i++) {
                    final View view = listView.getChildAt(i);
                    if (view instanceof ChatMessageCell && ((ChatMessageCell) view).getCurrentMessagesGroup() == group) {
                        pressedViews.add(view);
                    }
                }
                return;
            }
            cell.setPivotX((cell.getBackgroundDrawableLeft() + cell.getBackgroundDrawableRight()) / 2f);
            cell.setPivotY(cell.getHeight() / 2f);
        }
        pressedViews.add(child);
    }

    private void animateScale(ArrayList<View> views, float to, long duration, Interpolator interpolator) {
        if (animator != null) {
            final ValueAnimator prev = animator;
            animator = null;
            prev.cancel();
        }
        for (int i = 0; i < animatingViews.size(); i++) {
            final View view = animatingViews.get(i);
            if (!views.contains(view)) {
                setScale(view, 1f);
            }
        }
        animatingViews.clear();
        animatingViews.addAll(views);
        if (animatingViews.isEmpty()) {
            return;
        }
        final ValueAnimator a = ValueAnimator.ofFloat(animatingViews.get(0).getScaleX(), to);
        a.addUpdateListener(anim -> {
            final float scale = (float) anim.getAnimatedValue();
            for (int i = 0; i < animatingViews.size(); i++) {
                setScale(animatingViews.get(i), scale);
            }
            invalidate.run();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) {
                    animator = null;
                    if (to == 1f) {
                        animatingViews.clear();
                    }
                }
            }
        });
        a.setDuration(duration);
        a.setInterpolator(interpolator);
        animator = a;
        a.start();
    }

    private static void setScale(View view, float scale) {
        view.setScaleX(scale);
        view.setScaleY(scale);
    }
}
