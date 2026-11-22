package com.cmcplugins.quakerun.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/** Posted once per affected player when the estimated wave arrives. */
public final class WaveArrivalEvent extends Event {
    private final ServerPlayer player;
    private final QuakeData quake;

    public WaveArrivalEvent(ServerPlayer player, QuakeData quake) {
        this.player = player;
        this.quake = quake;
    }

    public ServerPlayer player() { return player; }
    public QuakeData quake() { return quake; }
}
