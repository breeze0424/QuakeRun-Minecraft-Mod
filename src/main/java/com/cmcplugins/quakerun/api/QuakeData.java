package com.cmcplugins.quakerun.api;

import com.cmcplugins.quakerun.QuakeEvent;

import java.time.Instant;

/** Immutable WOLFX EEW report data. Nullable values were absent from the feed. */
public record QuakeData(String source, String eventId, int serial, Instant reportTime,
                        Instant originTime, String place, double latitude, double longitude,
                        double magnitude, Double depthKm, Double maxIntensity) {
    public static QuakeData from(QuakeEvent event) {
        return new QuakeData(event.source(), event.eventId(), event.serial(),
                event.reportTime(), event.originTime(), event.place(), event.latitude(),
                event.longitude(), event.magnitude(), event.depthKm(), event.maxIntensity());
    }
}
