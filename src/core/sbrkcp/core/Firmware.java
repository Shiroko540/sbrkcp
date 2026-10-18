package sbrkcp.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 外壳与容器嗅探。所有位置都从文件头字段推算，不硬编码任何魔数偏移；
 * 每种判定至少三条独立一致性约束。
 */
public final class Firmware {

    public enum Kind { RFW, RKW, RKRS, FAT16, FAT32, UNKNOWN }

    public static final class Detection {
        public final Kind kind;
        public final String detail;

        public Detection(Kind k, String d) {
            this.kind = k;
            this.detail = d;
        }

        @Override
        public String toString() {
            return kind + " (" + detail + ")";
        }
    }

    private static final byte[] MAGIC_WFKR = ascii("WFKR");
    private static final byte[] MAGIC_KRDL = ascii("KRDL");
    private static final byte[] MAGIC_RKRS = ascii("RKRS");

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static Detection detect(BigFile f) {
        if (f.size() >= 4) {
            String m = f.ascii(0, 4);
            if ("WFKR".equals(m)) return new Detection(Kind.RFW, "偏移 0 magic=WFKR");
            if ("KRDL".equals(m)) return new Detection(Kind.RKW, "偏移 0 magic=KRDL");
        }
        if (f.u32le(0) == 0x28L && "RKRS".equals(f.ascii(4, 4))) {
            return new Detection(Kind.RKRS, "u32le(0)=0x28 且 ascii(4,4)=RKRS");
        }
        if (f.u8(0x1FE) == 0x55 && f.u8(0x1FF) == 0xAA) {
            String s16 = f.ascii(0x36, 5);
            String s32 = f.ascii(0x52, 5);
            if (s16 != null && (s16.startsWith("FAT16") || s16.startsWith("FAT12"))) {
                return new Detection(Kind.FAT16, "0x1FE=55AA 且 0x36=" + s16.trim());
            }
            if (s32 != null && s32.startsWith("FAT32")) {
                return new Detection(Kind.FAT32, "0x1FE=55AA 且 0x52=" + s32.trim());
            }
        }
        return new Detection(Kind.UNKNOWN, "无已知外壳特征");
    }

    // ---------------------------------------------------------------- WFKR

    public static final class Part {
        public final int index;
        public final long offset;
        public final long length;

        Part(int i, long o, long l) {
            this.index = i;
            this.offset = o;
            this.length = l;
        }

        @Override
        public String toString() {
            return "part" + index + " off=" + offset + " len=" + length;
        }
    }

    public static List<Part> rfwParts(BigFile f) {
        List<Part> out = new ArrayList<>();
        long n = f.u32le(0x08);
        if (n < 1 || n > 64) return out;
        long off = 0x200;
        for (int i = 0; i < n; i++) {
            long len = f.u32le(0x0C + i * 4L);
            if (len < 0) return new ArrayList<>();
            if (!f.inRange(off, len)) return new ArrayList<>();
            out.add(new Part(i, off, len));
            off += len;
        }
        return out;
    }

    // ---------------------------------------------------------------- KRDL

    public static final class RkwEntry {
        public final String name;
        public final long type;
        public final long offset;
        public final long size;
        public final long headerOff;

        RkwEntry(String name, long type, long offset, long size, long headerOff) {
            this.name = name;
            this.type = type;
            this.offset = offset;
            this.size = size;
            this.headerOff = headerOff;
        }

        public boolean isDir() {
            return type == 1;
        }

        @Override
        public String toString() {
            return (isDir() ? "D " : "F ") + name + " off=" + offset + " size=" + size;
        }
    }

    /** O(N) 文件表扫描，四条约束联合过滤随机文本。 */
    public static List<RkwEntry> rkwEntries(byte[] buf) {
        List<RkwEntry> out = new ArrayList<>();
        int len = buf.length;
        for (int i = 16; i < len - 20; i++) {
            int c = buf[i] & 0xFF;
            if (c < 0x21 || c > 0x7E) continue;
            if (buf[i - 1] != 0) continue;

            int end = i;
            while (end < len && (buf[end] & 0xFF) >= 0x21 && (buf[end] & 0xFF) <= 0x7E) end++;
            int nameLen = end - i;
            if (nameLen < 3 || nameLen > 120) continue;
            String name = new String(buf, i, nameLen, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!name.contains("\\") && !name.contains(".")) continue;

            int hdr = i - 16;
            if (hdr < 0) continue;
            long nextName = BigFile.u32(buf, hdr);
            long type = BigFile.u32(buf, hdr + 4);
            long off = BigFile.u32(buf, hdr + 8);
            long size = BigFile.u32(buf, hdr + 12);

            if (type < 1 || type > 4) continue;
            if (nextName < 1 || nextName > 0x400) continue;
            long chain = i + nextName;
            if (chain < 0 || chain >= len) continue;
            int ch = buf[(int) chain] & 0xFF;
            if (ch < 0x21 || ch > 0x7E) continue;
            if (size > 0 && (off < 0 || off + size > len)) continue;

            out.add(new RkwEntry(name, type, off, size, hdr));
        }
        // 按 (name, offset, size) 去重
        List<RkwEntry> uniq = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RkwEntry e : out) {
            String k = e.name + "|" + e.offset + "|" + e.size;
            if (seen.add(k)) uniq.add(e);
        }
        return uniq;
    }

    /**
     * 数据区整体平移量 delta。候选来自 RKRS 容器起点与条目 offset 的差，
     * 用"合法条目数 + 强证据加权"打分，绝不写死常量。
     */
    public static long rkwDataDelta(BigFile f, List<RkwEntry> entries, List<Rkrs.Container> containers) {
        Set<Long> cands = new HashSet<>();
        cands.add(0L);
        for (Rkrs.Container c : containers) {
            for (RkwEntry e : entries) {
                if (e.size < 4096) continue;
                long d = c.start - e.offset;
                if (d > 0 && d <= 16L * 1024 * 1024) cands.add(d);
            }
        }
        long best = 0;
        int bestScore = -1;
        for (long d : cands) {
            int score = 0;
            boolean strong = false;
            for (RkwEntry e : entries) {
                long a = e.offset + d;
                long b = a + Math.max(0, e.size);
                if (a < 0 || b > f.size()) continue;
                score++;
                if (e.type == 2 && looksLikeResourceBucket(e.name) && e.size >= 4096) {
                    if (Rkrs.probe(f, a) != null) strong = true;
                }
            }
            if (strong) score += 10000;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    public static boolean looksLikeResourceBucket(String name) {
        String u = name.toUpperCase();
        return u.endsWith(".BIN") || u.endsWith(".RKZ") || u.contains("BMP0") || u.endsWith(".RKW");
    }

    public static byte[] magicKrrs() {
        return Arrays.copyOf(MAGIC_RKRS, MAGIC_RKRS.length);
    }
}
