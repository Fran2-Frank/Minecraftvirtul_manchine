package cn.blockforge.generated.virtualmachine.computer;

import cn.blockforge.generated.virtualmachine.VirtualMachineMod;
import cn.blockforge.generated.virtualmachine.network.ComputerStatePayload;
import cn.blockforge.generated.virtualmachine.network.IsoProfilePayload;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 电脑的「容器」。里面没有物品格子——装盘是直接对着电脑右键插的，
 * 所以这个类只干两件事：知道玩家在看哪台电脑，以及把电脑状态推给客户端。
 */
public class ComputerScreenHandler extends ScreenHandler {
    private final PlayerEntity owner;
    private final ComputerBlockEntity computer;
    private final BlockPos pos;

    private ComputerState state;
    private int lastSentVersion = -1;
    /** 这个玩家已经收到的自定义镜像资料，避免每次状态刷新都重发一遍。 */
    private final Set<String> sentProfiles = new HashSet<>();

    /** 客户端用的构造函数（由 ScreenHandlerType 调用，此时还不知道坐标，等状态包过来）。 */
    public ComputerScreenHandler(int syncId, PlayerInventory inventory) {
        this(syncId, inventory, null, BlockPos.ORIGIN);
    }

    /** 服务端用的构造函数。 */
    public ComputerScreenHandler(int syncId, PlayerInventory inventory, ComputerBlockEntity computer) {
        this(syncId, inventory, computer, computer.getPos());
    }

    private ComputerScreenHandler(int syncId, PlayerInventory inventory, ComputerBlockEntity computer, BlockPos pos) {
        super(VirtualMachineMod.COMPUTER_SCREEN_HANDLER, syncId);
        this.owner = inventory.player;
        this.computer = computer;
        this.pos = pos;
    }

    public BlockPos getPos() {
        return this.state != null ? this.state.pos() : this.pos;
    }

    public ComputerState getState() {
        return this.state;
    }

    public void applyState(ComputerState newState) {
        this.state = newState;
    }

    /** 服务端定时调用：状态版本变了就推一包给玩家。 */
    public void pushState() {
        if (this.computer == null || this.computer.isRemoved() || !(this.owner instanceof ServerPlayerEntity player)) {
            return;
        }
        int version = this.computer.getVersion();
        if (version == this.lastSentVersion) {
            return;
        }
        this.lastSentVersion = version;
        this.pushProfiles(player);
        ServerPlayNetworking.send(player, new ComputerStatePayload(this.computer.snapshot()));
    }

    /**
     * 状态包里只带镜像 id，自定义 ISO 的具体资料（卷标、文件列表）单独发一次。
     * 先发资料再发状态，客户端按顺序收到，画界面时 id 就能查到了。
     */
    private void pushProfiles(ServerPlayerEntity player) {
        for (SystemImage image : new SystemImage[]{this.computer.diskImage(), this.computer.installedImage()}) {
            if (image instanceof CustomIso iso && this.sentProfiles.add(iso.getId())) {
                ServerPlayNetworking.send(player, new IsoProfilePayload(iso));
            }
        }
    }

    /** 玩家在自己电脑上选了 ISO：交给方块实体挂载（服务端说了算）。 */
    public void mountIso(CustomIso iso) {
        if (this.computer != null && !this.computer.isRemoved()) {
            this.computer.mountCustomIso(iso, this.owner);
        }
    }

    public void handleAction(String action, String arg) {
        if (this.computer != null && !this.computer.isRemoved()) {
            this.computer.handleAction(this.owner, action, arg);
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        if (this.computer == null) {
            return true;
        }
        if (this.computer.isRemoved() || this.computer.getWorld() == null) {
            return false;
        }
        BlockPos center = this.computer.getPos();
        return player.squaredDistanceTo(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5) <= 64.0;
    }
}
