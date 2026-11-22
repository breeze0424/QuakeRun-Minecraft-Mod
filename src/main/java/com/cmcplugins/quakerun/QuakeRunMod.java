package com.cmcplugins.quakerun;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(QuakeRunMod.MOD_ID)
public final class QuakeRunMod {
    public static final String MOD_ID = "quakerun";
    private QuakeServer service;

    public QuakeRunMod() {
        AlertNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void started(ServerStartedEvent event) {
        service = new QuakeServer(event.getServer());
        service.start();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        if (service != null) service.close();
        service = null;
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer player) service.join(player);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer player) service.leave(player);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (service != null && event.phase == TickEvent.Phase.END) service.tick();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        QuakeCommands.register(event.getDispatcher(), () -> service);
    }
}
