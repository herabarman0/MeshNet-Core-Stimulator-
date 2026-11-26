package com.herabarman.meshnet.android;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import com.herabarman.meshnet.kit.MeshClient;

/**
 * Feeds GPS and network (Wi-Fi / cell tower) fixes to MeshClient.updateLocation(); the zone follows by itself.
 * Uses plain LocationManager (no Play Services needed). The app must hold ACCESS_FINE_LOCATION or
 * ACCESS_COARSE_LOCATION; without it start() simply does nothing and the mesh runs in its "unlocated" zone.
 * Coarse network fixes are enough: a zone cell is about 1 km.
 */
public final class LocationFeeder implements LocationListener {
    private static final long MIN_TIME_MS = 30_000;
    private static final float MIN_DISTANCE_M = 100f;

    private final LocationManager lm;
    private final MeshClient client;
    private NearbyRadio radio;
    private boolean active;
    private volatile com.herabarman.meshnet.kit.LostPhone.Fix lastFix;

    public LocationFeeder(Context ctx, MeshClient client) {
        this.lm = (LocationManager) ctx.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        this.client = client;
    }

    /** Optional: also tell the radio how fast the phone moves, so its scan duty cycle adapts. */
    public LocationFeeder withRadio(NearbyRadio r) { this.radio = r; return this; }

    public void start() {
        if (active || lm == null) return;
        try {
            boolean any = false;
            for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (!lm.isProviderEnabled(provider)) continue;
                Location last = lm.getLastKnownLocation(provider);
                if (last != null) onLocationChanged(last);
                lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, this, Looper.getMainLooper());
                any = true;
            }
            active = any;
        } catch (SecurityException e) {
            active = false;   // permission not granted (yet): call start() again after it is
        }
    }

    /** The latest fix (any provider), or null. Used by the lost-phone finder. */
    public com.herabarman.meshnet.kit.LostPhone.Fix lastFix() { return lastFix; }

    public void stop() {
        if (lm != null) lm.removeUpdates(this);
        active = false;
    }

    @Override public void onLocationChanged(Location l) {
        client.updateLocation(l.getLatitude(), l.getLongitude());
        lastFix = new com.herabarman.meshnet.kit.LostPhone.Fix(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? (int) l.getAccuracy() : 0, System.currentTimeMillis());
        if (radio != null) radio.setSpeed(client.speedMps());
    }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
