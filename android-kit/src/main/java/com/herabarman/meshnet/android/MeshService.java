package com.herabarman.meshnet.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the mesh alive in the background. Android stops ordinary background work within minutes, so a mesh that lives in an
 * Activity dies when the screen turns off. This foreground service owns the {@link MeshNet}: it shows the required notification,
 * holds a partial wake lock while running, and is restarted by the system if it is killed (START_STICKY).
 * <p>
 * Use: subclass it (or use it as is), declare it in the manifest with android:foregroundServiceType="connectedDevice" and ask for
 * the permissions FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE (API 34+), POST_NOTIFICATIONS (API 33+), WAKE_LOCK.
 * Start it with {@code ContextCompat.startForegroundService(ctx, new Intent(ctx, MeshService.class))} after {@link #configure}.
 * <p>
 * NOT yet run on a real phone: OEM battery managers (Xiaomi, Huawei, Samsung...) may still kill it; the user may need to exempt the
 * app from battery optimisation (see {@link #batteryExemptionIntent}).
 */
public class MeshService extends Service {
    /** What the app wants started. Set once from Application.onCreate(), before the service starts. */
    public interface Factory { MeshNet start(Context appContext); }

    private static volatile Factory factory;
    private static volatile String title = "Mesh network running";
    private static volatile String text = "Relaying messages for people nearby";
    private static volatile int smallIcon = android.R.drawable.stat_sys_data_bluetooth;
    private static volatile MeshNet running;

    private PowerManager.WakeLock wake;

    public static void configure(Factory f, String notificationTitle, String notificationText, int iconRes) {
        factory = f;
        if (notificationTitle != null) title = notificationTitle;
        if (notificationText != null) text = notificationText;
        if (iconRes != 0) smallIcon = iconRes;
    }

    /** The running mesh, or null if the service is not up. */
    public static MeshNet mesh() { return running; }

    /** An intent that opens the system dialog asking the user to let the app ignore battery optimisation. */
    public static Intent batteryExemptionIntent(Context ctx) {
        Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
        return i;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1001, notification());
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meshnet:service");
                wake.setReferenceCounted(false);
                wake.acquire();
            }
        }
        if (running == null) {
            Factory f = factory;
            if (f != null) {
                try { running = f.start(getApplicationContext()); }
                catch (RuntimeException e) { android.util.Log.e("MeshService", "mesh failed to start: " + e); }
            }
        }
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("mesh", "Mesh network", NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "mesh");
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title).setContentText(text).setSmallIcon(smallIcon).setOngoing(true);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            b.setContentIntent(PendingIntent.getActivity(this, 0, launch, fl));
        }
        return b.build();
    }

    @Override public void onDestroy() {
        MeshNet m = running;
        running = null;
        if (m != null) { try { m.stop(); } catch (RuntimeException ignored) { } }
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
