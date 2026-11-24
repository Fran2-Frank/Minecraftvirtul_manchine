package cn.blockforge.generated.virtualmachine.client;

import cn.blockforge.generated.virtualmachine.computer.AppId;
import cn.blockforge.generated.virtualmachine.computer.ComputerPhase;
import cn.blockforge.generated.virtualmachine.computer.ComputerScreenHandler;
import cn.blockforge.generated.virtualmachine.computer.ComputerState;
import cn.blockforge.generated.virtualmachine.computer.CustomIso;
import cn.blockforge.generated.virtualmachine.computer.InstallStep;
import cn.blockforge.generated.virtualmachine.computer.MediaDiskItem;
import cn.blockforge.generated.virtualmachine.computer.MediaKind;
import cn.blockforge.generated.virtualmachine.computer.OsFile;
import cn.blockforge.generated.virtualmachine.computer.OsType;
import cn.blockforge.generated.virtualmachine.computer.SystemImage;
import cn.blockforge.generated.virtualmachine.network.ComputerActionPayload;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ScreenHandlerProvider;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * 整台电脑的界面：外壳、屏幕、光驱，以及屏幕里的 BIOS / 装机进度 / 桌面小程序。
 * 全部用矩形和文字画出来，没有额外贴图。
 */
public class ComputerScreen extends Screen implements ScreenHandlerProvider<ComputerScreenHandler> {
    private static final int CASE_W = 320;
    private static final int CASE_H = 234;
    private static final int SCREEN_OFF_X = 17;
    private static final int SCREEN_OFF_Y = 17;
    private static final int SCREEN_W = 286;
    private static final int SCREEN_H = 146;
    private static final int STRIP_X = 14;
    private static final int STRIP_Y = 172;
    private static final int STRIP_W = 292;
    private static final int STRIP_H = 32;

    private static final int COL_SCREEN_BG = 0xFF06080C;
    private static final int COL_BEZEL = 0xFF23262C;
    private static final int COL_BEZEL_LIGHT = 0xFF3B4048;
    private static final int COL_BEZEL_DARK = 0xFF13151A;
    private static final int COL_TEXT = 0xFFE7ECF5;
    private static final int COL_TEXT_DIM = 0xFF8A93A6;
    private static final int COL_GREEN = 0xFF6BFF8F;
    private static final int COL_AMBER = 0xFFFFC95C;
    private static final int COL_BLUE_SCREEN = 0xFF0000AA;

    // 安装程序（Setup）用的 95 风格配色
    private static final int COL_95_FACE = 0xFFC0C0C0;
    private static final int COL_95_LIGHT = 0xFFFFFFFF;
    private static final int COL_95_SHADOW = 0xFF808080;
    private static final int COL_95_DARK = 0xFF404040;
    private static final int COL_95_INK = 0xFF000000;
    private static final int COL_95_NAVY = 0xFF000080;

    private final ComputerScreenHandler menu;
    private final List<Hit> hits = new ArrayList<>();
    private final List<String> terminalLines = new ArrayList<>();
    private final List<String> history = new ArrayList<>();

    /** 最下面「选择你自己电脑的ISO」按钮旁边那一行小字。 */
    private String pickStatus = "";
    private boolean guestOpen;
    private boolean confirmForceOff;
    private final GuestDisplay guest = new GuestDisplay();
    private String terminalInput = "";
    private String notesDraft = "";
    private boolean notesDirty;
    /** 虚拟机窗口有没有盖在屏幕上。 */
    private boolean vmOpen;
    /** 虚拟机终端里正在敲的那一行。 */
    private String vmInput = "";
    /** 虚拟机日志往上翻了几行（0 表示停在最新的那一行）。 */
    private int vmScroll;
    private AppId openApp;
    private int fileSelection = -1;
    private int lastVersion = -1;
    private ComputerPhase lastPhase = ComputerPhase.OFF;
    private int historyIndex = -1;

    private int caseX;
    private int caseY;

    public ComputerScreen(ComputerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(title);
        this.menu = handler;
    }

    @Override
    public ComputerScreenHandler getScreenHandler() {
        return this.menu;
    }

    private record Hit(String id, int x, int y, int w, int h) {
        boolean inside(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.w && mouseY >= this.y && mouseY < this.y + this.h;
        }
    }

    // ------------------------------------------------------------------
    // 基础
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        this.caseX = (this.width - CASE_W) / 2;
        this.caseY = (this.height - CASE_H) / 2;
    }

    @Override
    public boolean shouldPause() {
        // 单人游戏里也不能暂停，否则装机永远装不完
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderInGameBackground(context);
    }

    @Override
    public void close() {
        this.flushNotes();
        if (this.client != null && this.client.player != null) {
            this.client.player.closeHandledScreen();
        }
        super.close();
    }

    @Override
    public void removed() {
        this.guest.release(this.client);
        super.removed();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        this.hits.clear();
        if (this.guestOpen) {
            this.drawGuest(context, mouseX, mouseY);
            return;
        }

        this.drawCase(context);

        ComputerState state = this.menu == null ? null : this.menu.getState();
        if (state == null) {
            int dx = this.caseX + SCREEN_OFF_X;
            int dy = this.caseY + SCREEN_OFF_Y;
            context.fill(dx, dy, dx + SCREEN_W, dy + SCREEN_H, COL_SCREEN_BG);
            context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("gui.virtual_machine.connecting"), dx + SCREEN_W / 2, dy + SCREEN_H / 2, COL_TEXT_DIM);
            this.drawScanlines(context);
            return;
        }

        this.syncState(state);
        int dx = this.caseX + SCREEN_OFF_X;
        int dy = this.caseY + SCREEN_OFF_Y;
        if (this.vmOpen) {
            // 虚拟机窗口盖在电脑屏幕上，像显示器切到了另一路信号
            this.drawVirtualMachine(context, state, mouseX, mouseY);
        } else {
            switch (state.phase()) {
                case OFF -> this.drawOff(context, dx, dy);
                case POST -> this.drawPost(context, state, dx, dy, mouseX, mouseY);
                case MEDIA_BOOT -> this.drawMediaBoot(context, state, dx, dy);
                case INSTALLING -> this.drawInstalling(context, state, dx, dy, mouseX, mouseY);
                case BOOTING -> this.drawBooting(context, state, dx, dy);
                case DESKTOP -> this.drawDesktop(context, state, dx, dy, mouseX, mouseY);
                case ERROR -> this.drawError(context, state, dx, dy);
            }
        }
        this.drawScanlines(context);
        this.drawDriveBay(context, state, mouseX, mouseY);
        this.drawButtons(context, state, mouseX, mouseY);
        this.drawIsoPicker(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawScanlines(DrawContext context) {
        int x = this.caseX + SCREEN_OFF_X;
        int y = this.caseY + SCREEN_OFF_Y;
        for (int sy = y + 1; sy < y + SCREEN_H; sy += 2) {
            context.fill(x, sy, x + SCREEN_W, sy + 1, 0x14000000);
        }
    }

    private void drawCase(DrawContext context) {
        int x = this.caseX;
        int y = this.caseY;
        context.fill(x, y, x + CASE_W, y + CASE_H, COL_BEZEL);
        context.drawBorder(x, y, CASE_W, CASE_H, COL_BEZEL_DARK);
        context.fill(x + 1, y + 1, x + CASE_W - 1, y + 3, COL_BEZEL_LIGHT);
        // 屏幕凹槽
        context.fill(x + SCREEN_OFF_X - 3, y + SCREEN_OFF_Y - 3,
            x + SCREEN_OFF_X + SCREEN_W + 3, y + SCREEN_OFF_Y + SCREEN_H + 3, COL_BEZEL_DARK);
        // 光驱/按钮区
        context.fill(x + STRIP_X, y + STRIP_Y, x + STRIP_X + STRIP_W, y + STRIP_Y + STRIP_H, 0xFF2A2E36);
        context.drawBorder(x + STRIP_X, y + STRIP_Y, STRIP_W, STRIP_H, COL_BEZEL_DARK);
        // 电源指示灯
        context.fill(x + CASE_W - 12, y + CASE_H - 8, x + CASE_W - 8, y + CASE_H - 4, 0xFF5CE07A);
    }

    private void drawDriveBay(DrawContext context, ComputerState state, int mouseX, int mouseY) {
        int bx = this.caseX + 18;
        int by = this.caseY + 176;
        SystemImage disk = state.diskImage();
        boolean canInsert = disk == null && this.heldMedia() != null;
        context.fill(bx, by, bx + 56, by + 24, 0xFF15171C);
        context.drawBorder(bx, by, 56, 24, canInsert ? COL_AMBER : 0xFF484D58);
        if (disk == null) {
            ItemStack icon = this.heldMedia();
            if (icon != null) {
                // 手里拿着安装盘：把光驱口画成可点的样子
                context.drawItem(icon, bx + 4, by + 4);
                context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.drive.insert"),
                    bx + 24, by + 8, COL_AMBER, false);
                this.hit("insert", bx, by, 56, 24);
            } else {
                context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("gui.virtual_machine.drive.empty"), bx + 28, by + 8, 0xFF5C6270);
            }
        } else {
            ItemStack icon = disk instanceof OsType builtin ? MediaDiskItem.stackFor(builtin) : ItemStack.EMPTY;
            if (!icon.isEmpty()) {
                context.drawItem(icon, bx + 4, by + 4);
            } else {
                // 玩家自己电脑上选的 ISO：没有对应物品，画一张小盘片
                context.fill(bx + 4, by + 4, bx + 20, by + 20, 0xFF1B3A6B);
                context.drawBorder(bx + 4, by + 4, 16, 16, 0xFF8FB3E8);
                context.fill(bx + 10, by + 10, bx + 14, by + 14, 0xFF0B1526);
            }
            context.drawText(this.textRenderer, Text.literal(disk.getMedia().getShortName()),
                bx + 24, by + 8, COL_AMBER, false);
        }
    }

    /** 玩家手里（主手或副手）拿着的安装盘，没拿就返回 null。只看物品，不改任何状态。 */
    private ItemStack heldMedia() {
        if (this.client == null || this.client.player == null) {
            return null;
        }
        ItemStack main = this.client.player.getMainHandStack();
        if (MediaDiskItem.osOf(main) != null) {
            return main;
        }
        ItemStack off = this.client.player.getOffHandStack();
        return MediaDiskItem.osOf(off) != null ? off : null;
    }

    // ------------------------------------------------------------------
    // 屏幕内容
    // ------------------------------------------------------------------

    /**
     * 虚拟机窗口：盖在电脑屏幕上的另一台小机器的屏幕。
     *
     * <p>黑底绿字，上面是它自己的 BIOS 日志（内容全都来自玩家那张盘：
     * 卷标、El Torito 引导映像的扇区号、引导扇区的前几个字节），
     * 底下是虚拟机的命令行，敲 ls / cat / info / install 都有反应。
     */
    private void drawVirtualMachine(DrawContext context, ComputerState state, int mouseX, int mouseY) {
        int dx = this.caseX + SCREEN_OFF_X;
        int dy = this.caseY + SCREEN_OFF_Y;
        context.fill(dx, dy, dx + SCREEN_W, dy + SCREEN_H, 0xFF02060A);

        // 标题栏
        context.fill(dx, dy, dx + SCREEN_W, dy + 12, 0xFF132E4D);
        context.fill(dx, dy + 12, dx + SCREEN_W, dy + 13, 0xFF2F5C8F);
        context.drawText(this.textRenderer,
            Text.literal("PC-Craft VM 0.9  \u00b7  " + vmStateName(state.vmState())),
            dx + 4, dy + 2, 0xFFB7D7FF, false);
        int closeX = dx + SCREEN_W - 11;
        boolean overClose = this.hover(mouseX, mouseY, closeX, dy + 2, 9, 9);
        context.fill(closeX, dy + 2, closeX + 9, dy + 11, overClose ? 0xFFE04A4A : 0xFF6B2B2B);
        context.drawText(this.textRenderer, Text.literal("x"), closeX + 3, dy + 3, 0xFFFFFFFF, false);
        this.hit("vm_close", closeX, dy + 2, 9, 9);

        // 引导日志：只显示最后几行，新的一行总是从底下顶上来
        List<String> lines = new ArrayList<>();
        if (state.vmLog() != null && !state.vmLog().isEmpty()) {
            lines.addAll(List.of(state.vmLog().split("\n", -1)));
        }
        int top = dy + 17;
        int inputY = dy + SCREEN_H - 12;
        int rowH = 9;
        int rows = Math.max(1, (inputY - top - 3) / rowH);
        int maxStart = Math.max(0, lines.size() - rows);
        int start = Math.max(0, maxStart - this.vmScroll);
        int end = Math.min(lines.size(), start + rows);
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            context.drawText(this.textRenderer,
                Text.literal(this.trim(line, SCREEN_W - 10)),
                dx + 5, top + (i - start) * rowH, vmLineColor(line), false);
        }
        if (lines.isEmpty()) {
            context.drawText(this.textRenderer, Text.literal("(虚拟机屏幕是空的)"),
                dx + 5, top, 0xFF3C4A5C, false);
        }
        if (this.vmScroll > 0) {
            // 正在往回翻：右下角提示还有多少行没显示
            context.drawText(this.textRenderer,
                Text.literal("\u25b2 " + this.vmScroll),
                dx + SCREEN_W - 22, inputY - 10, 0xFF8A93A6, false);
        }

        // 命令行
        context.fill(dx, inputY - 2, dx + SCREEN_W, dy + SCREEN_H, 0xFF0B1A2B);
        boolean booting = state.vmState() == 1;
        String prompt = booting ? "... " : (state.vmState() == 2 ? "vm> " : "off> ");
        String typing = this.trim(prompt + this.vmInput, SCREEN_W - 12);
        context.drawText(this.textRenderer, Text.literal(typing), dx + 5, inputY,
            booting ? 0xFF7A8798 : COL_GREEN, false);
        if (!booting && this.blink()) {
            int caret = dx + 5 + this.textRenderer.getWidth(typing);
            context.fill(caret, inputY, caret + 4, inputY + 8, COL_GREEN);
        }
    }

    private static String vmStateName(int vmState) {
        return switch (vmState) {
            case 1 -> "正在引导";
            case 2 -> "运行中";
            default -> "已关机";
        };
    }

    private static int vmLineColor(String line) {
        if (line == null || line.isEmpty()) {
            return COL_TEXT_DIM;
        }
        if (line.startsWith("vm> ")) {
            return COL_GREEN;
        }
        if (line.startsWith("---")) {
            return 0xFF7FB3FF;
        }
        if (line.startsWith("未知") || line.startsWith("找不到") || line.startsWith("虚拟机没有")) {
            return COL_AMBER;
        }
        return 0xFFC8D4E4;
    }

    private void drawOff(DrawContext context, int dx, int dy) {
        context.fill(dx, dy, dx + SCREEN_W, dy + SCREEN_H, COL_SCREEN_BG);
        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("gui.virtual_machine.screen.off"), dx + SCREEN_W / 2, dy + SCREEN_H / 2 - 10, 0xFF4E5563);
        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("gui.virtual_machine.screen.off_hint"), dx + SCREEN_W / 2, dy + SCREEN_H / 2 + 4, 0xFF39404D);
    }

    /**
     * 从介质引导（真机插盘后 BIOS 干的事）：自检一遍设备，认出光盘/软盘里的安装程序，
     * 然后直接把安装程序拉起来。整段不需要玩家按任何键。
     */
    private void drawMediaBoot(DrawContext context, ComputerState state, int dx, int dy) {
        context.fill(dx, dy, dx + SCREEN_W, dy + SCREEN_H, COL_SCREEN_BG);
        int x = dx + 8;
        int y = dy + 8;
        context.drawText(this.textRenderer, Text.literal("PC-CRAFT BIOS v2.11 (C) 1998"), x, y, COL_TEXT, false);
        y += 14;

        SystemImage disk = state.diskImage();
        SystemImage installed = state.installedImage();
        int timer = state.timer();
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.mediaboot.detect"), x, y, COL_TEXT_DIM, false);
        y += 10;
        if (timer >= 10) {
            Text hdd = installed == null
                ? Text.translatable("gui.virtual_machine.mediaboot.hdd_empty")
                : Text.translatable("gui.virtual_machine.mediaboot.hdd_ready", installed.getDisplayName());
            context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.mediaboot.hdd").copy().append(hdd),
                x, y, COL_GREEN, false);
            y += 10;
        }
        if (timer >= 18 && disk != null) {
            Text driveName = Text.literal(disk.getDisplayName() + " (" + disk.getMedia().getShortName() + ")");
            context.drawText(this.textRenderer,
                Text.translatable("gui.virtual_machine.mediaboot.drive").copy().append(driveName),
                x, y, COL_GREEN, false);
            y += 10;
        }
        // 盘上真实读到的那些东西：卷标、引导映像在哪个扇区、5 5 A A 签名在不在
        if (timer >= 20 && disk instanceof CustomIso iso) {
            if (!iso.getVolumeLabel().isEmpty()) {
                context.drawText(this.textRenderer,
                    Text.literal(this.trim("  LABEL: " + iso.getVolumeLabel(), SCREEN_W - 18)),
                    x, y, COL_TEXT_DIM, false);
                y += 9;
            }
            if (iso.isBootable() && iso.getBootImageLba() > 0) {
                String boot = "  EL TORITO LBA " + iso.getBootImageLba() + "/" + iso.getBootImageSectors()
                    + "s " + (iso.hasBootSignature() ? "55AA OK" : "NO 55AA");
                context.drawText(this.textRenderer, Text.literal(this.trim(boot, SCREEN_W - 18)),
                    x, y, COL_GREEN, false);
                y += 9;
            }
        }
        if (timer >= 28 && disk != null) {
            context.drawText(this.textRenderer,
                Text.translatable("gui.virtual_machine.mediaboot.found", disk.getDisplayName()),
                x, y, COL_AMBER, false);
            y += 10;
        }
        if (timer >= 36 && disk != null) {
            Text where = disk.getMedia() == MediaKind.ISO
                ? Text.translatable("gui.virtual_machine.mediaboot.cdrom")
                : Text.translatable("gui.virtual_machine.mediaboot.floppy");
            context.drawText(this.textRenderer,
                Text.translatable("gui.virtual_machine.mediaboot.booting", where), x, y, COL_TEXT, false);
            y += 10;
        }
        if (this.blink()) {
            context.fill(x, y, x + 5, y + 9, COL_GREEN);
        }
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.mediaboot.hint"),
            x, dy + SCREEN_H - 14, COL_TEXT_DIM, false);
    }

    private void drawPost(DrawContext context, ComputerState state, int dx, int dy, int mouseX, int mouseY) {
        context.fill(dx, dy, dx + SCREEN_W, dy + SCREEN_H, COL_SCREEN_BG);
        int x = dx + 6;
        int y = dy + 6;
        context.drawText(this.textRenderer, Text.literal("PC-CRAFT BIOS v2.11 (C) 1998"), x, y, COL_TEXT, false);
        y += 10;
        context.drawText(this.textRenderer, Text.literal("----------------------------------------"), x, y, COL_TEXT_DIM, false);
        y += 12;
        context.drawText(this.textRenderer, Text.literal("CPU   : Piston Core 1x @ 1.00GHz"), x, y, COL_GREEN, false);
        y += 10;
        context.drawText(this.textRenderer, Text.literal("MEMORY: 640K OK"), x, y, COL_GREEN, false);
        y += 10;
        SystemImage disk = state.diskImage();
        Text drive = disk == null
            ? Text.translatable("gui.virtual_machine.post.drive_empty")
            : Text.translatable("gui.virtual_machine.post.drive_ready", disk.getDisplayName(), disk.getMedia().getShortName());
        context.drawText(this.textRenderer, Text.literal("DRIVE : ").copy().append(drive), x, y, COL_GREEN, false);
        y += 10;
        SystemImage installed = state.installedImage();
        Text hdd = installed == null
            ? Text.translatable("gui.virtual_machine.post.hdd_empty")
            : Text.translatable("gui.virtual_machine.post.hdd_ready", installed.getDisplayName());
        context.drawText(this.textRenderer, Text.literal("HDD   : ").copy().append(hdd), x, y, COL_GREEN, false);
        y += 14;
        if (disk == null) {
            context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.post.hint_insert"), x, y, COL_AMBER, false);
        } else {
            context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.post.hint_install"), x, y, COL_AMBER, false);
            if (installed != null) {
                y += 10;
                context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.post.hint_boot"), x, y, COL_TEXT_DIM, false);
            }
        }
    }

    /**
     * 安装程序（Setup）。跟真机一样：灰底、深蓝标题栏、左边一条竖排的产品名，
     * 右边一页一页走「欢迎 → 许可协议 → 选择分区 → 复制文件 → 完成」。
     */
    private void drawInstalling(DrawContext context, ComputerState state, int dx, int dy, int mouseX, int mouseY) {
        context.fill(dx, dy, dx + SCREEN_W, dy + SCREEN_H, 0xFF008080);
        SystemImage os = state.diskImage();
        if (os == null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("gui.virtual_machine.err.media_gone"),
                dx + SCREEN_W / 2, dy + SCREEN_H / 2 - 4, 0xFFFF7B72);
            return;
        }

        int wx = dx + 4;
        int wy = dy + 3;
        int ww = SCREEN_W - 8;
        int wh = SCREEN_H - 6;
        // 窗体本体 + 95 风格的凸起边框
        context.fill(wx, wy, wx + ww, wy + wh, COL_95_FACE);
        context.drawBorder(wx, wy, ww, wh, COL_95_INK);
        context.drawHorizontalLine(wy + 1, wx + 1, wx + ww - 1, COL_95_LIGHT);
        context.drawVerticalLine(wx + 1, wy + 1, wy + wh - 1, COL_95_LIGHT);
        context.drawHorizontalLine(wy + wh - 2, wx + 1, wx + ww - 1, COL_95_SHADOW);
        context.drawVerticalLine(wx + ww - 2, wy + 1, wy + wh - 1, COL_95_SHADOW);

        // 标题栏
        int titleW = ww - 4;
        context.fill(wx + 2, wy + 2, wx + 2 + titleW, wy + 12, COL_95_NAVY);
        context.drawText(this.textRenderer,
            Text.literal(this.trim(Text.translatable("gui.virtual_machine.setup.window", os.getDisplayName()).getString(),
                titleW - 18)),
            wx + 5, wy + 3, COL_95_LIGHT, false);
        context.fill(wx + 3 + titleW - 9, wy + 4, wx + 3 + titleW - 5, wy + 10, COL_95_FACE);
        context.fill(wx + 3 + titleW - 4, wy + 4, wx + 3 + titleW, wy + 10, COL_95_FACE);

        int bodyY = wy + 14;
        int bodyH = wh - 14 - 20;
        // 左边竖排产品名
        context.fill(wx + 2, bodyY, wx + 46, bodyY + bodyH, COL_95_NAVY);
        this.drawVerticalText(context, os.getDisplayName(), wx + 24, bodyY + 6, bodyY + bodyH - 4, COL_95_LIGHT);

        // 右边白色内容页
        int cx = wx + 50;
        int cy = bodyY + 2;
        int cw = wx + ww - 4 - cx;
        int ch = bodyH - 4;
        context.fill(cx, cy, cx + cw, cy + ch, COL_95_LIGHT);
        context.drawBorder(cx, cy, cw, ch, COL_95_SHADOW);

        int tx = cx + 6;
        int ty = cy + 5;
        int tw = cw - 12;
        int bottom = cy + ch - 6;
        InstallStep step = state.setupStep();
        switch (step) {
            case WELCOME -> this.drawSetupWelcome(context, os, tx, ty, tw, bottom);
            case LICENSE -> this.drawSetupLicense(context, os, tx, ty, tw, bottom);
            case PARTITION -> this.drawSetupPartition(context, tx, ty, tw, bottom);
            case COPYING -> this.drawSetupCopying(context, os, state, tx, ty, tw, bottom);
            case FINISH -> this.drawSetupFinish(context, os, tx, ty, tw, bottom);
        }

        // 底部按钮条
        int barY = wy + wh - 18;
        context.drawHorizontalLine(barY, wx + 3, wx + ww - 3, COL_95_SHADOW);
        int by = barY + 2;
        int bw = 46;
        int bx = wx + ww - 6 - bw;
        switch (step) {
            case FINISH -> {
                this.setupButton(context, mouseX, mouseY, bx, by, bw, Text.translatable("gui.virtual_machine.setup.btn.restart"), true, "reboot");
                this.setupButton(context, mouseX, mouseY, bx - bw - 4, by, bw, Text.translatable("gui.virtual_machine.btn.eject"), true, "eject");
            }
            case COPYING -> this.setupButton(context, mouseX, mouseY, bx, by, bw, Text.translatable("gui.virtual_machine.setup.btn.cancel"), true, "cancel");
            default -> {
                this.setupButton(context, mouseX, mouseY, bx, by, bw,
                    Text.translatable(nextButtonKey(step)), true, "wizard_next");
                this.setupButton(context, mouseX, mouseY, bx - bw - 4, by, bw,
                    Text.translatable("gui.virtual_machine.setup.btn.cancel"), true, "cancel");
                if (step.canGoBack()) {
                    this.setupButton(context, mouseX, mouseY, bx - 2 * (bw + 4), by, bw,
                        Text.translatable("gui.virtual_machine.setup.btn.back"), true, "wizard_back");
                }
            }
        }
    }

    private static String nextButtonKey(InstallStep step) {
        return switch (step) {
            case LICENSE -> "gui.virtual_machine.setup.btn.agree";
            case PARTITION -> "gui.virtual_machine.setup.btn.begin";
            default -> "gui.virtual_machine.setup.btn.next";
        };
    }

    private void drawSetupWelcome(DrawContext context, SystemImage os, int tx, int ty, int tw, int bottom) {
        int y = this.setupHead(context, Text.translatable("gui.virtual_machine.setup.welcome.head", os.getDisplayName()),
            tx, ty, tw);
        String body = Text.translatable("gui.virtual_machine.setup.welcome.body",
            os.getDisplayName(), os.getMedia().getDisplayName().getString()).getString();
        this.drawWrapped(context, body, tx, y, tw, bottom, COL_95_INK);
    }

    private void drawSetupLicense(DrawContext context, SystemImage os, int tx, int ty, int tw, int bottom) {
        int y = this.setupHead(context, Text.translatable("gui.virtual_machine.setup.license.head"), tx, ty, tw);
        this.drawWrapped(context, os.getLicenseText(), tx, y, tw, bottom - 16, COL_95_INK);
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.setup.license.hint"),
            tx, bottom - 7, COL_95_SHADOW, false);
    }

    private void drawSetupPartition(DrawContext context, int tx, int ty, int tw, int bottom) {
        int y = this.setupHead(context, Text.translatable("gui.virtual_machine.setup.partition.head"), tx, ty, tw);
        y = this.drawWrapped(context, Text.translatable("gui.virtual_machine.setup.partition.body").getString(),
            tx, y, tw, bottom - 34, COL_95_INK);
        y += 3;
        // 硬盘列表：选中那一行是深蓝底白字
        int rowH = 13;
        context.fill(tx, y, tx + tw, y + rowH, COL_95_LIGHT);
        context.drawBorder(tx, y, tw, rowH, COL_95_SHADOW);
        context.fill(tx + 1, y + 1, tx + tw - 1, y + rowH - 1, COL_95_NAVY);
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.setup.partition.disk"),
            tx + 4, y + 3, COL_95_LIGHT, false);
        y += rowH + 2;
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.setup.partition.none"),
            tx + 4, y, 0xFF9A9A9A, false);
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.setup.partition.hint"),
            tx, bottom - 7, COL_95_SHADOW, false);
    }

    private void drawSetupCopying(DrawContext context, SystemImage os, ComputerState state, int tx, int ty, int tw,
                                  int bottom) {
        float fraction = state.installFraction();
        int y = this.setupHead(context, Text.translatable("gui.virtual_machine.setup.copying.head", os.getDisplayName()),
            tx, ty, tw);
        context.drawText(this.textRenderer,
            Text.translatable("gui.virtual_machine.install.stage" + OsType.installStage(fraction)),
            tx, y, COL_95_NAVY, false);
        y += 12;
        int barW = tw - 44;
        this.drawSetupProgress(context, tx, y, barW, 13, fraction);
        context.drawText(this.textRenderer, Text.literal((int) (fraction * 100.0f) + "%"),
            tx + barW + 7, y + 3, COL_95_INK, false);
        y += 19;

        // 「正在复制：」后面跟着滚动的文件路径
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.setup.copying.file"),
            tx, y, COL_95_INK, false);
        y += 10;
        List<String> paths = os.getInstallPaths();
        int index = 0;
        if (!paths.isEmpty() && state.total() > 0) {
            index = (int) ((long) paths.size() * state.progress() / state.total());
            index = Math.max(0, Math.min(paths.size() - 1, index));
        }
        String current = paths.isEmpty() ? "-" : paths.get(index);
        context.drawText(this.textRenderer, Text.literal(this.trim(current, tw - 8)), tx + 6, y,
            COL_95_NAVY, false);
        y += 11;
        int remain = Math.max(0, state.total() - state.progress());
        context.drawText(this.textRenderer,
            Text.translatable("gui.virtual_machine.install.remain", (remain + 19) / 20),
            tx, y, COL_95_SHADOW, false);
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.install.warning"),
            tx, bottom - 7, 0xFF9C2A2A, false);
    }

    private void drawSetupFinish(DrawContext context, SystemImage os, int tx, int ty, int tw, int bottom) {
        int y = this.setupHead(context, Text.translatable("gui.virtual_machine.setup.finish.head"), tx, ty, tw);
        // 100% 的进度条收个尾
        this.drawSetupProgress(context, tx, y, tw, 13, 1.0f);
        y += 19;
        String body = Text.translatable("gui.virtual_machine.setup.finish.body", os.getDisplayName()).getString();
        this.drawWrapped(context, body, tx, y, tw, bottom, COL_95_INK);
    }

    /** 页面小标题 + 一条分隔线，返回正文起始 y。 */
    private int setupHead(DrawContext context, Text title, int tx, int ty, int tw) {
        context.drawText(this.textRenderer, Text.literal(this.trim(title.getString(), tw)), tx, ty, COL_95_NAVY, false);
        context.drawHorizontalLine(ty + 11, tx, tx + tw, COL_95_FACE);
        return ty + 16;
    }

    /** 95 那种一块一块的进度条。 */
    private void drawSetupProgress(DrawContext context, int x, int y, int w, int h, float fraction) {
        context.fill(x, y, x + w, y + h, COL_95_LIGHT);
        context.drawBorder(x, y, w, h, COL_95_SHADOW);
        context.drawHorizontalLine(y, x, x + w, COL_95_SHADOW);
        context.drawVerticalLine(x, y, y + h, COL_95_SHADOW);
        int inner = w - 4;
        int filled = (int) (inner * Math.max(0.0f, Math.min(1.0f, fraction)));
        for (int i = 0; i < filled; i += 8) {
            int seg = Math.min(6, filled - i);
            if (seg > 0) {
                context.fill(x + 2 + i, y + 2, x + 2 + i + seg, y + h - 2, COL_95_NAVY);
            }
        }
    }

    /** 95 风格的凸起按钮。 */
    private void setupButton(DrawContext context, int mouseX, int mouseY, int x, int y, int w, Text label,
                             boolean enabled, String id) {
        int h = 13;
        boolean over = enabled && this.hover(mouseX, mouseY, x, y, w, h);
        context.fill(x, y, x + w, y + h, over ? 0xFFD2D2D2 : COL_95_FACE);
        context.fill(x, y, x + w, y + 1, enabled ? COL_95_LIGHT : COL_95_SHADOW);
        context.fill(x, y, x + 1, y + h, enabled ? COL_95_LIGHT : COL_95_SHADOW);
        context.fill(x + w - 1, y, x + w, y + h, enabled ? COL_95_DARK : COL_95_LIGHT);
        context.fill(x, y + h - 1, x + w, y + h, enabled ? COL_95_DARK : COL_95_LIGHT);
        int textW = this.textRenderer.getWidth(label);
        context.drawText(this.textRenderer, label, x + (w - textW) / 2, y + 3,
            enabled ? COL_95_INK : COL_95_SHADOW, false);
        if (enabled) {
            this.hit(id, x, y, w, h);
        }
    }

    /** 竖着写的产品名，像真安装盘的左边那条横幅。 */
    private void drawVerticalText(DrawContext context, String text, int centerX, int top, int maxY, int color) {
        int y = top;
        for (int i = 0; i < text.length() && y + 9 <= maxY; i++) {
            String ch = String.valueOf(text.charAt(i));
            context.drawText(this.textRenderer, Text.literal(ch),
                centerX - this.textRenderer.getWidth(ch) / 2, y, color, false);
            y += 9;
        }
    }

    /** 画一段自动换行的正文，超出 maxY 就截断，返回下一个可用 y。 */
    private int drawWrapped(DrawContext context, String text, int x, int y, int maxWidth, int maxY, int color) {
        for (String line : this.wrapText(text, maxWidth)) {
            if (y > maxY) {
                break;
            }
            context.drawText(this.textRenderer, Text.literal(line), x, y, color, false);
            y += 9;
        }
        return y;
    }

    /** 太长的路径截断成 xxx... 免得画出格子。 */
    private String trim(String text, int maxWidth) {
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = sb.toString() + text.charAt(i);
            if (this.textRenderer.getWidth(candidate + "...") > maxWidth) {
                break;
            }
            sb.append(text.charAt(i));
        }
        return sb.append("...").toString();
    }

    private void drawBooting(DrawContext context, ComputerState state, int dx, int dy) {
        context.fill(dx, dy, dx + SCREEN_W, dy + SCREEN_H, COL_SCREEN_BG);
        SystemImage installed = state.installedImage();
        int x = dx + 8;
        int y = dy + 8;
        context.drawText(this.textRenderer, Text.literal("PC-CRAFT BIOS v2.11"), x, y, COL_TEXT_DIM, false);
        y += 12;
        if (installed == null) {
            context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.boot.no_system"), x, y, 0xFFFF7B72, false);
            return;
        }
        List<String> lines = installed.getBootLines();
        int shown = Math.min(lines.size(), state.timer() / 8 + 1);
        for (int i = 0; i < shown; i++) {
            context.drawText(this.textRenderer, Text.literal(lines.get(i)), x, y, COL_GREEN, false);
            y += 10;
        }
        if (shown >= lines.size()) {
            context.drawText(this.textRenderer, Text.literal("_"), x, y, COL_GREEN, false);
        }
    }

    private void drawError(DrawContext context, ComputerState state, int dx, int dy) {
        context.fill(dx, dy, dx + SCREEN_W, dy + SCREEN_H, COL_BLUE_SCREEN);
        int x = dx + 10;
        int y = dy + 12;
        context.drawText(this.textRenderer, Text.literal(":( ").copy().append(Text.translatable("gui.virtual_machine.error.title")),
            x, y, 0xFFFFFFFF, false);
        y += 16;
        String msg = state.error() == null || state.error().isEmpty() ? "UNKNOWN" : state.error();
        for (String line : this.wrapText(msg, SCREEN_W - 20)) {
            context.drawText(this.textRenderer, Text.literal(line), x, y, 0xFFFFFFFF, false);
            y += 10;
        }
        y += 8;
        context.drawText(this.textRenderer, Text.literal("STOP CODE: 0x0000C0FFEE"), x, y, 0xFFB9C0FF, false);
        y += 18;
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.error.hint"), x, y, 0xFFFFFFFF, false);
    }

    // ------------------------------------------------------------------
    // 桌面
    // ------------------------------------------------------------------

    private void drawDesktop(DrawContext context, ComputerState state, int dx, int dy, int mouseX, int mouseY) {
        SystemImage os = state.installedImage();
        if (os == null) {
            this.drawError(context, state, dx, dy);
            return;
        }
        int desktopH = SCREEN_H - 16;
        context.fillGradient(dx, dy, dx + SCREEN_W, dy + desktopH, os.getDarkColor(), os.getLightColor());

        List<AppId> apps = os.getApps();
        int iconW = 52;
        int iconH = 30;
        for (int i = 0; i < apps.size(); i++) {
            AppId app = apps.get(i);
            int x = dx + 4;
            int y = dy + 4 + i * iconH;
            boolean over = this.hover(mouseX, mouseY, x, y, iconW, iconH);
            if (over) {
                context.fill(x, y, x + iconW, y + iconH, 0x44FFFFFF);
                context.drawBorder(x, y, iconW, iconH, 0x66FFFFFF);
            }
            this.drawAppIcon(context, app, x + 4, y + 4);
            context.drawText(this.textRenderer, app.getDisplayName(), x + 22, y + 10, 0xFFFFFFFF, true);
            this.hit("app:" + app.name(), x, y, iconW, iconH);
        }

        if (this.openApp != null && !apps.contains(this.openApp)) {
            this.openApp = null;
        }
        if (this.openApp != null) {
            this.drawAppWindow(context, state, os, dx, dy, mouseX, mouseY);
        } else {
            int hint = dx + SCREEN_W / 2;
            context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("gui.virtual_machine.desktop.hint"), hint, dy + desktopH / 2, 0x99FFFFFF);
        }

        this.drawTaskbar(context, os, dx, dy, desktopH, mouseX, mouseY);
    }

    private void drawTaskbar(DrawContext context, SystemImage os, int dx, int dy, int desktopH, int mouseX, int mouseY) {
        int ty = dy + desktopH;
        context.fill(dx, ty, dx + SCREEN_W, dy + SCREEN_H, 0xFF1B1E25);
        context.drawHorizontalLine(ty, dx, dx + SCREEN_W, 0xFF3A4150);
        // 开始按钮
        int bx = dx + 2;
        boolean startHover = this.hover(mouseX, mouseY, bx, ty + 2, 40, 12);
        context.fill(bx, ty + 2, bx + 40, ty + 14, startHover ? 0xFF3F4A63 : 0xFF2A303C);
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.taskbar.start"), bx + 4, ty + 4, COL_TEXT, false);
        this.hit("start", bx, ty + 2, 40, 12);
        // 当前程序
        if (this.openApp != null) {
            int ax = bx + 44;
            context.fill(ax, ty + 2, ax + 52, ty + 14, 0xFF33405A);
            context.drawText(this.textRenderer, this.openApp.getDisplayName(), ax + 4, ty + 4, COL_TEXT, false);
            this.hit("task:" + this.openApp.name(), ax, ty + 2, 52, 12);
        }
        // 时钟
        String clock = this.clockText();
        int clockW = this.textRenderer.getWidth(clock) + 6;
        context.drawText(this.textRenderer, Text.literal(clock), dx + SCREEN_W - clockW - 16, ty + 4, COL_TEXT, false);
        // 电源
        int px = dx + SCREEN_W - 14;
        boolean powerHover = this.hover(mouseX, mouseY, px, ty + 2, 12, 12);
        context.fill(px, ty + 2, px + 12, ty + 14, powerHover ? 0xFFAA3B3B : 0xFF5C2A2A);
        context.drawText(this.textRenderer, Text.literal("X"), px + 3, ty + 4, 0xFFFFFFFF, false);
        this.hit("power_off", px, ty + 2, 12, 12);
    }

    private String clockText() {
        if (this.client == null || this.client.world == null) {
            return "--:--";
        }
        long dayTime = this.client.world.getTimeOfDay() % 24000L;
        int hour = (int) ((dayTime / 1000L + 6L) % 24L);
        int minute = (int) ((dayTime % 1000L) * 60L / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private void drawAppIcon(DrawContext context, AppId app, int x, int y) {
        int color = switch (app) {
            case FILES -> 0xFFE8C24A;
            case TERMINAL -> 0xFF4AD8A0;
            case NOTES -> 0xFF7FB3FF;
            case ABOUT -> 0xFFC58CFF;
        };
        context.fill(x, y, x + 16, y + 16, color);
        context.fill(x + 1, y + 1, x + 15, y + 4, 0x55FFFFFF);
        String letter = app.getIconLetter();
        context.drawText(this.textRenderer, Text.literal(letter), x + 6, y + 4, 0xFF10131A, false);
    }

    private void drawAppWindow(DrawContext context, ComputerState state, SystemImage os, int dx, int dy,
                               int mouseX, int mouseY) {
        int wx = dx + 58;
        int wy = dy + 2;
        int ww = SCREEN_W - 62;
        int wh = SCREEN_H - 22;
        context.fill(wx + 2, wy + 2, wx + ww + 2, wy + wh + 2, 0x55000000);
        context.fill(wx, wy, wx + ww, wy + wh, 0xFF0F1218);
        context.drawBorder(wx, wy, ww, wh, 0xFF4A566E);
        context.fill(wx + 1, wy + 1, wx + ww - 1, wy + 12, os.getLightColor());
        context.drawText(this.textRenderer, this.openApp.getDisplayName(), wx + 4, wy + 3, 0xFF10131A, false);
        // 关闭按钮
        int cxButton = wx + ww - 13;
        boolean closeHover = this.hover(mouseX, mouseY, cxButton, wy + 2, 11, 9);
        context.fill(cxButton, wy + 2, cxButton + 11, wy + 11, closeHover ? 0xFFE04A4A : 0xFFB33A3A);
        context.drawText(this.textRenderer, Text.literal("x"), cxButton + 3, wy + 3, 0xFFFFFFFF, false);
        this.hit("close_app", cxButton, wy + 2, 11, 9);

        int cx = wx + 3;
        int cy = wy + 14;
        int cw = ww - 6;
        int ch = wh - 17;
        switch (this.openApp) {
            case TERMINAL -> this.drawTerminal(context, state, os, cx, cy, cw, ch);
            case NOTES -> this.drawNotes(context, cx, cy, cw, ch);
            case FILES -> this.drawFiles(context, state, os, cx, cy, cw, ch, mouseX, mouseY);
            case ABOUT -> this.drawAbout(context, state, os, cx, cy, cw, ch);
        }
    }

    private void drawTerminal(DrawContext context, ComputerState state, SystemImage os, int cx, int cy, int cw, int ch) {
        int lineH = 9;
        int maxLines = (ch / lineH) - 1;
        int start = Math.max(0, this.terminalLines.size() - maxLines);
        int y = cy;
        for (int i = start; i < this.terminalLines.size(); i++) {
            context.drawText(this.textRenderer, Text.literal(this.terminalLines.get(i)), cx, y, COL_GREEN, false);
            y += lineH;
        }
        String prompt = "> " + this.terminalInput;
        context.drawText(this.textRenderer, Text.literal(prompt), cx, cy + ch - lineH, 0xFFFFFFFF, false);
        if (this.blink()) {
            int w = this.textRenderer.getWidth(prompt);
            context.fill(cx + w + 1, cy + ch - lineH, cx + w + 5, cy + ch - 1, COL_GREEN);
        }
        if (os == null) {
            return;
        }
    }

    private void drawNotes(DrawContext context, int cx, int cy, int cw, int ch) {
        List<String> lines = this.wrapText(this.notesDraft, cw);
        int y = cy;
        int maxLines = ch / 9;
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            context.drawText(this.textRenderer, Text.literal(lines.get(i)), cx, y, COL_TEXT, false);
            y += 9;
        }
        if (lines.size() <= maxLines && this.blink()) {
            int lastW = lines.isEmpty() ? 0 : this.textRenderer.getWidth(lines.get(lines.size() - 1));
            int cursorY = cy + Math.max(0, lines.size() - 1) * 9;
            context.fill(cx + lastW + 1, cursorY, cx + lastW + 5, cursorY + 9, COL_TEXT);
        }
        context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.notes.hint"),
            cx, cy + ch - 9, COL_TEXT_DIM, false);
    }

    private void drawFiles(DrawContext context, ComputerState state, SystemImage os, int cx, int cy, int cw, int ch,
                           int mouseX, int mouseY) {
        List<OsFile> files = os.getFiles();
        if (this.fileSelection >= files.size()) {
            this.fileSelection = -1;
        }
        int listW = 74;
        context.fill(cx, cy, cx + listW, cy + ch, 0xFF151A22);
        for (int i = 0; i < files.size(); i++) {
            int y = cy + 2 + i * 10;
            boolean selected = i == this.fileSelection;
            boolean over = this.hover(mouseX, mouseY, cx, y, listW, 10);
            if (selected || over) {
                context.fill(cx, y, cx + listW, y + 10, selected ? 0xFF2F4B7A : 0xFF22303F);
            }
            context.drawText(this.textRenderer, Text.literal(this.trim(files.get(i).name(), listW - 5)),
                cx + 3, y + 1, COL_TEXT, false);
            this.hit("file:" + i, cx, y, listW, 10);
        }
        int textX = cx + listW + 5;
        int textW = cw - listW - 6;
        if (this.fileSelection >= 0) {
            OsFile file = files.get(this.fileSelection);
            int y = cy + 2;
            for (String line : this.wrapText(file.content(), textW)) {
                if (y > cy + ch - 9) {
                    break;
                }
                context.drawText(this.textRenderer, Text.literal(line), textX, y, COL_TEXT, false);
                y += 9;
            }
        } else {
            context.drawText(this.textRenderer, Text.translatable("gui.virtual_machine.files.hint"),
                textX, cy + 2, COL_TEXT_DIM, false);
            String used = String.format(Locale.ROOT, "%d KB", 64 + files.size() * 12);
            context.drawText(this.textRenderer, Text.literal(used), textX, cy + ch - 9, COL_TEXT_DIM, false);
        }
    }

    private void drawAbout(DrawContext context, ComputerState state, SystemImage os, int cx, int cy, int cw, int ch) {
        int y = cy;
        context.drawText(this.textRenderer, Text.literal(os.getDisplayName()), cx, y, COL_AMBER, false);
        y += 11;
        List<Text> lines = List.of(
            Text.literal("version : " + os.getVersion()),
            Text.literal("media   : " + os.getMedia().getShortName()),
            Text.literal("cpu     : Piston Core 1x"),
            Text.literal("memory  : 640K base / 8M ext"),
            Text.literal("display : PistonVGA 16c"),
            Text.literal("hdd     : 20MB IDE (MASTER)"),
            Text.literal("uptime  : " + (state.timer() / 20 + 1) + "s"),
            Text.literal("files   : " + os.getFiles().size()));
        for (Text line : lines) {
            if (y > cy + ch - 9) {
                break;
            }
            context.drawText(this.textRenderer, line, cx, y, COL_TEXT, false);
            y += 9;
        }
    }

    // ------------------------------------------------------------------
    // 底部按钮
    // ------------------------------------------------------------------

    private void drawButtons(DrawContext context, ComputerState state, int mouseX, int mouseY) {
        int y = this.caseY + 180;
        int w = 52;
        int gap = 4;
        int x = this.caseX + 82;
        switch (state.phase()) {
            case OFF -> {
                this.button(context, mouseX, mouseY, x, y, w, Text.translatable("gui.virtual_machine.btn.power_on"), true, "power_on");
                this.button(context, mouseX, mouseY, x + w + gap, y, w, Text.translatable("gui.virtual_machine.btn.eject"), state.hasDisk(), "eject");
            }
            case POST -> {
                this.button(context, mouseX, mouseY, x, y, w, Text.translatable("gui.virtual_machine.btn.install"), state.hasDisk(), "install");
                this.button(context, mouseX, mouseY, x + w + gap, y, w, Text.translatable("gui.virtual_machine.btn.boot"), state.hasInstalledOs(), "boot");
                this.button(context, mouseX, mouseY, x + 2 * (w + gap), y, w, Text.translatable("gui.virtual_machine.btn.eject"), state.hasDisk(), "eject");
                this.button(context, mouseX, mouseY, x + 3 * (w + gap), y, w, Text.translatable("gui.virtual_machine.btn.power_off"), true, "power_off");
            }
            case INSTALLING -> {
                // 安装程序窗口自己带「上一步 / 下一步 / 取消」，外壳上只留一个断电按钮
                this.button(context, mouseX, mouseY, this.caseX + (CASE_W - w) / 2, y, w,
                    Text.translatable("gui.virtual_machine.btn.power_off"), true, "power_off");
            }
            case MEDIA_BOOT -> {
                this.button(context, mouseX, mouseY, x, y, w, Text.translatable("gui.virtual_machine.btn.power_off"), true, "power_off");
                this.button(context, mouseX, mouseY, x + w + gap, y, w, Text.translatable("gui.virtual_machine.btn.eject"), state.hasDisk(), "eject");
            }
            case BOOTING -> this.button(context, mouseX, mouseY, x, y, w, Text.translatable("gui.virtual_machine.btn.power_off"), true, "power_off");
            case DESKTOP -> {
                this.button(context, mouseX, mouseY, x, y, w, Text.translatable("gui.virtual_machine.btn.reboot"), true, "reboot");
                this.button(context, mouseX, mouseY, x + w + gap, y, w, Text.translatable("gui.virtual_machine.btn.eject"), state.hasDisk(), "eject");
                this.button(context, mouseX, mouseY, x + 2 * (w + gap), y, w, Text.translatable("gui.virtual_machine.btn.power_off"), true, "power_off");
            }
            case ERROR -> {
                this.button(context, mouseX, mouseY, x, y, w, Text.translatable("gui.virtual_machine.btn.reboot"), true, "reboot");
                this.button(context, mouseX, mouseY, x + w + gap, y, w, Text.translatable("gui.virtual_machine.btn.eject"), state.hasDisk(), "eject");
                this.button(context, mouseX, mouseY, x + 2 * (w + gap), y, w, Text.translatable("gui.virtual_machine.btn.power_off"), true, "power_off");
            }
        }
    }

    private void button(DrawContext context, int mouseX, int mouseY, int x, int y, int w, Text label,
                        boolean enabled, String id) {
        boolean over = enabled && this.hover(mouseX, mouseY, x, y, w, 16);
        int bg = !enabled ? 0xFF22252B : (over ? 0xFF4E5F80 : 0xFF39425A);
        int border = !enabled ? 0xFF34373E : (over ? 0xFF9FB4DD : 0xFF6C7893);
        context.fill(x, y, x + w, y + 16, bg);
        context.drawBorder(x, y, w, 16, border);
        int tw = this.textRenderer.getWidth(label);
        context.drawText(this.textRenderer, label, x + (w - tw) / 2, y + 4,
            enabled ? COL_TEXT : 0xFF6C7078, false);
        if (enabled) {
            this.hit(id, x, y, w, 16);
        }
    }

    private void drawIsoPicker(DrawContext context, int mouseX, int mouseY) {
        int x = this.caseX + 12;
        int y = this.caseY + 207;
        this.button(context, mouseX, mouseY, x, y, 136, Text.translatable("gui.virtual_machine.btn.pick_iso"),
            !IsoFilePicker.isBusy() && !LocalVm.INSTANCE.isActive(), "pick_iso");
        this.button(context, mouseX, mouseY, x + 140, y, 76, Text.literal("虚拟机"), true, "guest_open");
        this.button(context, mouseX, mouseY, x + 220, y, 76, Text.literal("读盘终端"), true, "vm_toggle");
    }

    private void drawGuest(DrawContext context, int mouseX, int mouseY) {
        LocalVm vm = LocalVm.INSTANCE;
        context.fill(0, 0, this.width, this.height, 0xFF080C13);
        context.fill(0, 0, this.width, 20, 0xFF1F2836);
        this.guest.draw(this.client, context, this.width, this.height);
        if (!this.guest.hasFrame()) {
            String status = this.pickStatus.isEmpty() ? vm.status() : this.pickStatus;
            context.drawTextWrapped(this.textRenderer, Text.literal(status), 12, 36,
                Math.max(1, this.width - 24), COL_TEXT);
            if (!vm.isActive()) {
                context.drawTextWrapped(this.textRenderer,
                    Text.literal("自定义安装位置可以用「选择 QEMU」指定。虚拟机使用 2 GB 内存和 32 GB 按需扩展硬盘。"),
                    12, 86, Math.max(1, this.width - 24), COL_TEXT_DIM);
            }
        }
        this.button(context, mouseX, mouseY, 4, 2, 40, Text.literal("返回"), true, "guest_back");
        boolean active = vm.isActive();
        if (active) {
            this.button(context, mouseX, mouseY, 48, 2, 56, Text.literal("请求关机"), true, "guest_shutdown");
            this.button(context, mouseX, mouseY, 108, 2, 66,
                Text.literal(this.confirmForceOff ? "确认强关" : "强制关机"), true, "guest_force");
        } else {
            this.button(context, mouseX, mouseY, 48, 2, 96, Text.literal("选择 ISO / IMG"),
                !IsoFilePicker.isBusy(), "pick_iso");
            this.button(context, mouseX, mouseY, 148, 2, 72, Text.literal("从硬盘启动"),
                !IsoFilePicker.isBusy(), "guest_disk");
            this.button(context, mouseX, mouseY, 224, 2, 80, Text.literal("选择 QEMU"),
                !IsoFilePicker.isBusy(), "guest_qemu");
        }
        String hint = this.confirmForceOff && active ? "强关可能丢失未保存的文件"
            : "Ctrl+F12 返回电脑面板 · 退出游戏前请先在虚拟机里关机";
        context.drawCenteredTextWithShadow(this.textRenderer, this.trim(hint, this.width - 8),
            this.width / 2, this.height - 14, COL_TEXT_DIM);
    }

    private void hideGuest() {
        this.guestOpen = false;
        this.confirmForceOff = false;
        this.guest.release(this.client);
    }

    private Path vmDirectory() {
        return this.client.runDirectory.toPath().resolve("pc-craft-vm");
    }

    private void startGuest(Path image) {
        if (this.client == null || IsoFilePicker.isBusy()) {
            return;
        }
        this.guestOpen = true;
        this.pickStatus = "";
        this.confirmForceOff = false;
        LocalVm.INSTANCE.start(this.vmDirectory(), image);
    }

    private void openQemuPicker() {
        if (this.client == null || IsoFilePicker.isBusy() || LocalVm.INSTANCE.isActive()) {
            return;
        }
        this.pickStatus = "请选择同时装有 qemu-system-x86_64 和 qemu-img 的目录。";
        IsoFilePicker.openQemuDirectory(this.client, this.vmDirectory(), new IsoFilePicker.Callback() {
            @Override
            public void onPicked(Path directory) {
                pickStatus = "已保存 QEMU 安装目录。现在可以点「选择 ISO / IMG」启动安装盘。";
            }

            @Override
            public void onFailed(String reason) {
                pickStatus = "无法设置 QEMU。" + reason;
            }

            @Override
            public void onCancelled() {
                pickStatus = "";
            }
        });
    }

    /** 点「虚拟机」：开/关那块屏幕；虚拟机关着的话顺手帮它开机。 */
    private void toggleVm() {
        ComputerState state = this.menu == null ? null : this.menu.getState();
        this.vmOpen = !this.vmOpen;
        if (this.vmOpen && state != null && state.vmState() == 0) {
            this.send("vm_boot", "");
        }
    }

    /** 把虚拟机命令行里敲的这一行发给服务端。 */
    private void sendVmCommand() {
        String command = this.vmInput.trim();
        this.vmInput = "";
        this.vmScroll = 0;
        if (!command.isEmpty()) {
            this.send("vm_cmd", command);
        }
    }

    /** 弹出操作系统的文件选择窗口（在后台线程，游戏不会卡住）。 */
    private void openIsoPicker() {
        if (this.client == null) {
            return;
        }
        this.guestOpen = true;
        this.pickStatus = "请在系统文件窗口中选择安装镜像。";
        IsoFilePicker.open(this.client, new IsoFilePicker.Callback() {
            @Override
            public void onPicked(Path image) {
                pickStatus = "";
                if (client.currentScreen == ComputerScreen.this && guestOpen) {
                    startGuest(image);
                }
            }

            @Override
            public void onFailed(String reason) {
                pickStatus = "无法打开文件选择窗口（" + reason + "）";
            }

            @Override
            public void onCancelled() {
                pickStatus = "";
            }
        });
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.guestOpen && this.guest.click(mouseX, mouseY, button, true)) {
            return true;
        }
        if (button == 0) {
            for (int i = this.hits.size() - 1; i >= 0; i--) {
                Hit hit = this.hits.get(i);
                if (hit.inside(mouseX, mouseY)) {
                    this.onHit(hit.id());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (this.guestOpen) {
            this.guest.move(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.guestOpen) {
            this.guest.move(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.guestOpen) {
            return this.guest.click(mouseX, mouseY, button, false);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (this.guestOpen) {
            this.guest.scroll(mouseX, mouseY, vertical);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private void onHit(String id) {
        if (id.startsWith("app:")) {
            this.openApp = parseApp(id.substring(4));
            if (this.openApp == AppId.TERMINAL) {
                this.terminalLines.clear();
                this.terminalLines.add(Text.translatable("gui.virtual_machine.term.welcome").getString());
            }
            return;
        }
        if (id.startsWith("file:")) {
            try {
                this.fileSelection = Integer.parseInt(id.substring(5));
            } catch (NumberFormatException ignored) {
                this.fileSelection = -1;
            }
            return;
        }
        if (id.startsWith("task:")) {
            this.openApp = parseApp(id.substring(5));
            return;
        }
        switch (id) {
            case "close_app" -> {
                this.flushNotes();
                this.openApp = null;
            }
            case "start" -> this.openApp = null;
            case "pick_iso" -> this.openIsoPicker();
            case "guest_open" -> this.guestOpen = true;
            case "guest_back" -> this.hideGuest();
            case "guest_disk" -> this.startGuest(null);
            case "guest_qemu" -> this.openQemuPicker();
            case "guest_shutdown" -> {
                this.confirmForceOff = false;
                LocalVm.INSTANCE.powerButton();
            }
            case "guest_force" -> {
                if (this.confirmForceOff) {
                    LocalVm.INSTANCE.stop();
                }
                this.confirmForceOff = !this.confirmForceOff;
            }
            case "vm_toggle" -> this.toggleVm();
            case "vm_close" -> this.vmOpen = false;
            case "power_off", "power_on", "install", "boot", "eject", "cancel", "reboot",
                 "wizard_next", "wizard_back", "insert" -> this.send(id, "");
            default -> {
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.guestOpen) {
            if (keyCode == GLFW.GLFW_KEY_F12 && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                this.hideGuest();
            } else {
                this.guest.key(keyCode, true);
            }
            return true;
        }
        if (this.vmOpen) {
            // 虚拟机窗口盖着的时候，键盘先归它的命令行
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.vmOpen = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_UP) {
                this.vmScroll = Math.min(this.vmScroll + 5, 120);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_DOWN) {
                this.vmScroll = Math.max(0, this.vmScroll - 5);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                this.sendVmCommand();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!this.vmInput.isEmpty()) {
                    this.vmInput = this.vmInput.substring(0, this.vmInput.length() - 1);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                return true;
            }
        }
        if (this.openApp == AppId.TERMINAL) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                this.runCommand(this.terminalInput);
                this.terminalInput = "";
                this.historyIndex = -1;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!this.terminalInput.isEmpty()) {
                    this.terminalInput = this.terminalInput.substring(0, this.terminalInput.length() - 1);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP && !this.history.isEmpty()) {
                if (this.historyIndex < 0) {
                    this.historyIndex = this.history.size() - 1;
                } else if (this.historyIndex > 0) {
                    this.historyIndex--;
                }
                this.terminalInput = this.history.get(this.historyIndex);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN && this.historyIndex >= 0) {
                if (this.historyIndex < this.history.size() - 1) {
                    this.historyIndex++;
                    this.terminalInput = this.history.get(this.historyIndex);
                } else {
                    this.historyIndex = -1;
                    this.terminalInput = "";
                }
                return true;
            }
        }
        if (this.openApp == AppId.NOTES && keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (!this.notesDraft.isEmpty()) {
                this.notesDraft = this.notesDraft.substring(0, this.notesDraft.length() - 1);
                this.notesDirty = true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (this.guestOpen) {
            this.guest.key(keyCode, false);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (this.guestOpen) {
            return true;
        }
        if (this.vmOpen && !isControlChar(chr) && this.vmInput.length() < 44) {
            this.vmInput += chr;
            return true;
        }
        if (this.openApp == AppId.TERMINAL && !isControlChar(chr) && this.terminalInput.length() < 60) {
            this.terminalInput += chr;
            return true;
        }
        if (this.openApp == AppId.NOTES && !isControlChar(chr) && this.notesDraft.length() < 500) {
            this.notesDraft += chr;
            this.notesDirty = true;
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    private static boolean isControlChar(char chr) {
        return chr < 32 || chr == 127 || chr == 167;
    }

    // ------------------------------------------------------------------
    // 终端命令
    // ------------------------------------------------------------------

    private void runCommand(String raw) {
        String command = raw.trim();
        this.terminalLines.add("> " + raw);
        if (command.isEmpty()) {
            return;
        }
        if (this.history.isEmpty() || !this.history.get(this.history.size() - 1).equals(command)) {
            this.history.add(command);
        }
        ComputerState state = this.menu == null ? null : this.menu.getState();
        SystemImage os = state == null ? null : state.installedImage();
        String[] parts = command.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1] : "";
        switch (name) {
            case "help", "?" -> {
                this.print("help ls cat echo ver sysinfo clear reboot shutdown");
                this.print("文件用 ls 列，cat 文件名 看内容");
            }
            case "ls", "dir" -> {
                if (os == null) {
                    this.print("no filesystem");
                    break;
                }
                // 文件多的时候（比如玩家自己选的 ISO）一行一行折开，不然会画出格子
                StringBuilder sb = new StringBuilder();
                for (OsFile file : os.getFiles()) {
                    if (sb.length() + file.name().length() > 46) {
                        this.print(sb.toString());
                        sb.setLength(0);
                    }
                    sb.append(file.name()).append("  ");
                }
                if (sb.length() > 0) {
                    this.print(sb.toString());
                }
            }
            case "cat", "type" -> {
                if (os == null) {
                    this.print("no filesystem");
                    break;
                }
                OsFile found = null;
                for (OsFile file : os.getFiles()) {
                    if (file.name().equalsIgnoreCase(rest)) {
                        found = file;
                        break;
                    }
                }
                if (found == null) {
                    this.print("找不到文件: " + rest);
                } else {
                    for (String line : found.content().split("\n")) {
                        this.print(line);
                    }
                }
            }
            case "echo" -> this.print(rest);
            case "ver" -> this.print(os == null ? "no os" : os.getDisplayName() + " " + os.getVersion());
            case "sysinfo", "neofetch" -> {
                this.print("PC-CRAFT 486DX2 / 640K / 20MB HDD");
                this.print("OS: " + (os == null ? "none" : os.getDisplayName()));
            }
            case "clear", "cls" -> this.terminalLines.clear();
            case "reboot" -> {
                this.print("rebooting...");
                this.send("reboot", "");
            }
            case "shutdown", "exit" -> {
                this.print("bye");
                this.send("power_off", "");
            }
            case "format" -> {
                this.print("格式化硬盘并清空便签...");
                this.send("format", "");
            }
            case "notes" -> this.openApp = AppId.NOTES;
            default -> this.print("未知命令: " + name + "（输入 help 看帮助）");
        }
    }

    private void print(String line) {
        this.terminalLines.add(line);
        while (this.terminalLines.size() > 400) {
            this.terminalLines.remove(0);
        }
    }

    // ------------------------------------------------------------------
    // 状态同步
    // ------------------------------------------------------------------

    private void syncState(ComputerState state) {
        if (state.version() != this.lastVersion) {
            this.lastVersion = state.version();
            if (!this.notesDirty) {
                this.notesDraft = state.notes();
            }
        }
        if (state.phase() != this.lastPhase) {
            if (state.phase() == ComputerPhase.INSTALLING) {
                // 装系统的时候让安装程序占满屏幕，虚拟机窗口先收起来
                this.vmOpen = false;
                this.vmInput = "";
            }
            if (state.phase() == ComputerPhase.DESKTOP && this.lastPhase == ComputerPhase.BOOTING) {
                this.openApp = null;
                this.terminalLines.clear();
                SystemImage os = state.installedImage();
                if (os != null) {
                    this.terminalLines.add(os.getDisplayName() + " " + os.getVersion());
                    this.terminalLines.add(Text.translatable("gui.virtual_machine.term.welcome").getString());
                }
            }
            if (state.phase() != ComputerPhase.DESKTOP) {
                this.flushNotes();
                this.openApp = null;
            }
            this.lastPhase = state.phase();
        }
    }

    private void flushNotes() {
        if (this.notesDirty) {
            this.notesDirty = false;
            this.send("notes", this.notesDraft);
        }
    }

    private void send(String action, String arg) {
        ComputerState state = this.menu == null ? null : this.menu.getState();
        if (state != null) {
            ClientPlayNetworking.send(new ComputerActionPayload(state.pos(), action, arg));
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static AppId parseApp(String name) {
        for (AppId app : AppId.values()) {
            if (app.name().equals(name)) {
                return app;
            }
        }
        return null;
    }

    private boolean blink() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }

    private boolean hover(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private void hit(String id, int x, int y, int w, int h) {
        this.hits.add(new Hit(id, x, y, w, h));
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                String candidate = current + String.valueOf(c);
                if (current.length() > 0 && this.textRenderer.getWidth(candidate) > maxWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(c);
            }
            lines.add(current.toString());
        }
        return lines;
    }
}
