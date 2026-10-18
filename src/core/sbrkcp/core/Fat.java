package sbrkcp.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;

/**
 * FAT12 / FAT16 / FAT32 镜像遍历与导出（L1）。
 *
 * 与首版相比的硬约束（全部来自 RK28 总纲 §2.4 的四个坑）：
 * 1. LFN 片段按 byte0 低 5 位排序后再拼 UTF-16LE，不排序必乱码；
 * 2. 目录一律按簇链逐簇拼接，禁止「首簇 + 连续读」，目录跨簇且簇不连续时会串数据；
 * 3. 簇链维护 visited 集合与深度上限，命中环记 DIR-LOOP 后跳过；
 * 4. size == 0 直接建空文件，不走簇链（首簇为 0 时 chain 会读到错误内容）；
 * 5. 链尾按 FAT 位宽判定（FAT12 0xFF8 / FAT16 0xFFF8 / FAT32 0x0FFFFFF8）。
 */
public final class Fat {

    /** L1 统计，用于门禁对账（目录数 / 文件数 / 总字节 / 顶层项 / 异常数）。 */
    public static final class Stats {
        public String fsType = "?";
        public int fatBits;
        public int bytesPerSec;
        public int secPerClus;
        public int reserved;
        public int numFats;
        public int rootEnts;
        public long totalSec;
        public long fatSize;
        public long dataOff;
        public long clusterCount;
        public String label = "";

        public int dirs;
        public int files;
        public int topItems;
        public int emptyFiles;
        public int anomalies;
        public int dirLoops;
        public long totalBytes;

        @Override
        public String toString() {
            return fsType + " 簇=" + secPerClus * bytesPerSec + "B"
                    + " 目录 " + dirs + " 文件 " + files + " 合计 " + totalBytes + "B"
                    + " 顶层 " + topItems + " 空文件 " + emptyFiles + " 异常 " + anomalies;
        }
    }

    private final BigFile f;
    private final int bytesPerSec;
    private final int secPerClus;
    private final int reserved;
    private final int numFats;
    private final int rootEnts;
    private final long totalSec;
    private final long fatSize;
    private final long fat32RootClus;
    private final long rootDirOff;
    private final long rootDirSize;
    private final long dataOff;
    private final int fatBits;
    private final int[] fat;
    private final Stats st = new Stats();
    private int anomalies;

    private static final OutputStream NULL_SINK = new OutputStream() {
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    };

    private static final int MAX_DEPTH = 12;
    private static final long MAX_DIR_BYTES = 8L * 1024 * 1024;

    public Fat(BigFile f) {
        this.f = f;
        this.bytesPerSec = f.u16le(0x0B);
        this.secPerClus = f.u8(0x0D);
        this.reserved = f.u16le(0x0E);
        this.numFats = f.u8(0x10);
        this.rootEnts = f.u16le(0x11);
        long ts16 = f.u16le(0x13);
        long ts32 = f.u32le(0x20);
        this.totalSec = ts16 != 0 ? ts16 : ts32;
        long fs16 = f.u16le(0x16);
        long fs32 = f.u32le(0x24);
        this.fatSize = fs16 != 0 ? fs16 : fs32;
        this.fat32RootClus = fs16 == 0 ? f.u32le(0x2C) : 0;
        long rootDirSecs = fs16 != 0 ? (rootEnts * 32L + Math.max(1, bytesPerSec) - 1) / Math.max(1, bytesPerSec) : 0;
        this.rootDirOff = (reserved + numFats * fatSize) * (long) bytesPerSec;
        this.rootDirSize = rootDirSecs * bytesPerSec;
        this.dataOff = rootDirOff + rootDirSize;

        long clusters = 0;
        if (bytesPerSec > 0 && secPerClus > 0 && totalSec > reserved + numFats * fatSize + rootDirSecs) {
            clusters = (totalSec - reserved - numFats * fatSize - rootDirSecs) / secPerClus;
        }
        this.fatBits = clusters == 0 ? 16 : (clusters < 4085 ? 12 : (clusters < 65525 ? 16 : 32));

        st.fatBits = fatBits;
        st.bytesPerSec = bytesPerSec;
        st.secPerClus = secPerClus;
        st.reserved = reserved;
        st.numFats = numFats;
        st.rootEnts = rootEnts;
        st.totalSec = totalSec;
        st.fatSize = fatSize;
        st.dataOff = dataOff;
        st.clusterCount = clusters;
        st.fsType = "FAT" + fatBits;
        if (fs16 != 0 && bytesPerSec > 0 && reserved > 0) {
            String s = f.ascii(0x36, 8);
            if (s != null) st.fsType = s.trim();
            String vol = f.ascii(0x2B, 11);
            if (vol != null) st.label = vol.trim();
        }
        this.fat = loadFat();
    }

    public Stats stats() {
        return st;
    }

    public boolean valid() {
        return bytesPerSec > 0 && secPerClus > 0 && fatSize > 0 && fat != null && totalSec > 0
                && bytesPerSec <= 8192 && (bytesPerSec & (bytesPerSec - 1)) == 0;
    }

    // ------------------------------------------------------------- FAT 表

    private int[] loadFat() {
        long bytes = fatSize * bytesPerSec;
        if (bytes <= 0 || bytes > 64L * 1024 * 1024) return null;
        if (!f.inRange(reserved * (long) bytesPerSec, bytes)) return null;
        byte[] b = f.bytes(reserved * (long) bytesPerSec, (int) bytes);
        if (b == null) return null;
        int n;
        int[] t;
        switch (fatBits) {
            case 12:
                n = (int) (b.length * 2L / 3L);
                t = new int[n];
                for (int i = 0; i < n; i++) {
                    int off = i + i / 2;
                    if (off + 1 >= b.length) break;
                    int v = (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
                    t[i] = ((i & 1) == 0) ? (v & 0x0FFF) : (v >> 4);
                }
                break;
            case 32:
                n = b.length / 4;
                t = new int[n];
                for (int i = 0; i < n; i++) t[i] = (int) (BigFile.u32(b, i * 4) & 0x0FFFFFFFL);
                break;
            default:
                n = b.length / 2;
                t = new int[n];
                for (int i = 0; i < n; i++) t[i] = (b[i * 2] & 0xFF) | ((b[i * 2 + 1] & 0xFF) << 8);
                break;
        }
        return n == 0 ? null : t;
    }

    private boolean isEoc(int v) {
        switch (fatBits) {
            case 12: return v >= 0xFF8;
            case 32: return v >= 0x0FFFFFF8;
            default: return v >= 0xFFF8;
        }
    }

    private boolean isBad(int v) {
        return fatBits == 32 ? (v & 0x0FFFFFFF) == 0x0FFFFFF7 : (fatBits == 12 ? v == 0xFF7 : v == 0xFFF7);
    }

    private long clusterOff(int clus) {
        return dataOff + (long) (clus - 2) * secPerClus * bytesPerSec;
    }

    private int[] chain(int startClus) {
        if (startClus < 2 || startClus >= fat.length) return null;
        List<Integer> list = new ArrayList<Integer>();
        Set<Integer> visited = new HashSet<Integer>();
        int c = startClus;
        while (c >= 2 && c < fat.length && visited.add(c)) {
            list.add(c);
            int next = fat[c];
            if (isEoc(next) || isBad(next) || next < 2) {
                break;
            }
            if (visited.contains(next)) {
                st.dirLoops++;
                break;
            }
            c = next;
        }
        if (list.isEmpty()) return null;
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /**
     * 把簇链写入输出流（sizeHint <= 0 表示读到链尾）。返回实际写出的字节数；
     * 读取不足时计入异常。md 非空时同步计算摘要。
     */
    private long streamChain(int startClus, long sizeHint, OutputStream os, MessageDigest md,
                             ProgressListener p, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        if (sizeHint == 0) return 0;
        int[] cl = chain(startClus);
        if (cl == null) return -1;
        long done = 0;
        long limit = sizeHint > 0 ? sizeHint : Long.MAX_VALUE;
        int chunk = secPerClus * bytesPerSec;
        for (int c : cl) {
            if (cancelled != null && cancelled.get()) break;
            long want = Math.min(chunk, limit - done);
            if (want <= 0) break;
            byte[] part = f.bytes(clusterOff(c), (int) want);
            if (part == null) break;
            try {
                if (os != null) os.write(part);
            } catch (IOException e) {
                break;
            }
            if (md != null) md.update(part);
            done += part.length;
            if (part.length < want) break;
        }
        return done;
    }

    private byte[] dirBytes(int firstClus) {
        List<Integer> all = new ArrayList<Integer>();
        int[] cl = chain(firstClus);
        if (cl == null) return null;
        long total = 0;
        for (int c : cl) {
            all.add(c);
            total += (long) secPerClus * bytesPerSec;
            if (total >= MAX_DIR_BYTES) break;
        }
        int size = (int) Math.min(total, MAX_DIR_BYTES);
        byte[] out = new byte[size];
        int pos = 0;
        for (int c : all) {
            int want = Math.min(secPerClus * bytesPerSec, size - pos);
            if (want <= 0) break;
            byte[] part = f.bytes(clusterOff(c), want);
            if (part == null) break;
            System.arraycopy(part, 0, out, pos, want);
            pos += want;
            if (part.length < want) break;
        }
        return pos == size ? out : java.util.Arrays.copyOf(out, pos);
    }

    // ------------------------------------------------------------- 导出

    /** 遍历并解出全部文件到 outDir/files/。返回文件数。 */
    public int extract(final File outDir, final ProgressListener p, final UnpackResult r,
                       final UnpackOptions opt) {
        if (!valid()) return 0;
        this.outRoot = outDir;
        final Set<Integer> visitedDirs = new HashSet<Integer>();
        if (fatBits == 32) {
            byte[] root = dirBytes((int) fat32RootClus);
            if (root != null) walkEntries(root, "", outDir, p, r, opt, 0, visitedDirs, true);
        } else {
            int size = (int) Math.min(rootDirSize, MAX_DIR_BYTES);
            byte[] root = size > 0 ? f.bytes(rootDirOff, size) : null;
            if (root != null) walkEntries(root, "", outDir, p, r, opt, 0, visitedDirs, true);
        }
        st.anomalies = anomalies + st.dirLoops;
        return st.files;
    }

    private void walkEntries(byte[] d, String path, File outDir, ProgressListener p,
                             UnpackResult r, UnpackOptions opt, int depth,
                             Set<Integer> visitedDirs, boolean top) {
        List<byte[]> lfn = new ArrayList<byte[]>();
        for (int off = 0; off + 32 <= d.length; off += 32) {
            int b0 = d[off] & 0xFF;
            if (b0 == 0x00) break;
            if (b0 == 0xE5) { lfn.clear(); continue; }

            int attr = d[off + 11] & 0xFF;
            if ((attr & 0x0F) == 0x0F) {
                byte[] frag = java.util.Arrays.copyOfRange(d, off, off + 32);
                lfn.add(frag);
                continue;
            }
            if ((attr & 0x08) != 0) { lfn.clear(); continue; } // 卷标

            String name = lfn.isEmpty() ? shortName(d, off) : longName(lfn, d, off);
            lfn.clear();
            if (name == null) continue;
            name = sanitize(name);
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
            String rel = path.isEmpty() ? name : path + "/" + name;

            int hi = fatBits == 32 ? (d[off + 20] & 0xFF) : 0;
            int lo = (d[off + 26] & 0xFF) | ((d[off + 27] & 0xFF) << 8);
            int clus = (hi << 16) | lo;
            long size = BigFile.u32(d, off + 28);
            boolean isDir = (attr & 0x10) != 0;
            if (top) st.topItems++;

            if (isDir) {
                st.dirs++;
                int key = clus;
                if (clus < 2) {
                    anomalies++;
                    note(r, "目录首簇非法: " + rel + " clus=" + clus);
                    continue;
                }
                if (!visitedDirs.add(key)) {
                    st.dirLoops++;
                    note(r, "DIR-LOOP: " + rel + " 簇链成环，已跳过");
                    continue;
                }
                if (depth + 1 > MAX_DEPTH) {
                    anomalies++;
                    note(r, "目录深度超限: " + rel);
                    continue;
                }
                byte[] sub = dirBytes(clus);
                if (sub == null || sub.length == 0) {
                    anomalies++;
                    note(r, "目录读取失败: " + rel);
                    continue;
                }
                if (p != null) p.log("DIR  " + rel + "/ (" + st.dirs + ")");
                walkEntries(sub, rel, outDir, p, r, opt, depth + 1, visitedDirs, false);
            } else {
                st.files++;
                st.totalBytes += Math.max(0, size);
                if (size == 0) st.emptyFiles++;
                if (r != null) r.fileCount++;
                File out = new File(new File(outDir, "files"), rel.replace('/', File.separatorChar));
                long written;
                String md5 = null;
                if (size == 0) {
                    written = 0;
                    if (opt == null || !opt.listOnly) {
                        try {
                            File parent = out.getParentFile();
                            if (parent != null) parent.mkdirs();
                            Files.write(out.toPath(), new byte[0]);
                        } catch (IOException e) {
                            anomalies++;
                            note(r, rel + " 建空文件失败: " + e.getMessage());
                        }
                    }
                    md5 = "d41d8cd98f00b204e9800998ecf8427e";
                } else {
                    written = writeFile(out, clus, size, opt, r);
                    md5 = lastMd5;
                }
                if (written < 0) {
                    anomalies++;
                    note(r, "FAT 读取失败: " + rel + " (首簇 " + clus + ")");
                } else if (written != size) {
                    anomalies++;
                    note(r, "簇链长度不足: " + rel + " 期望 " + size + " 实得 " + written);
                }
                if (record != null) {
                    record.append(csv(rel)).append(',').append(size).append(',')
                            .append(clus).append(',').append(Math.max(0, written)).append(',')
                            .append(md5 == null ? "" : md5).append('\n');
                }
                if (opt != null && opt.fatTranscodeText && written >= 0) {
                    transcode(new File(new File(outDir, "text"), rel.replace('/', File.separatorChar)), rel, opt, r);
                }
                if (p != null) {
                    p.log("FILE " + rel + " (" + size + " B)");
                    if (st.files % 16 == 0) p.progress(Math.min(99, st.files / 4), "解出 " + st.files);
                    if (p.isCancelled()) return;
                }
            }
        }
    }

    private String lastMd5;

    private long writeFile(File out, int clus, long size, UnpackOptions opt, UnpackResult r) {
        lastMd5 = null;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            md = null;
        }
        if (opt != null && opt.listOnly) {
            long n = streamChain(clus, size, NULL_SINK, md, null, null);
            lastMd5 = hex(md);
            return n;
        }
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        OutputStream os = null;
        try {
            os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
            long n = streamChain(clus, size, os, md, null, null);
            os.flush();
            lastMd5 = hex(md);
            return n;
        } catch (IOException e) {
            if (r != null) r.note(out.getName() + " 写盘失败: " + e.getMessage());
            return -1;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignore) {
                    // 忽略
                }
            }
        }
    }

    private static String hex(MessageDigest md) {
        if (md == null) return null;
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private void note(UnpackResult r, String s) {
        if (r != null) r.note(s);
    }

    // ------------------------------------------------------------- 文件名

    private static String shortName(byte[] d, int off) {
        byte[] n = new byte[8];
        for (int i = 0; i < 8; i++) n[i] = d[off + i];
        if (n[0] == 0x05) n[0] = (byte) 0xE5;
        int l = 8;
        while (l > 0 && (n[l - 1] == ' ' || n[l - 1] == 0)) l--;
        String base = new String(n, 0, l, StandardCharsets.ISO_8859_1);
        String ext = new String(d, off + 8, 3, StandardCharsets.ISO_8859_1).trim();
        int nt = d[off + 12] & 0xFF;
        if ((nt & 0x08) != 0) base = base.toLowerCase();
        if ((nt & 0x10) != 0) ext = ext.toLowerCase();
        return ext.isEmpty() ? base : base + "." + ext;
    }

    /** LFN 片段按 byte0 低 5 位升序拼 UTF-16LE；遇 0x0000 终止、0xFFFF 丢弃。 */
    private static String longName(List<byte[]> frags, byte[] d, int off) {
        List<byte[]> sorted = new ArrayList<byte[]>(frags);
        java.util.Collections.sort(sorted, new java.util.Comparator<byte[]>() {
            @Override
            public int compare(byte[] a, byte[] b) {
                return (a[0] & 0x1F) - (b[0] & 0x1F);
            }
        });
        StringBuilder sb = new StringBuilder();
        for (byte[] e : sorted) {
            sb.append(decodeLfnPart(e, 1, 10));
            sb.append(decodeLfnPart(e, 14, 12));
            sb.append(decodeLfnPart(e, 28, 4));
        }
        String s = sb.toString();
        int end = s.indexOf('\u0000');
        if (end >= 0) s = s.substring(0, end);
        s = s.replace("\uFFFF", "");
        if (s.trim().isEmpty()) return shortName(d, off);
        return s;
    }

    private static String decodeLfnPart(byte[] e, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < len; i += 2) {
            int ch = (e[off + i] & 0xFF) | ((e[off + i + 1] & 0xFF) << 8);
            if (ch == 0x0000 || ch == 0xFFFF) break;
            sb.append((char) ch);
        }
        return sb.toString();
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == '<' || c == '>' || c == ':' || c == '"' || c == '/' || c == '\\'
                    || c == '|' || c == '?' || c == '*') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ------------------------------------------------------------- 清单 / 转码

    private StringBuilder record;

    public void beginManifest() {
        record = new StringBuilder("path,size,first_clus,read_bytes,md5\n");
    }

    public String manifest() {
        return record == null ? null : record.toString();
    }

    private static String csv(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private void transcode(File dst, String rel, UnpackOptions opt, UnpackResult r) {
        String lower = rel.toLowerCase();
        if (!(lower.endsWith(".txt") || lower.endsWith(".ime") || lower.endsWith(".cfg") || lower.endsWith(".rkz"))) {
            return;
        }
        File src = new File(new File(outRoot == null ? new File(".") : outRoot, "files"),
                rel.replace('/', File.separatorChar));
        if (!src.isFile()) return;
        try {
            byte[] b = Files.readAllBytes(src.toPath());
            String text;
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
                text = new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
            } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
                text = new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
            } else {
                Charset gbk;
                try {
                    gbk = Charset.forName("GBK");
                } catch (Exception e) {
                    gbk = StandardCharsets.ISO_8859_1;
                }
                text = new String(b, gbk);
            }
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.write(dst.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            note(r, rel + " 转码失败: " + e.getMessage());
        }
    }

    private File outRoot;
}