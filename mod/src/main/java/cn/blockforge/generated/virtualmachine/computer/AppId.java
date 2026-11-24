package cn.blockforge.generated.virtualmachine.computer;

import net.minecraft.text.Text;

/** 系统里能装的小程序。 */
public enum AppId {
    FILES("gui.virtual_machine.app.files", "F"),
    TERMINAL("gui.virtual_machine.app.terminal", ">"),
    NOTES("gui.virtual_machine.app.notes", "N"),
    ABOUT("gui.virtual_machine.app.about", "i");

    private final String key;
    private final String iconLetter;

    AppId(String key, String iconLetter) {
        this.key = key;
        this.iconLetter = iconLetter;
    }

    public Text getDisplayName() {
        return Text.translatable(this.key);
    }

    /** 图标上画的那个字符。 */
    public String getIconLetter() {
        return this.iconLetter;
    }
}
