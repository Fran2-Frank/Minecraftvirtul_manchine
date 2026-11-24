package cn.blockforge.generated.virtualmachine.computer;

import net.minecraft.util.StringIdentifiable;

/**
 * 方块状态里的「屏幕画面」变体，用来让方块正面换成不同的贴图。
 * 名字会直接写进 blockstates 的 json，全部小写。
 */
public enum ComputerVisual implements StringIdentifiable {
    OFF("off"),
    BOOT("boot"),
    DESKTOP("desktop"),
    ERROR("error");

    private final String name;

    ComputerVisual(String name) {
        this.name = name;
    }

    @Override
    public String asString() {
        return this.name;
    }
}
