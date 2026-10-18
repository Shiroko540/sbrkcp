package sbrkcp.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;

/** 编排：四种外壳的完整解包流程 + 报告写出，不含任何 GUI 依赖。 */
public final class Engine {

    private static final long MAX_RKW_BYTES = 64L * 1024 * 1024;
    private static final long MAX_PART_BYTES = 96L * 1024 * 1024;

    private Engine() {
    }

    // ---------------------------------------------------------------- 入口

    public static UnpackResult runRkw(File in, File out, ProgressListener p, UnpackOptions opt) throws Exception {
        UnpackResult r = new UnpackResult();
        r.moduleId = "fuckrkw";
        r.input = in;
        r.outDir = out;
        try (BigFile f = new BigFile(in)) {
            r.kind = "KRDL";
            byte[] buf = f.readAll(MAX_RKW_BYTES);
            if (buf == null) {
                r.note("文件过大，超出 RKW 扫描上限");
                r.exitCode = 4;
                return r;
            }
            List<Firmware.RkwEntry> entries = Firmware.rkwEntries(buf);
            List<Rkrs.Container> conts = Rkrs.scanAll(f, 0, f.size());
            long delta = Firmware.rkwDataDelta(f, entries, conts);
            p.log("RKW 文件表条目：" + entries.size() + "，delta=0x" + Long.toHexString(delta)
                    + " (" + delta + ")，探测到 RKRS 容器 " + conts.size() + " 个");
            r.note("文件表条目 " + entries.size() + "，delta=" + delta);

            File filesDir = new File(out, "files");
            int n = 0;
            int total = entries.size();
            int idx = 0;
            java.util.Set<File> buckets = new java.util.LinkedHashSet<>();
            for (Firmware.RkwEntry e : entries) {
                idx++;
                if (p.isCancelled()) {
                    r.cancelled = true;
                    break;
                }
                if (e.isDir() || e.size <= 0) continue;
                long abs = e.offset + delta;
                // 用已探测容器校正起点：条目 size 与容器 totalSize 一致（容差 16）时，
                // 容器起点比 delta 推算更可信 —— delta 是全局众数，个别条目会偏。
                for (Rkrs.Container c : conts) {
                    if (e.size < 4096) break;
                    if (Math.abs(c.totalSize - e.size) <= 16) {
                        abs = c.start;
                        break;
                    }
                }
                if (abs < 0 || abs + e.size > f.size()) {
                    r.note("条目越界跳过: " + e.name);
                    continue;
                }
                n++;
                r.fileCount++;
                if (!opt.listOnly) {
                    byte[] data = f.bytes(abs, (int) e.size);
                    if (data == null) {
                        r.note("读取失败: " + e.name);
                        continue;
                    }
                    File dst = new File(filesDir, e.name.replace('\\', '/'));
                    File parent = dst.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try {
                        Files.write(dst.toPath(), data);
                    } catch (IOException ex) {
                        r.note("写盘失败: " + e.name + " -> " + ex.getMessage());
                        continue;
                    }
                    if (Firmware.looksLikeResourceBucket(e.name)) buckets.add(dst);
                } else {
                    p.log(String.format("LIST %-44s %10d @0x%08x", e.name, e.size, abs));
                }
                if (total > 0 && idx % 4 == 0) p.progress(idx * 100 / Math.max(total, 1), "导出 " + e.name);
            }
            r.note("files/ 导出 " + n + " 个文件");

            // 职责边界：RKW 模块只解 RKDL 外壳。内嵌的 BMP0.BIN / RKRS / 子 RKW 一律原样导出，
            // 不做任何二次解析 —— 需要图片时由用户自己把 files/ 下的条目交给对应模块。
            if (!buckets.isEmpty()) {
                r.note("内嵌资源 " + buckets.size() + " 个已原样导出，未做二次解析"
                        + "（需要图片请自行用 fuckbmp0 解 files/ 下的 BMP0.BIN）");
            }
        }
        return r;
    }

    public static UnpackResult runRfw(File in, File out, ProgressListener p, UnpackOptions opt) throws Exception {
        UnpackResult r = new UnpackResult();
        r.moduleId = "fuckrfw";
        r.input = in;
        r.outDir = out;
        try (BigFile f = new BigFile(in)) {
            r.kind = "WFKR";
            List<Firmware.Part> parts = Firmware.rfwParts(f);
            if (parts.isEmpty()) {
                r.note("分区表解析失败");
                r.exitCode = 4;
                return r;
            }
            p.log("WFKR 分区数：" + parts.size());
            r.note("分区数 " + parts.size());
            for (Firmware.Part part : parts) {
                if (p.isCancelled()) {
                    r.cancelled = true;
                    break;
                }
                File partFile = new File(out, String.format("part%d_%08x.bin", part.index, part.offset));
                if (!opt.listOnly) {
                    if (!writeSlice(f, part.offset, part.length, partFile)) {
                        r.note("分区写盘失败: " + part);
                        continue;
                    }
                }
                int before = r.pngCount;
                byte[] data = part.length <= MAX_PART_BYTES
                        ? f.bytes(part.offset, (int) part.length) : null;
                if (data == null) {
                    r.note("分区过大或读取失败: part" + part.index);
                    continue;
                }
                try (BigFile pf = new BigFile(partFile.isFile() ? partFile : in)) {
                    long scanBase = partFile.isFile() ? 0 : part.offset;
                    long scanEnd = partFile.isFile() ? pf.size() : part.offset + part.length;
                    List<Rkrs.Container> conts = Rkrs.scanAll(pf, scanBase, scanEnd);
                    if (!conts.isEmpty()) {
                        int ci = 0;
                        for (Rkrs.Container c : conts) {
                            File dir = new File(out, "part" + part.index + "_rkrs" + (ci == 0 ? "" : "_" + ci));
                            Rkrs.export(pf, c, dir, "img", p, r, opt);
                            ci++;
                        }
                        r.note("part" + part.index + " 走 RKRS 路径，容器 " + conts.size() + " 个");
                    } else {
                        List<RawImage.Segment> segs = RawImage.scan(data, part.offset);
                        if (!segs.isEmpty()) {
                            int got = 0;
                            int trustSeg = 0;
                            int trustPng = 0;
                            for (RawImage.Segment s : segs) {
                                int n = RawImage.export(data, s, part.offset, out, p, r, opt,
                                        "part" + part.index + "_");
                                got += n;
                                if (s.trusted()) {
                                    trustSeg++;
                                    trustPng += n;
                                } else {
                                    r.note("part" + part.index + " " + s
                                            + "（头部字段与真实 UI 记录不符，已导出供人工复核）");
                                }
                            }
                            r.note("part" + part.index + " 裸图段 " + segs.size() + " 段 / " + got
                                    + " 张（可信段 " + trustSeg + " 段 / " + trustPng + " 张）");
                        } else {
                            r.note("part" + part.index + " 未识别出图像资源，保留原始分区文件");
                        }
                    }
                }
                p.progress((part.index + 1) * 100 / parts.size(), "分区 " + (part.index + 1) + "/" + parts.size());
                p.log("part" + part.index + " 导出 " + (r.pngCount - before) + " 张");
            }
        }
        return r;
    }

    public static UnpackResult runRkrs(File in, File out, ProgressListener p, UnpackOptions opt) throws Exception {
        UnpackResult r = new UnpackResult();
        r.moduleId = "fuckbmp0";
        r.input = in;
        r.outDir = out;
        try (BigFile f = new BigFile(in)) {
            Firmware.Detection d = Firmware.detect(f);
            r.kind = d.kind.name();
            List<Rkrs.Container> conts = Rkrs.scanAll(f, 0, f.size());
            if (conts.isEmpty()) {
                r.unknown = true;
                r.note("未发现合法 RKRS 容器");
                analyseUnknown(f, r);
                r.exitCode = 0;
                return r;
            }
            p.log("发现 RKRS 容器 " + conts.size() + " 个");
            int ci = 0;
            for (Rkrs.Container c : conts) {
                File dir = conts.size() == 1 ? out : new File(out, "c" + ci);
                Rkrs.export(f, c, dir, "img", p, r, opt);
                ci++;
            }
        }
        return r;
    }

    public static UnpackResult runFat(File in, File out, ProgressListener p, UnpackOptions opt) throws Exception {
        UnpackResult r = new UnpackResult();
        r.moduleId = "fuckrkimg";
        r.input = in;
        r.outDir = out;
        try (BigFile f = new BigFile(in)) {
            r.kind = "FAT";
            Fat fat = new Fat(f);
            if (!fat.valid()) {
                r.note("FAT 引导扇区解析失败");
                r.exitCode = 4;
                return r;
            }
            if (opt.fatManifest && !opt.listOnly) fat.beginManifest();
            int n = fat.extract(out, p, r, opt);
            Fat.Stats st = fat.stats();
            r.note("FAT 文件 " + n + " 个");
            r.note("L1 统计：" + st);
            if (st.anomalies > 0) r.note("L1 校验异常 " + st.anomalies + " 条，详见报告 notes");
            if (opt.fatManifest && !opt.listOnly) {
                String csv = fat.manifest();
                if (csv != null) {
                    File mf = new File(out, "dump_manifest.csv");
                    try {
                        File parent = mf.getParentFile();
                        if (parent != null) parent.mkdirs();
                        java.nio.file.Files.write(mf.toPath(), csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        p.log("清单已写出：" + mf.getAbsolutePath());
                    } catch (java.io.IOException e) {
                        r.note("清单写出失败: " + e.getMessage());
                    }
                }
            }
        }
        return r;
    }

    /** 自动分派：按 detect 结果选流程，UNKNOWN 走全盘 RKRS 扫描兜底。 */
    public static UnpackResult auto(File in, File out, ProgressListener p, UnpackOptions opt) throws Exception {
        Firmware.Kind k;
        try (BigFile f = new BigFile(in)) {
            k = Firmware.detect(f).kind;
        }
        switch (k) {
            case RFW:
                return runRfw(in, out, p, opt);
            case RKW:
                return runRkw(in, out, p, opt);
            case RKRS:
                return runRkrs(in, out, p, opt);
            case FAT16:
            case FAT32:
                return runFat(in, out, p, opt);
            default:
                UnpackResult r = runRkrs(in, out, p, opt);
                r.moduleId = "-";
                return r;
        }
    }

    // ------------------------------------------------------------ 辅助

    private static boolean writeSlice(BigFile f, long off, long len, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dst, "rw")) {
                raf.setLength(0);
                byte[] buf = new byte[1 << 20];
                long done = 0;
                while (done < len) {
                    int want = (int) Math.min(buf.length, len - done);
                    if (!f.inRange(off + done, want)) break;
                    int got = f.readAt(off + done, buf, want);
                    if (got <= 0) break;
                    raf.write(buf, 0, got);
                    done += got;
                }
                return done == len;
            }
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------- 加密/未知判定

    public static void analyseUnknown(BigFile f, UnpackResult r) {
        long size = f.size();
        if (size <= 0) {
            r.note("空文件");
            return;
        }
        int sample = (int) Math.min(size, 4L * 1024 * 1024);
        byte[] b = f.bytes(size / 2 - sample / 2 >= 0 ? size / 2 - sample / 2 : 0, sample);
        if (b == null) b = f.bytes(0, sample);
        if (b == null) return;

        int[] hist = new int[256];
        for (byte x : b) hist[x & 0xFF]++;
        double entropy = 0;
        for (int c : hist) {
            if (c == 0) continue;
            double p = (double) c / b.length;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        long zeros = hist[0];
        double zeroRatio = (double) zeros / b.length;
        long filler = hist[0xFF];

        // 16 字节块重复率（ECB 分组痕迹）
        Map<String, Integer> blocks = new HashMap<>();
        int dup = 0;
        int totalBlocks = 0;
        for (int i = 0; i + 16 <= Math.min(b.length, 1 << 20); i += 16) {
            String key = Arrays.toString(Arrays.copyOfRange(b, i, i + 16));
            totalBlocks++;
            int c = blocks.merge(key, 1, Integer::sum);
            if (c > 1) dup++;
        }
        double dupRatio = totalBlocks == 0 ? 0 : (double) dup / totalBlocks;

        // 可读字符串（ASCII 连续 >=6）
        int run = 0, runs = 0, printable = 0, chars = 0;
        for (byte x : b) {
            int c = x & 0xFF;
            chars++;
            boolean ok = (c >= 0x20 && c <= 0x7E);
            if (ok) {
                printable++;
                run++;
            } else {
                if (run >= 6) runs++;
                run = 0;
            }
        }
        if (run >= 6) runs++;
        double printableRatio = (double) printable / Math.max(1, chars);

        r.note(String.format("熵=%.3f bit/byte，0x00 占比=%.4f，0xFF 计数=%d，16 字节块重复率=%.4f，可打印字符占比=%.4f，可读串段数=%d",
                entropy, zeroRatio, filler, dupRatio, printableRatio, runs));

        // 疑似加密：整体高熵 + 无 0x00 填充 + 可打印率接近随机(95/256≈0.371) + 可读串密度极低。
        // 注意：随机字节的可打印率期望约 0.371，不能用 <0.35 这种把随机数据也排除的阈值。
        double runsPerMb = runs * 1048576.0 / Math.max(1, b.length);
        boolean likelyEncrypted = entropy > 7.7 && zeroRatio < 0.01
                && printableRatio < 0.42 && runsPerMb < 2000;
        if (likelyEncrypted) {
            r.encrypted = true;
            r.note("结论：疑似加密镜像，未提取到资源");
        } else {
            r.note("结论：未识别的数据（未提取到资源）");
        }
    }

    // ------------------------------------------------------------- 报告

    public static void writeReport(UnpackResult r, File outDir) {
        if (outDir == null) return;
        outDir.mkdirs();
        File rep = new File(outDir, "unpack_report.txt");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(rep.toPath(), StandardCharsets.UTF_8))) {
            w.println("sbrkcp 解包报告");
            w.println("========================");
            w.println("模块    : " + r.moduleId);
            w.println("输入    : " + (r.input == null ? "-" : r.input.getAbsolutePath()));
            w.println("输出    : " + (r.outDir == null ? "-" : r.outDir.getAbsolutePath()));
            w.println("识别    : " + r.kind);
            if (r.input != null && r.input.isFile()) {
                w.println("大小    : " + r.input.length());
                w.println("MD5     : " + md5(r.input));
            }
            w.println("PNG     : " + r.pngCount);
            w.println("文件    : " + r.fileCount);
            w.println("加密    : " + r.encrypted);
            w.println("中断    : " + r.cancelled);
            w.println();
            w.println("-- 说明 --");
            for (String n : r.notes) w.println("  · " + n);
            w.println();
            w.println("-- 图片条目明细 --");
            for (UnpackResult.Entry e : r.entries) w.println("  " + e);
        } catch (IOException e) {
            System.err.println("报告写入失败: " + e);
        }
    }

    public static String md5(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }
}
