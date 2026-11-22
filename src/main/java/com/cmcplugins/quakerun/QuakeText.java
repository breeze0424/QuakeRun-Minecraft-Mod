package com.cmcplugins.quakerun;

import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared display values for server chat and the client HUD. */
public final class QuakeText {
    private static final DateTimeFormatter ORIGIN_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneOffset.ofHours(8));

    private QuakeText() {}

    public static Component epicenter(String place, double latitude, double longitude) {
        String lat = String.format(Locale.ROOT, "%.4f", latitude);
        String lon = String.format(Locale.ROOT, "%.4f", longitude);
        Component name = Component.literal(place == null ? "" : place);
        return place == null || place.isBlank()
                ? Component.translatable("text.quakerun.epicenter_coordinates", lat, lon)
                : Component.translatable("text.quakerun.epicenter_with_place", name, lat, lon);
    }

    public static String originTime(long epochMillis) {
        return ORIGIN_TIME.format(Instant.ofEpochMilli(epochMillis));
    }
}
