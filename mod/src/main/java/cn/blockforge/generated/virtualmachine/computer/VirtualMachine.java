package cn.blockforge.generated.virtualmachine.computer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

/**
 * 装在电脑里的一台虚拟机：一台假想的小机器，有自己的 BIOS、内存、硬盘和光驱。
 *
 * <p>它不是 x86 模拟器（那得几十万行代码），但引导过程是真的：
 * 先把 BIOS 自检打出来，然后按 ISO 的卷描述符、El Torito 引导目录、引导映像首扇区、
 * 0x55AA 签名这几样**盘上真实存在的数据**一步步走，读到的字节、扇区号、文件名全都来自玩家的那张盘。
 * 引导完成后进入运行状态，可以在这个小终端里 ls 目录、cat 盘上真实的文本文件。
 *
 * <p>状态存在方块实体的存档里，所以关掉界面再打开、甚至退出游戏重进，虚拟机还在那儿。
 */
public final class VirtualMachine {
    public enum State {
        /** 没开机。 */
        OFF,
        /** 正在引导：一行一行往外打。 */
        BOOTING,
        /** 引导好了，能敲命令。 */
        RUNNING;

        public static State byOrdinal(int ordinal) {
            State[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : OFF;
        }
    }

    public static final int MAX_LOG_LINES = 72;
    public static final int MAX_LINE_CHARS = 96;
    /** 状态包里的日志字符串上限（网络包字段长度是硬的，超了会抛异常）。 */
    public static final int MAX_LOG_TEXT = 3800;
    /** 引导时每几 tick 打一行。 */
    private static final int LINE_TICKS = 2;
    private static final int MAX_LS_LINES = 16;
    /** cat 一个文件最多打几行，保证整段都能停在虚拟机屏幕上。 */
    private static final int MAX_CAT_LINES = 10;

    private State state = State.OFF;
    private final List<String> log = new ArrayList<>();
    private final Deque<String> pending = new ArrayDeque<>();
    private int lineTimer;
    private String mediaName = "";

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public State getState() {
        return this.state;
    }

    public boolean isRunning() {
        return this.state == State.RUNNING;
    }

    public String getMediaName() {
        return this.mediaName;
    }

    public List<String> getLogLines() {
        return List.copyOf(this.log);
    }

    /** 发给客户端的一整段日志，按行用 \n 连起来，超长时只留最后几行。 */
    public String logText() {
        StringBuilder sb = new StringBuilder();
        int start = 0;
        int total = 0;
        for (String line : this.log) {
            total += line.length() + 1;
        }
        while (start < this.log.size() - 1 && total > MAX_LOG_TEXT) {
            total -= this.log.get(start).length() + 1;
            start++;
        }
        for (int i = start; i < this.log.size(); i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(this.log.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 开机 / 引导
    // ------------------------------------------------------------------

    /** 开机：把 BIOS 自检和引导过程排成队，由 {@link #tick} 一行一行放出来。 */
    public void boot(SystemImage media) {
        this.log.clear();
        this.pending.clear();
        this.lineTimer = 0;
        this.mediaName = media == null ? "" : media.getDisplayName();
        this.pending.addAll(script(media));
        this.state = this.pending.isEmpty() ? State.OFF : State.BOOTING;
    }

    /** 服务器每 tick 调一次；返回 true 表示日志有变化，需要同步给客户端。 */
    public boolean tick() {
        if (this.state != State.BOOTING) {
            return false;
        }
        if (++this.lineTimer < LINE_TICKS) {
            return false;
        }
        this.lineTimer = 0;
        String line = this.pending.poll();
        if (line == null) {
            this.state = State.RUNNING;
            return true;
        }
        this.append(line);
        return true;
    }

    /** 介质被抽走了：虚拟机里那张盘就没了。 */
    public void onMediaEjected(String displayName) {
        if (this.state == State.OFF) {
            return;
        }
        this.pending.clear();
        this.state = State.OFF;
        this.mediaName = "";
        this.append("ATAPI CD-ROM: 介质已弹出（" + cut(displayName, 32) + "）");
        this.append("虚拟机停机。");
    }

    public void halt() {
        if (this.state == State.OFF) {
            return;
        }
        this.pending.clear();
        this.state = State.OFF;
        this.append("虚拟机已关机。");
    }

    // ------------------------------------------------------------------
    // 在虚拟机里敲命令
    // ------------------------------------------------------------------

    /**
     * 执行一条命令。
     *
     * @return true 表示这条命令要求「把这张盘装到电脑硬盘上」，由方块实体接着走装机流程
     */
    public boolean command(String raw, SystemImage media) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (this.state != State.RUNNING) {
            if (this.state == State.BOOTING) {
                this.append("虚拟机正在引导，等一下。");
            } else if (media == null) {
                this.append("虚拟机里没有盘，先挂一个 ISO 上来。");
            } else {
                this.append("虚拟机没有开机，输入 boot 开机。");
            }
            return false;
        }
        this.append("vm> " + cut(text, 60));
        String[] parts = text.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        boolean install = false;
        switch (name) {
            case "help", "?" -> {
                this.append("help  看这些命令");
                this.append("ls    列出盘上的文件");
                this.append("cat   看盘上文本文件的内容");
                this.append("info  看这张盘的真实信息");
                this.append("install  把这张盘装进电脑硬盘");
                this.append("boot / halt / clear / exit");
            }
            case "ls", "dir" -> this.listFiles(media);
            case "cat", "type" -> this.catFile(media, rest);
            case "info", "ver" -> this.info(media);
            case "install", "setup" -> {
                if (media == null) {
                    this.append("虚拟机里没有盘。");
                } else if (media.getMedia() == MediaKind.ISO) {
                    this.append("开始把 " + cut(media.getDisplayName(), 40) + " 装进硬盘 ...");
                    this.append("虚拟机会保持开机，进度看安装程序。");
                    install = true;
                } else {
                    this.append("这张是软盘镜像，装不了系统。");
                }
            }
            case "boot", "reboot" -> this.boot(media);
            case "halt", "off", "shutdown" -> this.halt();
            case "clear", "cls" -> this.log.clear();
            case "exit" -> this.append("退出虚拟机终端，回电脑桌面。");
            default -> this.append("未知命令: " + cut(name, 20) + "（输入 help）");
        }
        return install;
    }

    private void listFiles(SystemImage media) {
        if (!(media instanceof CustomIso iso)) {
            if (media == null) {
                this.append("虚拟机里没有盘。");
                return;
            }
            this.append("这张盘没挂文件系统，只有一个安装程序。");
            return;
        }
        List<String> entries = iso.getEntries();
        if (entries.isEmpty()) {
            this.append("盘上没有可列的目录（不是 ISO9660 文件系统）。");
            return;
        }
        int shown = 0;
        for (int i = 0; i < entries.size() && shown < MAX_LS_LINES; i++) {
            String path = entries.get(i);
            String size = path.endsWith("/") ? "<DIR>" : formatSize(iso.sizeOf(i));
            this.append(pad(path, 34) + size);
            shown++;
        }
        if (entries.size() > shown) {
            this.append("... 还有 " + (entries.size() - shown) + " 项没列出来");
        }
    }

    private void catFile(SystemImage media, String path) {
        if (!(media instanceof CustomIso iso)) {
            this.append("虚拟机里没有能读的文件系统。");
            return;
        }
        if (path.isEmpty()) {
            this.append("用法: cat <文件路径>，先 ls 看看有什么");
            return;
        }
        CustomIso.IsoTextFile found = iso.findTextFile(path);
        if (found == null) {
            // 只给文件名也行
            String want = path.startsWith("/") ? path : "/" + path;
            for (CustomIso.IsoTextFile file : iso.getTextFiles()) {
                String filePath = file.path();
                if (filePath.equalsIgnoreCase(want) || filePath.endsWith(want)
                    || filePath.substring(filePath.lastIndexOf('/') + 1).equalsIgnoreCase(path)) {
                    found = file;
                    break;
                }
            }
        }
        if (found == null) {
            this.append("找不到这个文件: " + cut(path, 40));
            this.append("（虚拟机只保留了盘上几个小文本文件）");
            return;
        }
        this.append("--- " + cut(found.path(), 48) + " ---");
        String[] lines = found.content().split("\n");
        int shown = Math.min(lines.length, MAX_CAT_LINES);
        for (int i = 0; i < shown; i++) {
            this.append(cut(lines[i], MAX_LINE_CHARS));
        }
        if (lines.length > shown) {
            this.append("... 还有 " + (lines.length - shown) + " 行没显示");
        }
        this.append("--- 文件结束 ---");
    }

    private void info(SystemImage media) {
        this.append("PC-Craft VM 0.9 / 640K 内存 / 20MB 硬盘");
        if (media == null) {
            this.append("光驱: 空");
            return;
        }
        this.append("介质: " + cut(media.getDisplayName(), 44));
        this.append("类型: " + media.getMedia().getShortName() + " / " + cut(media.getVersion(), 40));
        if (media instanceof CustomIso iso) {
            this.append("文件: " + cut(iso.getFileName(), 56));
            if (!iso.getVolumeLabel().isEmpty()) {
                this.append("卷标: " + cut(iso.getVolumeLabel(), 44));
            }
            this.append("扇区大小 2048 字节，共 " + (iso.getSizeBytes() / 2048L) + " 个扇区");
            if (iso.getBootImageLba() > 0) {
                this.append("引导映像: LBA " + iso.getBootImageLba() + "，"
                    + iso.getBootImageSectors() + " 扇区");
                this.append("0x55AA 签名: " + (iso.hasBootSignature() ? "有" : "没有"));
            }
            this.append("根目录读到 " + iso.getEntries().size() + " 项");
        }
    }

    // ------------------------------------------------------------------
    // 引导脚本
    // ------------------------------------------------------------------

    private static List<String> script(SystemImage media) {
        List<String> out = new ArrayList<>();
        out.add("PC-Craft VM 0.9  (VGA 80x25, 640K RAM)");
        out.add("CPU : Piston Core 1x @ 1.00GHz");
        out.add("MEM : 640K OK");
        out.add("IDE0: 20MB 主盘 C:");
        if (media == null) {
            out.add("ATAPI CD-ROM: 空");
            out.add("BIOS: 没有找到可引导设备");
            out.add("虚拟机停机，挂一张 ISO 再试。");
            return out;
        }
        if (media instanceof CustomIso iso) {
            out.add("ATAPI CD-ROM: " + cut(iso.getFileName(), 46));
            out.add("读卷描述符 LBA 16 ... CD001");
            out.add("卷标: " + orNone(iso.getVolumeLabel()));
            out.add("系统标识: " + orNone(iso.getSystemId()));
            out.add("根目录: " + iso.getEntries().size() + " 项");
            if (iso.isBootable()) {
                out.add("El Torito 引导目录 ... 找到");
                if (iso.getBootImageLba() > 0) {
                    out.add("引导映像 LBA " + iso.getBootImageLba() + " / " + iso.getBootImageSectors() + " 扇区");
                    if (!iso.getBootSectorHex().isEmpty()) {
                        out.add("0000:7C00 " + cut(iso.getBootSectorHex(), 44));
                    }
                    out.add("0x55AA 引导签名 ... " + (iso.hasBootSignature() ? "OK" : "缺失"));
                    out.add("载入引导映像 -> 0x0000:7C00");
                    out.add("跳转 0x0000:7C00");
                } else {
                    out.add("引导目录里没读到可用的引导项");
                }
            } else {
                out.add("没有 El Torito 引导目录");
                if (!iso.getBootSectorHex().isEmpty()) {
                    out.add("0000:7C00 " + cut(iso.getBootSectorHex(), 44));
                }
                out.add("按 ISO9660 目录直接引导 ...");
            }
            for (CustomIso.IsoTextFile file : iso.getTextFiles()) {
                out.add("读 " + cut(file.path(), 40) + " ... " + file.content().length() + " 字节");
            }
            out.add("引导完成，虚拟机进入运行状态");
            out.add("输入 help 查看命令");
            return out;
        }
        out.add("ATAPI CD-ROM: " + cut(media.getDisplayName(), 40));
        out.add("读取自带引导代码 ... 完成");
        out.add("安装程序入口 /SETUP.EXE");
        for (String line : media.getBootLines()) {
            out.add(cut(line, MAX_LINE_CHARS));
        }
        out.add("引导完成，虚拟机进入运行状态");
        out.add("输入 help 查看命令");
        return out;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void append(String line) {
        String text = line == null ? "" : line;
        if (text.length() > MAX_LINE_CHARS) {
            text = text.substring(0, MAX_LINE_CHARS);
        }
        this.log.add(text);
        while (this.log.size() > MAX_LOG_LINES) {
            this.log.remove(0);
        }
    }

    private static String cut(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "..";
    }

    private static String pad(String text, int width) {
        String body = text.length() > width ? text.substring(0, width) : text;
        StringBuilder sb = new StringBuilder(body);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String orNone(String text) {
        return text == null || text.isBlank() ? "（无）" : cut(text, 44);
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fK", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fM", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    public void writeNbt(NbtCompound nbt) {
        nbt.putString("VmState", this.state.name());
        nbt.putString("VmMedia", this.mediaName);
        NbtList lines = new NbtList();
        for (String line : this.log) {
            lines.add(NbtString.of(line));
        }
        nbt.put("VmLog", lines);
        NbtList queue = new NbtList();
        for (String line : this.pending) {
            queue.add(NbtString.of(line));
        }
        nbt.put("VmPending", queue);
        nbt.putInt("VmTimer", this.lineTimer);
    }

    public void readNbt(NbtCompound nbt) {
        this.state = State.byOrdinal(indexOf(nbt.getString("VmState")));
        this.mediaName = nbt.getString("VmMedia");
        this.log.clear();
        NbtList lines = nbt.getList("VmLog", NbtElement.STRING_TYPE);
        for (int i = 0; i < lines.size() && this.log.size() < MAX_LOG_LINES; i++) {
            this.append(lines.getString(i));
        }
        this.pending.clear();
        NbtList queue = nbt.getList("VmPending", NbtElement.STRING_TYPE);
        for (int i = 0; i < queue.size(); i++) {
            this.pending.add(queue.getString(i));
        }
        this.lineTimer = Math.max(0, nbt.getInt("VmTimer"));
        if (this.state == State.BOOTING && this.pending.isEmpty()) {
            this.state = State.RUNNING;
        }
    }

    private static int indexOf(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        State[] values = State.values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].name().equals(name)) {
                return i;
            }
        }
        return 0;
    }
}
