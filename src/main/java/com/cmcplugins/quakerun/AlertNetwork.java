package com.cmcplugins.quakerun;

import com.cmcplugins.quakerun.client.ClientAlerts;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;
import net.minecraftforge.network.NetworkEvent;

public final class AlertNetwork {
    private static final String VERSION = "4";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(QuakeRunMod.MOD_ID, "alerts"), () -> VERSION,
            VERSION::equals, VERSION::equals);

    private AlertNetwork() {}

    public record Alert(String id, boolean cancel, String place,
                        double epicenterLatitude, double epicenterLongitude, long originEpochMs,
                        String cityZh, String cityEn, double magnitude, double intensity,
                        long arrivalDelayMs) {
        static void encode(Alert msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.id, 100);
            buf.writeBoolean(msg.cancel);
            buf.writeUtf(msg.place, 100);
            buf.writeDouble(msg.epicenterLatitude);
            buf.writeDouble(msg.epicenterLongitude);
            buf.writeLong(msg.originEpochMs);
            buf.writeUtf(msg.cityZh, 80);
            buf.writeUtf(msg.cityEn, 80);
            buf.writeDouble(msg.magnitude);
            buf.writeDouble(msg.intensity);
            buf.writeLong(msg.arrivalDelayMs);
        }
        static Alert decode(FriendlyByteBuf buf) {
            return new Alert(buf.readUtf(100), buf.readBoolean(),
                    buf.readUtf(100), buf.readDouble(), buf.readDouble(), buf.readLong(),
                    buf.readUtf(80), buf.readUtf(80),
                    buf.readDouble(), buf.readDouble(), buf.readLong());
        }
    }

    public static void register() {
        CHANNEL.registerMessage(0, Alert.class, Alert::encode, Alert::decode,
                (message, context) -> handle(message, context));
    }

    private static void handle(Alert message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientAlerts.receive(message)));
        context.get().setPacketHandled(true);
    }

    public static void send(ServerPlayer player, Alert packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
