package com.cmcplugins.quakerun.client;

import com.cmcplugins.quakerun.AlertNetwork;
import com.cmcplugins.quakerun.QuakeRunMod;
import com.cmcplugins.quakerun.QuakeText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber(modid = QuakeRunMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientAlerts {
    private static final long SHAKE_NS = 10_000_000_000L;
    private static final Map<String, Active> ALERTS = new HashMap<>();
    private static final Map<String, Long> SHAKEN = new HashMap<>();

    private ClientAlerts() {}

    public static void receive(AlertNetwork.Alert message) {
        if (message.cancel()) {
            ALERTS.remove(message.id());
            return;
        }
        long now = System.nanoTime();
        // Source-to-city travel may exceed ten minutes for a very large earthquake.
        if (message.arrivalDelayMs() < -86_400_000L || message.arrivalDelayMs() > 86_400_000L) return;
        long arrival = now + message.arrivalDelayMs() * 1_000_000L;
        Active current = ALERTS.get(message.id());
        if (current == null) {
            current = new Active(message, arrival);
            if (SHAKEN.containsKey(message.id())) current.shakeStarted = true;
            ALERTS.put(message.id(), current);
        } else {
            current.message = message;
            current.arrival = arrival;
        }
        advance(now);
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Minecraft.getInstance().level == null) {
            ALERTS.clear();
            SHAKEN.clear();
        } else advance(System.nanoTime());
    }

    private static void advance(long now) {
        SHAKEN.values().removeIf(expiry -> now >= expiry);
        Iterator<Map.Entry<String, Active>> it = ALERTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Active> entry = it.next();
            Active alert = entry.getValue();
            if (!alert.shakeStarted && now >= alert.arrival && now < alert.arrival + SHAKE_NS) {
                alert.shakeStarted = true;
                alert.shakeStart = alert.arrival;
                alert.shakeEnd = alert.arrival + SHAKE_NS;
                SHAKEN.put(entry.getKey(), now + 1_800_000_000_000L);
            }
            if (now >= alert.arrival + SHAKE_NS) it.remove();
        }
    }

    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        long now = System.nanoTime();
        advance(now);
        Active shaking = null;
        for (Active active : ALERTS.values()) {
            if (active.shakeStarted && now < active.shakeEnd
                    && (shaking == null || active.message.intensity() > shaking.message.intensity()))
                shaking = active;
        }
        if (shaking == null) return;
        double seconds = (now - shaking.shakeStart) / 1_000_000_000.0;
        event.setYaw(event.getYaw() + (float)(5.0 * Math.sin(seconds * 17.0)));
        event.setPitch(event.getPitch() + (float)(4.0 * Math.sin(seconds * 21.0 + 0.7)));
        event.setRoll(event.getRoll() + (float)(6.0 * Math.sin(seconds * 13.0 + 1.4)));
    }

    public static void render(GuiGraphics graphics, int width, int height) {
        if (Minecraft.getInstance().player == null) return;
        long now = System.nanoTime();
        advance(now);
        Active alert = null;
        for (Active a : ALERTS.values())
            if (alert == null || a.message.intensity() > alert.message.intensity()) alert = a;
        if (alert == null) return;

        AlertNetwork.Alert data = alert.message;
        int panelWidth = Math.min(width - 12, 440);
        int left = (width - panelWidth) / 2;
        int top = Math.max(8, height / 9);
        graphics.fill(left, top, left + panelWidth, top + 122, 0xDA180B0B);
        graphics.fill(left, top, left + panelWidth, top + 5, 0xFFFF3737);

        Component title = Component.translatable("text.quakerun.title");
        float titleScale = Math.min(1.7f, (panelWidth - 16f) /
                Math.max(1, Minecraft.getInstance().font.width(title)));
        graphics.pose().pushPose();
        graphics.pose().scale(titleScale, titleScale, 1f);
        graphics.drawCenteredString(Minecraft.getInstance().font, title,
                Math.round(width / (2f * titleScale)), Math.round((top + 11) / titleScale), 0xFFFFFF77);
        graphics.pose().popPose();

        long seconds = Math.max(0, (long)Math.ceil((alert.arrival - now) / 1_000_000_000.0));
        Component stage = now < alert.arrival
                ? Component.translatable("text.quakerun.arrival_in", seconds)
                : Component.translatable("text.quakerun.arrived");
        graphics.drawCenteredString(Minecraft.getInstance().font, stage,
                width / 2, top + 36, 0xFFFFFFFF);
        graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.translatable("text.quakerun.local_intensity",
                        city(data),
                        format(data.intensity())),
                width / 2, top + 53, 0xFFFFDD88);
        drawFitted(graphics, Component.translatable("text.quakerun.magnitude",
                        QuakeText.epicenter(data.place(), data.epicenterLatitude(),
                                data.epicenterLongitude()), format(data.magnitude())),
                width, panelWidth, top + 69, 0xFFFFFFFF);
        graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.translatable("text.quakerun.origin_time",
                        QuakeText.originTime(data.originEpochMs())),
                width / 2, top + 86, 0xFFFFFFFF);
        drawFitted(graphics, Component.translatable("text.quakerun.source_notice"),
                width, panelWidth, top + 103, 0xFFCCCCCC);
    }

    private static void drawFitted(GuiGraphics graphics, Component line, int width,
                                   int panelWidth, int y, int color) {
        float scale = Math.min(1f, (panelWidth - 16f) /
                Math.max(1, Minecraft.getInstance().font.width(line)));
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1f);
        graphics.drawCenteredString(Minecraft.getInstance().font, line,
                Math.round(width / (2f * scale)), Math.round(y / scale), color);
        graphics.pose().popPose();
    }

    private static Component city(AlertNetwork.Alert data) {
        String selected = Minecraft.getInstance().getLanguageManager().getSelected();
        String value = selected.startsWith("zh_") ? data.cityZh() : data.cityEn();
        if (value.isBlank()) value = data.cityZh().isBlank() ? data.cityEn() : data.cityZh();
        return value.isBlank() ? Component.translatable("text.quakerun.unknown_city") : Component.literal(value);
    }

    private static String format(double number) {
        return String.format(java.util.Locale.ROOT, "%.1f", number);
    }

    private static final class Active {
        AlertNetwork.Alert message;
        long arrival;
        long shakeStart;
        long shakeEnd;
        boolean shakeStarted;
        Active(AlertNetwork.Alert message, long arrival) { this.message = message; this.arrival = arrival; }
    }
}
