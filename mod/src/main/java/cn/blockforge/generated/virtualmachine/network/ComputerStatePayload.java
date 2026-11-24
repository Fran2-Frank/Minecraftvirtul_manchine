package cn.blockforge.generated.virtualmachine.network;

import cn.blockforge.generated.virtualmachine.VirtualMachineMod;
import cn.blockforge.generated.virtualmachine.computer.ComputerPhase;
import cn.blockforge.generated.virtualmachine.computer.ComputerState;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 服务端 -> 客户端：电脑当前状态的完整快照。
 */
public record ComputerStatePayload(ComputerState state) implements CustomPayload {
    public static final CustomPayload.Id<ComputerStatePayload> ID =
        new CustomPayload.Id<>(Identifier.of(VirtualMachineMod.MOD_ID, "state"));

    public static final PacketCodec<RegistryByteBuf, ComputerStatePayload> CODEC = PacketCodec.of(
        (payload, buf) -> {
            ComputerState s = payload.state;
            buf.writeBlockPos(s.pos());
            buf.writeByte(s.phase().ordinal());
            buf.writeVarInt(s.progress());
            buf.writeVarInt(s.total());
            buf.writeVarInt(s.timer());
            buf.writeVarInt(s.installStep());
            buf.writeVarInt(Math.max(0, s.version()));
            buf.writeString(s.installedOs(), 32);
            buf.writeString(s.diskOs(), 32);
            buf.writeString(s.notes(), 512);
            buf.writeString(s.error(), 160);
            buf.writeByte(s.vmState());
            buf.writeString(s.vmLog() == null ? "" : s.vmLog(), 4096);
        },
        buf -> {
            BlockPos pos = buf.readBlockPos();
            int phase = buf.readByte();
            int progress = buf.readVarInt();
            int total = buf.readVarInt();
            int timer = buf.readVarInt();
            int installStep = buf.readVarInt();
            int version = buf.readVarInt();
            String installed = buf.readString(32);
            String disk = buf.readString(32);
            String notes = buf.readString(512);
            String error = buf.readString(160);
            int vmState = buf.readByte();
            String vmLog = buf.readString(4096);
            return new ComputerStatePayload(new ComputerState(
                pos, ComputerPhase.byOrdinal(phase), progress, total, timer, installStep,
                installed, disk, notes, error, version, vmState, vmLog));
        });

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
