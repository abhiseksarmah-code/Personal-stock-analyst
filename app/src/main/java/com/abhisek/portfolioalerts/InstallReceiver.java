package com.abhisek.portfolioalerts;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Result of an update install: finishes silently, or asks for one tap when Android needs it. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int version = intent.getIntExtra("version", 0);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Updater.status(ctx, "Update ready (build " + version + "): approve the install");
            if (MainActivity.visible) {
                try {
                    ctx.startActivity(confirm);
                    return;
                } catch (Exception ignored) {
                    // fall back to a notification
                }
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, Engine.UPDATE_ID, confirm,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Engine.notifyUser(ctx, Engine.UPDATE_ID, "Portfolio Alerts update ready",
                    "Build " + version + " is downloaded. Tap to install it.", pi);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.status(ctx, "Updated to build " + version);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(Engine.UPDATE_ID);
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            Updater.status(ctx, "Update (build " + version + ") not installed yet. Tap Update to install.");
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Updater.status(ctx, "Update failed" + (msg != null ? ": " + msg : "") + ". Tap Update to retry.");
        }
    }
}
