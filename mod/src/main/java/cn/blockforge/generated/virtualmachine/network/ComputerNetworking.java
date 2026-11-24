package cn.blockforge.generated.virtualmachine.network;

import cn.blockforge.generated.virtualmachine.computer.ComputerScreenHandler;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/** 网络包注册（两端都要跑的那部分）。 */
public final class ComputerNetworking {
    private ComputerNetworking() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(ComputerActionPayload.ID, ComputerActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(IsoMountPayload.ID, IsoMountPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ComputerStatePayload.ID, ComputerStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(IsoProfilePayload.ID, IsoProfilePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ComputerActionPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                if (player.isRemoved()) {
                    return;
                }
                if (player.currentScreenHandler instanceof ComputerScreenHandler handler
                    && handler.getPos().equals(payload.pos())) {
                    handler.handleAction(payload.action(), payload.arg());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(IsoMountPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                if (player.isRemoved()) {
                    return;
                }
                if (player.currentScreenHandler instanceof ComputerScreenHandler handler
                    && handler.getPos().equals(payload.pos())) {
                    handler.mountIso(payload.image());
                }
            });
        });
    }
}
