package com.abhisek.portfolioalerts;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps the app itself up to date: checks the repo's "latest" GitHub release for a newer build,
 * downloads it and hands it to Android's package installer. Android installs it without asking
 * when it allows that (Android 12+, once this app installed the previous update); otherwise it
 * shows its usual one-tap "Update" prompt.
 */
final class Updater {
    static final long FOREGROUND_GAP = Engine.HOUR;
    static final long BACKGROUND_GAP = 6 * Engine.HOUR;

    private Updater() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Engine.PREFS, Context.MODE_PRIVATE);
    }

    static String releaseFile(String name) {
        return "https://github.com/" + Engine.REPO + "/releases/latest/download/" + name;
    }

    @SuppressWarnings("deprecation")
    static int currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static void status(Context ctx, String text) {
        prefs(ctx).edit().putString("updStatus", text).apply();
    }

    static JSONObject info(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        JSONObject o = new JSONObject();
        try {
            int cur = currentVersion(ctx);
            int latest = sp.getInt("updLatest", 0);
            o.put("version", cur);
            o.put("latest", latest);
            o.put("available", latest > cur);
            o.put("status", sp.getString("updStatus", ""));
            o.put("checkedAt", sp.getLong("updCheck", 0));
        } catch (Exception ignored) {
            // empty info
        }
        return o;
    }

    /** Checks for a newer build at most once per {@code minGap} and installs it once per version. */
    static synchronized void check(Context ctx, long minGap) {
        SharedPreferences sp = prefs(ctx);
        long now = System.currentTimeMillis();
        if (now - sp.getLong("updCheck", 0) < minGap) return;
        int latest;
        try {
            latest = Integer.parseInt(Engine.get(releaseFile("version.txt") + "?t=" + now).trim());
        } catch (Exception e) {
            return; // offline or no release yet: try again next time
        }
        sp.edit().putLong("updCheck", now).putInt("updLatest", latest).apply();
        int cur = currentVersion(ctx);
        if (latest <= cur) {
            if (sp.getString("updStatus", "").startsWith("Update")) status(ctx, "");
            return;
        }
        if (sp.getInt("updTried", 0) == latest) return; // already offered this one; the in-app button retries
        install(ctx, latest);
    }

    /** Downloads build {@code version} and starts the install. Safe to call from any background thread. */
    static synchronized void install(Context ctx, int version) {
        SharedPreferences sp = prefs(ctx);
        sp.edit().putInt("updTried", version).apply();
        status(ctx, "Downloading update (build " + version + ")…");
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ctx.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        releaseFile("PortfolioAlerts.apk") + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() >= 400) throw new Exception("download failed (HTTP " + c.getResponseCode() + ")");
                try (InputStream in = c.getInputStream();
                     OutputStream out = session.openWrite("PortfolioAlerts.apk", 0, -1)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                c.disconnect();
                Intent done = new Intent(ctx, InstallReceiver.class).putExtra("version", version);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(ctx, sessionId, done, flags);
                status(ctx, "Installing update (build " + version + ")…");
                session.commit(pi.getIntentSender());
            }
        } catch (Exception e) {
            if (sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                    // already gone
                }
            }
            status(ctx, "Update failed: " + e.getMessage() + ". Tap Update to retry.");
        }
    }
}
