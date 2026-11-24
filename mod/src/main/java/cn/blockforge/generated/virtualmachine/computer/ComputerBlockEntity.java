package cn.blockforge.generated.virtualmachine.computer;

import cn.blockforge.generated.virtualmachine.VirtualMachineMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 电脑方块实体：光驱里插了什么盘、当前是开机还是蓝屏、装没装系统，全都记在这里。
 * 每 tick 推进装机和启动进度，状态变了就把版本号 +1，让 GUI 知道要刷新。
 */
public class ComputerBlockEntity extends BlockEntity {
    public static final int BOOT_TICKS = 70;
    /** 从介质引导：自检、认出光盘、读出引导扇区，大约两秒半。 */
    public static final int MEDIA_BOOT_TICKS = 50;
    public static final int MAX_NOTES = 500;
    public static final int MAX_ERROR = 120;

    private ItemStack disk = ItemStack.EMPTY;
    /** 玩家从自己电脑上选出来的 ISO：没有实体物品，挂在这台机器的光驱里。 */
    private CustomIso customDisk;
    /** 装在这台电脑里的一台虚拟机：有自己的 BIOS 和光驱，能拿盘上真实数据引导。 */
    private final VirtualMachine vm = new VirtualMachine();
    /** 硬盘里装着的系统如果来自自定义 ISO，就存一份，读档后桌面才能显示它。 */
    private CustomIso installedCustom;
    private ComputerPhase phase = ComputerPhase.OFF;
    private int progress;
    private int total;
    private int timer;
    private int version = 1;
    private InstallStep installStep = InstallStep.WELCOME;
    private String installedOs = "";
    private String notes = "";
    private String error = "";

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(VirtualMachineMod.COMPUTER_ENTITY, pos, state);
    }

    // ------------------------------------------------------------------
    // 服务端 tick：推进状态机
    // ------------------------------------------------------------------

    public void serverTick() {
        if (this.world == null || this.world.isClient) {
            return;
        }
        boolean changed = false;
        // 虚拟机是一台独立的小机器：它的引导和电脑本身的开机进度各走各的
        if (this.vm.tick()) {
            changed = true;
        }
        switch (this.phase) {
            case BOOTING -> {
                this.timer++;
                changed = true;
                if (this.timer >= BOOT_TICKS) {
                    this.phase = ComputerPhase.DESKTOP;
                    this.timer = 0;
                    this.progress = 0;
                    this.total = 0;
                    this.applyVisual();
                    this.play(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.7f, 1.4f);
                }
            }
            case MEDIA_BOOT -> {
                // 从光盘/软盘引导：自检完自己认出引导扇区，然后直接把安装程序拉起来
                this.timer++;
                changed = true;
                SystemImage booting = this.diskImage();
                if (booting == null) {
                    // 自检途中介质被抽走了：退回 BIOS 菜单，不蓝屏（这时硬盘还没被碰过）
                    this.phase = ComputerPhase.POST;
                    this.timer = 0;
                    this.applyVisual();
                } else if (this.timer >= MEDIA_BOOT_TICKS) {
                    this.beginInstall(booting);
                }
            }
            case INSTALLING -> {
                // 安装程序还停在欢迎页/协议页/分区页时硬盘一点都不动，等玩家自己按「下一步」
                if (!this.installStep.isCopying()) {
                    break;
                }
                SystemImage source = this.diskImage();
                if (source == null) {
                    this.fail(Text.translatable("gui.virtual_machine.err.media_gone").getString());
                    return;
                }
                if (this.total <= 0) {
                    this.total = source.getInstallTicks();
                }
                this.progress++;
                if (this.progress % 12 == 0) {
                    this.play(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.35f, 1.8f);
                }
                if (this.progress >= this.total) {
                    this.installedOs = source.getId();
                    this.installedCustom = source instanceof CustomIso custom ? custom : null;
                    this.notes = "";
                    this.progress = this.total;
                    this.timer = 0;
                    this.installStep = InstallStep.FINISH;
                    this.applyVisual();
                    this.play(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.9f, 1.0f);
                }
                changed = true;
            }
            default -> {
            }
        }
        if (changed) {
            this.sync();
        }
    }

    // ------------------------------------------------------------------
    // 玩家操作
    // ------------------------------------------------------------------

    public void openGui(ServerPlayerEntity player) {
        if (this.world == null || this.world.isClient) {
            return;
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inventory, p) -> new ComputerScreenHandler(syncId, inventory, this),
            Text.translatable("block.virtual_machine.computer")));
    }

    /** 把手里的安装盘塞进光驱，塞进去就自动检测、自动从盘引导安装程序。成功返回 true。 */
    public boolean tryInsertDisk(ItemStack stack, PlayerEntity player) {
        if (this.world == null || this.world.isClient) {
            return false;
        }
        SystemImage os = MediaDiskItem.osOf(stack);
        if (os == null) {
            return false;
        }
        if (!this.disk.isEmpty() || this.customDisk != null) {
            if (player != null) {
                player.sendMessage(Text.translatable("gui.virtual_machine.msg.drive_busy"), true);
            }
            return false;
        }
        if (this.phase == ComputerPhase.INSTALLING || this.phase == ComputerPhase.BOOTING
            || this.phase == ComputerPhase.MEDIA_BOOT) {
            // 机器正忙着读写，这时候驱动器是锁住的（和真机的托盘一样）
            if (player != null) {
                player.sendMessage(Text.translatable("gui.virtual_machine.msg.drive_busy"), true);
            }
            return false;
        }
        this.disk = stack.copyWithCount(1);
        stack.decrement(1);
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.8f, 0.9f);
        if (player != null) {
            player.sendMessage(Text.translatable("gui.virtual_machine.msg.inserted", os.getDisplayName()), true);
        }
        this.autoDetectMedia(os, player);
        this.sync();
        return true;
    }

    /**
     * 玩家在自己电脑上选了 ISO 并按了「选择你自己电脑的ISO」：把这台机器当光驱，
     * 把那个镜像挂上去，然后跟插了真盘一样自动引导。
     * 返回 false 表示驱动器正忙 / 里面已经有盘了。
     */
    public boolean mountCustomIso(CustomIso iso, PlayerEntity player) {
        if (this.world == null || this.world.isClient || iso == null) {
            return false;
        }
        if (!this.disk.isEmpty() || this.customDisk != null) {
            if (player != null) {
                player.sendMessage(Text.translatable("gui.virtual_machine.msg.drive_busy"), true);
            }
            return false;
        }
        if (this.phase == ComputerPhase.INSTALLING || this.phase == ComputerPhase.BOOTING
            || this.phase == ComputerPhase.MEDIA_BOOT) {
            if (player != null) {
                player.sendMessage(Text.translatable("gui.virtual_machine.msg.drive_busy"), true);
            }
            return false;
        }
        this.customDisk = iso;
        CustomIso.register(iso);
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.8f, 0.9f);
        if (player != null) {
            player.sendMessage(Text.translatable("gui.virtual_machine.msg.iso_mounted", iso.summary()), true);
        }
        this.autoDetectMedia(iso, player);
        this.sync();
        return true;
    }

    /**
     * 插入介质之后机器自己干的事，跟真机一样：
     * 关机 -> 自己开机；开着 -> 认出盘里的安装程序，直接从盘引导。
     */
    private void autoDetectMedia(SystemImage os, PlayerEntity player) {
        // 装在这台电脑里的虚拟机也跟着认出这张盘，并且用盘上的真实数据走一遍引导
        this.vm.boot(os);
        if (this.phase == ComputerPhase.OFF) {
            this.phase = ComputerPhase.POST;
            this.applyVisual();
        }
        if (os.getId().equals(this.installedOs)) {
            if (player != null) {
                player.sendMessage(Text.translatable("gui.virtual_machine.msg.already_installed", os.getDisplayName()), true);
            }
            this.sync();
            return;
        }
        if (this.phase == ComputerPhase.POST || this.phase == ComputerPhase.DESKTOP
            || this.phase == ComputerPhase.ERROR) {
            this.startMediaBoot(os, player);
        }
    }

    public void handleAction(PlayerEntity player, String action, String arg) {
        switch (action) {
            case "power_on" -> this.powerOn(player);
            case "power_off" -> this.powerOff();
            case "install" -> this.startInstall(player);
            case "wizard_next" -> this.nextSetupStep();
            case "wizard_back" -> this.previousSetupStep();
            case "boot" -> this.bootOs();
            case "eject" -> this.eject(player);
            case "insert" -> this.insertHeldDisk(player);
            case "cancel" -> this.cancelInstall();
            case "reboot" -> {
                this.error = "";
                this.timer = 0;
                this.progress = 0;
                this.total = 0;
                this.installStep = InstallStep.WELCOME;
                SystemImage bootable = this.diskImage();
                if (!this.installedOs.isEmpty()) {
                    // 硬盘里已经有系统：按 BIOS 的启动顺序先试硬盘
                    this.phase = ComputerPhase.BOOTING;
                } else if (bootable != null) {
                    // 硬盘空空如也：还插着安装盘的话，重启后自己再从盘引导
                    this.phase = ComputerPhase.MEDIA_BOOT;
                } else {
                    this.phase = ComputerPhase.POST;
                }
                this.applyVisual();
                this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.7f, 1.2f);
                this.sync();
            }
            case "format" -> {
                this.installedOs = "";
                this.installedCustom = null;
                this.notes = "";
                this.error = "";
                this.timer = 0;
                this.phase = ComputerPhase.POST;
                this.applyVisual();
                this.play(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.8f, 0.7f);
                this.sync();
            }
            case "notes" -> {
                String text = arg == null ? "" : arg;
                this.notes = text.length() > MAX_NOTES ? text.substring(0, MAX_NOTES) : text;
                this.sync();
            }
            case "vm_boot" -> this.vmBoot();
            case "vm_halt" -> {
                this.vm.halt();
                this.sync();
            }
            case "vm_cmd" -> this.vmCommand(player, arg);
            default -> {
            }
        }
    }

    private void powerOn(PlayerEntity player) {
        if (this.phase != ComputerPhase.OFF && this.phase != ComputerPhase.ERROR) {
            return;
        }
        this.timer = 0;
        this.progress = 0;
        this.total = 0;
        this.error = "";
        SystemImage bootable = this.diskImage();
        if (bootable != null && this.installedOs.isEmpty()) {
            // 硬盘里没系统、驱动器里又有盘：BIOS 直接从盘引导，不用人管
            this.startMediaBoot(bootable, player);
            return;
        }
        this.phase = ComputerPhase.POST;
        this.applyVisual();
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.8f, 1.0f);
        this.sync();
    }

    private void powerOff() {
        this.phase = ComputerPhase.OFF;
        this.timer = 0;
        this.progress = 0;
        this.total = 0;
        this.error = "";
        this.applyVisual();
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7f, 0.8f);
        this.sync();
    }

    private void startInstall(PlayerEntity player) {
        if (this.phase != ComputerPhase.POST && this.phase != ComputerPhase.DESKTOP) {
            return;
        }
        SystemImage source = this.diskImage();
        if (source == null) {
            this.fail(Text.translatable("gui.virtual_machine.err.no_media").getString());
            return;
        }
        this.startMediaBoot(source, player);
    }

    /** 认出介质、从它引导：这是「插入即自动安装」的第一步，玩家什么都不用按。 */
    private void startMediaBoot(SystemImage source, PlayerEntity player) {
        this.phase = ComputerPhase.MEDIA_BOOT;
        this.timer = 0;
        this.progress = 0;
        this.total = 0;
        this.installStep = InstallStep.WELCOME;
        this.error = "";
        this.applyVisual();
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.8f, 0.8f);
        if (player != null) {
            player.sendMessage(Text.translatable("gui.virtual_machine.msg.detected", source.getDisplayName()), true);
        }
        this.sync();
    }

    /** 引导结束，安装程序自己弹出来（还是停在欢迎页，剩下的交给玩家点）。 */
    private void beginInstall(SystemImage source) {
        this.phase = ComputerPhase.INSTALLING;
        this.installStep = InstallStep.WELCOME;
        this.progress = 0;
        this.total = source.getInstallTicks();
        this.timer = 0;
        this.error = "";
        this.applyVisual();
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.9f, 1.5f);
        this.sync();
    }

    /** 给虚拟机开机（或者重新引导）：拿光驱里现在的盘走一遍真实的引导过程。 */
    private void vmBoot() {
        this.vm.boot(this.diskImage());
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.6f, 1.6f);
        this.sync();
    }

    /**
     * 在虚拟机里敲一条命令。要是敲的是 install，就把这张盘交给电脑的安装程序，
     * 虚拟机保持开机，装的就是它光驱里那张盘。
     */
    private void vmCommand(PlayerEntity player, String raw) {
        SystemImage media = this.diskImage();
        boolean install = this.vm.command(raw, media);
        this.sync();
        if (install && media != null && this.phase != ComputerPhase.INSTALLING) {
            this.startMediaBoot(media, player);
        }
    }

    /** 界面里点驱动器槽：把手里拿着的 ISO / IMG 插进去。 */
    private void insertHeldDisk(PlayerEntity player) {
        if (player == null) {
            return;
        }
        ItemStack main = player.getMainHandStack();
        if (MediaDiskItem.osOf(main) != null) {
            this.tryInsertDisk(main, player);
            return;
        }
        ItemStack off = player.getOffHandStack();
        if (MediaDiskItem.osOf(off) != null) {
            this.tryInsertDisk(off, player);
        }
    }

    /** 安装向导的「下一步」。走到「复制文件」那一页才开始真正写盘。 */
    private void nextSetupStep() {
        if (this.phase != ComputerPhase.INSTALLING || this.installStep.isFinished()) {
            return;
        }
        SystemImage source = this.diskImage();
        if (source == null) {
            this.fail(Text.translatable("gui.virtual_machine.err.media_gone").getString());
            return;
        }
        InstallStep from = this.installStep;
        InstallStep to = from.next();
        if (to == from) {
            return;
        }
        if (to == InstallStep.COPYING) {
            // 这才真的开始写硬盘：进度归零，文件从头复制
            this.progress = 0;
            this.total = source.getInstallTicks();
            this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.9f, 1.3f);
        } else {
            this.play(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.45f, 1.7f);
        }
        this.installStep = to;
        this.sync();
    }

    /** 安装向导的「上一步」。已经开始复制文件就不能往回退。 */
    private void previousSetupStep() {
        if (this.phase != ComputerPhase.INSTALLING || !this.installStep.canGoBack()) {
            return;
        }
        this.installStep = this.installStep.previous();
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.45f, 1.4f);
        this.sync();
    }

    private void bootOs() {
        if (this.installedOs.isEmpty()) {
            this.fail(Text.translatable("gui.virtual_machine.err.no_system").getString());
            return;
        }
        this.phase = ComputerPhase.BOOTING;
        this.timer = 0;
        this.progress = 0;
        this.total = 0;
        this.error = "";
        this.applyVisual();
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 0.8f, 1.1f);
        this.sync();
    }

    private void cancelInstall() {
        if (this.phase != ComputerPhase.INSTALLING) {
            return;
        }
        this.phase = ComputerPhase.POST;
        this.installStep = InstallStep.WELCOME;
        this.progress = 0;
        this.total = 0;
        this.timer = 0;
        this.applyVisual();
        this.sync();
    }

    private void eject(PlayerEntity player) {
        // 盘一拔，装在这台电脑里的虚拟机里那张盘也就没了
        if (this.vm.getState() != VirtualMachine.State.OFF) {
            SystemImage current = this.diskImage();
            this.vm.onMediaEjected(current == null ? "" : current.getDisplayName());
        }
        if (this.customDisk != null) {
            // 虚拟 ISO 没有物品可以还给你，直接卸载
            boolean writing = this.phase == ComputerPhase.INSTALLING && this.installStep.isCopying();
            this.customDisk = null;
            if (writing) {
                this.fail(Text.translatable("gui.virtual_machine.err.media_removed_mid").getString());
                return;
            }
            if (this.phase == ComputerPhase.INSTALLING || this.phase == ComputerPhase.MEDIA_BOOT) {
                this.phase = ComputerPhase.POST;
                this.installStep = InstallStep.WELCOME;
                this.progress = 0;
                this.total = 0;
                this.timer = 0;
                this.applyVisual();
            }
            this.play(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.8f, 1.2f);
            this.sync();
            return;
        }
        if (this.disk.isEmpty()) {
            return;
        }
        boolean copying = this.phase == ComputerPhase.INSTALLING && this.installStep.isCopying();
        ItemStack out = this.disk;
        this.disk = ItemStack.EMPTY;
        if (player != null && !this.world.isClient) {
            player.getInventory().offerOrDrop(out);
        }
        if (copying) {
            // 写盘写到一半把盘抽走，真机就是这么蓝屏的
            this.fail(Text.translatable("gui.virtual_machine.err.media_removed_mid").getString());
            return;
        }
        if (this.phase == ComputerPhase.INSTALLING || this.phase == ComputerPhase.MEDIA_BOOT) {
            this.phase = ComputerPhase.POST;
            this.installStep = InstallStep.WELCOME;
            this.progress = 0;
            this.total = 0;
            this.timer = 0;
            this.applyVisual();
        }
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.8f, 1.2f);
        this.sync();
    }

    private void fail(String message) {
        this.error = message == null ? "" : (message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message);
        this.phase = ComputerPhase.ERROR;
        this.progress = 0;
        this.total = 0;
        this.timer = 0;
        this.applyVisual();
        this.play(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1.0f, 0.6f);
        this.sync();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void applyVisual() {
        if (this.world == null || this.world.isClient) {
            return;
        }
        ComputerVisual visual = switch (this.phase) {
            case OFF -> ComputerVisual.OFF;
            case ERROR -> ComputerVisual.ERROR;
            case DESKTOP -> ComputerVisual.DESKTOP;
            default -> ComputerVisual.BOOT;
        };
        BlockState state = this.world.getBlockState(this.pos);
        if (state.getBlock() instanceof ComputerBlock && state.get(ComputerBlock.SCREEN) != visual) {
            this.world.setBlockState(this.pos, state.with(ComputerBlock.SCREEN, visual), Block.NOTIFY_LISTENERS);
        }
    }

    private void play(SoundEvent event, float volume, float pitch) {
        if (this.world != null && !this.world.isClient) {
            this.world.playSound(null, this.pos, event, SoundCategory.BLOCKS, volume, pitch);
        }
    }

    /** 状态有任何变化都调这个：版本号 +1，让 GUI 重新拉一次状态。 */
    public void sync() {
        this.version++;
        this.markDirty();
    }

    // ------------------------------------------------------------------
    // getter
    // ------------------------------------------------------------------

    public ItemStack getDisk() {
        return this.disk;
    }

    /** 光驱里现在是什么介质：实体安装盘或玩家自己选的 ISO，空的返回 null。 */
    public SystemImage diskImage() {
        if (this.customDisk != null) {
            return this.customDisk;
        }
        return MediaDiskItem.osOf(this.disk);
    }

    /** 硬盘里装着的系统，没装返回 null。 */
    public SystemImage installedImage() {
        if (this.installedCustom != null) {
            return this.installedCustom;
        }
        return OsType.byId(this.installedOs);
    }

    public boolean hasCustomDisk() {
        return this.customDisk != null;
    }

    public ComputerPhase getPhase() {
        return this.phase;
    }

    public int getProgress() {
        return this.progress;
    }

    public int getTotal() {
        return this.total;
    }

    public int getTimer() {
        return this.timer;
    }

    public int getVersion() {
        return this.version;
    }

    public String getInstalledOs() {
        return this.installedOs;
    }

    public String getNotes() {
        return this.notes;
    }

    public String getError() {
        return this.error;
    }

    public String getDiskOsId() {
        SystemImage os = this.diskImage();
        return os == null ? "" : os.getId();
    }

    public ComputerState snapshot() {
        return new ComputerState(this.pos, this.phase, this.progress, this.total, this.timer,
            this.installStep.ordinal(), this.installedOs, this.getDiskOsId(), this.notes, this.error,
            this.version, this.vm.getState().ordinal(), this.vm.logText());
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.putString("Phase", this.phase.name());
        nbt.putString("InstallStep", this.installStep.name());
        nbt.putInt("Progress", this.progress);
        nbt.putInt("Total", this.total);
        nbt.putInt("Timer", this.timer);
        nbt.putInt("Version", this.version);
        nbt.putString("InstalledOs", this.installedOs);
        nbt.putString("Notes", this.notes);
        nbt.putString("Error", this.error);
        nbt.putString("DiskOs", this.getDiskOsId());
        if (this.customDisk != null) {
            nbt.put("CustomDisk", this.customDisk.toNbt());
        }
        if (this.installedCustom != null) {
            nbt.put("InstalledCustom", this.installedCustom.toNbt());
        }
        this.vm.writeNbt(nbt);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        this.phase = ComputerPhase.byOrdinal(indexOfPhase(nbt.getString("Phase")));
        this.installStep = InstallStep.byOrdinal(indexOfStep(nbt.getString("InstallStep")));
        this.progress = nbt.getInt("Progress");
        this.total = nbt.getInt("Total");
        this.timer = nbt.getInt("Timer");
        this.version = Math.max(1, nbt.getInt("Version"));
        this.installedOs = nbt.getString("InstalledOs");
        this.notes = nbt.getString("Notes");
        this.error = nbt.getString("Error");
        this.customDisk = nbt.contains("CustomDisk") ? CustomIso.fromNbt(nbt.getCompound("CustomDisk")) : null;
        this.installedCustom = nbt.contains("InstalledCustom")
            ? CustomIso.fromNbt(nbt.getCompound("InstalledCustom")) : null;
        OsType diskType = OsType.byId(nbt.getString("DiskOs"));
        this.disk = diskType == null ? ItemStack.EMPTY : MediaDiskItem.stackFor(diskType);
        this.vm.readNbt(nbt);
    }

    private static int indexOfPhase(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        for (ComputerPhase value : ComputerPhase.values()) {
            if (value.name().equals(name)) {
                return value.ordinal();
            }
        }
        return 0;
    }

    private static int indexOfStep(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        for (InstallStep value : InstallStep.values()) {
            if (value.name().equals(name)) {
                return value.ordinal();
            }
        }
        return 0;
    }

    /** 方块被拆掉时把光驱里的盘还给玩家。 */
    public void dropDisk(World world, BlockPos pos) {
        this.vm.halt();
        if (!this.disk.isEmpty()) {
            Block.dropStack(world, pos, this.disk);
            this.disk = ItemStack.EMPTY;
        }
        this.customDisk = null;
    }
}
