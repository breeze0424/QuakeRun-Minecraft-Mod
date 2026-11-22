package com.cmcplugins.quakerun.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import java.time.Instant;

/** Posted once when a player first qualifies for a given earthquake warning. */
public final class PlayerAffectedEvent extends Event {
    private final ServerPlayer player;
    private final QuakeData quake;
    private final double predictedIntensity;
    private final Instant estimatedArrival;

    public PlayerAffectedEvent(ServerPlayer player, QuakeData quake,
                               double predictedIntensity, Instant estimatedArrival) {
        this.player = player;
        this.quake = quake;
        this.predictedIntensity = predictedIntensity;
        this.estimatedArrival = estimatedArrival;
    }

    public ServerPlayer player() { return player; }
    public QuakeData quake() { return quake; }
    public double predictedIntensity() { return predictedIntensity; }
    public Instant estimatedArrival() { return estimatedArrival; }
}
