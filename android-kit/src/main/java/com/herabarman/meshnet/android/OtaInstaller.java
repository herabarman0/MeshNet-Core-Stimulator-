package com.herabarman.meshnet.android;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Hands a verified update (see MeshOta) to Android's package installer.
 *
 * What Android allows: the app cannot install silently. The system shows its own "Update this app?" screen and the user
 * taps Install; the app needs the REQUEST_INSTALL_PACKAGES permission and the user must have allowed "install unknown
 * apps" for it. Android also refuses the APK unless it is signed with the SAME key as the installed app, which is a second
 * safety net behind the mesh-level signature check. NOT compiled or tried on a device in the build environment.
 */
public final class OtaInstaller {
    private static final String ACTION = "com.herabarman.meshnet.android.OTA_INSTALL_RESULT";

    private OtaInstaller() { }

    public static void install(final Context ctx, byte[] apk) throws IOException {
        final Context app = ctx.getApplicationContext();
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            OutputStream out = session.openWrite("update.apk", 0, apk.length);
            try {
                out.write(apk);
                session.fsync(out);
            } finally {
                out.close();
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(confirm);            // the system's own confirmation screen
                        }
                    } else {
                        try { context.unregisterReceiver(this); } catch (RuntimeException ignored) { }
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(receiver, new IntentFilter(ACTION));
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;   // the installer adds its result extras
            PendingIntent pi = PendingIntent.getBroadcast(app, id, new Intent(ACTION).setPackage(app.getPackageName()), flags);
            session.commit(pi.getIntentSender());
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            session.close();
        }
    }
}
