package cn.blockforge.generated.virtualmachine.computer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 读玩家电脑上那个 ISO 文件，把「能当虚拟机用的东西」全读出来。
 *
 * <p>只按扇区读几百 KB，不会把整个 700MB 的盘读进内存：
 * <ul>
 *   <li>ISO9660 卷描述符：卷标、系统标识、大小；</li>
 *   <li>根目录（和一层子目录）里每一条的真名、大小、扇区号；</li>
 *   <li>El Torito 引导目录，以及它指向的引导映像：起始扇区、扇区数、首扇区字节、0x55AA 签名；</li>
 *   <li>根目录里的小文本文件（README、isolinux.cfg 之类）的真实内容，虚拟机里可以 cat 出来。</li>
 * </ul>
 * 不是 ISO9660 的文件（比如 raw 整盘映像）也不会报错，只是文件列表为空。
 */
public final class IsoReader {
    private static final int SECTOR = 2048;
    private static final int MAX_ENTRIES = CustomIso.MAX_ENTRIES;
    private static final int MAX_SUBDIRS = 8;
    private static final int MAX_DIR_BYTES = 64 * SECTOR;
    private static final int BOOT_SECTOR_BYTES = 512;
    private static final int BOOT_HEX_BYTES = 48;
    private static final int MAX_TEXT_FILES = 4;
    private static final int MAX_TEXT_BYTES = 8192;
    private static final int MAX_TEXT_CHARS = CustomIso.MAX_TEXT_CHARS;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "txt", "cfg", "conf", "ini", "md", "nfo", "lic", "lst", "sh", "bat", "inf", "log");

    private IsoReader() {
    }

    /** 读一个镜像文件，永远不会返回 null；读不动会抛 IOException。 */
    public static CustomIso read(Path path) throws IOException {
        String fileName = path.getFileName() == null ? "image.iso" : path.getFileName().toString();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long size = raf.length();
            if (size <= 0) {
                throw new IOException("文件是空的");
            }
            List<String> entries = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            List<Integer> lbas = new ArrayList<>();
            String label = "";
            String system = "";
            boolean bootable = false;
            int bootCatalogLba = 0;
            int bootImageLba = 0;
            int bootImageSectors = 0;
            boolean bootSignature = false;
            String bootSectorHex = "";

            byte[] primary = sector(raf, 16);
            boolean iso9660 = isVolumeDescriptor(primary);
            if (iso9660) {
                byte[] primaryVd = null;
                byte[] jolietVd = null;
                for (int i = 16; i < 32; i++) {
                    byte[] vd = sector(raf, i);
                    if (vd == null) {
                        break;
                    }
                    int type = vd[0] & 0xFF;
                    if (type == 0xFF) {
                        break;
                    }
                    if (type == 0 && containsElTorito(vd)) {
                        bootable = true;
                        bootCatalogLba = le32(vd, 71);
                    }
                    if (type == 1 && primaryVd == null) {
                        primaryVd = vd;
                        label = ascii(vd, 40, 32);
                        system = ascii(vd, 8, 32);
                    }
                    if (type == 2 && isJoliet(vd) && jolietVd == null) {
                        jolietVd = vd;
                    }
                }
                byte[] chosen = jolietVd != null ? jolietVd : primaryVd;
                if (chosen != null) {
                    int rootLba = le32(chosen, 158);
                    int rootLen = le32(chosen, 166);
                    if (rootLba > 0 && rootLen > 0 && (long) rootLba * SECTOR < size) {
                        int[] budget = {MAX_SUBDIRS};
                        listDir(raf, size, rootLba, rootLen, jolietVd != null, "", entries, sizes, lbas, budget, 0);
                    }
                }
                // El Torito 引导目录 -> 默认引导项 -> 引导映像
                if (bootCatalogLba > 0) {
                    BootEntry entry = readBootEntry(raf, bootCatalogLba);
                    if (entry != null) {
                        bootImageLba = entry.lba();
                        bootImageSectors = entry.sectors();
                        byte[] boot = readBytes(raf, (long) bootImageLba * SECTOR, BOOT_SECTOR_BYTES);
                        if (boot != null) {
                            bootSignature = (boot[510] & 0xFF) == 0x55 && (boot[511] & 0xFF) == 0xAA;
                            bootSectorHex = hex(boot, BOOT_HEX_BYTES);
                        }
                    }
                }
            } else {
                // 不是 ISO9660：看看开头有没有 0x55AA 引导签名，文件列表留空
                byte[] head = new byte[BOOT_SECTOR_BYTES];
                raf.seek(0);
                int read = raf.read(head);
                bootable = read >= 512 && (head[510] & 0xFF) == 0x55 && (head[511] & 0xFF) == 0xAA;
                if (read > 0) {
                    bootSectorHex = hex(head, BOOT_HEX_BYTES);
                }
            }
            List<CustomIso.IsoTextFile> textFiles = readTextFiles(raf, entries, sizes, lbas);
            return new CustomIso(null, fileName, label, system, size, bootable, entries,
                toArray(sizes, entries.size()), toArray(lbas, entries.size()),
                bootImageLba, bootImageSectors, bootSignature, bootSectorHex, textFiles);
        }
    }

    // ------------------------------------------------------------------
    // El Torito 引导目录
    // ------------------------------------------------------------------

    private record BootEntry(int lba, int sectors) {
    }

    /**
     * 引导目录第一项是校验头，后面每一项是一个引导项（0x88 = 可引导）。
     * 取第一条可引导的，也就是 BIOS 会用的那个。
     */
    private static BootEntry readBootEntry(RandomAccessFile raf, int catalogLba) throws IOException {
        byte[] catalog = sector(raf, catalogLba);
        if (catalog == null) {
            return null;
        }
        for (int offset = 32; offset + 32 <= catalog.length; offset += 32) {
            int indicator = catalog[offset] & 0xFF;
            if (indicator != 0x88) {
                continue;
            }
            int sectors = le16(catalog, offset + 6);
            int lba = le32(catalog, offset + 8);
            if (lba > 0 && sectors > 0) {
                return new BootEntry(lba, sectors);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 目录解析
    // ------------------------------------------------------------------

    private static void listDir(RandomAccessFile raf, long fileSize, int lba, int length, boolean ucs2,
                                String prefix, List<String> out, List<Integer> sizes, List<Integer> lbas,
                                int[] dirBudget, int depth) throws IOException {
        int want = Math.min(length, MAX_DIR_BYTES);
        long offset = (long) lba * SECTOR;
        if (offset < 0 || offset + want > fileSize) {
            return;
        }
        byte[] data = new byte[want];
        raf.seek(offset);
        raf.readFully(data);
        int i = 0;
        while (i + 34 <= want && out.size() < MAX_ENTRIES) {
            int recLen = data[i] & 0xFF;
            if (recLen == 0) {
                // 一条目录的末尾：跳到下一个扇区开头
                i = ((i / SECTOR) + 1) * SECTOR;
                continue;
            }
            if (recLen < 34 || i + recLen > want) {
                break;
            }
            int extent = le32(data, i + 2);
            int dataLen = le32(data, i + 10);
            boolean dir = (data[i + 25] & 0x02) != 0;
            int nameLen = data[i + 32] & 0xFF;
            String name = decodeName(data, i + 33, nameLen, ucs2);
            i += recLen;
            if (name.isEmpty() || nameLen <= 1) {
                // 空名 / "." / ".."
                continue;
            }
            String path = prefix + "/" + name + (dir ? "/" : "");
            out.add(path);
            sizes.add(dataLen);
            lbas.add(extent);
            if (dir && depth == 0 && dirBudget[0] > 0) {
                dirBudget[0]--;
                listDir(raf, fileSize, extent, dataLen, ucs2, prefix + "/" + name, out, sizes, lbas, dirBudget, depth + 1);
            }
        }
    }

    private static String decodeName(byte[] data, int offset, int length, boolean ucs2) {
        if (length <= 0 || offset + length > data.length) {
            return "";
        }
        String raw;
        if (ucs2) {
            int chars = length / 2;
            StringBuilder sb = new StringBuilder(chars);
            for (int i = 0; i < chars; i++) {
                sb.append((char) (((data[offset + i * 2] & 0xFF) << 8) | (data[offset + i * 2 + 1] & 0xFF)));
            }
            raw = sb.toString();
        } else {
            raw = new String(data, offset, length, StandardCharsets.ISO_8859_1);
        }
        raw = raw.trim();
        int semi = raw.lastIndexOf(';');
        if (semi > 0) {
            raw = raw.substring(0, semi);
        }
        if (raw.endsWith(".")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < CustomIso.MAX_ENTRY_CHARS - 2; i++) {
            char c = raw.charAt(i);
            sb.append(c < 32 || c == 127 || c == '/' ? '_' : c);
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // 盘上的小文本文件（虚拟机里 cat 用）
    // ------------------------------------------------------------------

    private static List<CustomIso.IsoTextFile> readTextFiles(RandomAccessFile raf, List<String> entries,
                                                             List<Integer> sizes, List<Integer> lbas) {
        List<CustomIso.IsoTextFile> out = new ArrayList<>();
        for (int i = 0; i < entries.size() && out.size() < MAX_TEXT_FILES; i++) {
            String path = entries.get(i);
            if (path.endsWith("/")) {
                continue;
            }
            int size = i < sizes.size() ? sizes.get(i) : 0;
            int lba = i < lbas.size() ? lbas.get(i) : 0;
            if (size <= 0 || size > MAX_TEXT_BYTES || lba <= 0 || !looksLikeText(path)) {
                continue;
            }
            try {
                byte[] raw = readBytes(raf, (long) lba * SECTOR, Math.min(size, MAX_TEXT_BYTES));
                if (raw == null || raw.length == 0) {
                    continue;
                }
                String text = decodeText(raw);
                if (!text.isBlank()) {
                    out.add(new CustomIso.IsoTextFile(path, text));
                }
            } catch (IOException ignored) {
                // 某一条读不动不影响整个镜像
            }
        }
        return out;
    }

    private static boolean looksLikeText(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** 先按 UTF-8 解，解出替换符就退回单字节编码（老光盘多半是 ASCII / Latin-1）。 */
    private static String decodeText(byte[] raw) {
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        String text = utf8.indexOf('\uFFFD') >= 0 ? new String(raw, StandardCharsets.ISO_8859_1) : utf8;
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_TEXT_CHARS));
        for (int i = 0; i < text.length() && sb.length() < MAX_TEXT_CHARS; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (c == '\n') {
                    sb.append('\n');
                }
            } else if (c == '\t') {
                sb.append(' ');
            } else if (c >= 32 && c != 127) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static byte[] sector(RandomAccessFile raf, long lba) throws IOException {
        return readBytes(raf, lba * SECTOR, SECTOR);
    }

    private static byte[] readBytes(RandomAccessFile raf, long offset, int length) throws IOException {
        if (offset < 0 || length <= 0 || offset + length > raf.length()) {
            return null;
        }
        byte[] data = new byte[length];
        raf.seek(offset);
        raf.readFully(data);
        return data;
    }

    private static int[] toArray(List<Integer> values, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count && i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static String hex(byte[] data, int bytes) {
        int count = Math.min(bytes, data.length);
        StringBuilder sb = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(Character.forDigit((data[i] >> 4) & 0xF, 16)))
                .append(Character.toUpperCase(Character.forDigit(data[i] & 0xF, 16)));
        }
        return sb.toString();
    }

    private static boolean isVolumeDescriptor(byte[] vd) {
        return vd != null && vd[0] == 0x01 && "CD001".equals(new String(vd, 1, 5, StandardCharsets.US_ASCII));
    }

    private static boolean isJoliet(byte[] vd) {
        if (vd == null) {
            return false;
        }
        // 转义序列 %/@ %/C %/E 在卷描述符的第 88 字节
        return vd[88] == 0x25 && vd[89] == 0x2F
            && (vd[90] == 0x40 || vd[90] == 0x43 || vd[90] == 0x45);
    }

    private static boolean containsElTorito(byte[] vd) {
        String id = new String(vd, 7, 23, StandardCharsets.US_ASCII);
        return id.startsWith("EL TORITO SPECIFICATION");
    }

    private static String ascii(byte[] data, int offset, int length) {
        if (offset + length > data.length) {
            return "";
        }
        return new String(data, offset, length, StandardCharsets.US_ASCII).trim();
    }

    private static int le16(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            return 0;
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return -1;
        }
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }
}
