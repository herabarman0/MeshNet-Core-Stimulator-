package com.herabarman.meshnet.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import com.herabarman.meshnet.kit.MeshClient;

/**
 * Tells the mesh how much battery the phone has. Below 20% the engine stops relaying other people's traffic
 * (the phone still sends and receives its own messages) and the phone is not made a gateway; at 25% it relays again.
 * A phone that is charging reports a full battery: it costs nothing to relay and it makes the best relay in the zone.
 */
final class BatteryFeeder {
    private final Context ctx;
    private final MeshClient client;
    private BroadcastReceiver receiver;

    BatteryFeeder(Context ctx, MeshClient client) {
        this.ctx = ctx;
        this.client = client;
    }

    void start() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                feed(intent);
            }
        };
        // ACTION_BATTERY_CHANGED is a sticky system broadcast: registering returns the current state at once.
        Intent current = ctx.registerReceiver(receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (current != null) feed(current);
    }

    void stop() {
        if (receiver == null) return;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (IllegalArgumentException alreadyGone) {
            // nothing to do
        }
        receiver = null;
    }

    private void feed(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        client.setPowerState(charging ? 1.0 : (double) level / scale, charging);
    }
}
