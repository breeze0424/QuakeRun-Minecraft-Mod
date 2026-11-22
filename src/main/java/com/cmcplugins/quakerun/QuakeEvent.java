package com.cmcplugins.quakerun;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** A validated, source-neutral snapshot of one EEW report. */
public record QuakeEvent(String source, String eventId, int serial, Instant reportTime,
                         Instant originTime, String place, double latitude, double longitude,
                         double magnitude, Double depthKm, Double maxIntensity) {
    private static final Set<String> SOURCES = Set.of("cenc_eew", "sc_eew", "fj_eew", "cq_eew");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneOffset CST = ZoneOffset.ofHours(8);

    public String sourceKey() { return source + ":" + eventId; }

    public static QuakeEvent parse(String json, Instant now) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            String source = string(o, "type");
            if (!SOURCES.contains(source)) return null;
            String eventId = string(o, "EventID");
            String place = string(o, "HypoCenter");
            if (eventId == null || eventId.isBlank() || eventId.length() > 80) return null;
            int serial = integer(o, "ReportNum");
            double lat = number(o, "Latitude");
            double lon = number(o, "Longitude");
            double magnitude = number(o, "Magnitude");
            Double depth = optionalNumber(o, "Depth");
            Double intensity = optionalNumber(o, "MaxIntensity");
            Instant report = time(string(o, "ReportTime"));
            Instant origin = time(string(o, "OriginTime"));
            if (serial < 1 || !Double.isFinite(lat) || lat < -90 || lat > 90
                    || !Double.isFinite(lon) || lon < -180 || lon > 180
                    || !Double.isFinite(magnitude) || magnitude <= 0 || magnitude > 10
                    || depth != null && (!Double.isFinite(depth) || depth < 0 || depth > 700)
                    || intensity != null && (!Double.isFinite(intensity) || intensity < 0 || intensity > 12)
                    || report == null || origin == null || report.isBefore(now.minusSeconds(300))
                    || report.isAfter(now.plusSeconds(60)) || origin.isAfter(now.plusSeconds(60))) return null;
            String cleanPlace = place == null ? "" : place.replaceAll("[\\p{Cntrl}]", "");
            return new QuakeEvent(source, eventId, serial, report, origin,
                    cleanPlace.substring(0, Math.min(80, cleanPlace.length())),
                    lat, lon, magnitude, depth, intensity);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }
    private static double number(JsonObject o, String key) {
        Double value = optionalNumber(o, key);
        return value == null ? Double.NaN : value;
    }
    private static Double optionalNumber(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsDouble();
    }
    private static int integer(JsonObject o, String key) {
        Double value = optionalNumber(o, key);
        return value == null || !Double.isFinite(value) || value.intValue() != value ? -1 : value.intValue();
    }
    private static Instant time(String s) {
        return s == null ? null : LocalDateTime.parse(s, TIME).toInstant(CST);
    }
}
