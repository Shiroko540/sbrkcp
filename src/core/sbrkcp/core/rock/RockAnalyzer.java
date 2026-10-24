package sbrkcp.core.rock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对目录表条目做分类与结构化探测，全部基于统计与自洽判据，
 * 不含型号、偏移、条目数等常量。
 */
public final class RockAnalyzer {

    /** 分类标签。 */
    public static final String C_EMPTY = "空槽";
    public static final String C_ZERO = "零填充";
    public static final String C_FILL = "分块填充";
    public static final String C_CIPHER = "高熵分块";
    public static final String C_TEXT = "明文文本";
    public static final String C_FONT = "点阵字库";
    public static final String C_INDEX = "索引表";
    public static final String C_PIXEL = "像素区";
    public static final String C_RAW = "明文二进制";
    public static final String C_PENDING = "待结构判定";

    /** 探测上下文。 */
    public static final class Ctx {
        public byte[] data;
        public RockTable table;
        public final List<FontHit> fonts = new ArrayList<FontHit>();
        public final List<PixelHit> pixels = new ArrayList<PixelHit>();
        public final List<IndexHit> indexes = new ArrayList<IndexHit>();
        public final List<BlockCluster> clusters = new ArrayList<BlockCluster>();
        public final List<TrialLog> trials = new ArrayList<TrialLog>();
        public final List<TextHit> texts = new ArrayList<TextHit>();
    }

    public static final class FontHit {
        public int entryIndex;
        public int glyphBytes;
        public int glyphW;
        public int glyphH;
        public int glyphCount;
        public double density;
        public double hamming;
        public long offset;
        public int windowLen;
    }

    public static final class PixelHit {
        public int entryIndex;
        public int rowBytes;
        public int width;
        public long offset;
        public long size;
        public String layout = "RGB565LE";
        public double rowDiff;
        public double globalDiff;
        public int tilePeriod;
    }

    public static final class IndexHit {
        public int entryIndex;
        public long offset;
        public int count;
        public int step;
        public long first;
        public long last;
    }

    public static final class BlockCluster {
        public int entryIndex;
        public int blockSize;
        public int blockCount;
        public String template = "";
        public double stableRatio;
    }

    public static final class TrialLog {
        public int entryIndex;
        public String algorithm;
        public String keyDesc;
        public boolean hit;
        public double printable;
        public double entropy;
        public String note = "";
    }

    public static final class TextHit {
        public int entryIndex;
        public long offset;
        public String encoding;
        public String text;
    }

    // ---------------------------------------------------------------- 主入口

    public static Ctx analyze(byte[] d, RockTable t) {
        Ctx c = new Ctx();
        c.data = d;
        c.table = t;
        for (RockTable.Entry e : t.entries) {
            if (e.isEmpty()) {
                e.category = C_EMPTY;
                e.evidence = "type=" + e.type + " size=" + e.size;
                continue;
            }
            int off = (int) e.offset;
            int len = (int) Math.min(e.size, Integer.MAX_VALUE);
            if (off < 0 || len <= 0 || off + len > d.length) {
                e.category = C_RAW;
                e.evidence = "区间越界，跳过统计";
                continue;
            }
            e.entropy = RockStats.entropy(d, off, len);
            e.zeroRatio = RockStats.zeroRatio(d, off, len);
            e.printable = RockStats.printableRatio(d, off, len);
            e.longestText = RockStats.longestPrintable(d, off, len, 8);
            e.repeat512 = RockStats.blockRepeat(d, off, len, 512);
            e.repeat16 = RockStats.blockRepeat(d, off, len, 16);
            e.selfSim0x200 = RockStats.selfSim(d, off, len, 512);
            classify(e);
        }
        // 结构化探测：先求结构（字库 / 像素 / 索引），再落高熵与填充
        for (RockTable.Entry e : t.entries) {
            if (e.isEmpty()) {
                continue;
            }
            int off = (int) e.offset;
            int len = (int) Math.min(e.size, Integer.MAX_VALUE);
            if (off + len > d.length || len <= 0) {
                continue;
            }
            if (C_CIPHER.equals(e.category)) {
                int bs = detectBlockSize(c, e, off, len);
                clusterBlocks(c, e, off, len, bs);
                trialDecrypt(c, e, off, len);
            } else {
                detectIndex(c, e, off, len);
                detectPixel(c, e, off, len);
                if (!C_INDEX.equals(e.category) && !C_PIXEL.equals(e.category)) {
                    detectTile(c, e, off, len);
                }
                if (!C_INDEX.equals(e.category) && !C_PIXEL.equals(e.category)) {
                    detectFont(c, e, off, len);
                }
                finalizeCategory(e);
            }
            if (e.printable > 0.10 || C_TEXT.equals(e.category) || C_RAW.equals(e.category)) {
                extractTexts(c, e, off, len);
            }
        }
        return c;
    }

    // ---------------------------------------------------------------- 分类

    private static void classify(RockTable.Entry e) {
        StringBuilder ev = new StringBuilder();
        ev.append("H=").append(fmt(e.entropy));
        ev.append(" Z=").append(fmt(e.zeroRatio));
        ev.append(" P=").append(fmt(e.printable));
        ev.append(" R512=").append(fmt(e.repeat512));
        if (e.zeroRatio >= 0.95) {
            e.category = C_ZERO;
            ev.append(" → 几乎全零");
        } else if (e.entropy >= 6.90 && e.printable < 0.50) {
            e.category = C_CIPHER;
            ev.append(" → 熵接近 8bit/byte");
        } else if (e.printable >= 0.60) {
            e.category = C_TEXT;
            ev.append(" → 可打印占比高");
        } else {
            e.category = C_PENDING;
            ev.append(" → 待结构判定");
        }
        e.evidence = ev.toString();
    }

    /** 结构探测后的收尾：仍未定性时按重复度 / 可打印率落类。 */
    private static void finalizeCategory(RockTable.Entry e) {
        if (!C_PENDING.equals(e.category)) {
            return;
        }
        if (e.repeat512 >= 0.40) {
            e.category = C_FILL;
            e.evidence += " → 512B 块重复率 " + fmt(e.repeat512);
        } else if (e.printable >= 0.40) {
            e.category = C_TEXT;
            e.evidence += " → 可打印占比 " + fmt(e.printable);
        } else {
            e.category = C_RAW;
            e.evidence += " → 无结构命中，列为原始二进制";
        }
    }

    // ---------------------------------------------------------------- 索引表

    /** 在明文区寻找 u16 等差索引（步长取 2 的幂次附近候选）。 */
    private static void detectIndex(Ctx c, RockTable.Entry e, int off, int len) {
        if (len < 512) {
            return;
        }
        int[] steps = {256, 512, 128, 1024, 64};
        int limit = Math.min(len, 1 << 20);
        for (int s : steps) {
            int bestRun = 0;
            int bestPos = -1;
            int run = 0;
            int pos = -1;
            long prev = -1;
            for (int i = 0; i + 2 <= limit; i += 2) {
                long v = RockTable.u32(c.data, off + i) & 0xffff;
                if (prev >= 0 && v - prev == s) {
                    if (run == 0) {
                        pos = i - 2;
                        run = 2;
                    } else {
                        run++;
                    }
                } else {
                    if (run > bestRun) {
                        bestRun = run;
                        bestPos = pos;
                    }
                    run = 0;
                }
                prev = v;
            }
            if (run > bestRun) {
                bestRun = run;
                bestPos = pos;
            }
            if (bestRun >= 128 && bestPos >= 0) {
                IndexHit h = new IndexHit();
                h.entryIndex = e.index;
                h.offset = e.offset + bestPos;
                h.count = bestRun;
                h.step = s;
                h.first = RockTable.u32(c.data, off + bestPos) & 0xffff;
                h.last = RockTable.u32(c.data, off + bestPos + (bestRun - 1) * 2) & 0xffff;
                c.indexes.add(h);
                if (C_PENDING.equals(e.category) || C_RAW.equals(e.category) || C_TEXT.equals(e.category)) {
                    e.category = C_INDEX;
                    e.evidence += " → u16 等差步长 " + s + " 连续 " + bestRun + " 项 @" + RockTable.hex(h.offset);
                }
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 像素

    /**
     * 行宽扫描：相邻行差异应显著低于全局中位水平。
     * 仅以统计显著性判定，不预设任何分辨率；行宽能整除条目长度者加权。
     */
    private static void detectPixel(Ctx c, RockTable.Entry e, int off, int len) {
        if (len < 32 * 32 || e.zeroRatio >= 0.60 || e.printable >= 0.55) {
            return;
        }
        int sampleRows = 16;
        int maxRow = 4096;
        double[] diffs = new double[maxRow / 2 + 1];
        double[] adj = new double[maxRow / 2 + 1];
        int bestIdx = -1;
        double bestAdj = Double.MAX_VALUE;
        double bestRaw = 0;
        boolean bestDiv = false;
        int k = 0;
        for (int rb = 32; rb <= maxRow; rb += 2) {
            if ((long) rb * sampleRows > len) {
                break;
            }
            int step = Math.max(2, (rb / 64) & ~1);
            double s = 0;
            int cnt = 0;
            for (int r = 0; r < sampleRows; r++) {
                int a = off + r * rb;
                int b = a + rb;
                for (int i = 0; i + 1 < rb; i += step) {
                    s += Math.abs((c.data[a + i] & 0xff) - (c.data[b + i] & 0xff));
                    cnt++;
                }
            }
            double d = cnt > 0 ? s / cnt : Double.MAX_VALUE;
            boolean div = (len % rb == 0);
            double a2 = div ? d * 0.85 : d;
            if (a2 < bestAdj) {
                bestAdj = a2;
                bestIdx = k;
                bestRaw = d;
                bestDiv = div;
            }
            diffs[k] = d;
            adj[k] = a2;
            k++;
        }
        if (bestIdx < 0 || k < 8) {
            return;
        }
        double[] sorted = java.util.Arrays.copyOf(diffs, k);
        java.util.Arrays.sort(sorted);
        double med = sorted[k / 2];
        int rowBytes = 32 + 2 * bestIdx;
        if (med < 1.0 || bestRaw > med * 0.60 || rowBytes < 128) {
            return;
        }
        PixelHit h = new PixelHit();
        h.entryIndex = e.index;
        h.rowBytes = rowBytes;
        h.width = rowBytes / 2;
        h.offset = e.offset;
        h.size = e.size;
        h.rowDiff = bestRaw;
        h.globalDiff = med;
        c.pixels.add(h);
        if (C_PENDING.equals(e.category) || C_RAW.equals(e.category)) {
            e.category = C_PIXEL;
        }
        e.evidence += " → 行宽 " + rowBytes + "B（宽 " + h.width + "px，邻行差 " + fmt(bestRaw)
                + " ≪ 中位 " + fmt(med) + (bestDiv ? "，行宽整除区间" : "") + "）";
    }

    /** 相邻行字节平均绝对差。 */
    private static double rowDiff(byte[] d, int off, int rb) {
        int step = Math.max(2, (rb / 64) & ~1);
        double s = 0;
        int cnt = 0;
        for (int r = 0; r < 16 && off + (r + 1) * rb + rb <= d.length; r++) {
            int a = off + r * rb;
            for (int i = 0; i + 1 < rb; i += step) {
                s += Math.abs((d[a + i] & 0xff) - (d[a + i + rb] & 0xff));
                cnt++;
            }
        }
        return cnt > 0 ? s / cnt : Double.MAX_VALUE;
    }

    /**
     * 周期分块像素区：文末填充常以固定周期重复，
     * 取其周期因数中邻行差最小者作行宽，不以任何分辨率为前提。
     */
    private static void detectTile(Ctx c, RockTable.Entry e, int off, int len) {
        if (len < 65536 || e.printable >= 0.55 || e.zeroRatio >= 0.85) {
            return;
        }
        int lim = Math.min(len, 262144);
        Map<String, List<Integer>> pos = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i + 4 <= lim; i++) {
            String h = hexStr(c.data, off + i, 4);
            List<Integer> l = pos.get(h);
            if (l == null) {
                l = new ArrayList<Integer>();
                pos.put(h, l);
            }
            if (l.size() < 6) {
                l.add(Integer.valueOf(i));
            }
        }
        int period = -1;
        int bestCnt = 0;
        for (List<Integer> l : pos.values()) {
            if (l.size() < 3) {
                continue;
            }
            int g = 0;
            for (int i = 1; i < l.size(); i++) {
                int dd = l.get(i).intValue() - l.get(i - 1).intValue();
                g = g == 0 ? dd : gcd(g, dd);
                if (g == 1) {
                    break;
                }
            }
            if (g >= 1024 && g <= len / 2 && (period < 0 || g > period)) {
                bestCnt = l.size();
                period = g;
            }
        }
        if (period < 0) {
            return;
        }
        int bestR = -1;
        double bd = Double.MAX_VALUE;
        for (int r = 128; r <= 4096; r += 2) {
            if (period % r != 0 || (long) r * 17 > len) {
                continue;
            }
            double dv = rowDiff(c.data, off, r);
            if (dv < bd) {
                bd = dv;
                bestR = r;
            }
        }
        if (bestR < 0) {
            return;
        }
        PixelHit h = new PixelHit();
        h.entryIndex = e.index;
        h.rowBytes = bestR;
        h.width = bestR / 2;
        h.offset = e.offset;
        h.size = e.size;
        h.rowDiff = bd;
        h.globalDiff = bd;
        h.tilePeriod = period;
        c.pixels.add(h);
        if (C_PENDING.equals(e.category) || C_RAW.equals(e.category)) {
            e.category = C_PIXEL;
        }
        e.evidence += " → 周期 " + period + "B（" + (period / bestR) + " 行/tile），行宽 "
                + bestR + "B = " + h.width + "px，邻行差 " + fmt(bd);
    }

    // ---------------------------------------------------------------- 字库

    /**
     * 点阵字模识别：按 glyphBytes 分块，要求位密度适中、块间差异适中。
     * 全条目不通时按 64KB 窗口滑动，以容纳「明文 + 字库」混排条目。
     */
    private static void detectFont(Ctx c, RockTable.Entry e, int off, int len) {
        FontHit h = tryFont(c, e, off, len);
        int win = 65536;
        if (h == null && len > win) {
            for (int base = 0; base + win <= len; base += 32768) {
                h = tryFont(c, e, off + base, win);
                if (h != null) {
                    h.offset = e.offset + base;
                    h.windowLen = win;
                    break;
                }
            }
        }
        if (h == null) {
            return;
        }
        c.fonts.add(h);
        if (C_RAW.equals(e.category) || C_PIXEL.equals(e.category) || C_PENDING.equals(e.category)) {
            e.category = C_FONT;
        }
        e.evidence += " → 字模 " + h.glyphBytes + "B/字 × " + h.glyphCount + " 字 @"
                + RockTable.hex(h.offset) + "（密度 " + fmt(h.density) + "，块间汉明 " + fmt(h.hamming) + "）";
    }

    private static FontHit tryFont(Ctx c, RockTable.Entry e, int off, int len) {
        int[] cand = {32, 16, 24, 48, 64, 72, 128, 36, 62};
        for (int gb : cand) {
            int n = len / gb;
            if (n < 32) {
                continue;
            }
            int take = Math.min(n, 512);
            double dens = 0;
            int usable = 0;
            for (int i = 0; i < take; i++) {
                double dv = RockStats.bitDensity(c.data, off + i * gb, gb);
                dens += dv;
                if (dv > 0.01 && dv < 0.62) {
                    usable++;
                }
            }
            dens /= take;
            double usableRatio = (double) usable / take;
            if (usableRatio < 0.80 || dens < 0.05 || dens > 0.45) {
                continue;
            }
            // 块间平均汉明距离
            int pairs = Math.min(take - 1, 256);
            long ham = 0;
            for (int i = 0; i < pairs; i++) {
                for (int b = 0; b < gb; b++) {
                    ham += Integer.bitCount((c.data[off + i * gb + b] ^ c.data[off + (i + 1) * gb + b]) & 0xff);
                }
            }
            double avgHam = (double) ham / pairs / gb;
            if (avgHam < 1.2) {
                continue;
            }
            FontHit h = new FontHit();
            h.entryIndex = e.index;
            h.glyphBytes = gb;
            h.glyphCount = n;
            h.glyphH = gb % 2 == 0 ? gb / 2 : 16;
            h.glyphW = h.glyphH;
            h.density = dens;
            h.hamming = avgHam;
            h.offset = e.offset;
            return h;
        }
        return null;
    }

    // ---------------------------------------------------------------- 高熵分块

    /**
     * 由条目自身求分块长度：取前 64KB 内高频 4 字节窗口，
     * 用其重复出现偏移差的公约数作为块长候选。
     */
    private static int detectBlockSize(Ctx c, RockTable.Entry e, int off, int len) {
        int lim = Math.min(len, 65536);
        Map<String, List<Integer>> pos = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i + 4 <= lim; i += 1) {
            String h = hexStr(c.data, off + i, 4);
            List<Integer> l = pos.get(h);
            if (l == null) {
                l = new ArrayList<Integer>();
                pos.put(h, l);
            }
            if (l.size() < 6) {
                l.add(Integer.valueOf(i));
            }
        }
        int bestGcd = -1;
        int bestCount = 0;
        for (List<Integer> l : pos.values()) {
            if (l.size() < 3) {
                continue;
            }
            int g = 0;
            for (int i = 1; i < l.size(); i++) {
                int d = l.get(i).intValue() - l.get(i - 1).intValue();
                g = g == 0 ? d : gcd(g, d);
                if (g == 1) {
                    break;
                }
            }
            if (g >= 64 && g <= 65536 && l.size() > bestCount) {
                bestCount = l.size();
                bestGcd = g;
            }
        }
        if (bestGcd > 0 && len / bestGcd >= 2) {
            e.evidence += " → 块长 " + bestGcd + "B（由 " + bestCount + " 次重复偏移求得）";
            return bestGcd;
        }
        return 512;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** 定长分块 + 块头模板聚类。 */
    private static void clusterBlocks(Ctx c, RockTable.Entry e, int off, int len, int bs) {
        int n = len / bs;
        if (n < 2) {
            return;
        }
        int probe = 8;
        int take = Math.min(n, 4096);
        StringBuilder tpl = new StringBuilder();
        double stable = 0;
        for (int b = 0; b < probe; b++) {
            int first = c.data[off + b] & 0xff;
            boolean same = true;
            for (int i = 1; i < take; i++) {
                if ((c.data[off + i * bs + b] & 0xff) != first) {
                    same = false;
                    break;
                }
            }
            if (same) {
                stable++;
                tpl.append(String.format("%02X ", first));
            } else {
                tpl.append("?? ");
            }
        }
        BlockCluster cl = new BlockCluster();
        cl.entryIndex = e.index;
        cl.blockSize = bs;
        cl.blockCount = n;
        cl.template = tpl.toString().trim();
        cl.stableRatio = stable / probe;
        c.clusters.add(cl);
        e.evidence += " → " + n + " 个 " + bs + "B 块，块头模板 [" + cl.template + "]";
    }

    // ---------------------------------------------------------------- 试解

    /** 候选密钥：全部来自文件自身与通用弱钥模板，不引用官方算法说明。 */
    private static List<String[]> keyCandidates(byte[] d, int off, int len) {
        List<String[]> ks = new ArrayList<String[]>();
        ks.add(new String[]{"rockchip", "hex"});
        ks.add(new String[]{"ROCKCHIP", "hex"});
        ks.add(new String[]{"rk26", "hex"});
        ks.add(new String[]{"RK26", "hex"});
        ks.add(new String[]{"rock", "hex"});
        ks.add(new String[]{"sbrkcp", "hex"});
        ks.add(new String[]{"0123456789abcdef", "hex"});
        // 版本串
        StringBuilder ver = new StringBuilder();
        for (int i = 0; i < Math.min(32, d.length) && d[i] != 0; i++) {
            ver.append((char) (d[i] & 0xff));
        }
        if (ver.length() >= 8) {
            ks.add(new String[]{ver.toString(), "text"});
        }
        // 条目首 8 / 16 / 32 字节
        for (int k : new int[]{8, 16, 32}) {
            if (len >= k) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < k; i++) {
                    sb.append(String.format("%02x", d[off + i] & 0xff));
                }
                ks.add(new String[]{sb.toString(), "hex"});
            }
        }
        // 条目高熵区中的固定串（取前 4KB 出现 >= 3 次的 8 字节窗口）
        Map<String, Integer> freq = new LinkedHashMap<String, Integer>();
        Map<String, Integer> first = new LinkedHashMap<String, Integer>();
        int lim = Math.min(len, 4096);
        for (int i = 0; i + 8 <= lim; i++) {
            String h = hexStr(d, off + i, 8);
            Integer cc = freq.get(h);
            freq.put(h, Integer.valueOf(cc == null ? 1 : cc.intValue() + 1));
            if (!first.containsKey(h)) {
                first.put(h, Integer.valueOf(i));
            }
        }
        int added = 0;
        for (Map.Entry<String, Integer> en : freq.entrySet()) {
            if (en.getValue().intValue() >= 3 && added < 6) {
                ks.add(new String[]{en.getKey(), "hex"});
                added++;
            }
        }
        return ks;
    }

    /** 批量试解：XOR(单字节全枚举) + RC4 / DES / 3DES / AES(ECB,CBC)。 */
    private static void trialDecrypt(Ctx c, RockTable.Entry e, int off, int len) {
        int n = Math.min(len, 8192);
        byte[] sample = new byte[n];
        System.arraycopy(c.data, off, sample, 0, n);

        // 1) 单字节 XOR 全枚举
        int bestK = -1;
        double bestP = -1;
        for (int k = 1; k < 256; k++) {
            byte[] t = new byte[n];
            for (int i = 0; i < n; i++) {
                t[i] = (byte) ((sample[i] ^ k) & 0xff);
            }
            double p = RockStats.printableRatio(t, 0, n);
            if (p > bestP) {
                bestP = p;
                bestK = k;
            }
        }
        TrialLog xl = new TrialLog();
        xl.entryIndex = e.index;
        xl.algorithm = "XOR-1B";
        xl.keyDesc = "0x" + Integer.toHexString(bestK) + "（256 组中最佳）";
        xl.printable = bestP;
        xl.hit = bestP > 0.60;
        c.trials.add(xl);

        // 2) 密钥算法
        List<String[]> keys = keyCandidates(c.data, off, len);
        for (String[] k : keys) {
            byte[] raw = rawKey(k[0], k[1]);
            tryBlock(c, e, sample, "RC4", k[0], rc4(raw, sample));
            for (String alg : new String[]{"DES", "DESede", "AES"}) {
                for (String mode : new String[]{"ECB", "CBC"}) {
                    tryBlock(c, e, sample, alg + "-" + mode, k[0], blockCipher(raw, alg, mode, sample));
                }
            }
        }
    }

    private static void tryBlock(Ctx c, RockTable.Entry e, byte[] sample,
                                 String alg, String keyDesc, byte[] out) {
        if (out == null || out.length < 16) {
            return;
        }
        TrialLog tl = new TrialLog();
        tl.entryIndex = e.index;
        tl.algorithm = alg;
        tl.keyDesc = shorten(keyDesc);
        tl.printable = RockStats.printableRatio(out, 0, out.length);
        tl.entropy = RockStats.entropy(out, 0, out.length);
        tl.hit = tl.printable > 0.60 && tl.entropy < 7.0;
        if (!tl.hit) {
            String s = new String(out, 0, Math.min(out.length, 2000));
            int run = longestRun(s);
            if (run >= 24) {
                tl.hit = true;
                tl.note = "最长可读串 " + run + " 字节";
            }
        }
        c.trials.add(tl);
    }

    private static byte[] rawKey(String k, String kind) {
        if ("hex".equals(kind)) {
            String s = k.replaceAll("[^0-9a-fA-F]", "");
            if (s.length() % 2 != 0) {
                s = "0" + s;
            }
            byte[] b = new byte[s.length() / 2];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
            return b;
        }
        return k.getBytes();
    }

    private static byte[] rc4(byte[] key, byte[] in) {
        if (key == null || key.length == 0) {
            return null;
        }
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xff)) & 0xff;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        byte[] out = new byte[in.length];
        int i = 0;
        j = 0;
        for (int k = 0; k < in.length; k++) {
            i = (i + 1) & 0xff;
            j = (j + s[i]) & 0xff;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
            out[k] = (byte) (in[k] ^ s[(s[i] + s[j]) & 0xff]);
        }
        return out;
    }

    private static byte[] blockCipher(byte[] raw, String alg, String mode, byte[] in) {
        try {
            String tk;
            int need;
            if ("DES".equals(alg)) {
                tk = "DES";
                need = 8;
            } else if ("DESede".equals(alg)) {
                tk = "DESede";
                need = 24;
            } else {
                tk = "AES";
                need = 16;
            }
            byte[] key = new byte[need];
            for (int i = 0; i < need; i++) {
                key[i] = raw[i % raw.length];
            }
            String cif = "CBC".equals(mode) ? alg + "/CBC/NoPadding" : alg + "/ECB/NoPadding";
            Cipher cp = Cipher.getInstance(cif);
            if ("CBC".equals(mode)) {
                cp.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, tk), new IvParameterSpec(new byte[8 == key.length ? 8 : 16]));
            } else {
                cp.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, tk));
            }
            int block = "AES".equals(alg) ? 16 : 8;
            int n = in.length - (in.length % block);
            return cp.doFinal(in, 0, n);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 文本

    /** 明文串抽取：ASCII（>=6）与 UTF-16LE 中文（>=2 字）。 */
    private static void extractTexts(Ctx c, RockTable.Entry e, int off, int len) {
        int lim = Math.min(len, 4 << 20);
        StringBuilder sb = new StringBuilder();
        int start = -1;
        for (int i = 0; i < lim; i++) {
            int b = c.data[off + i] & 0xff;
            boolean pr = b >= 0x20 && b < 0x7f;
            if (pr) {
                if (start < 0) {
                    start = i;
                }
                sb.append((char) b);
            } else {
                if (start >= 0 && sb.length() >= 6) {
                    TextHit h = new TextHit();
                    h.entryIndex = e.index;
                    h.offset = e.offset + start;
                    h.encoding = "ASCII";
                    h.text = sb.toString();
                    c.texts.add(h);
                }
                sb.setLength(0);
                start = -1;
            }
            if (c.texts.size() > 20000) {
                return;
            }
        }
        // UTF-16LE 中文
        StringBuilder u = new StringBuilder();
        int us = -1;
        for (int i = 0; i + 1 < lim; i += 2) {
            int lo = c.data[off + i] & 0xff;
            int hi = c.data[off + i + 1] & 0xff;
            int cp = lo | (hi << 8);
            boolean cjk = cp >= 0x4e00 && cp <= 0x9fff;
            boolean ascii2 = cp >= 0x20 && cp < 0x7f;
            if (cjk || ascii2) {
                if (us < 0) {
                    us = i;
                }
                u.append((char) cp);
            } else {
                if (us >= 0 && u.length() >= 2 && hasCjk(u)) {
                    TextHit h = new TextHit();
                    h.entryIndex = e.index;
                    h.offset = e.offset + us;
                    h.encoding = "UTF-16LE";
                    h.text = u.toString();
                    c.texts.add(h);
                }
                u.setLength(0);
                us = -1;
            }
        }
    }

    private static boolean hasCjk(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4e00 && ch <= 0x9fff) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 工具

    private static String hexStr(byte[] d, int off, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", d[off + i] & 0xff));
        }
        return sb.toString();
    }

    private static int longestRun(String s) {
        int best = 0;
        int cur = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x20 && ch < 0x7f) {
                cur++;
                if (cur > best) {
                    best = cur;
                }
            } else {
                cur = 0;
            }
        }
        return best;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    public static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.3f", Double.valueOf(v));
    }
}

