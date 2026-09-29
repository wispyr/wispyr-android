package org.wispyr.ui.Components.Premium;

import static org.wispyr.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;

import org.wispyr.messenger.AndroidUtilities;
import org.wispyr.messenger.LiteMode;
import org.wispyr.messenger.utils.Choreographer60FpsContent;
import org.wispyr.ui.ActionBar.Theme;
import org.wispyr.ui.Cells.TextCell;
import org.wispyr.ui.Stars.StarsReactionsSheet;

public class ProfilePremiumCell extends TextCell {

    private final StarsReactionsSheet.Particles particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 15);
    private final int colorKey;

    private final Runnable invalidateRunnable = this::invalidate;

    public ProfilePremiumCell(Context context, int type, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        colorKey = type == 1 ? Theme.key_starsGradient1 : Theme.key_premiumGradient2;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        float cx = imageView.getX() + imageView.getWidth() / 2f;
        float cy = imageView.getPaddingTop() + imageView.getY() + imageView.getHeight() / 2f - dp(3);
        AndroidUtilities.rectTmp.set(
            cx - dp(16), cy - dp(16),
            cx + dp(16), cy + dp(16)
        );
        particles.setBounds(AndroidUtilities.rectTmp);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (LiteMode.isEnabled(LiteMode.FLAG_PARTICLES)) {
            particles.process();
            particles.draw(canvas, Theme.getColor(colorKey));
            Choreographer60FpsContent.getInstance().addFrameCallback(invalidateRunnable, 15);
        } else {
            Choreographer60FpsContent.getInstance().removeFrameCallback(invalidateRunnable);
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer60FpsContent.getInstance().removeFrameCallback(invalidateRunnable);
    }
}
