package com.cmcplugins.quakerun;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/** GeoIP is queried on joins, never in the Minecraft tick thread. No IP is logged or persisted. */
public final class GeoLocator implements AutoCloseable {
    public record Location(String cityZh, String cityEn, double latitude, double longitude) {
        public String cityFor(String locale) {
            String preferred = locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("zh_")
                    ? cityZh : cityEn;
            return preferred.isBlank() ? (cityZh.isBlank() ? cityEn : cityZh) : preferred;
        }
    }
    public record Self(boolean publicIp, Location location) {}
    private record Entry(CompletableFuture<Location> future, long expiresAt) {}

    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), runnable -> {
                Thread t = new Thread(runnable, "quakerun-geoip");
                t.setDaemon(true);
                return t;
            });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicInteger successes = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public int successes() { return successes.get(); }
    public int failures() { return failures.get(); }

    public CompletableFuture<Self> self() {
        try {
            return CompletableFuture.supplyAsync(() -> {
                JsonObject json = request("https://api.wolfx.jp/geoip.php");
                String ip = text(json, "ip");
                boolean publicIp = isPublicIp(ip);
                Location location = publicIp ? location(json) : null;
                if (location != null) successes.incrementAndGet(); else failures.incrementAndGet();
                return new Self(publicIp, location);
            }, workers);
        } catch (RuntimeException ex) {
            failures.incrementAndGet();
            return CompletableFuture.completedFuture(new Self(false, null));
        }
    }

    public CompletableFuture<Location> player(String ip) {
        if (!isPublicIp(ip)) return CompletableFuture.completedFuture(null);
        long now = System.currentTimeMillis();
        if (cache.size() > 1024) cache.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        try {
            Entry entry = cache.compute(ip, (key, existing) -> {
                if (existing != null && existing.expiresAt > now) return existing;
                CompletableFuture<Location> future = CompletableFuture.supplyAsync(() -> {
                    Location result = location(request("https://api.wolfx.jp/geoip.php?ip="
                            + URLEncoder.encode(key, StandardCharsets.UTF_8)));
                    if (result != null) successes.incrementAndGet(); else failures.incrementAndGet();
                    return result;
                }, workers);
                future.thenAccept(result -> {
                    if (result == null) {
                        Entry saved = cache.get(key);
                        if (saved != null && saved.future == future) cache.remove(key, saved);
                    }
                });
                return new Entry(future, now + Duration.ofHours(24).toMillis());
            });
            return entry.future;
        } catch (RuntimeException ex) {
            failures.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    private JsonObject request(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "QuakeRun/1.0.0 Forge-1.20.1").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200 || res.body().length() > 32768) return null;
            return JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Location location(JsonObject json) {
        try {
            if (json == null || !json.has("latitude") || !json.has("longitude")
                    || json.get("latitude").isJsonNull() || json.get("longitude").isJsonNull()) return null;
            double lat = json.get("latitude").getAsDouble();
            double lon = json.get("longitude").getAsDouble();
            if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon)
                    || lon < -180 || lon > 180) return null;
            String cityZh = cleanCity(text(json, "city_zh"));
            String cityEn = cleanCity(text(json, "city"));
            return new Location(cityZh, cityEn, lat, lon);
        } catch (RuntimeException ex) { return null; }
    }

    private static String cleanCity(String city) {
        if (city == null) return "";
        city = city.replaceAll("[\\p{Cntrl}]", "");
        return city.substring(0, Math.min(60, city.length()));
    }

    private static String text(JsonObject json, String field) {
        return json == null || !json.has(field) || json.get(field).isJsonNull()
                ? null : json.get(field).getAsString();
    }

    static boolean isPublicIp(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            // Reject names; only numeric literals obtained from a socket or Wolfx may be queried.
            if (!value.matches("[0-9a-fA-F:.%]+")) return false;
            InetAddress address = InetAddress.getByName(value);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
            byte[] b = address.getAddress();
            if (address instanceof Inet4Address) {
                int a = b[0] & 255, c = b[1] & 255;
                return a != 0 && a != 10 && a != 127 && a < 224
                        && !(a == 100 && c >= 64 && c <= 127)
                        && !(a == 192 && c == 0)
                        && !(a == 192 && c == 2)
                        && !(a == 192 && c == 168)
                        && !(a == 198 && c == 51 && (b[2] & 255) == 100)
                        && !(a == 198 && (c == 18 || c == 19))
                        && !(a == 203 && c == 0 && (b[2] & 255) == 113)
                        && !(a == 169 && c == 254)
                        && !(a == 172 && c >= 16 && c <= 31);
            }
            return (b[0] & 0xfe) != 0xfc && !(b[0] == 0x20 && b[1] == 0x01
                    && b[2] == 0x0d && (b[3] & 0xff) == 0xb8);
        } catch (Exception ex) { return false; }
    }

    @Override public void close() { workers.shutdownNow(); cache.clear(); }
}
