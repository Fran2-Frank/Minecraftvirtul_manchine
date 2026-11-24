package cn.blockforge.generated.virtualmachine;

import cn.blockforge.generated.virtualmachine.client.ComputerScreen;
import cn.blockforge.generated.virtualmachine.computer.ComputerScreenHandler;
import cn.blockforge.generated.virtualmachine.computer.CustomIso;
import cn.blockforge.generated.virtualmachine.network.ComputerStatePayload;
import cn.blockforge.generated.virtualmachine.network.IsoProfilePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

/** 客户端入口：注册界面和状态包的接收器。 */
public class VirtualMachineClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HandledScreens.register(VirtualMachineMod.COMPUTER_SCREEN_HANDLER, ComputerScreen::new);

        ClientPlayNetworking.registerGlobalReceiver(IsoProfilePayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> CustomIso.register(payload.image()));
        });

        ClientPlayNetworking.registerGlobalReceiver(ComputerStatePayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                if (client.player != null
                    && client.player.currentScreenHandler instanceof ComputerScreenHandler handler) {
                    handler.applyState(payload.state());
                }
            });
        });
    }
}
