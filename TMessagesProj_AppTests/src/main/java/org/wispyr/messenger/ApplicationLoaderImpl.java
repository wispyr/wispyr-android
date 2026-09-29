package org.wispyr.messenger;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected boolean isAndroidTestEnv() {
        return true;
    }
}
