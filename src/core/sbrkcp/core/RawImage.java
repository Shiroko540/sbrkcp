package sbrkcp.core;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;

/**
 * 裸图段（RK26xx / RK27xx 早期方案）：记录头 0x200 字节 + RGB565 大端像素。
 * 采用链式段发现，不用固定偏移顺序切。
 * <p>判据（V3I ROCK.RFW 实测校正）：记录长 = 0x200 + w*h*2，<b>记录之间零填充紧邻，
 * 不做任何对齐运算</b>；段内 &gt;=3 条连续合法记录才收段。w/h 下界取 {@link #MIN_SIDE}，
 * 用于剔除 1x1 / 1x2 这类由像素数据误判出的退化伪头（保留 4x4 及以上的真实图标）。</p>
 * <p>历史坑：早期版本按 4 字节对齐推进（(len+3)&amp;~3），只有 w*h*2 能被 4 整除的记录
 * 才不漂移，实测表现为"每段第 1 条尺寸正确、第 2 条起离谱、3~4 条后链断"，P0/P1 少采一段、
 * P2/P3 各漏采数十条。</p>
 */
public final class RawImage {

    /** w/h 下界：低于该值的记录视为数据误判，不收。 */
    private static final int MIN_SIDE = 4;
    /** w/h 上界：硬上限 2048（V3I 口径）。 */
    private static final int MAX_SIDE = 2048;
    /**
     * 单条记录像素数上限：实测最大真记录 1277x1277≈1.63M、1560x1022≈1.59M。
     * 放开到 2048x2048 会让像素数据里偶然凑出的 4 字节伪头一次吃掉 8MB，
     * 把后面的真实段整段吞掉（实测 P2 0x0E43B0 处即如此）。
     */
    private static final long MAX_PIXELS = 1700000L;
    /** 成段所需的最少连续记录数：孤立/双条命中不作为段（文档 3.4 口径）。 */
    private static final int MIN_CHAIN = 3;
    /** 扫描步进：段起点在"分区内相对偏移"上恒为 4 的倍数（文件绝对偏移可能不对齐）。 */
    private static final int STEP = 4;

    /** 记录头偏移 +4 处的 u16 字段：实测真实 UI 记录恒为 0x0018，用于可信度诊断（不作硬过滤）。 */
    private static final int F3_TRUSTED = 0x0018;

    public static final class Segment {
        public final long offset;
        public final int count;
        public final long bytes;
        /** 段首记录 +4 处的 u16 值。 */
        public final int f3First;
        /** 段内该字段的取值种类数；1 表示整段同族。 */
        public final int f3Distinct;

        public Segment(long offset, int count, long bytes, int f3First, int f3Distinct) {
            this.offset = offset;
            this.count = count;
            this.bytes = bytes;
            this.f3First = f3First;
            this.f3Distinct = f3Distinct;
        }

        /** 头部字段同族且等于已知真实值，判定为可信段；仅用于报告诊断，不影响导出。 */
        public boolean trusted() {
            return f3Distinct == 1 && f3First == F3_TRUSTED;
        }

        @Override
        public String toString() {
            return "段@0x" + Long.toHexString(offset) + " 记录=" + count + " 字节=" + bytes
                    + " f3=0x" + String.format("%04x", f3First) + " 变体=" + f3Distinct
                    + (trusted() ? " 可信" : " 疑似伪段");
        }
    }

    private static int u16be(byte[] d, int p) {
        if (p < 0 || p + 1 >= d.length) return -1;
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    /**
     * 单条记录占用字节数 = 0x200（头/保留区）+ w*h*2（RGB565 像素）。
     * <b>不做任何对齐</b>：记录之间零填充紧邻，next = cur + 0x200 + w*h*2。
     * 返回 -1 表示该位置不是合法记录头。
     */
    private static long recordLen(byte[] d, long p) {
        if (p < 0 || p + 0x200 > d.length) return -1;
        int w = u16be(d, (int) p);
        int h = u16be(d, (int) p + 2);
        if (w < MIN_SIDE || w > MAX_SIDE || h < MIN_SIDE || h > MAX_SIDE) return -1;
        if ((long) w * h > MAX_PIXELS) return -1;
        return 0x200L + (long) w * h * 2;
    }

    /**
     * 链式扫描整个数据块：连续 {@link #MIN_CHAIN} 条以上合法记录即成一个段，贪婪推进到链断为止。
     * <p>步进用 {@link #STEP} 字节（分区内相对偏移口径）：段起点只保证"分区内相对偏移"是 4 的倍数，
     * 换算到文件绝对偏移后可能落在奇数字节（如 P3 基址 0x11DAC3B + 0x500 = 0x11DB13B），
     * 因此绝不能对文件绝对偏移做对齐，只能在相对偏移上按 4 步进。</p>
     * <p>注意：链式扫描不会在已收段内部再找段，所以一条超长"记录"若是像素数据偶然凑出的伪头，
     * 会把其覆盖区间内的真实短段整段吞掉（如 P2 0x00B1740C 的 1119x1087 吞掉 0x00BC2A7C 的
     * 1018x855）。{@link #MAX_PIXELS} 就是用来压住这类吞噬规模的闸门。</p>
     */
    public static List<Segment> scan(byte[] d, long base) {
        List<Segment> out = new ArrayList<>();
        long p = 0;
        long end = d.length;
        while (p + 0x200 < end) {
            long first = p;
            long total = 0;
            int recs = 0;
            long cur = p;
            while (true) {
                long len = recordLen(d, cur);
                if (len <= 0 || cur + len > end) break;
                recs++;
                total += len;
                cur += len;
            }
            if (recs >= MIN_CHAIN) {
                int f3First = u16be(d, (int) first + 4);
                java.util.LinkedHashSet<Integer> f3s = new java.util.LinkedHashSet<>();
                long t = first;
                for (int i = 0; i < recs; i++) {
                    f3s.add(u16be(d, (int) t + 4));
                    t += recordLen(d, t);
                }
                out.add(new Segment(base + first, recs, total, f3First, f3s.size()));
                p = cur;
            } else {
                p += STEP;
            }
        }
        return out;
    }

    /** 导出段内所有记录为 PNG，命名 s{段起点:08x}_{序号:04d}_{w}x{h}.png。 */
    public static int export(byte[] d, Segment seg, long dataBase, File outDir,
                             ProgressListener p, UnpackResult r, UnpackOptions opt) {
        return export(d, seg, dataBase, outDir, p, r, opt, "");
    }

    /** 带文件名前缀的重载：RFW 多分区场景用 "partN_" 前缀，便于回溯归属。 */
    public static int export(byte[] d, Segment seg, long dataBase, File outDir,
                             ProgressListener p, UnpackResult r, UnpackOptions opt, String prefix) {
        long rel = seg.offset - dataBase;
        if (rel < 0 || rel >= d.length) return 0;
        int done = 0;
        long cur = rel;
        for (int i = 0; i < seg.count; i++) {
            if (p != null && p.isCancelled()) {
                if (r != null) r.cancelled = true;
                break;
            }
            long len = recordLen(d, cur);
            if (len <= 0 || cur + len > d.length) break;
            int w = u16be(d, (int) cur);
            int h = u16be(d, (int) cur + 2);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] px = new int[w * h];
            int base = (int) cur + 0x200;
            int q = base;
            for (int k = 0; k < w * h; k++) {
                if (q + 1 >= d.length) break;
                int v = ((d[q] & 0xFF) << 8) | (d[q + 1] & 0xFF);
                q += 2;
                int rr = (v >> 11) & 0x1F;
                int gg = (v >> 5) & 0x3F;
                int bb = v & 0x1F;
                rr = (rr << 3) | (rr >> 2);
                gg = (gg << 2) | (gg >> 4);
                bb = (bb << 3) | (bb >> 2);
                px[k] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
            if (opt != null && opt.flip) {
                int[] f = new int[px.length];
                for (int y = 0; y < h; y++) {
                    System.arraycopy(px, (h - 1 - y) * w, f, y * w, w);
                }
                px = f;
            }
            img.setRGB(0, 0, w, h, px, 0, w);

            String name = (prefix == null ? "" : prefix)
                    + String.format("s%08x_%04d_%dx%d.png", seg.offset, i, w, h);
            long bytes = 0;
            String path = "";
            if (opt == null || !opt.listOnly) {
                File of = new File(outDir, name);
                File parent = of.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                try {
                    ImageIO.write(img, "png", of);
                    bytes = of.length();
                    path = of.getAbsolutePath();
                } catch (IOException e) {
                    if (r != null) r.note(name + " 写盘失败: " + e.getMessage());
                    cur += len;
                    continue;
                }
            }
            done++;
            if (r != null) {
                r.pngCount++;
                r.entries.add(new UnpackResult.Entry(name, path, w, h, 16, 0, bytes));
            }
            cur += len;
        }
        return done;
    }
}
