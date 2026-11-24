package cn.blockforge.generated.virtualmachine.computer;

import net.minecraft.util.math.BlockPos;

/**
 * 电脑的完整状态快照，服务端每帧变化时打包发给正在看屏幕的玩家。
 */
public record ComputerState(
    BlockPos pos,
    ComputerPhase phase,
    int progress,
    int total,
    int timer,
    int installStep,
    String installedOs,
    String diskOs,
    String notes,
    String error,
    int version,
    /** 装在电脑里的虚拟机状态：0 关机、1 正在引导、2 运行中（见 VirtualMachine.State）。 */
    int vmState,
    /** 虚拟机屏幕上的一整段日志，按行用 \n 隔开。 */
    String vmLog
) {
    public static final ComputerState EMPTY =
        new ComputerState(BlockPos.ORIGIN, ComputerPhase.OFF, 0, 0, 0, 0, "", "", "", "", -1, 0, "");

    public boolean hasInstalledOs() {
        return !this.installedOs.isEmpty();
    }

    public boolean hasDisk() {
        return !this.diskOs.isEmpty();
    }

    /** 硬盘里装着的系统；没装就是 null。自带系统和玩家自己选的 ISO 都在这里。 */
    public SystemImage installedImage() {
        return SystemImage.byId(this.installedOs);
    }

    /** 驱动器里的介质；空的就是 null。 */
    public SystemImage diskImage() {
        return SystemImage.byId(this.diskOs);
    }

    /** 安装程序当前停在哪一页。 */
    public InstallStep setupStep() {
        return InstallStep.byOrdinal(this.installStep);
    }

    public float installFraction() {
        if (this.total <= 0) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, (float) this.progress / (float) this.total));
    }
}
