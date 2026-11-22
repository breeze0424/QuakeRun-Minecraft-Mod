package com.cmcplugins.quakerun;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public final class QuakeCommands {
    private QuakeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Supplier<QuakeServer> service) {
        dispatcher.register(Commands.literal("quakerun")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    QuakeServer running = service.get();
                    if (running == null) {
                        context.getSource().sendFailure(Component.translatable(
                                "message.quakerun.service_unavailable"));
                        return 0;
                    }
                    String locale = context.getSource().getEntity() instanceof ServerPlayer player
                            ? player.getLanguage() : "en_us";
                    context.getSource().sendSuccess(() -> running.status(locale), false);
                    return 1;
                })));
    }
}
