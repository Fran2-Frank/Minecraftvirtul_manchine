package cn.blockforge.generated.virtualmachine.network;

import cn.blockforge.generated.virtualmachine.VirtualMachineMod;
import cn.blockforge.generated.virtualmachine.computer.CustomIso;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 客户端 -> 服务端：玩家在界面里按了「选择你自己电脑的ISO」并选好了一个镜像。
 * 客户端已经把 ISO 的卷标、大小、目录列表读出来了，打包发给服务端挂到电脑上。
 */
public record IsoMountPayload(BlockPos pos, CustomIso image) implements CustomPayload {
    public static final CustomPayload.Id<IsoMountPayload> ID =
        new CustomPayload.Id<>(Identifier.of(VirtualMachineMod.MOD_ID, "mount_iso"));

    public static final PacketCodec<RegistryByteBuf, IsoMountPayload> CODEC = PacketCodec.of(
        (payload, buf) -> {
            buf.writeBlockPos(payload.pos);
            payload.image.write(buf);
        },
        buf -> new IsoMountPayload(buf.readBlockPos(), CustomIso.read(buf)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
