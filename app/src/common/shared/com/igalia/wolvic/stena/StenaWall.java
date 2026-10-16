package com.igalia.wolvic.stena;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import com.igalia.wolvic.AppExecutors;
import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserApplication;
import com.igalia.wolvic.browser.engine.Session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * "Open as wall": what the extension popup does on a click (apps/extension/src/popup/popup.ts), with the browser in place
 * of chrome.scripting. The page's own world is asked about `window.__diazoma` (Level 3); probe.js and detect.js run in an
 * isolated world, as the extension's content scripts do, and decide the level against the verified lists. Level 3
 * and recipes (Level 0) navigate; a manifest (Level 2) or the page's own items (Level 1) get overlay.js in the page.
 * {@link #hint} is the extension's badge: after a page load, match.js says whether the address bar shows the wall button.
 */
public final class StenaWall {
    // Warnings, not info: release builds strip Log.i/d/v (proguard-rules.pro), and these are one line per click.
    private static final String LOGTAG = "StenaWall";
    /** Isolated world for all Diazoma scripts (1..10, see org.chromium.wolvic.Tab#executeScript). */
    private static final int WORLD = 1;
    private static final int MAIN_WORLD = 0;
    private static final String APP_ORIGIN = "https://diazoma.app";
    private static final String APP_WALL = APP_ORIGIN + "/app/wall.html";
    private static final String L3 = "(window.__diazoma ? { route: String(window.__diazoma.route || '/') } : null)";
    private static final long POLL_MS = 150;
    private static final long TIMEOUT_MS = 20_000;
    private static final Map<String, String> sAssets = new HashMap<>();

    public interface Notifier {
        void show(@StringRes int message);
    }

    private StenaWall() {}

    @UiThread
    public static void open(@NonNull Context context, @NonNull Session session, @NonNull Notifier notifier) {
        final String url = session.getCurrentUri();
        String origin = StenaSettings.originOf(url);
        if (origin == null) {
            notifier.show(R.string.stena_not_a_page);
            return;
        }
        if (StenaSettings.get(context).isOff(origin)) {
            notifier.show(R.string.stena_site_off);
            return;
        }
        Log.w(LOGTAG, "Open as wall");
        AppExecutors executors = executors(context);
        executors.networkIO().execute(() -> {
            List<String> bodies = StenaLists.get(context).bodies();
            String scripts;
            try {
                scripts = asset(context, "stena/probe.js") + "\n;" + asset(context, "stena/detect.js");
            } catch (IOException e) {
                Log.e(LOGTAG, "Missing scripts: " + e);
                executors.mainThread().execute(() -> notifier.show(R.string.stena_failed));
                return;
            }
            Log.w(LOGTAG, "Verified lists: " + bodies.size());
            String json = new JSONArray(bodies).toString();
            executors.mainThread().execute(() -> detect(context, session, url, scripts, json, notifier));
        });
    }

    /**
     * After a page load: true when a recipe matches the address or the page has its own wall, so the address bar shows
     * the wall button. Nothing leaves the browser; manifests and page items are only looked for on a click.
     */
    @UiThread
    public static void hint(@NonNull Context context, @NonNull Session session, @NonNull Consumer<Boolean> show) {
        final String url = session.getCurrentUri();
        String origin = StenaSettings.originOf(url);
        StenaSettings settings = StenaSettings.get(context);
        if (origin == null || !settings.isAutoDetect() || settings.isOff(origin)) {
            show.accept(false);
            return;
        }
        AppExecutors executors = executors(context);
        executors.networkIO().execute(() -> {
            List<String> bodies = StenaLists.get(context).bodies();
            String script;
            try {
                script = asset(context, "stena/match.js");
            } catch (IOException e) {
                Log.e(LOGTAG, "Missing scripts: " + e);
                return;
            }
            String json = new JSONArray(bodies).toString();
            executors.mainThread().execute(() -> session.executeScript(L3, MAIN_WORLD, l3 -> {
                String call = script + "\n;globalThis.__dzMatch(" + json + ", " + (l3 == null ? "null" : l3) + ")";
                session.executeScript(call, WORLD, result -> {
                    if (url.equals(session.getCurrentUri())) {
                        String source = sourceOf(result);
                        show.accept("recipe".equals(source) || "site-wall".equals(source));
                    }
                });
            }));
        });
    }

    /** The per-site switch of the extension popup. */
    public static void setSiteOff(@NonNull Context context, @NonNull String url, boolean off) {
        String origin = StenaSettings.originOf(url);
        if (origin != null) {
            StenaSettings.get(context).setOff(origin, off);
        }
    }

    public static boolean isSiteOff(@NonNull Context context, @NonNull String url) {
        return StenaSettings.get(context).isOff(StenaSettings.originOf(url));
    }

    private static void detect(Context context, Session session, String url, String scripts, String bodies, Notifier notifier) {
        // The JSON the engine hands back is a valid JS literal, so the page's answer goes into the next script as data.
        session.executeScript(L3, MAIN_WORLD, l3 -> {
            Log.w(LOGTAG, "Page world: " + ("null".equals(l3) ? "no site wall" : "site wall"));
            String start = scripts + "\n;globalThis.__dzDetect(" + bodies + ", " + (l3 == null ? "null" : l3) + "); null";
            session.executeScript(start, WORLD, ignored -> poll(context, session, url, notifier, System.currentTimeMillis()));
        });
    }

    private static void poll(Context context, Session session, String url, Notifier notifier, long since) {
        if (!url.equals(session.getCurrentUri())) {
            Log.w(LOGTAG, "The page changed, detection dropped");
            return;
        }
        session.executeScript("globalThis.__dzDetectR ?? null", WORLD, result -> {
            if (result == null || "null".equals(result)) {
                if (System.currentTimeMillis() - since > TIMEOUT_MS) {
                    Log.w(LOGTAG, "Detection timed out");
                    notifier.show(R.string.stena_failed);
                } else {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> poll(context, session, url, notifier, since), POLL_MS);
                }
                return;
            }
            // Only the kind of result: the rest carries addresses from the page, and logcat outlives the visit.
            Log.w(LOGTAG, "Detected: " + sourceOf(result));
            try {
                act(context, session, new JSONObject(result), notifier);
            } catch (JSONException | IOException e) {
                Log.e(LOGTAG, "Cannot act on " + sourceOf(result) + ": " + e.getClass().getSimpleName());
                notifier.show(R.string.stena_failed);
            }
        });
    }

    private static void act(Context context, Session session, JSONObject d, Notifier notifier) throws JSONException, IOException {
        switch (d.getString("source")) {
            case "site-wall":
                if (d.isNull("wallUrl")) {
                    notifier.show(R.string.stena_site_wall_unchecked);
                } else {
                    session.loadUri(d.getString("wallUrl"));
                }
                return;
            case "manifest":
                overlay(context, session, new JSONObject().put("mode", "manifest").put("manifestUrl", d.getString("manifestUrl")));
                return;
            case "page":
                overlay(context, session, new JSONObject().put("mode", "dom"));
                return;
            case "recipe": {
                // Only the recipe id goes to diazoma.app: the page address stays in the browser.
                Uri.Builder wall = Uri.parse(APP_WALL).buildUpon().appendQueryParameter("recipe", d.getString("id"));
                if (!d.getBoolean("privileged")) {
                    session.loadUri(wall.build().toString());
                    return;
                }
                // The pictures come from hosts that send no CORS headers. The extension reads them with its host
                // permissions; here those picture CDNs (hosts.json, no logins there) are opened to diazoma.app's pages
                // before the wall loads. Never a host with user sessions: a page could read it with the user's cookies.
                String[] hosts = privilegedHosts(context);
                Log.w(LOGTAG, "Opening " + hosts.length + " picture hosts to the wall");
                String target = wall.appendQueryParameter("via", "browser").build().toString();
                session.allowCrossOriginReads(APP_ORIGIN, hosts, () -> session.loadUri(target));
                return;
            }
            case "denied":
                notifier.show(R.string.stena_denied);
                return;
            case "manifest-error":
                notifier.show("invalid".equals(d.optString("reason")) ? R.string.stena_manifest_invalid : R.string.stena_manifest_unreadable);
                return;
            case "none":
                notifier.show(R.string.stena_none);
                return;
            default:
                Log.w(LOGTAG, "Detection failed: " + d.optString("source"));
                notifier.show(R.string.stena_failed);
        }
    }

    /** The hosts of hosts.json (the extension's CORE_MEDIA_HOSTS), all of them `https://<host>/*`. */
    private static String[] privilegedHosts(Context context) throws IOException, JSONException {
        JSONArray patterns = new JSONArray(asset(context, "stena/hosts.json"));
        List<String> hosts = new ArrayList<>();
        for (int i = 0; i < patterns.length(); i++) {
            Uri u = Uri.parse(patterns.getString(i).replace("/*", "/"));
            if ("https".equals(u.getScheme()) && u.getHost() != null) {
                hosts.add(u.getHost());
            }
        }
        return hosts.toArray(new String[0]);
    }

    private static void overlay(Context context, Session session, JSONObject args) throws JSONException, IOException {
        args.put("inputProfile", StenaSettings.get(context).getInputProfile())
                .put("closeLabel", context.getString(R.string.stena_close_wall));
        String script = "globalThis.__dzOverlayArgs = " + args + ";\n" + asset(context, "stena/overlay.js") + "\n;null";
        session.executeScript(script, WORLD, ignored -> {});
    }

    private static AppExecutors executors(Context context) {
        return ((VRBrowserApplication) context.getApplicationContext()).getExecutors();
    }

    private static String sourceOf(String result) {
        try {
            return new JSONObject(result).optString("source", "?");
        } catch (JSONException e) {
            return "unparsable";
        }
    }

    private static synchronized String asset(Context context, String name) throws IOException {
        String cached = sAssets.get(name);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            String text = out.toString(StandardCharsets.UTF_8.name());
            sAssets.put(name, text);
            return text;
        }
    }
}
