package org.wispyr.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.wispyr.ui.ActionBar.BaseFragment;
import org.wispyr.ui.Components.SizeNotifierFrameLayout;

public class EmptyBaseFragment extends BaseFragment {

    @Override
    public View createView(Context context) {
        return fragmentView = new SizeNotifierFrameLayout(context);
    }

}
