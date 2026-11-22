package com.cmcplugins.quakerun;

import com.cmcplugins.quakerun.api.PlayerAffectedEvent;
import com.cmcplugins.quakerun.api.QuakeData;
import com.cmcplugins.quakerun.api.QuakeReportEvent;
import com.cmcplugins.quakerun.api.WaveArrivalEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** All maps and event decisions are confined to the Minecraft server thread. */
public final class QuakeServer implements AutoCloseable {
    private final MinecraftServer server;
    private final GeoLocator geo = new GeoLocator();
    private final WolfxFeed feed;
    private final WildlifePanic wildlife = new WildlifePanic();
    private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quakerun-startup");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, GeoLocator.Location> players = new HashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, String> sourceToGroup = new HashMap<>();
    private GeoLocator.Location serverLocation;
    private boolean publicEgress;
    private boolean active = true;
    private int ticks;
    private Instant lastValid;

    public QuakeServer(MinecraftServer server) {
        this.server = server;
        this.feed = new WolfxFeed(event -> server.execute(() -> process(event)));
    }

    public void start() { locateServer(); }

    private void locateServer() {
        if (!active || publicEgress && serverLocation != null) return;
        geo.self().thenAccept(result -> server.execute(() -> {
            if (!active) return;
            if (result.publicIp()) {
                publicEgress = true;
                if (result.location() != null) serverLocation = result.location();
            }
            if (publicEgress) {
                feed.start();
                for (ServerPlayer player : server.getPlayerList().getPlayers())
                    if (!players.containsKey(player.getUUID())) join(player);
            }
            if (!publicEgress || serverLocation == null) {
                retry.schedule(() -> server.execute(this::locateServer), 60, TimeUnit.SECONDS);
            }
        }));
    }

    public void join(ServerPlayer player) {
        if (!active || !publicEgress) return;
        UUID uuid = player.getUUID();
        SocketAddress remote = player.connection.getRemoteAddress();
        String ip = remote instanceof InetSocketAddress socket && socket.getAddress() != null
                ? socket.getAddress().getHostAddress() : null;
        if (!GeoLocator.isPublicIp(ip)) {
            if (serverLocation != null) setPlayerLocation(uuid, serverLocation);
            return;
        }
        geo.player(ip).thenAccept(location -> server.execute(() -> {
            if (!active || server.getPlayerList().getPlayer(uuid) == null) return;
            if (location != null) setPlayerLocation(uuid, location);
        }));
    }

    private void setPlayerLocation(UUID uuid, GeoLocator.Location location) {
        players.put(uuid, location);
        for (Group group : groups.values()) evaluatePlayer(group, uuid, location, Instant.now());
    }

    public void leave(ServerPlayer player) {
        UUID uuid = player.getUUID();
        players.remove(uuid);
        for (Group group : groups.values()) {
            group.alerted.remove(uuid);
            group.panicked.remove(uuid);
        }
    }

    public void process(QuakeEvent event) {
        if (!active || !publicEgress) return;
        Instant now = Instant.now();
        if (event.reportTime().isBefore(now.minusSeconds(300))
                || event.reportTime().isAfter(now.plusSeconds(60))) return;
        String known = sourceToGroup.get(event.sourceKey());
        Group group = known == null ? match(event) : groups.get(known);
        if (group == null) {
            group = new Group(UUID.randomUUID().toString());
            groups.put(group.id, group);
        }
        QuakeEvent previous = group.sources.get(event.sourceKey());
        if (previous != null && event.serial() <= previous.serial()) return;
        group.sources.put(event.sourceKey(), event);
        sourceToGroup.put(event.sourceKey(), group.id);
        lastValid = now;
        MinecraftForge.EVENT_BUS.post(new QuakeReportEvent(QuakeData.from(event)));
        for (Map.Entry<UUID, GeoLocator.Location> entry : players.entrySet())
            evaluatePlayer(group, entry.getKey(), entry.getValue(), now);
        if (serverLocation != null && !group.chatSent && group.bestAt(serverLocation, now) != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Affected players already receive the HUD and its camera effect.
                if (!group.alerted.contains(player.getUUID()))
                    player.sendSystemMessage(Component.translatable("message.quakerun.server_affected"));
            }
            group.chatSent = true;
        }
    }

    private Group match(QuakeEvent event) {
        List<Group> candidates = new ArrayList<>();
        for (Group group : groups.values()) {
            QuakeEvent first = group.sources.values().iterator().next();
            if (QuakeMath.samePhysicalQuake(first, event)
                    && group.sources.values().stream().noneMatch(q -> q.source().equals(event.source())))
                candidates.add(group);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private void evaluatePlayer(Group group, UUID uuid, GeoLocator.Location location, Instant now) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) return;
        QuakeEvent selected = group.bestAt(location, now);
        if (selected == null) {
            if (group.alerted.remove(uuid)) AlertNetwork.send(player,
                    new AlertNetwork.Alert(group.id, true, "", 0, 0, 0,
                            "", "", 0, 0, 0));
            return;
        }
        double intensity = QuakeMath.predictedIntensity(selected, location.latitude(), location.longitude());
        Instant arrival = QuakeMath.arrival(selected, location.latitude(), location.longitude());
        long delay = Duration.between(now, arrival).toMillis();
        boolean newlyAffected = !group.alerted.contains(uuid);
        AlertNetwork.send(player, new AlertNetwork.Alert(group.id, false, selected.place(),
                selected.latitude(), selected.longitude(),
                selected.originTime().toEpochMilli(), location.cityZh(), location.cityEn(),
                selected.magnitude(), intensity, delay));
        group.alerted.add(uuid);
        if (newlyAffected) MinecraftForge.EVENT_BUS.post(new PlayerAffectedEvent(player,
                QuakeData.from(selected), intensity, arrival));
    }

    public void tick() {
        ++ticks;
        wildlife.tick();
        if (ticks % 5 == 0) panicNearbyAnimals();
        if (ticks % 1200 == 0 && publicEgress) {
            for (ServerPlayer player : server.getPlayerList().getPlayers())
                if (!players.containsKey(player.getUUID())) join(player);
        }
        if (ticks % 200 != 0) return;
        Instant cutoff = Instant.now().minusSeconds(1800);
        groups.values().removeIf(group -> {
            boolean stale = group.sources.values().stream().allMatch(q -> q.reportTime().isBefore(cutoff));
            if (stale) for (String key : group.sources.keySet()) sourceToGroup.remove(key);
            return stale;
        });
    }

    private void panicNearbyAnimals() {
        Instant now = Instant.now();
        for (Group group : groups.values()) {
            for (UUID uuid : group.alerted) {
                if (group.panicked.contains(uuid)) continue;
                GeoLocator.Location location = players.get(uuid);
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (location == null || player == null) continue;
                QuakeEvent quake = group.bestAt(location, now);
                if (quake == null) continue;
                long elapsed = Duration.between(QuakeMath.arrival(quake,
                        location.latitude(), location.longitude()), now).toMillis();
                if (elapsed < 0 || elapsed >= 10_000) continue;
                group.panicked.add(uuid);
                MinecraftForge.EVENT_BUS.post(new WaveArrivalEvent(player, QuakeData.from(quake)));
                wildlife.startAround(player, 10_000 - elapsed);
            }
        }
    }

    public Component status(String locale) {
        Component city = serverLocation == null
                ? Component.translatable("text.quakerun.unavailable")
                : Component.literal(serverLocation.cityFor(locale));
        return Component.translatable("message.quakerun.status",
                Component.translatable(feed.connected() ? "text.quakerun.connected" : "text.quakerun.disconnected"),
                Component.translatable(publicEgress ? "text.quakerun.available" : "text.quakerun.unavailable"),
                city, lastValid == null ? Component.translatable("text.quakerun.none") : Component.literal(lastValid.toString()),
                geo.successes(), geo.failures());
    }

    private static String format(double number) {
        return String.format(java.util.Locale.ROOT, "%.1f", number);
    }

    @Override public void close() {
        active = false;
        feed.close();
        retry.shutdownNow();
        geo.close();
        wildlife.close();
        players.clear();
        groups.clear();
        sourceToGroup.clear();
    }

    private static final class Group {
        final String id;
        final Map<String, QuakeEvent> sources = new LinkedHashMap<>();
        final Set<UUID> alerted = new HashSet<>();
        final Set<UUID> panicked = new HashSet<>();
        boolean chatSent;

        Group(String id) { this.id = id; }

        QuakeEvent bestAt(GeoLocator.Location location, Instant now) {
            QuakeEvent best = null;
            double strongest = QuakeMath.FELT_THRESHOLD;
            for (QuakeEvent event : sources.values()) {
                if (event.reportTime().isBefore(now.minusSeconds(300))
                        || QuakeMath.arrival(event, location.latitude(), location.longitude())
                        .isBefore(now.minusSeconds(10))) continue;
                double predicted = QuakeMath.predictedIntensity(event,
                        location.latitude(), location.longitude());
                if (predicted >= strongest) { strongest = predicted; best = event; }
            }
            return best;
        }
    }
}
