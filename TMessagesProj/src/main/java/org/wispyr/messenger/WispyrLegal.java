package org.wispyr.messenger;

import android.net.Uri;

/**
 * Wispyr's own terms of use and privacy policy (server/web). They cover the app; Telegram's terms and
 * privacy policy keep covering the Telegram service and stay linked separately.
 */
public final class WispyrLegal {

    private WispyrLegal() {
    }

    public static String termsUrl() {
        return url("/terms");
    }

    public static String privacyUrl() {
        return url("/privacy");
    }

    // The site shows the document in this language, or in English when it has no translation.
    private static String url(String path) {
        return BuildVars.WISPYR_WEB_URL + path + "?lang=" + Uri.encode(LocaleController.getLocaleStringIso639());
    }
}
