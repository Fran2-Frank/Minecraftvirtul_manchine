package cn.blockforge.generated.virtualmachine.computer;

import java.util.List;

/**
 * 可以被「装进电脑」的操作系统镜像。
 * 每个系统有自己的介质类型、安装耗时、配色、自带文件和小程序。
 */
public enum OsType implements SystemImage {
    LINDOWS("lindows", "Lindows 95", MediaKind.ISO, 260, 0xFF0B3D91, 0xFF4FA3E3, "95 SR2",
        List.of(AppId.FILES, AppId.TERMINAL, AppId.NOTES, AppId.ABOUT),
        List.of(
            new OsFile("README.TXT",
                "Lindows 95 快速上手\n"
                    + "===============\n"
                    + "1. 双击桌面上的图标打开程序。\n"
                    + "2. 终端里可以输入 help 看命令。\n"
                    + "3. 便签里的内容会存在这台电脑里。\n"
                    + "祝你玩得开心！"),
            new OsFile("CONFIG.SYS",
                "DEVICE=C:\\LINDOWS\\HIMEM.SYS\n"
                    + "DOS=HIGH,UMB\n"
                    + "FILES=30\n"
                    + "BUFFERS=20"),
            new OsFile("AUTOEXEC.BAT",
                "@ECHO OFF\n"
                    + "PROMPT $P$G\n"
                    + "PATH C:\\LINDOWS;C:\\LINDOWS\\COMMAND\n"
                    + "SET TEMP=C:\\TEMP"),
            new OsFile("WALLPAPER.BMP", "640x480 256 色 · 蓝天白云"),
            new OsFile("驱动记录.LOG", "显示适配器：PistonVGA 2MB\n声卡：NoteBlaster 16\n网卡：无")),
        List.of(
            "Lindows 95 正在启动 ...",
            "载入 HIMEM.SYS / VMM32.VXD",
            "检测硬件配置 ............ 正常",
            "初始化图形界面 ...",
            "欢迎回来，用户"),
        List.of(
            "C:\\LINDOWS\\SETUP.EXE",
            "C:\\LINDOWS\\SYSTEM\\VMM32.VXD",
            "C:\\LINDOWS\\SYSTEM\\KERNEL32.DLL",
            "C:\\LINDOWS\\SYSTEM\\USER32.DLL",
            "C:\\LINDOWS\\SYSTEM\\GDI32.DLL",
            "C:\\LINDOWS\\SYSTEM\\SHELL32.DLL",
            "C:\\LINDOWS\\SYSTEM\\PISTON.VXD",
            "C:\\LINDOWS\\FONTS\\ARIAL.FON",
            "C:\\LINDOWS\\HELP\\README.HLP",
            "C:\\LINDOWS\\MEDIA\\STARTUP.WAV",
            "C:\\LINDOWS\\SYSTEM.INI",
            "C:\\WINDOWS\\WIN.INI",
            "C:\\AUTOEXEC.BAT")),

    PENGUIX("penguix", "Penguix 2.6", MediaKind.ISO, 220, 0xFF1F1F24, 0xFFE95420, "2.6.0-tux",
        List.of(AppId.FILES, AppId.TERMINAL, AppId.NOTES, AppId.ABOUT),
        List.of(
            new OsFile("readme.md",
                "# Penguix 2.6\n"
                    + "- 内核：penguix 2.6.0\n"
                    + "- 桌面：TuxShell\n"
                    + "- 包管理：没有，自己编译。\n"
                    + "终端里 `help` 可以看命令。"),
            new OsFile("/etc/motd",
                "欢迎来到 Penguix。\n"
                    + "本机不联网，但足够自由。"),
            new OsFile("/home/guest/hello.c",
                "#include <stdio.h>\n"
                    + "int main(void) {\n"
                    + "    puts(\"hello, penguin\");\n"
                    + "    return 0;\n"
                    + "}"),
            new OsFile("/home/guest/INSTALL.LOG",
                "boot: 读取 ISO9660 ... 完成\n"
                    + "rootfs: 解包 ... 完成\n"
                    + "grub: 写入引导 ... 完成")),
        List.of(
            "Penguix 引导加载器 2.6.0",
            "解压内核镜像 ... 完成",
            "挂载根文件系统 (iso9660) ... 完成",
            "启动 TuxShell ...",
            "penguix login: guest"),
        List.of(
            "/boot/vmlinuz-2.6.0",
            "/boot/initrd.img-2.6.0",
            "/boot/grub/menu.lst",
            "/lib/modules/2.6.0/kernel/fs/ext2.ko",
            "/lib/modules/2.6.0/kernel/drivers/display/pistonvga.ko",
            "/lib/modules/2.6.0/kernel/drivers/input/kbd.ko",
            "/usr/bin/busybox",
            "/usr/bin/vi",
            "/usr/share/terminfo/p/penguix",
            "/etc/fstab",
            "/etc/passwd",
            "/var/log/install.log")),

    PCDOS("pcdos", "PC-DOS 6.2", MediaKind.IMG, 140, 0xFF000000, 0xFF2FA84F, "6.22",
        List.of(AppId.TERMINAL, AppId.ABOUT),
        List.of(
            new OsFile("README.TXT",
                "PC-DOS 6.2\n"
                    + "只有 1.44MB，却什么都能干。\n"
                    + "输入 help 查看命令，dir 看文件。"),
            new OsFile("AUTOEXEC.BAT",
                "@ECHO OFF\n"
                    + "PROMPT $P$G\n"
                    + "PATH A:\\"),
            new OsFile("COMMAND.COM", "DOS 命令解释器（二进制）"),
            new OsFile("EDIT.COM", "文本编辑器（二进制）")),
        List.of(
            "PC-DOS 引导扇区读取 ... OK",
            "加载 IO.SYS / MSDOS.SYS",
            "加载 COMMAND.COM",
            "A:\\>"),
        List.of(
            "A:\\IO.SYS",
            "A:\\MSDOS.SYS",
            "C:\\DOS\\COMMAND.COM",
            "C:\\DOS\\FORMAT.COM",
            "C:\\DOS\\FDISK.EXE",
            "C:\\DOS\\EDIT.COM",
            "C:\\DOS\\HIMEM.SYS",
            "C:\\DOS\\EMM386.EXE",
            "C:\\CONFIG.SYS",
            "C:\\AUTOEXEC.BAT")),

    NANOOS("nanoos", "NanoOS 0.1", MediaKind.IMG, 90, 0xFF16213A, 0xFF6FE3C4, "0.1-flash",
        List.of(AppId.TERMINAL, AppId.NOTES, AppId.ABOUT),
        List.of(
            new OsFile("note.txt",
                "NanoOS 是一个 64KB 的玩具系统。\n"
                    + "它只做三件事：开机、打字、关机。"),
            new OsFile("sys.cfg",
                "cpu=piston-core\n"
                    + "ram=64KB\n"
                    + "display=green-crt")),
        List.of(
            "NanoOS 0.1 (flash)",
            "内存自检 64KB ... OK",
            "加载 shell ...",
            "就绪。"),
        List.of(
            "/sys/kernel.bin",
            "/sys/shell.bin",
            "/sys/font.crt",
            "/sys/drv/vga.drv",
            "/sys/drv/kbd.drv",
            "/sys/drv/flash.drv",
            "/cfg/sys.cfg",
            "/cfg/boot.cfg",
            "/doc/note.txt"));

    private final String id;
    private final String displayName;
    private final MediaKind media;
    private final int installTicks;
    private final int darkColor;
    private final int lightColor;
    private final String version;
    private final List<AppId> apps;
    private final List<OsFile> files;
    private final List<String> bootLines;
    private final List<String> installPaths;

    OsType(String id, String displayName, MediaKind media, int installTicks, int darkColor, int lightColor,
           String version, List<AppId> apps, List<OsFile> files, List<String> bootLines,
           List<String> installPaths) {
        this.id = id;
        this.displayName = displayName;
        this.media = media;
        this.installTicks = installTicks;
        this.darkColor = darkColor;
        this.lightColor = lightColor;
        this.version = version;
        this.apps = apps;
        this.files = files;
        this.bootLines = bootLines;
        this.installPaths = installPaths;
    }

    public String getId() {
        return this.id;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public MediaKind getMedia() {
        return this.media;
    }

    public int getInstallTicks() {
        return this.installTicks;
    }

    public int getDarkColor() {
        return this.darkColor;
    }

    public int getLightColor() {
        return this.lightColor;
    }

    public String getVersion() {
        return this.version;
    }

    public List<AppId> getApps() {
        return this.apps;
    }

    public List<OsFile> getFiles() {
        return this.files;
    }

    public List<String> getBootLines() {
        return this.bootLines;
    }

    /** 装机时进度条下面滚动显示的「正在复制 C:\...」文件列表。 */
    public List<String> getInstallPaths() {
        return this.installPaths;
    }

    /** 安装程序的许可协议正文（四个系统用同一份模板，换个名字）。 */
    public String getLicenseText() {
        return "《" + this.displayName + " 最终用户许可协议》\n\n"
            + "1. 本软件按「原样」提供，出问题请先重启试试。\n\n"
            + "2. 可以装在一台电脑上，也可以拆了换台机器重装。\n\n"
            + "3. 不得用于自动挖矿，那是别的模组的业务。";
    }

    public static OsType byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (OsType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }

    /** 装机时显示的阶段文字对应的下标（0..3）。 */
    public static int installStage(float fraction) {
        if (fraction < 0.12f) {
            return 0;
        }
        if (fraction < 0.30f) {
            return 1;
        }
        if (fraction < 0.82f) {
            return 2;
        }
        if (fraction < 0.95f) {
            return 3;
        }
        return 4;
    }
}
