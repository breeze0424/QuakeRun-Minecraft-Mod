package com.cmcplugins.quakerun;

import java.time.Instant;
import java.time.Duration;

/** Transparent approximations, not site measurements or an official warning model. */
public final class QuakeMath {
    public static final double FELT_THRESHOLD = 2.5; // Chinese intensity III begins at 2.5.
    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double S_KM_PER_SEC = 3.5;

    private QuakeMath() {}

    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat2 - lat1);
        double b = Math.toRadians(lon2 - lon1);
        double h = Math.pow(Math.sin(a / 2), 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(b / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
    }

    /** The feed maximum is a proxy for epicentral intensity, not an observed local value. */
    public static double predictedIntensity(QuakeEvent event, double latitude, double longitude) {
        double input = event.maxIntensity() == null ? estimatedEpicentralIntensity(event.magnitude())
                : event.maxIntensity();
        double d = distanceKm(event.latitude(), event.longitude(), latitude, longitude);
        return Math.max(0, input - 4.0 * Math.log10(d / 10.0 + 1.0));
    }

    /** Fallback extrapolates a mainland-China M5–8 regression when the feed has no intensity. */
    static double estimatedEpicentralIntensity(double magnitude) {
        double major = 6.1709 + 1.3343 * magnitude - 1.9119 * Math.log(30);
        double minor = 1.9348 + 1.3783 * magnitude - 1.2711 * Math.log(6);
        return Math.max(0, Math.min(12, Math.max(major, minor)));
    }

    public static Instant arrival(QuakeEvent event, double latitude, double longitude) {
        double d = distanceKm(event.latitude(), event.longitude(), latitude, longitude);
        double depth = event.depthKm() == null ? 10.0 : event.depthKm();
        long millis = Math.round(Math.hypot(d, depth) / S_KM_PER_SEC * 1000);
        return event.originTime().plusMillis(millis);
    }

    public static boolean samePhysicalQuake(QuakeEvent a, QuakeEvent b) {
        return !a.source().equals(b.source())
                && Math.abs(Duration.between(a.originTime(), b.originTime()).toMillis()) <= 10_000
                && distanceKm(a.latitude(), a.longitude(), b.latitude(), b.longitude()) <= 30;
    }
}
