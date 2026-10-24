package sbrkcp.core.rock;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/** 目录表、分区地图、探测凭证、点阵 / 像素预览的落盘。 */
public final class RockReport {

    private RockReport() {
    }

    /** 输出目录表 CSV。 */
    public static void writeTable(File out, RockTable t, RockAnalyzer.Ctx c) throws IOException {
        PrintWriter w = writer(new File(out, "rock_table.csv"));
        try {
            w.println("index,rec_offset,type,data_offset,size,end,flag,entropy,zero_ratio,printable,repeat512,category,evidence");
            for (RockTable.Entry e : t.entries) {
                w.println(e.index + "," + RockTable.hex(e.recOffset)
                        + "," + e.type + "," + RockTable.hex(e.offset)
                        + "," + e.size + "," + RockTable.hex(e.offset + e.size)
                        + "," + RockTable.hex(e.flag)
                        + "," + RockAnalyzer.fmt(e.entropy)
                        + "," + RockAnalyzer.fmt(e.zeroRatio)
                        + "," + RockAnalyzer.fmt(e.printable)
                        + "," + RockAnalyzer.fmt(e.repeat512)
                        + "," + q(e.category) + "," + q(e.evidence));
            }
        } finally {
            w.close();
        }
    }

    /** 分区地图 Markdown。 */
    public static void writeMap(File out, File src, RockTable t, RockAnalyzer.Ctx c) throws IOException {
        PrintWriter w = writer(new File(out, "rock_partition.md"));
        try {
            w.println("# 分区地图：" + src.getName());
            w.println();
            w.println("| 项 | 值 |");
            w.println("| --- | --- |");
            w.println("| 文件大小 | " + t.fileLen + " B (" + RockTable.hex(t.fileLen) + ") |");
            w.println("| 签名 | " + t.signature + " |");
            w.println("| 版本串 | " + t.version + " |");
            w.println("| 目录表起点 | " + RockTable.hex(t.tableOffset) + " |");
            w.println("| 条目数 | " + t.entryCount + " |");
            int filled = 0;
            for (RockTable.Entry e : t.entries) {
                if (!e.isEmpty()) {
                    filled++;
                }
            }
            w.println("| 有效条目 | " + filled + " |");
            w.println();
            w.println("| # | 偏移 | 大小 | 结束 | 熵 | 分类 | 判据 |");
            w.println("| --- | --- | --- | --- | --- | --- | --- |");
            for (RockTable.Entry e : t.entries) {
                if (e.isEmpty()) {
                    continue;
                }
                w.println("| " + e.index + " | " + RockTable.hex(e.offset) + " | " + e.size
                        + " | " + RockTable.hex(e.offset + e.size)
                        + " | " + RockAnalyzer.fmt(e.entropy)
                        + " | " + e.category + " | " + e.evidence.replace("|", "/") + " |");
            }
        } finally {
            w.close();
        }
    }

    /** 探测凭证 report.txt。 */
    public static void writeReport(File out, File src, RockTable t, RockAnalyzer.Ctx c,
                                   List<String> warnings, long elapsedMs) throws IOException {
        PrintWriter w = writer(new File(out, "rock_report.txt"));
        try {
            w.println("sbrkcp · ROCK 容器探测凭证");
            w.println("file      : " + src.getAbsolutePath());
            w.println("size      : " + t.fileLen + " B (" + RockTable.hex(t.fileLen) + ")");
            w.println("signature : " + (t.signature.isEmpty() ? "未命中" : t.signature));
            w.println("version   : " + t.version);
            w.println("table     : " + RockTable.hex(t.tableOffset) + "  条目 " + t.entryCount);
            w.println("elapsed   : " + elapsedMs + " ms");
            w.println();
            w.println("[表定位依据]");
            w.println("  16 字节步长自洽扫描 0x20 起，判据：type∈{0,1}、offset 单调非减、");
            w.println("  offset+size≤文件长、表尾==首条 offset。命中 " + RockTable.hex(t.tableOffset)
                    + "，表尾 " + RockTable.hex(t.tableOffset + (long) t.entryCount * 16));
            for (String n : t.notes) {
                w.println("  · " + n);
            }
            w.println();
            w.println("[边界校验]");
            if (warnings.isEmpty()) {
                w.println("  无越界、无重叠、无尾部残留");
            } else {
                for (String s : warnings) {
                    w.println("  ! " + s);
                }
            }
            w.println();
            Map<String, Integer> cat = new LinkedHashMap<String, Integer>();
            for (RockTable.Entry e : t.entries) {
                if (e.isEmpty()) {
                    continue;
                }
                Integer v = cat.get(e.category);
                cat.put(e.category, Integer.valueOf(v == null ? 1 : v.intValue() + 1));
            }
            w.println("[分类统计]");
            for (Map.Entry<String, Integer> en : cat.entrySet()) {
                w.println("  " + en.getKey() + " : " + en.getValue());
            }
            w.println();
            w.println("[点阵字库]");
            if (c.fonts.isEmpty()) {
                w.println("  未命中");
            } else {
                for (RockAnalyzer.FontHit h : c.fonts) {
                    w.println("  #" + h.entryIndex + " @" + RockTable.hex(h.offset)
                            + "  " + h.glyphBytes + " B/字 × " + h.glyphCount
                            + "（" + h.glyphW + "×" + h.glyphH + "，密度 "
                            + RockAnalyzer.fmt(h.density) + "，块间汉明 "
                            + RockAnalyzer.fmt(h.hamming) + "）");
                }
            }
            w.println();
            w.println("[像素区行宽]");
            if (c.pixels.isEmpty()) {
                w.println("  未命中");
            } else {
                for (RockAnalyzer.PixelHit h : c.pixels) {
                    w.println("  #" + h.entryIndex + " @" + RockTable.hex(h.offset)
                            + "  行宽 " + h.rowBytes + " B = " + h.width + " px（"
                            + h.layout + "），邻行差 " + RockAnalyzer.fmt(h.rowDiff)
                            + (h.tilePeriod > 0 ? "，tile 周期 " + h.tilePeriod + " B"
                                    : " / 行差中位 " + RockAnalyzer.fmt(h.globalDiff)));
                }
            }
            w.println();
            w.println("[索引表]");
            if (c.indexes.isEmpty()) {
                w.println("  未命中");
            } else {
                for (RockAnalyzer.IndexHit h : c.indexes) {
                    w.println("  #" + h.entryIndex + " @" + RockTable.hex(h.offset)
                            + "  u16 步长 " + h.step + " × " + h.count
                            + " 项（" + h.first + " → " + h.last + "）");
                }
            }
            w.println();
            w.println("[高熵块头聚类]");
            if (c.clusters.isEmpty()) {
                w.println("  未命中");
            } else {
                for (RockAnalyzer.BlockCluster b : c.clusters) {
                    w.println("  #" + b.entryIndex + "  " + b.blockCount + " × " + b.blockSize
                            + "B，块头 [" + b.template + "]，稳定位 "
                            + RockAnalyzer.fmt(b.stableRatio));
                }
            }
            w.println();
            w.println("[试解凭证]");
            int hits = 0;
            int keys = 0;
            Map<String, Integer> algCount = new LinkedHashMap<String, Integer>();
            for (RockAnalyzer.TrialLog tl : c.trials) {
                Integer v = algCount.get(tl.algorithm);
                algCount.put(tl.algorithm, Integer.valueOf(v == null ? 1 : v.intValue() + 1));
                if (tl.hit) {
                    hits++;
                }
            }
            if (!c.trials.isEmpty()) {
                keys = c.trials.size() / Math.max(1, algCount.size());
            }
            w.println("  组合 " + c.trials.size() + " 次，覆盖算法 " + algCount.size()
                    + " 种，候选钥约 " + keys + " 组；命中 " + hits);
            for (Map.Entry<String, Integer> en : algCount.entrySet()) {
                w.println("    · " + en.getKey() + " × " + en.getValue());
            }
            if (hits == 0) {
                w.println("  结论：以上组合均未解出可读明文；不据此断定该区不可解，");
                w.println("        仅记录为已试清单，后续可按块头模板与块长继续。");
            }
            w.println();
            w.println("[明文串]");
            w.println("  抽取 " + c.texts.size() + " 条（ASCII ≥6 / UTF-16LE 中文 ≥2 字）");
            w.println();
            w.println("[产物]");
            w.println("  rock_table.csv  目录表");
            w.println("  rock_partition.md  分区地图");
            w.println("  rock_blocks.csv  块头聚类");
            w.println("  rock_texts.csv  明文串");
            w.println("  rock_font_*.png / rock_pixel_*.png  预览");
        } finally {
            w.close();
        }
    }

    /** 块头聚类 CSV。 */
    public static void writeBlocks(File out, RockAnalyzer.Ctx c) throws IOException {
        PrintWriter w = writer(new File(out, "rock_blocks.csv"));
        try {
            w.println("entry,block_size,block_count,template,stable_ratio");
            for (RockAnalyzer.BlockCluster b : c.clusters) {
                w.println(b.entryIndex + "," + b.blockSize + "," + b.blockCount + ","
                        + q(b.template) + "," + RockAnalyzer.fmt(b.stableRatio));
            }
        } finally {
            w.close();
        }
    }

    /** 明文串 CSV。 */
    public static void writeTexts(File out, RockAnalyzer.Ctx c) throws IOException {
        PrintWriter w = writer(new File(out, "rock_texts.csv"));
        try {
            w.println("entry,offset,encoding,text");
            for (RockAnalyzer.TextHit h : c.texts) {
                w.println(h.entryIndex + "," + RockTable.hex(h.offset) + "," + h.encoding + "," + q(h.text));
            }
        } finally {
            w.close();
        }
    }

    /** 字库切片 PNG。 */
    public static List<File> writeFontPng(File out, byte[] d, RockAnalyzer.Ctx c) throws IOException {
        List<File> files = new ArrayList<File>();
        int seq = 0;
        for (RockAnalyzer.FontHit h : c.fonts) {
            int gb = h.glyphBytes;
            int bits = gb * 8;
            int wpx = (int) Math.round(Math.sqrt(bits));
            wpx = Math.max(8, (wpx / 8) * 8);
            int hpx = bits / wpx;
            int cols = 16;
            int rows = (int) Math.ceil(Math.min(h.glyphCount, 256) / (double) cols);
            int scale = 2;
            int cw = (wpx + 1) * scale;
            int ch = (hpx + 1) * scale;
            BufferedImage img = new BufferedImage(cols * cw + 1, rows * ch + 1, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    img.setRGB(x, y, 0x202020);
                }
            }
            int show = Math.min(h.glyphCount, 256);
            for (int g = 0; g < show; g++) {
                int cx = (g % cols) * cw + 1;
                int cy = (g / cols) * ch + 1;
                long base = h.offset + (long) g * gb;
                for (int y = 0; y < hpx; y++) {
                    for (int x = 0; x < wpx; x++) {
                        int bitIndex = y * wpx + x;
                        int byteIndex = bitIndex / 8;
                        int bit = 7 - (bitIndex % 8);
                        if (base + byteIndex >= d.length) {
                            continue;
                        }
                        boolean on = ((d[(int) base + byteIndex] >> bit) & 1) != 0;
                        int rgb = on ? 0xF0F0F0 : 0x101010;
                        for (int dy = 0; dy < scale; dy++) {
                            for (int dx = 0; dx < scale; dx++) {
                                int px = cx + x * scale + dx;
                                int py = cy + y * scale + dy;
                                if (px < img.getWidth() && py < img.getHeight()) {
                                    img.setRGB(px, py, rgb);
                                }
                            }
                        }
                    }
                }
            }
            File f = new File(out, "rock_font_" + seq + "_" + wpx + "x" + hpx + ".png");
            ImageIO.write(img, "png", f);
            files.add(f);
            seq++;
        }
        return files;
    }

    /** 像素区预览 PNG（RGB565LE）。 */
    public static List<File> writePixelPng(File out, byte[] d, RockAnalyzer.Ctx c) throws IOException {
        List<File> files = new ArrayList<File>();
        int seq = 0;
        for (RockAnalyzer.PixelHit h : c.pixels) {
            int w = h.width;
            int rowBytes = h.rowBytes;
            if (w <= 0 || rowBytes <= 0) {
                continue;
            }
            int maxRows = 512;
            int rows = (int) Math.min(h.size / rowBytes, maxRows);
            if (rows <= 0) {
                continue;
            }
            BufferedImage img = new BufferedImage(w, rows, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < w; x++) {
                    long p = h.offset + (long) y * rowBytes + x * 2L;
                    if (p + 1 >= d.length) {
                        continue;
                    }
                    int v = (d[(int) p] & 0xff) | ((d[(int) p + 1] & 0xff) << 8);
                    int r = (v >> 11) & 0x1f;
                    int g = (v >> 5) & 0x3f;
                    int b = v & 0x1f;
                    int rgb = ((r * 255 / 31) << 16) | ((g * 255 / 63) << 8) | (b * 255 / 31);
                    img.setRGB(x, y, rgb);
                }
            }
            File f = new File(out, "rock_pixel_" + seq + "_" + w + "x" + rows + ".png");
            ImageIO.write(img, "png", f);
            files.add(f);
            seq++;
        }
        return files;
    }

    private static PrintWriter writer(File f) throws IOException {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"));
    }

    private static String q(String s) {
        if (s == null) {
            return "";
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
