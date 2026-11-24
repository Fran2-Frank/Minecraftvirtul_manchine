package cn.blockforge.generated.virtualmachine.network;

import cn.blockforge.generated.virtualmachine.VirtualMachineMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 客户端 -> 服务端：玩家在电脑界面里按了某个按钮 / 敲了某条命令。
 */
public record ComputerActionPayload(BlockPos pos, String action, String arg) implements CustomPayload {
    public static final CustomPayload.Id<ComputerActionPayload> ID =
        new CustomPayload.Id<>(Identifier.of(VirtualMachineMod.MOD_ID, "action"));

    public static final PacketCodec<RegistryByteBuf, ComputerActionPayload> CODEC = PacketCodec.of(
        (payload, buf) -> {
            buf.writeBlockPos(payload.pos);
            buf.writeString(payload.action, 32);
            buf.writeString(payload.arg, 512);
        },
        buf -> new ComputerActionPayload(buf.readBlockPos(), buf.readString(32), buf.readString(512)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
