package cn.blockforge.generated.virtualmachine.computer;

import net.minecraft.text.Text;

/** 安装介质的种类：ISO 光盘 / IMG 软盘镜像。 */
public enum MediaKind {
    ISO("ISO", "gui.virtual_machine.kind.iso"),
    IMG("IMG", "gui.virtual_machine.kind.img");

    private final String shortName;
    private final String key;

    MediaKind(String shortName, String key) {
        this.shortName = shortName;
        this.key = key;
    }

    public String getShortName() {
        return this.shortName;
    }

    public Text getDisplayName() {
        return Text.translatable(this.key);
    }
}
