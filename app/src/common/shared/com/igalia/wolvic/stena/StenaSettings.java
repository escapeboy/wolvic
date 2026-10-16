package com.igalia.wolvic.stena;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The extension's settings (apps/extension/src/lib/storage.ts), minus telemetry: this browser sends none. */
public final class StenaSettings {
    public static final String PROFILE_DEFAULT = "auto";

    private static final String AUTO_DETECT = "autoDetect";
    private static final String INPUT_PROFILE = "inputProfile";
    private static final String OFF_SITES = "offSites";
    private static final String CUSTOM_LISTS = "customLists";

    private final SharedPreferences mPrefs;

    public static StenaSettings get(@NonNull Context context) {
        return new StenaSettings(context.getApplicationContext());
    }

    private StenaSettings(Context context) {
        mPrefs = context.getSharedPreferences("stena_settings", Context.MODE_PRIVATE);
    }

    /** Show the wall button in the address bar when the page matches a recipe or has its own wall. */
    public boolean isAutoDetect() {
        return mPrefs.getBoolean(AUTO_DETECT, true);
    }

    public void setAutoDetect(boolean on) {
        mPrefs.edit().putBoolean(AUTO_DETECT, on).apply();
    }

    /** One of the `inputProfile` values of the extension's settings, passed to the overlay. */
    public @NonNull String getInputProfile() {
        return mPrefs.getString(INPUT_PROFILE, PROFILE_DEFAULT);
    }

    public void setInputProfile(@NonNull String profile) {
        mPrefs.edit().putString(INPUT_PROFILE, profile).apply();
    }

    /** Origins the user switched Diazoma off for. */
    public @NonNull Set<String> getOffSites() {
        return new HashSet<>(mPrefs.getStringSet(OFF_SITES, new HashSet<>()));
    }

    public boolean isOff(@Nullable String origin) {
        return origin != null && getOffSites().contains(origin);
    }

    public void setOff(@NonNull String origin, boolean off) {
        Set<String> sites = getOffSites();
        if (off) {
            sites.add(origin);
        } else {
            sites.remove(origin);
        }
        mPrefs.edit().putStringSet(OFF_SITES, sites).apply();
    }

    /** Extra signed list addresses, in the order they were added. The core list is always on. */
    public @NonNull List<String> getCustomLists() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(mPrefs.getString(CUSTOM_LISTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                out.add(a.getString(i));
            }
        } catch (JSONException e) {
            // A broken value is the same as no custom lists.
        }
        return out;
    }

    void setCustomLists(@NonNull List<String> urls) {
        mPrefs.edit().putString(CUSTOM_LISTS, new JSONArray(urls).toString()).apply();
    }

    /** `https://host[:port]` of an http(s) address, as the extension's originOf(). Null for anything else. */
    public static @Nullable String originOf(@Nullable String url) {
        if (url == null) {
            return null;
        }
        android.net.Uri u = android.net.Uri.parse(url);
        String scheme = u.getScheme();
        if (u.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            return null;
        }
        int port = u.getPort();
        boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + u.getHost().toLowerCase(java.util.Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
