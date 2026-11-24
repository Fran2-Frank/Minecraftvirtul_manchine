package cn.blockforge.generated.virtualmachine.computer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 安装介质：一张 ISO 光盘或者一张 IMG 软盘。
 * 拿到电脑上右键就插进光驱/软驱，然后就能在 BIOS 里装机。
 */
public class MediaDiskItem extends Item {
    private static final Map<OsType, MediaDiskItem> BY_OS = new EnumMap<>(OsType.class);

    private final OsType os;

    public MediaDiskItem(OsType os, Settings settings) {
        super(settings);
        this.os = os;
        BY_OS.put(os, this);
    }

    public OsType getOs() {
        return this.os;
    }

    public static ItemStack stackFor(OsType os) {
        MediaDiskItem item = BY_OS.get(os);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    public static MediaDiskItem ofOs(OsType os) {
        return BY_OS.get(os);
    }

    /** 从物品栈读出它是哪个系统的安装盘，不是安装盘就返回 null。 */
    public static OsType osOf(ItemStack stack) {
        if (stack != null && stack.getItem() instanceof MediaDiskItem disk) {
            return disk.os;
        }
        return null;
    }

    /** 这个系统能不能用这种介质装。 */
    public static boolean matchesMedia(OsType os, MediaKind kind) {
        return os != null && os.getMedia() == kind;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(this.os.getMedia().getDisplayName().copy().formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("gui.virtual_machine.disk.for_os", this.os.getDisplayName())
            .formatted(Formatting.AQUA));
        tooltip.add(Text.translatable("gui.virtual_machine.disk.howto").formatted(Formatting.DARK_GRAY));
    }
}
