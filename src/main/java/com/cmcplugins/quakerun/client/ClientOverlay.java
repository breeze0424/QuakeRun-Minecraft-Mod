package com.cmcplugins.quakerun.client;

import com.cmcplugins.quakerun.QuakeRunMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = QuakeRunMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientOverlay {
    private ClientOverlay() {}

    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("earthquake_warning", (forgeGui, graphics, partialTick, width, height) ->
                ClientAlerts.render(graphics, width, height));
    }
}
