package cn.blockforge.generated.virtualmachine.computer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.PacketByteBuf;

/**
 * 玩家从自己电脑上选的那个 ISO 镜像。
 *
 * <p>游戏没法真的开虚拟机，但可以把这个 ISO 里「真实」的东西拿出来用：
 * 卷标、系统标识、大小、有没有引导记录，还有从根目录读出来的文件列表。
 * 于是开机自检、安装程序标题、正在复制的文件名、装完后的文件管理器，
 * 全都是这张盘自己的内容，而不是模组编的。
 *
 * <p>同一个镜像在服务端和客户端各有一份，靠 id 找回来（见 {@link #byId}）。
 */
public final class CustomIso implements SystemImage {
    public static final String ID_PREFIX = "custom:";
    public static final int MAX_ENTRIES = 96;
    public static final int MAX_ENTRY_CHARS = 96;
    /** 虚拟机里 cat 一个文件最多显示多少字符。 */
    public static final int MAX_TEXT_CHARS = 1400;

    private static final Map<String, CustomIso> REGISTRY = new ConcurrentHashMap<>();
    private static final int MAX_REGISTRY = 64;

    /** 盘上的一个小文本文件，虚拟机里可以 cat 出来。 */
    public record IsoTextFile(String path, String content) {
    }

    private final String id;
    private final String fileName;
    private final String volumeLabel;
    private final String systemId;
    private final long sizeBytes;
    private final boolean bootable;
    private final List<String> entries;
    private final int[] entrySizes;
    private final int[] entryLbas;
    private final int bootImageLba;
    private final int bootImageSectors;
    private final boolean bootSignature;
    private final String bootSectorHex;
    private final List<IsoTextFile> textFiles;
    private final String displayName;
    private final List<OsFile> files;
    private final List<String> installPaths;
    private final List<String> bootLines;

    /** 老构造器：只有路径列表，其余资料留空（存档 / 网络包里没有这些字段时用）。 */
    public CustomIso(String id, String fileName, String volumeLabel, String systemId,
                     long sizeBytes, boolean bootable, List<String> entries) {
        this(id, fileName, volumeLabel, systemId, sizeBytes, bootable, entries, null, null,
            0, 0, false, "", null);
    }

    /**
     * @param id              已存的 id（从存档 / 网络包里读回来时传），新建时传 null 会自动算
     * @param fileName        玩家选的文件名，例如 ubuntu-24.04-desktop-amd64.iso
     * @param volumeLabel     ISO9660 卷标，读不到就传空串
     * @param systemId        ISO9660 系统标识，读不到就传空串
     * @param sizeBytes       文件字节数
     * @param bootable        有没有 El Torito 引导记录
     * @param entries         目录里读到的路径（以 / 开头，目录以 / 结尾）
     * @param entrySizes      每个条目在盘上的字节数（和 entries 对齐）
     * @param entryLbas       每个条目在盘上的起始扇区（和 entries 对齐）
     * @param bootImageLba    El Torito 默认引导映像的起始扇区，0 表示没有
     * @param bootImageSectors 引导映像占几个 512 字节扇区
     * @param bootSignature   引导映像首扇区结尾是不是 0x55AA
     * @param bootSectorHex   引导映像首扇区前几十字节的十六进制，虚拟机开机时打出来
     * @param textFiles       盘上读到的小文本文件（虚拟机里 cat 用）
     */
    public CustomIso(String id, String fileName, String volumeLabel, String systemId,
                     long sizeBytes, boolean bootable, List<String> entries,
                     int[] entrySizes, int[] entryLbas,
                     int bootImageLba, int bootImageSectors, boolean bootSignature,
                     String bootSectorHex, List<IsoTextFile> textFiles) {
        this.fileName = clean(fileName, 96);
        this.volumeLabel = clean(volumeLabel, 32);
        this.systemId = clean(systemId, 40);
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.bootable = bootable;
        this.entries = sanitizeEntries(entries);
        this.entrySizes = align(entrySizes, this.entries.size());
        this.entryLbas = align(entryLbas, this.entries.size());
        this.bootImageLba = Math.max(0, bootImageLba);
        this.bootImageSectors = Math.max(0, bootImageSectors);
        this.bootSignature = bootSignature;
        this.bootSectorHex = clean(bootSectorHex, 200);
        this.textFiles = sanitizeTextFiles(textFiles);
        this.id = id != null && !id.isEmpty() ? id : computeId();
        this.displayName = makeDisplayName();
        this.installPaths = this.entries.isEmpty() ? fallbackPaths() : this.entries;
        this.files = buildFiles();
        this.bootLines = buildBootLines();
        register(this);
    }

    private static int[] align(int[] raw, int count) {
        int[] out = new int[count];
        if (raw != null) {
            System.arraycopy(raw, 0, out, 0, Math.min(raw.length, count));
        }
        return out;
    }

    private static List<IsoTextFile> sanitizeTextFiles(List<IsoTextFile> raw) {
        List<IsoTextFile> out = new ArrayList<>();
        if (raw == null) {
            return List.copyOf(out);
        }
        for (IsoTextFile file : raw) {
            if (file == null) {
                continue;
            }
            String path = clean(file.path(), MAX_ENTRY_CHARS);
            String content = cleanText(file.content(), MAX_TEXT_CHARS);
            if (path.isEmpty() || content.isEmpty()) {
                continue;
            }
            out.add(new IsoTextFile(path, content));
            if (out.size() >= 4) {
                break;
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // 构造辅助
    // ------------------------------------------------------------------

    private static List<String> sanitizeEntries(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return List.copyOf(out);
        }
        for (String entry : raw) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            String path = clean(entry, MAX_ENTRY_CHARS);
            if (path.isEmpty() || !path.startsWith("/")) {
                path = "/" + path;
            }
            if (!out.contains(path)) {
                out.add(path);
            }
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String clean(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(text.length(), maxChars));
        for (int i = 0; i < text.length() && sb.length() < maxChars; i++) {
            char c = text.charAt(i);
            if (c < 32 || c == 127) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 和 {@link #clean} 一样，但保留换行（文本文件内容要按行显示）。 */
    private static String cleanText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(text.length(), maxChars));
        for (int i = 0; i < text.length() && sb.length() < maxChars; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sb.append('\n');
            } else if (c >= 32 && c != 127) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private String computeId() {
        long hash = 0xcbf29ce484222325L;
        String seed = this.fileName + '|' + this.volumeLabel + '|' + this.sizeBytes + '|' + this.entries.size();
        for (int i = 0; i < seed.length(); i++) {
            hash ^= seed.charAt(i);
            hash *= 0x100000001b3L;
        }
        return ID_PREFIX + Long.toHexString(hash);
    }

    private String makeDisplayName() {
        if (!this.volumeLabel.isEmpty()) {
            return this.volumeLabel;
        }
        String name = this.fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.isEmpty() ? "IMAGE" : name;
    }

    private List<String> fallbackPaths() {
        // 不是 ISO9660 的镜像（比如整盘 raw 映像）：编一份看着合理的安装文件列表
        String base = "/" + (this.displayName.isEmpty() ? "IMAGE" : toToken(this.displayName));
        return List.of(base + "/BOOT.BIN", base + "/SYSTEM.IMG", base + "/DRIVERS.SYS", base + "/README.TXT");
    }

    private static String toToken(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < 12; i++) {
            char c = text.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
        }
        return sb.length() == 0 ? "IMAGE" : sb.toString();
    }

    private List<OsFile> buildFiles() {
        List<OsFile> out = new ArrayList<>();
        for (String path : this.installPaths) {
            boolean dir = path.endsWith("/");
            String base = path;
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            int slash = base.lastIndexOf('/');
            String name = slash >= 0 ? base.substring(slash + 1) : base;
            if (name.isEmpty()) {
                name = base;
            }
            String content = "来源：" + this.fileName + "\n"
                + "路径：" + path + "\n"
                + "类型：" + (dir ? "目录" : "文件") + "\n"
                + "卷标：" + (this.volumeLabel.isEmpty() ? "（无）" : this.volumeLabel) + "\n\n"
                + "这一条是从你选的 ISO 的目录结构里真实读出来的，\n"
                + "游戏里没法真的运行里面的程序，但文件名是真的。";
            out.add(new OsFile(name, content));
        }
        return List.copyOf(out);
    }

    private List<String> buildBootLines() {
        long mb = Math.max(1L, this.sizeBytes / (1024L * 1024L));
        List<String> lines = new ArrayList<>();
        lines.add("CD-ROM: " + this.fileName + " (" + mb + " MB)");
        lines.add("读取 ISO9660 卷结构 ... 完成");
        lines.add("挂载根目录 ... " + this.installPaths.size() + " 项");
        lines.add(this.bootable ? "El Torito 引导记录 ... 找到" : "无引导记录，按兼容模式引导");
        lines.add("启动 " + this.displayName + " ...");
        return List.copyOf(lines);
    }

    // ------------------------------------------------------------------
    // SystemImage
    // ------------------------------------------------------------------

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public String getDisplayName() {
        return this.displayName;
    }

    @Override
    public MediaKind getMedia() {
        return MediaKind.ISO;
    }

    @Override
    public int getInstallTicks() {
        long mb = Math.max(1L, this.sizeBytes / (1024L * 1024L));
        return (int) Math.min(420L, 90L + mb / 3L);
    }

    @Override
    public int getDarkColor() {
        return 0xFF000000 | hsv(hue(), 0.78f, 0.34f);
    }

    @Override
    public int getLightColor() {
        return 0xFF000000 | hsv(hue(), 0.55f, 0.82f);
    }

    @Override
    public String getVersion() {
        long mb = Math.max(1L, this.sizeBytes / (1024L * 1024L));
        return (this.bootable ? "El Torito" : "ISO9660") + " · " + mb + " MB";
    }

    @Override
    public List<AppId> getApps() {
        return List.of(AppId.FILES, AppId.TERMINAL, AppId.NOTES, AppId.ABOUT);
    }

    @Override
    public List<OsFile> getFiles() {
        return this.files;
    }

    @Override
    public List<String> getBootLines() {
        return this.bootLines;
    }

    @Override
    public List<String> getInstallPaths() {
        return this.installPaths;
    }

    @Override
    public String getLicenseText() {
        return "《" + this.displayName + " 最终用户许可协议》\n\n"
            + "1. 这张盘来自你自己的电脑：" + this.fileName + "。\n\n"
            + "2. 装进游戏里的只是它的目录结构和名字，不是真的系统。\n\n"
            + "3. 可以随便重装，硬盘清空不会影响你电脑上那个 ISO。";
    }

    public String getFileName() {
        return this.fileName;
    }

    public String getVolumeLabel() {
        return this.volumeLabel;
    }

    public String getSystemId() {
        return this.systemId;
    }

    public long getSizeBytes() {
        return this.sizeBytes;
    }

    public boolean isBootable() {
        return this.bootable;
    }

    public List<String> getEntries() {
        return this.entries;
    }

    /** 第 index 个条目在盘上的字节数，越界返回 0。 */
    public int sizeOf(int index) {
        return index >= 0 && index < this.entrySizes.length ? this.entrySizes[index] : 0;
    }

    /** 第 index 个条目在盘上的起始扇区，越界返回 0。 */
    public int lbaOf(int index) {
        return index >= 0 && index < this.entryLbas.length ? this.entryLbas[index] : 0;
    }

    public int getBootImageLba() {
        return this.bootImageLba;
    }

    public int getBootImageSectors() {
        return this.bootImageSectors;
    }

    /** 引导映像首扇区结尾是不是 0x55AA（真机 BIOS 就靠这个判断能不能引导）。 */
    public boolean hasBootSignature() {
        return this.bootSignature;
    }

    public String getBootSectorHex() {
        return this.bootSectorHex;
    }

    public List<IsoTextFile> getTextFiles() {
        return this.textFiles;
    }

    /** 盘上的某个文件（按路径找，忽略大小写），找不到返回 null。 */
    public IsoTextFile findTextFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String want = path.startsWith("/") ? path : "/" + path;
        for (IsoTextFile file : this.textFiles) {
            if (file.path().equalsIgnoreCase(want)) {
                return file;
            }
        }
        return null;
    }

    /** 聊天栏里的一句话介绍。 */
    public String summary() {
        long mb = Math.max(1L, this.sizeBytes / (1024L * 1024L));
        return this.displayName + "（" + mb + " MB）";
    }

    private float hue() {
        return (float) Math.floorMod(this.id.hashCode(), 360) / 360.0f;
    }

    /** 由 id 生成的稳定配色，保证同一个 ISO 每次颜色都一样。 */
    private static int hsv(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6.0f;
        int i = (int) hh % 6;
        float f = hh - (float) Math.floor(hh);
        int p = (int) (v * (1.0f - s) * 255.0f);
        int q = (int) (v * (1.0f - f * s) * 255.0f);
        int t = (int) (v * (1.0f - (1.0f - f) * s) * 255.0f);
        int vv = (int) (v * 255.0f);
        int r;
        int g;
        int b;
        switch (i) {
            case 0 -> {
                r = vv;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = vv;
                b = p;
            }
            case 2 -> {
                r = p;
                g = vv;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = vv;
            }
            case 4 -> {
                r = t;
                g = p;
                b = vv;
            }
            default -> {
                r = vv;
                g = p;
                b = q;
            }
        }
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // ------------------------------------------------------------------
    // 存档 / 网络 / 登记表
    // ------------------------------------------------------------------

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("Id", this.id);
        nbt.putString("File", this.fileName);
        nbt.putString("Label", this.volumeLabel);
        nbt.putString("System", this.systemId);
        nbt.putLong("Size", this.sizeBytes);
        nbt.putBoolean("Bootable", this.bootable);
        NbtList list = new NbtList();
        for (String entry : this.entries) {
            list.add(NbtString.of(entry));
        }
        nbt.put("Entries", list);
        nbt.putIntArray("EntrySizes", this.entrySizes);
        nbt.putIntArray("EntryLbas", this.entryLbas);
        nbt.putInt("BootLba", this.bootImageLba);
        nbt.putInt("BootSectors", this.bootImageSectors);
        nbt.putBoolean("BootSig", this.bootSignature);
        nbt.putString("BootHex", this.bootSectorHex);
        NbtList texts = new NbtList();
        for (IsoTextFile file : this.textFiles) {
            NbtCompound one = new NbtCompound();
            one.putString("Path", file.path());
            one.putString("Text", file.content());
            texts.add(one);
        }
        nbt.put("TextFiles", texts);
        return nbt;
    }

    public static CustomIso fromNbt(NbtCompound nbt) {
        if (nbt == null || !nbt.contains("File")) {
            return null;
        }
        NbtList list = nbt.getList("Entries", NbtElement.STRING_TYPE);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < list.size() && entries.size() < MAX_ENTRIES; i++) {
            entries.add(list.getString(i));
        }
        NbtList texts = nbt.getList("TextFiles", NbtElement.COMPOUND_TYPE);
        List<IsoTextFile> textFiles = new ArrayList<>();
        for (int i = 0; i < texts.size() && textFiles.size() < 4; i++) {
            NbtCompound one = texts.getCompound(i);
            textFiles.add(new IsoTextFile(one.getString("Path"), one.getString("Text")));
        }
        return new CustomIso(nbt.getString("Id"), nbt.getString("File"), nbt.getString("Label"),
            nbt.getString("System"), nbt.getLong("Size"), nbt.getBoolean("Bootable"), entries,
            nbt.getIntArray("EntrySizes"), nbt.getIntArray("EntryLbas"),
            nbt.getInt("BootLba"), nbt.getInt("BootSectors"), nbt.getBoolean("BootSig"),
            nbt.getString("BootHex"), textFiles);
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(this.id, 64);
        buf.writeString(this.fileName, 96);
        buf.writeString(this.volumeLabel, 40);
        buf.writeString(this.systemId, 48);
        buf.writeVarLong(this.sizeBytes);
        buf.writeBoolean(this.bootable);
        buf.writeVarInt(this.entries.size());
        for (String entry : this.entries) {
            buf.writeString(entry, MAX_ENTRY_CHARS);
        }
        for (int value : this.entrySizes) {
            buf.writeVarInt(value);
        }
        for (int value : this.entryLbas) {
            buf.writeVarInt(value);
        }
        buf.writeVarInt(this.bootImageLba);
        buf.writeVarInt(this.bootImageSectors);
        buf.writeBoolean(this.bootSignature);
        buf.writeString(this.bootSectorHex, 200);
        buf.writeVarInt(this.textFiles.size());
        for (IsoTextFile file : this.textFiles) {
            buf.writeString(file.path(), MAX_ENTRY_CHARS);
            buf.writeString(file.content(), MAX_TEXT_CHARS);
        }
    }

    /** 客户端来的数据不可信：长度全在 codec 里卡住了，这里再把条目数卡一遍。 */
    public static CustomIso read(PacketByteBuf buf) {
        String id = buf.readString(64);
        String file = buf.readString(96);
        String label = buf.readString(40);
        String system = buf.readString(48);
        long size = buf.readVarLong();
        boolean bootable = buf.readBoolean();
        int count = Math.max(0, Math.min(MAX_ENTRIES, buf.readVarInt()));
        List<String> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(buf.readString(MAX_ENTRY_CHARS));
        }
        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) {
            sizes[i] = buf.readVarInt();
        }
        int[] lbas = new int[count];
        for (int i = 0; i < count; i++) {
            lbas[i] = buf.readVarInt();
        }
        int bootLba = buf.readVarInt();
        int bootSectors = buf.readVarInt();
        boolean bootSig = buf.readBoolean();
        String bootHex = buf.readString(200);
        int textCount = Math.max(0, Math.min(4, buf.readVarInt()));
        List<IsoTextFile> textFiles = new ArrayList<>(textCount);
        for (int i = 0; i < textCount; i++) {
            textFiles.add(new IsoTextFile(buf.readString(MAX_ENTRY_CHARS), buf.readString(MAX_TEXT_CHARS)));
        }
        return new CustomIso(id, file, label, system, size, bootable, entries,
            sizes, lbas, bootLba, bootSectors, bootSig, bootHex, textFiles);
    }

    public static CustomIso byId(String id) {
        return id == null ? null : REGISTRY.get(id);
    }

    public static void register(CustomIso iso) {
        if (iso == null) {
            return;
        }
        if (REGISTRY.size() > MAX_REGISTRY) {
            REGISTRY.clear();
        }
        REGISTRY.put(iso.id, iso);
    }
}
