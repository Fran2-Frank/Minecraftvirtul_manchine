package cn.blockforge.generated.virtualmachine.computer;

/**
 * 电脑当前所处的阶段。整台机器就是一台很小很小的状态机：
 * 关机 -> 自检(BIOS) -> 从介质引导 -> 装机 -> 启动 -> 桌面，出错时进 ERROR。
 */
public enum ComputerPhase {
    /** 关机。 */
    OFF,
    /** BIOS / 自检菜单，装机、启动、弹仓都在这里操作。 */
    POST,
    /** 检测到可启动介质，正在从光盘/软盘引导安装程序（真机的「Boot from CD-ROM」）。 */
    MEDIA_BOOT,
    /** 正在把光盘/镜像里的系统写进硬盘。 */
    INSTALLING,
    /** 正在从硬盘启动。 */
    BOOTING,
    /** 桌面。 */
    DESKTOP,
    /** 蓝屏。 */
    ERROR;

    private static final ComputerPhase[] VALUES = values();

    public static ComputerPhase byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : OFF;
    }
}
