package cn.blockforge.generated.virtualmachine.computer;

/**
 * 安装程序（Setup）的向导页。真实机器上的安装程序也是一页一页走的：
 * 欢迎 -> 许可协议 -> 选择分区 -> 复制文件 -> 完成。
 */
public enum InstallStep {
    /** 欢迎页：介绍要装的系统、介质和硬盘。 */
    WELCOME,
    /** 许可协议：看完按「下一步」表示同意。 */
    LICENSE,
    /** 选择安装位置：目前只有一块 C: 主盘。 */
    PARTITION,
    /** 复制文件：进度条在这里才真的开始走。 */
    COPYING,
    /** 装完了，等玩家按「立即重启」。 */
    FINISH;

    private static final InstallStep[] VALUES = values();

    public static InstallStep byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : WELCOME;
    }

    /** 只有这一页会让硬盘进度往前走。 */
    public boolean isCopying() {
        return this == COPYING;
    }

    /** 系统已经写进硬盘，只差重启。 */
    public boolean isFinished() {
        return this == FINISH;
    }

    /** 这一页能不能按「上一步」。 */
    public boolean canGoBack() {
        return this == LICENSE || this == PARTITION;
    }

    /** 下一页；最后一页返回自己。 */
    public InstallStep next() {
        return switch (this) {
            case WELCOME -> LICENSE;
            case LICENSE -> PARTITION;
            case PARTITION -> COPYING;
            default -> this;
        };
    }

    /** 上一页；第一页返回自己。 */
    public InstallStep previous() {
        return switch (this) {
            case LICENSE -> WELCOME;
            case PARTITION -> LICENSE;
            default -> this;
        };
    }
}
