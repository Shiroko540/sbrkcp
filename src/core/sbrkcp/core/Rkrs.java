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
 * RKRS 资源归档：探测、全盘扫描、条目解码与导出。
 * 容器起点 = "RKRS" 所在位置 - 4。
 */
public final class Rkrs {

    private static final byte[] MAGIC = {'R', 'K', 'R', 'S'};

    public static final class Container {
        public final long start;
        public final long headLen;
        public final long tableOff;
        public final long dataOff;
        public final long count;
        public final long totalSize;

        Container(long start, long headLen, long tableOff, long dataOff, long count, long totalSize) {
            this.start = start;
            this.headLen = headLen;
            this.tableOff = tableOff;
            this.dataOff = dataOff;
            this.count = count;
            this.totalSize = totalSize;
        }

        @Override
        public String toString() {
            return "RKRS@0x" + Long.toHexString(start) + " count=" + count
                    + " table=0x" + Long.toHexString(tableOff)
                    + " data=0x" + Long.toHexString(dataOff)
                    + " size=" + totalSize;
        }
    }

    /** 探测容器：dataOff == tableOff + count*8 为核心判据，另加四条约束。 */
    public static Container probe(BigFile f, long start) {
        if (start < 0 || !f.inRange(start, 0x48)) return null;
        if (!"RKRS".equals(f.ascii(start + 4, 4))) return null;
        long headLen = f.u32le(start);
        if (headLen != 0x28 && headLen != 0x20 && headLen != 0x2C) return null;
        long totalSize = f.u32le(start + 0x14);
        long tableOff = f.u32le(start + 0x30);
        long dataOff = f.u32le(start + 0x34);
        if (tableOff < 0x40 || tableOff > 16L * 1024 * 1024) return null;
        if (dataOff < tableOff) return null;
        if (totalSize <= 0 || !f.inRange(start, totalSize) || totalSize < dataOff) return null;
        if (!f.inRange(start + tableOff, 8)) return null;

        long count = -1;
        long[] countOffs = {0x3E, 0x3C, 0x38, 0x40, 0x44};
        for (long co : countOffs) {
            long c = f.u32le(start + co);
            if (c <= 0 || c > 200000) continue;
            if (dataOff == tableOff + c * 8) {
                count = c;
                break;
            }
        }
        if (count < 0) return null;

        int n = (int) Math.min(count, 24);
        int hit = 0;
        for (int i = 0; i < n; i++) {
            long off = f.u32le(start + tableOff + i * 8L + 4);
            if (off >= dataOff && off < totalSize) hit++;
        }
        if (n > 0 && hit * 2 < n) return null;
        return new Container(start, headLen, tableOff, dataOff, count, totalSize);
    }

    /** 全盘扫描所有 RKRS 容器，成功探测后跳过整个容器，保证 O(容器数)。 */
    public static List<Container> scanAll(BigFile f, long from, long to) {
        List<Container> out = new ArrayList<>();
        long pos = Math.max(0, from);
        long limit = Math.min(to, f.size());
        while (pos < limit) {
            long idx = f.find(MAGIC, pos, limit);
            if (idx < 0) break;
            long start = idx - 4;
            Container c = start >= 0 ? probe(f, start) : null;
            if (c != null) {
                out.add(c);
                long next = start + c.totalSize;
                pos = next > pos ? next : idx + 1;
            } else {
                pos = idx + 1;
            }
        }
        return out;
    }

    // -------------------------------------------------------------- 解码

    public static final class Decoded {
        public final BufferedImage image;
        public final int w;
        public final int h;
        public final int depth;
        public final int compress;
        /** 解码后的 ARGB 像素（未翻转）；供逐条像素 MD5 对拍使用。 */
        public final int[] px;
        /** 本条目实际采用的块头字节数（8 / 14）。 */
        public final int headerLen;

        Decoded(BufferedImage img, int[] px, int w, int h, int depth, int compress, int headerLen) {
            this.image = img;
            this.px = px;
            this.w = w;
            this.h = h;
            this.depth = depth;
            this.compress = compress;
            this.headerLen = headerLen;
        }
    }

    /**
     * 逐条像素 MD5：按 BGRA 字节序（B,G,R,A 每像素 4 字节，小端无换序）计算，
     * 与 tools/bmp0_ref.py 的口径一致。flip 不影响本值（始终用未翻转的原始像素）。
     */
    public static String pixelMd5(int[] px, int w, int h) {
        int n = w * h;
        if (px == null || n <= 0) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[16384];
            int p = 0;
            while (p < n) {
                int k = 0;
                while (p < n && k + 4 <= buf.length) {
                    int v = px[p++];
                    buf[k++] = (byte) (v & 0xFF);           // B
                    buf[k++] = (byte) ((v >>> 8) & 0xFF);   // G
                    buf[k++] = (byte) ((v >>> 16) & 0xFF);  // R
                    buf[k++] = (byte) ((v >>> 24) & 0xFF);  // A
                }
                md.update(buf, 0, k);
            }
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : out) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static Decoded decode(BigFile f, Container c, long index, boolean flip) {
        return decode(f, c, index, flip, null);
    }

    /**
     * 读取并解码一条资源；非法条目返回 null，绝不抛出。
     * diag 非空时写入失败细节（w/h/depth/comp/len/off/原因），避免静默跳过。
     */
    public static Decoded decode(BigFile f, Container c, long index, boolean flip, StringBuilder diag) {
        if (index < 0 || index >= c.count) {
            reason(diag, "索引越界");
            return null;
        }
        long len = f.u32le(c.start + c.tableOff + index * 8L);
        long off = f.u32le(c.start + c.tableOff + index * 8L + 4);
        if (off <= 0 || len < 8) {
            reason(diag, "索引项非法 len=" + len + " off=0x" + Long.toHexString(off));
            return null;
        }
        long abs = c.start + off;
        if (!f.inRange(abs, 8) || !f.inRange(abs, len)) {
            // 合法偏移但长度越界：按到容器末尾截断再试
            long avail = c.start + c.totalSize - abs;
            if (avail < 8) {
                reason(diag, "越界 len=" + len + " off=0x" + Long.toHexString(off) + " avail=" + avail);
                return null;
            }
            len = Math.min(len, avail);
        }
        // 资源头四个字段都是 int16 LE：w / h / depth / comp
        int w = f.u16le(abs);
        int h = f.u16le(abs + 2);
        int depth = f.u16le(abs + 4);
        int compress = f.u16le(abs + 6);
        if (compress != 0 && compress != 1 && compress != 5) {
            // 旧固件把压缩标志放在 +5，+6 取到非法值时回退
            int alt = f.u8(abs + 5);
            compress = (alt == 0 || alt == 1 || alt == 5) ? alt : 0;
        }
        if (w < 1 || w > 2048 || h < 1 || h > 2048) {
            reason(diag, "w/h 非法 w=" + w + " h=" + h + " len=" + len
                    + " off=0x" + Long.toHexString(off));
            return null;
        }
        if (depth != 8 && depth != 16 && depth != 24 && depth != 32) {
            reason(diag, "depth 非法 " + depth + " w=" + w + " h=" + h + " len=" + len
                    + " off=0x" + Long.toHexString(off));
            return null;
        }

        final int bpp = depth / 8;
        final long rawNeed = (long) w * h * bpp;
        // 资源头长度自适应：旧固件 8 字节，RK28 SDK V1.2 为 14 字节（+8 处再放
        // u16 mode + FF FF FF + CD，comp=5 的 mode 忽略）。写死任一值都会整体错位。
        int head = headerLen(f, abs, len, w, h, bpp, compress);
        if (head < 0) {
            reason(diag, "数据不足 comp=" + compress + " w=" + w + " h=" + h
                    + " depth=" + depth + " len=" + len + " off=0x" + Long.toHexString(off));
            return null;
        }
        if (compress == 1 && len <= head) {
            reason(diag, "RLE 长度不足 len=" + len + " head=" + head
                    + " off=0x" + Long.toHexString(off));
            return null;
        }

        byte[] data = f.bytes(abs + head, (int) Math.min(len - head, Integer.MAX_VALUE - 8));
        if (data == null) {
            reason(diag, "读取 payload 失败 len=" + len + " head=" + head
                    + " off=0x" + Long.toHexString(off));
            return null;
        }
        int[] px = new int[w * h];
        boolean ok = decodeBody(data, px, w, h, depth, compress);
        if (!ok && compress == 1) {
            // RLE 长度无法反推头长：8/14 互试一次（样本 A 为 8，样本 B 为 14）
            int alt = head == 8 ? 14 : 8;
            if (len - alt > 8) {
                byte[] d2 = f.bytes(abs + alt, (int) Math.min(len - alt, Integer.MAX_VALUE - 8));
                if (d2 != null) {
                    int[] px2 = new int[w * h];
                    if (decodeBody(d2, px2, w, h, depth, compress)) {
                        px = px2;
                        ok = true;
                        head = alt;
                    }
                }
            }
        }
        if (!ok) {
            reason(diag, "解码失败 comp=" + compress + " w=" + w + " h=" + h
                    + " depth=" + depth + " len=" + len + " head=" + head
                    + " off=0x" + Long.toHexString(off));
            return null;
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        if (flip) {
            for (int y = 0; y < h; y++) {
                int src = (h - 1 - y) * w;
                img.setRGB(0, y, w, 1, px, src, w);
            }
        } else {
            img.setRGB(0, 0, w, h, px, 0, w);
        }
        return new Decoded(img, px, w, h, depth, compress, head);
    }

    /**
     * 资源头长度：8（旧固件）或 14（RK28 SDK V1.2）。
     * RAW 类可由 length - w*h*bpp 唯一确定；RLE 长度不可反推，只能看 +8 的扩展特征。
     */
    private static int headerLen(BigFile f, long abs, long len, int w, int h, int bpp, int comp) {
        final long raw = (long) w * h * bpp;
        if (comp == 0 || comp == 5) {
            long diff = len - raw;
            if (diff == 14) return 14;
            if (diff == 8) return 8;
            if (diff > 8 && diff <= 14) return 8;     // 8 字节头 + 0~6 字节尾部残留
            if (diff > 14) return 14;                 // 14 字节头 + 尾部残留
            if (comp == 5) return 14;                 // comp=5 恒带 6 字节扩展
            return -1;                                // 数据不足
        }
        if (comp == 5) return 14;
        // comp=1：14 字节头的 +8 处是 CD CD CD CD CD CD（样本 B 402/402），
        // 8 字节头在此处已是 token 流，六字节全命中 0xCD 的概率可忽略。
        byte[] ext = f.bytes(abs + 8, 6);
        if (ext != null) {
            boolean allCd = true;
            for (byte b : ext) {
                if ((b & 0xFF) != 0xCD) {
                    allCd = false;
                    break;
                }
            }
            if (allCd) return 14;
        }
        return 8;
    }

    /** 按 comp 分流：0=RAW 填充位、5=RAW 真实 alpha、1=RLE。 */
    private static boolean decodeBody(byte[] d, int[] px, int w, int h, int depth, int comp) {
        if (comp == 1) {
            if (!decodeRle(d, px, w, h)) return false;
            applyColorKey(px);
            return true;
        }
        boolean keepAlpha = (comp == 5);
        if (!decodeRaw(d, px, w, h, depth, keepAlpha)) return false;
        // comp=5 的 alpha 是真实数据（有 0~255 梯度），禁止改写；色键也不动它
        if (!keepAlpha) applyColorKey(px);
        return true;
    }

    private static boolean decodeRaw(byte[] d, int[] px, int w, int h, int depth, boolean keepAlpha) {
        int n = w * h;
        if (depth != 8 && depth != 16 && depth != 24 && depth != 32) return false;
        if (d == null || d.length < n * (depth / 8)) return false;
        if (depth == 32) {
            int p = 0;
            for (int i = 0; i < n; i++, p += 4) {
                int b = d[p] & 0xFF;
                int g = d[p + 1] & 0xFF;
                int r = d[p + 2] & 0xFF;
                // comp=0 的第 4 字节是填充位（样本 A 实测 1063/1063 全 0），当 alpha
                // 用会整幅全透明；comp=5 的第 4 字节是真实 alpha，必须原样保留。
                int a = keepAlpha ? (d[p + 3] & 0xFF) : 0xFF;
                px[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        } else if (depth == 24) {
            int p = 0;
            for (int i = 0; i < n; i++, p += 3) {
                px[i] = 0xFF000000 | ((d[p + 2] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p] & 0xFF);
            }
        } else if (depth == 16) {
            int p = 0;
            for (int i = 0; i < n; i++, p += 2) {
                int v = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
                int r = (v >> 11) & 0x1F;
                int g = (v >> 5) & 0x3F;
                int b = v & 0x1F;
                r = (r << 3) | (r >> 2);
                g = (g << 2) | (g >> 4);
                b = (b << 3) | (b >> 2);
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else {
            for (int i = 0; i < n; i++) {
                int v = d[i] & 0xFF;
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        return true;
    }

    private static void reason(StringBuilder diag, String msg) {
        if (diag != null) diag.append(msg);
    }

    /**
     * RK 非标准 RLE：每 4 字节一组 token = B, G, R, count（count 按无符号读）。
     * <ul>
     *   <li>count == 0x00：流终结符，结束解码；</li>
     *   <li>count == 0x01..0xFE：本游程重复该 BGR 共 count 次；</li>
     *   <li>count == 0xFF：扩展计数，紧跟 4 字节小端 uint32 extra，游程 = 255 + extra
     *       （实测单次最大 95745，u8/u16 都读不下，必须 uint32）。</li>
     * </ul>
     * 游程是跨行连续的（207 条 RLE 中 173 条单个游程大于图宽），必须往一条线性的
     * w*h 缓冲里连写，绝不能按行重置。游程允许超出剩余像素（编码器会写余量，
     * 如样本 B 的 0xB007：run=385024 > 384000），必须截断而不是报溢出。
     */
    private static boolean decodeRle(byte[] d, int[] px, int w, int h) {
        final int n = w * h;
        int out = 0;
        int p = 0;
        while (p + 4 <= d.length && out < n) {
            final int b = d[p] & 0xFF;
            final int g = d[p + 1] & 0xFF;
            final int r = d[p + 2] & 0xFF;
            final int c = d[p + 3] & 0xFF;      // 必须 &0xFF，否则 0xFF 永远匹配不上
            int run;
            if (c == 0xFF) {
                if (p + 8 > d.length) break;
                final long extra = (d[p + 4] & 0xFFL)
                        | ((d[p + 5] & 0xFFL) << 8)
                        | ((d[p + 6] & 0xFFL) << 16)
                        | ((d[p + 7] & 0xFFL) << 24);
                run = (int) Math.min(255L + extra, n - out);   // 允许超出，截断即可
                p += 8;
            } else {
                if (c == 0) break;              // 零终结符
                run = Math.min(c, n - out);
                p += 4;
            }
            final int argb = 0xFF000000 | (r << 16) | (g << 8) | b;
            for (int k = 0; k < run; k++) px[out + k] = argb;   // 线性连写，允许跨行
            out += run;
        }
        return out == n;
    }

    /** 洋红 (B=255,G=0,R=255) 是透明色键：只把 alpha 置 0，RGB 原样保留。 */
    private static void applyColorKey(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int v = px[i];
            if ((v & 0xFFFFFF) == 0xFF00FF) px[i] = v & 0x00FFFFFF;
        }
    }

    // -------------------------------------------------------------- 导出

    public static int export(BigFile f, Container c, File outDir, String prefix,
                             ProgressListener p, UnpackResult r, UnpackOptions opt) {
        int done = 0;
        final boolean wantMan = opt != null && opt.bmp0Manifest;
        final boolean wantDiag = opt != null && opt.bmp0Diag;
        StringBuilder man = wantMan
                ? new StringBuilder("index,md5,w,h,depth,comp,header_len,png_bytes\n") : null;
        int head8 = 0, head14 = 0, headOther = 0, failed = 0;
        int nComp0 = 0, nComp1 = 0, nComp5 = 0, nCompOther = 0;
        int aZero = 0, aFull = 0, aMid = 0;
        StringBuilder failLines = wantDiag ? new StringBuilder() : null;

        for (long i = 0; i < c.count; i++) {
            if (p != null && p.isCancelled()) {
                if (r != null) r.cancelled = true;
                break;
            }
            try {
                StringBuilder why = new StringBuilder();
                Decoded d = decode(f, c, i, opt != null && opt.flip, why);
                if (d == null) {
                    // 失败必须带上证据（w/h/depth/comp/len/off），不能只有「已跳过」
                    failed++;
                    if (r != null) r.note(prefix + " #" + i + " 解码失败，已跳过: " + why);
                    if (failLines != null) failLines.append(i).append(" | ").append(why).append('\n');
                    continue;
                }
                done++;
                if (r != null) r.pngCount++;
                if (d.headerLen == 8) head8++;
                else if (d.headerLen == 14) head14++;
                else headOther++;
                if (d.compress == 0) nComp0++;
                else if (d.compress == 1) nComp1++;
                else if (d.compress == 5) nComp5++;
                else nCompOther++;
                // comp=5 的第 4 字节是真实 alpha，统计梯度分布用于核对样本特征
                if (wantDiag && d.compress == 5 && d.px != null) {
                    for (int v : d.px) {
                        int a = (v >>> 24) & 0xFF;
                        if (a == 0) aZero++;
                        else if (a == 255) aFull++;
                        else aMid++;
                    }
                }
                String name = String.format("%s_%04d_%dx%d.png", prefix, i, d.w, d.h);
                long bytes = 0;
                String path = "";
                if (opt == null || !opt.listOnly) {
                    File out = new File(outDir, name);
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    try {
                        ImageIO.write(d.image, "png", out);
                        bytes = out.length();
                        path = out.getAbsolutePath();
                    } catch (IOException e) {
                        if (r != null) r.note(name + " 写盘失败: " + e.getMessage());
                        if (r != null) r.pngCount--;
                        done--;
                        continue;
                    }
                }
                if (man != null) {
                    man.append(i).append(',')
                       .append(pixelMd5(d.px, d.w, d.h)).append(',')
                       .append(d.w).append(',').append(d.h).append(',')
                       .append(d.depth).append(',').append(d.compress).append(',')
                       .append(d.headerLen).append(',').append(bytes).append('\n');
                }
                if (r != null) r.entries.add(new UnpackResult.Entry(name, path, d.w, d.h, d.depth, d.compress, bytes));
                if (p != null && c.count > 0 && (i % 32 == 0 || i == c.count - 1)) {
                    p.progress((int) ((i + 1) * 100 / c.count), "解码 " + (i + 1) + "/" + c.count);
                }
            } catch (Throwable t) {
                failed++;
                if (r != null) r.note(prefix + " #" + i + " 异常: " + t);
                if (failLines != null) failLines.append(i).append(" | 异常 ").append(t).append('\n');
            }
        }

        if (man != null) writeText(new File(outDir, "bmp0_manifest.csv"), man.toString(), r);
        if (wantDiag) {
            StringBuilder dg = new StringBuilder();
            dg.append("# BMP0 解码诊断报告\n");
            dg.append("容器条目数: ").append(c.count).append('\n');
            dg.append("成功解码: ").append(done).append('\n');
            dg.append("失败: ").append(failed).append('\n');
            dg.append("\n## 块头长度\n");
            dg.append("8 字节: ").append(head8).append('\n');
            dg.append("14 字节: ").append(head14).append('\n');
            if (headOther > 0) dg.append("其他: ").append(headOther).append('\n');
            dg.append("\n## 压缩方式\n");
            dg.append("comp=0: ").append(nComp0).append('\n');
            dg.append("comp=1: ").append(nComp1).append('\n');
            dg.append("comp=5: ").append(nComp5).append('\n');
            if (nCompOther > 0) dg.append("comp=其他: ").append(nCompOther).append('\n');
            dg.append("\n## comp=5 alpha 分布（像素数）\n");
            dg.append("a=0: ").append(aZero).append('\n');
            dg.append("a=255: ").append(aFull).append('\n');
            dg.append("0<a<255: ").append(aMid).append('\n');
            if (failLines != null && failLines.length() > 0) {
                dg.append("\n## 失败条目\n").append(failLines);
            }
            writeText(new File(outDir, "bmp0_diag.txt"), dg.toString(), r);
        }
        return done;
    }

    private static void writeText(File out, String text, UnpackResult r) {
        if (out == null || text == null) return;
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            java.nio.file.Files.write(out.toPath(),
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (r != null) r.note(out.getName() + " 写盘失败: " + e.getMessage());
        }
    }

    public static List<Container> containers(BigFile f) {
        return scanAll(f, 0, f.size());
    }
}
