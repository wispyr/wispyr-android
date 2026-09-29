package org.wispyr.ui.Stories.recorder;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.wispyr.messenger.voip.NativeInstance;
import org.wispyr.messenger.voip.VideoCapturerDevice;
import org.wispyr.messenger.voip.VoIPService;
import org.wispyr.ui.ActionBar.BaseFragment;
import org.wispyr.ui.Components.LayoutHelper;
import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.TextureViewRenderer;

public class TestCameraFragment extends BaseFragment {

    @Override
    public View createView(Context context) {

        fragmentView = new FrameLayout(context);

        TextureViewRenderer tv = new TextureViewRenderer(context);
        tv.setOpaque(false);
        tv.setEnableHardwareScaler(true);
        tv.setIsCamera(true);
        tv.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);

        VoIPService.ProxyVideoSink sink = new VoIPService.ProxyVideoSink();
        long captureDevice = NativeInstance.createVideoCapturer(sink, 0);

        tv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View view) {
                if (tv != null) {
                    tv.init(VideoCapturerDevice.getEglBase().getEglBaseContext(), new RendererCommon.RendererEvents() {
                        @Override
                        public void onFirstFrameRendered() {

                        }

                        @Override
                        public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {

                        }
                    });
                }
                sink.setTarget(tv);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View view) {
                sink.setTarget(null);
            }
        });

        ((FrameLayout) fragmentView).addView(tv);

        return fragmentView;
    }
}
