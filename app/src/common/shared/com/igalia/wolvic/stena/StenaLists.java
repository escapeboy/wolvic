package com.igalia.wolvic.stena;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.igalia.wolvic.R;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The signed lists, as the extension keeps them (apps/extension/src/lib/lists.ts and update.ts): the core list from
 * diazoma.app plus the custom lists in the settings, each verified over the exact bytes against a pinned key and never
 * rolled back to an older revision. Every failure keeps the stored copy.
 */
public final class StenaLists {
    private static final String LOGTAG = "StenaLists";
    public static final String CORE_URL = "https://diazoma.app/v1/lists/core";
    /** Same keys as PINNED_KEYS in apps/extension/src/config.ts. Rotation = ship a build with the new key added. */
    private static final Map<String, String> PINNED_KEYS = Map.of(
            "b1b2f846468d5e48", "YtUs7jvojy5NKHRYMWpCGki61IIm+cKnbkpWOTGXLpA=");
    private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(1);
    /** A subscribed list with no stored copy is tried again after this long, not on every page load. */
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(10);
    private static final Pattern SIGNATURE = Pattern.compile("^ed25519 kid=([A-Za-z0-9._-]{1,64}) sig=([A-Za-z0-9+/]+={0,2})$");

    /** What the settings screen shows about one list. */
    public static final class Info {
        public final @NonNull String url;
        public final int revision;
        public final int recipes;

        Info(@NonNull String url, int revision, int recipes) {
            this.url = url;
            this.revision = revision;
            this.recipes = recipes;
        }
    }

    /** The last update of all lists: when, and the problems, if any. */
    public static final class Status {
        public final long at;
        public final @Nullable String problems;

        Status(long at, @Nullable String problems) {
            this.at = at;
            this.problems = problems;
        }
    }

    private static StenaLists sInstance;

    private final Context mContext;
    private final File mDir;
    private final SharedPreferences mPrefs;
    private final StenaSettings mSettings;
    private final OkHttpClient mHttp = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public static synchronized StenaLists get(Context context) {
        if (sInstance == null) {
            sInstance = new StenaLists(context.getApplicationContext());
        }
        return sInstance;
    }

    private StenaLists(Context context) {
        mContext = context;
        mDir = new File(new File(context.getFilesDir(), "stena"), "lists");
        mPrefs = context.getSharedPreferences("stena_lists", Context.MODE_PRIVATE);
        mSettings = StenaSettings.get(context);
    }

    /** A list on diazoma.app, with or without a slash at the end (a list added as `…/lists/adult/` is still adult). */
    private static final Pattern DIAZOMA_LIST = Pattern.compile("^https://diazoma\\.app/v1/lists/([a-z]+)/?$");

    /**
     * The home page address with the ids of the subscribed lists other than core (only lists diazoma.app serves):
     * `…&lists=adult`, or `…&lists=` when there are none, so the start page forgets a list that was removed. Reads the
     * settings only, never the network, and takes no lock: it runs on the UI thread while an update may hold one.
     */
    public @NonNull String appendListIds(@NonNull String home) {
        return home + (home.contains("?") ? "&" : "?") + "lists=" + listIds();
    }

    private String listIds() {
        List<String> ids = new ArrayList<>();
        for (String u : mSettings.getCustomLists()) {
            java.util.regex.Matcher m = DIAZOMA_LIST.matcher(u);
            if (acceptableUrl(u) && m.matches() && !"core".equals(m.group(1)) && !ids.contains(m.group(1))) ids.add(m.group(1));
        }
        return String.join(",", ids);
    }

    /**
     * True when a loaded page is Diazoma's start page (the default home page is set) but says other lists than the
     * settings: a tab restored from an older session, or a list added or removed since the page was opened. The
     * caller then loads the home address again. Never true for the address {@link #appendListIds} made, so no loop.
     */
    public boolean isStaleHome(@NonNull String uri) {
        String home = mContext.getString(R.string.HOMEPAGE_URL);
        if (!home.equals(com.igalia.wolvic.browser.SettingsStore.getInstance(mContext).getHomepage())) return false;
        Uri u = Uri.parse(uri);
        Uri h = Uri.parse(home);
        if (!"https".equals(u.getScheme()) || !java.util.Objects.equals(u.getHost(), h.getHost())
                || !java.util.Objects.equals(u.getPath(), h.getPath()) || !"browser".equals(u.getQueryParameter("via"))) return false;
        return !listIds().equals(u.getQueryParameter("lists"));
    }

    /** {@link #appendListIds} for Diazoma's default home page; any other home page is returned as it is. */
    public @NonNull String homeWithListIds(@NonNull String home) {
        return home.equals(mContext.getString(R.string.HOMEPAGE_URL)) ? appendListIds(home) : home;
    }

    private List<String> subscribed() {
        List<String> urls = new ArrayList<>();
        urls.add(CORE_URL);
        for (String u : mSettings.getCustomLists()) {
            if (acceptableUrl(u) && !urls.contains(u)) {
                urls.add(u);
            }
        }
        return urls;
    }

    /**
     * The verified bodies of the subscribed lists, updated first when the last update is a day old (or a list is
     * missing and the last try was a while ago). Blocks on the network then.
     */
    @WorkerThread
    public synchronized @NonNull List<String> bodies() {
        long since = System.currentTimeMillis() - status().at;
        boolean missing = false;
        for (String url : subscribed()) {
            missing |= !file(url).exists();
        }
        if (since > MAX_AGE_MS || (missing && since > RETRY_MS)) {
            updateAll();
        }
        List<String> out = new ArrayList<>();
        for (String url : subscribed()) {
            String body = read(url);
            if (body != null) {
                out.add(body);
            }
        }
        return out;
    }

    /** Refreshes every subscribed list and drops the ones no longer subscribed. */
    @WorkerThread
    public synchronized @NonNull Status updateAll() {
        List<String> urls = subscribed();
        List<String> problems = new ArrayList<>();
        for (String url : urls) {
            String bad = update(url);
            if (bad != null) {
                problems.add(Uri.parse(url).getHost() + Uri.parse(url).getPath() + ": " + bad);
            }
        }
        JSONObject meta = meta();
        for (Iterator<String> it = meta.keys(); it.hasNext(); ) {
            String url = it.next();
            if (!urls.contains(url)) {
                it.remove();
                file(url).delete();
            }
        }
        saveMeta(meta);
        Status s = new Status(System.currentTimeMillis(), problems.isEmpty() ? null : String.join("; ", problems));
        mPrefs.edit().putLong("updatedAt", s.at).putString("problems", s.problems).apply();
        return s;
    }

    public synchronized @NonNull Status status() {
        return new Status(mPrefs.getLong("updatedAt", 0), mPrefs.getString("problems", null));
    }

    /** The stored copy of a list, or null when it was never verified. */
    public synchronized @Nullable Info info(@NonNull String url) {
        JSONObject m = meta().optJSONObject(url);
        return m == null || !file(url).exists() ? null : new Info(url, m.optInt("revision"), m.optInt("recipes"));
    }

    /** Adds a custom list only when it verifies against a pinned key right now. Returns null, or why it was refused. */
    @WorkerThread
    public synchronized @Nullable String addCustom(@NonNull String raw) {
        String url = raw.trim();
        if (!acceptableUrl(url)) {
            return mContext.getString(R.string.stena_list_https_only);
        }
        if (url.equals(CORE_URL)) {
            return mContext.getString(R.string.stena_list_core_always_on);
        }
        List<String> custom = mSettings.getCustomLists();
        if (custom.contains(url)) {
            return null;
        }
        String bad = update(url);
        if (bad != null) {
            file(url).delete();
            return bad.startsWith("signature") ? mContext.getString(R.string.stena_list_untrusted) : bad;
        }
        custom.add(url);
        mSettings.setCustomLists(custom);
        return null;
    }

    public synchronized void removeCustom(@NonNull String url) {
        List<String> custom = mSettings.getCustomLists();
        custom.remove(url);
        mSettings.setCustomLists(custom);
        JSONObject meta = meta();
        meta.remove(url);
        saveMeta(meta);
        file(url).delete();
    }

    /** Fetches one list and stores it when it verifies and passes the rules across lists. Returns null, or the reason. */
    private @Nullable String update(String url) {
        JSONObject meta = meta();
        JSONObject prev = meta.optJSONObject(url);
        File f = file(url);
        Request.Builder request = new Request.Builder().url(url);
        if (prev != null && f.exists() && prev.optString("etag", null) != null) {
            request.header("If-None-Match", prev.optString("etag"));
        }
        try (Response res = mHttp.newCall(request.build()).execute()) {
            if (res.code() == 304 && prev != null && f.exists()) {
                return null;
            }
            if (!res.isSuccessful() || res.body() == null) {
                return "HTTP " + res.code();
            }
            byte[] body = res.body().bytes();
            if (!verify(body, res.header("X-Diazoma-Signature"))) {
                return "signature does not verify";
            }
            JSONObject list = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String id = list.optString("id", "");
            int revision = list.getInt("revision");
            if (id.isEmpty() || list.optJSONArray("recipes") == null) {
                return "not a list";
            }
            String bad = guard(meta, url, id, revision);
            if (bad != null) {
                return bad;
            }
            File tmp = new File(mDir, f.getName() + ".tmp");
            mDir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body);
            }
            if (!tmp.renameTo(f)) {
                return "cannot store the list";
            }
            meta.put(url, new JSONObject()
                    .put("id", id)
                    .put("revision", revision)
                    .put("recipes", list.getJSONArray("recipes").length())
                    .put("etag", res.header("ETag")));
            saveMeta(meta);
            return null;
        } catch (IOException e) {
            return "network: " + e.getClass().getSimpleName();
        } catch (JSONException e) {
            return "not a list";
        }
    }

    /**
     * guardList() of the extension: the core address must serve `core`, a custom list may not use that id, and an older
     * revision of a list id than one already stored is refused whichever address serves it.
     */
    @VisibleForTesting
    static @Nullable String guard(JSONObject meta, String url, String id, int revision) {
        if (url.equals(CORE_URL) && !"core".equals(id)) {
            return "the core address served list " + id;
        }
        if (!url.equals(CORE_URL) && "core".equals(id)) {
            return "a custom list may not use the id core";
        }
        for (Iterator<String> it = meta.keys(); it.hasNext(); ) {
            String other = it.next();
            JSONObject m = meta.optJSONObject(other);
            if (m != null && id.equals(m.optString("id")) && m.optInt("revision") > revision) {
                return "rollback: revision " + revision + " of " + id + " is older than " + m.optInt("revision") + " already stored";
            }
        }
        return null;
    }

    static boolean acceptableUrl(String url) {
        Uri u = Uri.parse(url);
        return "https".equals(u.getScheme()) && u.getHost() != null && !u.getHost().isEmpty();
    }

    private JSONObject meta() {
        try {
            return new JSONObject(mPrefs.getString("meta", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void saveMeta(JSONObject meta) {
        mPrefs.edit().putString("meta", meta.toString()).apply();
    }

    private File file(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", h[i]));
            }
            return new File(mDir, hex + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private @Nullable String read(String url) {
        File f = file(url);
        try {
            return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            Log.w(LOGTAG, "Cannot read a stored list: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** `ed25519 kid=<kid> sig=<base64>` over the exact body bytes, as verifyBody() in apps/extension/src/lib/verify.ts. */
    @VisibleForTesting
    static boolean verify(byte[] body, @Nullable String header) {
        Matcher m = SIGNATURE.matcher(header == null ? "" : header.trim());
        if (!m.matches()) {
            return false;
        }
        String key = PINNED_KEYS.get(m.group(1));
        if (key == null) {
            return false;
        }
        byte[] pk;
        byte[] sig;
        try {
            pk = Base64.decode(key, Base64.NO_WRAP);
            sig = Base64.decode(m.group(2), Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (pk.length != Ed25519.PUBLIC_KEY_SIZE || sig.length != Ed25519.SIGNATURE_SIZE) {
            return false;
        }
        return Ed25519.verify(sig, 0, pk, 0, body, 0, body.length);
    }
}
