package sbrkcp.core.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RKRS 字符串表（S1 / S2）。
 *
 * 五字段全部小端 u32，位于容器基址 + 固定偏移：
 *   0x30 中文索引表起始 / 0x34 中文文本区起始 / 0x44 中文文本区长度
 *   0x50 英文索引表起始 / 0x54 英文文本区起始
 * 索引记录 8 字节：u32 字节长度 + u32 文本偏移（同为相对容器基址的绝对偏移语义）。
 * 文本 UTF-16LE，长度含结尾 0x0000。
 *
 * 注意：本类不做"准入判定"。魔数 RKRS 可能撞车（图片容器也用它），
 * 因此只以字段自洽 + 表长 8 的倍数 + 可解码率打分，分数低只是不选中，不否定文件。
 */
public final class StringTable {

    public static final int OFF_CN_TAB = 0x30;
    public static final int OFF_CN_TXT = 0x34;
    public static final int OFF_CN_LEN = 0x44;
    public static final int OFF_EN_TAB = 0x50;
    public static final int OFF_EN_TXT = 0x54;
    public static final byte[] MAGIC = {'R', 'K', 'R', 'S'};

    /** 单个基址的解析结果。 */
    public static final class Hit {
        public long base;
        public int cnCount;
        public int enCount;
        public long cnTab;
        public long cnTxt;
        public long cnLen;
        public long enTab;
        public long enTxt;
        public double score;
        public String note = "";
        public final List<StringsEntry> entries = new ArrayList<StringsEntry>();

        public boolean ok() {
            return !entries.isEmpty();
        }

        public String describe() {
            return "base=0x" + Long.toHexString(base)
                    + " cn=" + cnCount + "=" + cnTab + "/" + cnTxt + "/" + cnLen
                    + " en=" + enCount + "=" + enTab + "/" + enTxt
                    + " score=" + String.format(java.util.Locale.ROOT, "%.2f", score)
                    + (note.isEmpty() ? "" : " (" + note + ")");
        }
    }

    private StringTable() {
    }

    public static long u32(byte[] d, long off) {
        if (off < 0 || off + 4 > d.length) return -1;
        int o = (int) off;
        return (d[o] & 0xFFL)
                | ((d[o + 1] & 0xFFL) << 8)
                | ((d[o + 2] & 0xFFL) << 16)
                | ((d[o + 3] & 0xFFL) << 24);
    }

    /** 全文滑窗找 RKRS，返回命中位置（魔数首字节所在偏移）。 */
    public static List<Long> findMagic(byte[] d, int limit) {
        List<Long> out = new ArrayList<Long>();
        byte[] m = MAGIC;
        int n = d.length - m.length;
        for (int i = 0; i <= n; i++) {
            if (d[i] == m[0] && d[i + 1] == m[1] && d[i + 2] == m[2] && d[i + 3] == m[3]) {
                out.add((long) i);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /**
     * 候选基址：命中点本身、命中点-4、命中点-0x28、命中点-0x2c，外加基址 0。
     * 全部尝试后按分数择优（S2），不做提前剪枝。
     */
    public static List<Long> candidateBases(byte[] d, int magicLimit) {
        List<Long> bases = new ArrayList<Long>();
        bases.add(0L);
        for (Long p : findMagic(d, magicLimit)) {
            long[] cands = {p.longValue(), p.longValue() - 4, p.longValue() - 0x28, p.longValue() - 0x2c};
            for (long c : cands) {
                if (c >= 0 && !bases.contains(Long.valueOf(c))) bases.add(Long.valueOf(c));
            }
        }
        return bases;
    }

    /** 按给定基址解析。永远返回对象，失败时 entries 为空并带 note。 */
    public static Hit parse(byte[] d, long base) {
        Hit h = new Hit();
        h.base = base;
        if (base < 0 || base + OFF_EN_TXT + 4 > d.length) {
            h.note = "基址越界";
            return h;
        }
        h.cnTab = u32(d, base + OFF_CN_TAB);
        h.cnTxt = u32(d, base + OFF_CN_TXT);
        h.cnLen = u32(d, base + OFF_CN_LEN);
        h.enTab = u32(d, base + OFF_EN_TAB);
        h.enTxt = u32(d, base + OFF_EN_TXT);

        StringBuilder bad = new StringBuilder();
        if (h.cnTab <= 0 || h.cnTab >= d.length) bad.append("cnTab ");
        if (h.cnTxt <= h.cnTab || h.cnTxt >= d.length) bad.append("cnTxt ");
        if (h.cnLen <= 0 || h.cnLen > d.length) bad.append("cnLen ");
        if (h.enTab <= h.cnTxt || h.enTab >= d.length) bad.append("enTab ");
        if (h.enTxt <= h.enTab || h.enTxt >= d.length) bad.append("enTxt ");
        if ((h.cnTxt - h.cnTab) % 8 != 0) bad.append("cnTable%8 ");
        if ((h.enTxt - h.enTab) % 8 != 0) bad.append("enTable%8 ");
        long cnSpan = h.cnTxt - h.cnTab;
        long enSpan = h.enTxt - h.enTab;
        if (cnSpan <= 0 || cnSpan > 64L * 1024 * 1024) bad.append("cnSpan ");
        if (enSpan <= 0 || enSpan > 64L * 1024 * 1024) bad.append("enSpan ");
        if (bad.length() > 0) {
            h.note = "字段不自洽: " + bad.toString().trim();
            h.score = 0;
            return h;
        }

        h.cnCount = (int) (cnSpan / 8);
        h.enCount = (int) (enSpan / 8);
        int n = Math.min(h.cnCount, h.enCount);
        if (h.enCount < h.cnCount) n = h.enCount;

        int decoded = 0;
        int badChar = 0;
        for (int i = 0; i < n; i++) {
            long r = base + h.cnTab + (long) i * 8;
            long len = u32(d, r);
            long off = u32(d, r + 4);
            if (len == 0 && off == 0) break; // 表尾
            if (len < 0 || len > 65535 || off < 0 || base + off + len > d.length) {
                badChar++;
                continue;
            }
            String cn = decodeUtf16(d, base + off, (int) len);
            String en = "";
            long re = base + h.enTab + (long) i * 8;
            if (i < h.enCount) {
                long lenE = u32(d, re);
                long offE = u32(d, re + 4);
                if (lenE > 0 && lenE <= 65535 && offE >= 0 && base + offE + lenE <= d.length) {
                    en = decodeUtf16(d, base + offE, (int) lenE);
                }
            }
            if (cn.indexOf('\uFFFD') >= 0 || en.indexOf('\uFFFD') >= 0) badChar++;
            if (cn.length() > 0) decoded++;
            StringsEntry e = new StringsEntry(i, base + off, cn, en, "S1", "UTF-16LE");
            e.byteLength = (int) len;
            h.entries.add(e);
        }

        double decodeRate = h.entries.isEmpty() ? 0 : (double) decoded / h.entries.size();
        double cleanRate = h.entries.isEmpty() ? 0 : 1.0 - (double) badChar / Math.max(1, h.entries.size());
        // 单调性：中文文本区结束应落在英文表起点附近（允许 8 字节对齐误差）
        long cnEnd = h.cnTxt + h.cnLen;
        long gap = Math.abs(cnEnd - h.enTab);
        double monoScore = gap <= 8 ? 1.0 : (gap <= 4096 ? 0.5 : 0.0);
        h.score = h.entries.size() * (0.5 + decodeRate) * (0.5 + cleanRate) * (0.5 + monoScore);
        if (gap > 8) {
            h.note = "文本区与英文表间隔 " + gap + " 字节";
        }
        return h;
    }

    /** 多基址择优：全跑一遍取最高分（S2）。 */
    public static Hit best(byte[] d, int magicLimit) {
        Hit best = new Hit();
        best.note = "无候选基址";
        for (Long b : candidateBases(d, magicLimit)) {
            Hit h = parse(d, b.longValue());
            if (h.score > best.score) best = h;
        }
        return best;
    }

    public static String decodeUtf16(byte[] d, long off, int len) {
        int n = len & ~1;
        if (off < 0 || off + n > d.length) return "";
        try {
            return new String(d, (int) off, n, "UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    /** 供调度器复用：把命中结果转成不可变列表。 */
    public static List<StringsEntry> entriesOf(Hit h) {
        if (h == null) return Collections.emptyList();
        return Collections.unmodifiableList(h.entries);
    }
}
