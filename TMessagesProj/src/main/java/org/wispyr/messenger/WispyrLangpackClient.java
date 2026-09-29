package org.wispyr.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to the Wispyr language pack server (server/langpack). Packs are requested for this app
 * version; the server answers with the newest release that is not newer than the app, in the same
 * XML format LocaleController caches. All methods block and must run off the UI thread.
 */
final class WispyrLangpackClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    static final class Language {
        String code;
        String name;
        String nativeName;
        String pluralCode;
        boolean rtl;
    }

    // SHA-256 of each pack as listed by the last manifest; a CDN in front of the server may rewrite
    // ETags, so the manifest is what a download is checked against.
    private static final ConcurrentHashMap<String, String> manifestHashes = new ConcurrentHashMap<>();

    private WispyrLangpackClient() {
    }

    static boolean isEnabled() {
        return !TextUtils.isEmpty(BuildVars.LANGPACK_URL);
    }

    /** Languages offered for this app version, or null when the server could not be reached. */
    static ArrayList<Language> fetchLanguages() {
        HttpURLConnection connection = null;
        try {
            connection = open(releaseUrl());
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            JSONArray array = new JSONObject(readString(connection.getInputStream())).getJSONArray("languages");
            ArrayList<Language> languages = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Language language = new Language();
                language.code = object.getString("code");
                language.name = object.getString("name");
                language.nativeName = object.getString("nativeName");
                language.pluralCode = object.optString("pluralCode", language.code);
                language.rtl = object.optBoolean("rtl", false);
                languages.add(language);
                manifestHashes.put(language.code, object.getString("hash"));
            }
            return languages;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Replaces {@code target} with the server's pack for {@code code} unless it still has
     * {@code currentHash}. Returns the SHA-256 of the new pack when the file was replaced, null when
     * it is unchanged or the download failed. A download whose hash matches neither the manifest nor
     * the server's ETag (the server uses the hash as ETag) is never installed.
     */
    static String downloadPack(String code, String currentHash, File target) {
        HttpURLConnection connection = null;
        File temp = new File(target.getPath() + ".download");
        try {
            connection = open(releaseUrl() + "/" + code + ".xml");
            if (currentHash != null && target.exists()) {
                connection.setRequestProperty("If-None-Match", "\"" + currentHash + "\"");
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            String expected = manifestHashes.get(code);
            if (expected == null) {
                expected = hashFromEtag(connection.getHeaderField("ETag"));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(connection.getInputStream(), digest);
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            String hash = Utilities.bytesToHex(digest.digest()).toLowerCase();
            if (!hash.equals(expected)) {
                FileLog.e("langpack " + code + ": content hash does not match the server's");
                return null;
            }
            if (!temp.renameTo(target)) {
                return null;
            }
            return hash;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            temp.delete();
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String hashFromEtag(String etag) {
        if (etag == null) {
            return null;
        }
        if (etag.startsWith("W/")) {
            etag = etag.substring(2);
        }
        return etag.replace("\"", "").toLowerCase();
    }

    private static String releaseUrl() {
        return BuildVars.LANGPACK_URL + "/v1/langpacks/" + BuildVars.BUILD_VERSION_STRING;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "Wispyr-Android/" + BuildVars.BUILD_VERSION_STRING);
        return connection;
    }

    private static String readString(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
