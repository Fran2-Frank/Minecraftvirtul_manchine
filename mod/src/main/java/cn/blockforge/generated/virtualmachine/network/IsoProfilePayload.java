package cn.blockforge.generated.virtualmachine.network;

import cn.blockforge.generated.virtualmachine.VirtualMachineMod;
import cn.blockforge.generated.virtualmachine.computer.CustomIso;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端 -> 客户端：这台电脑上挂着的自定义 ISO 的完整资料。
 * 只在镜像换了的时候发一次，之后状态包只带一个 id，客户端按 id 查。
 */
public record IsoProfilePayload(CustomIso image) implements CustomPayload {
    public static final CustomPayload.Id<IsoProfilePayload> ID =
        new CustomPayload.Id<>(Identifier.of(VirtualMachineMod.MOD_ID, "iso_profile"));

    public static final PacketCodec<RegistryByteBuf, IsoProfilePayload> CODEC = PacketCodec.of(
        (payload, buf) -> payload.image.write(buf),
        buf -> new IsoProfilePayload(CustomIso.read(buf)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
