package sbrkcp.core.text;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 界面文案提取调度器：S1 → S10 顺序全跑，命中即产出，异常不中断。
 * 不做文件级白名单，不做固件型号判定，不因签名缺失提前退出。
 */
public final class StringsProbe {

    public static final class Options {
        public boolean dedup = true;
        public boolean stripTrailing = false;
        public boolean lowConfidenceInMain = false;
        public int minCnLen = 2;
        public int minEnLen = 4;
        public String strategies = "";
        public int maxDepth = 3;
        public long maxBytes = 512L * 1024 * 1024;
        public boolean recursion = true;
        /** 单条裸扫策略的条数上限；裸扫只是候选，不追求全量。 */
        public int bareLimit = 5000;
        /** 一次任务（含目录递归）的裸扫总量上限；裸扫只是候选池。 */
        public int bareTotalLimit = 30000;

        public static Options defaults() {
            return new Options();
        }

        public Options copy() {
            Options c = new Options();
            c.dedup = dedup;
            c.stripTrailing = stripTrailing;
            c.lowConfidenceInMain = lowConfidenceInMain;
            c.minCnLen = minCnLen;
            c.minEnLen = minEnLen;
            c.strategies = strategies;
            c.maxDepth = maxDepth;
            c.maxBytes = maxBytes;
            c.recursion = recursion;
            c.bareLimit = bareLimit;
            c.bareTotalLimit = bareTotalLimit;
            return c;
        }
    }

    /** 单条策略的执行台账。 */
    public static final class Step {
        public final String id;
        public final String name;
        public String status = "skip";
        public int hits;
        public long ms;
        public String note = "";

        Step(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String line() {
            return id + " " + name + "  status=" + status + " hits=" + hits + " " + ms + "ms"
                    + (note.isEmpty() ? "" : "  " + note);
        }
    }

    public static final class Report {
        public String name = "";
        public long size;
        public final List<StringsEntry> structured = new ArrayList<StringsEntry>();
        public final List<StringsEntry> bare = new ArrayList<StringsEntry>();
        public final List<Step> steps = new ArrayList<Step>();
        public final List<String> notes = new ArrayList<String>();
        public CryptoDiag diag;
        public int filesScanned;
        public final List<String> perFile = new ArrayList<String>();

        public int total() {
            return structured.size() + bare.size();
        }

        public boolean empty() {
            return structured.isEmpty() && bare.isEmpty();
        }

        public List<StringsEntry> all() {
            List<StringsEntry> out = new ArrayList<StringsEntry>(structured);
            out.addAll(bare);
            return out;
        }

        public Step step(String id) {
            for (Step s : steps) {
                if (s.id.equals(id)) return s;
            }
            return null;
        }
    }

    private StringsProbe() {
    }

    // ---------- 对外入口 ----------

    public static Report runFile(File f, Options o) {
        Report r = new Report();
        r.name = f.getName();
        r.size = f.length();
        if (!f.isFile()) {
            Step s = add(r, "S0", "读取输入");
            s.status = "fail";
            s.note = "不是文件：" + f.getAbsolutePath();
            return r;
        }
        if (f.length() > o.maxBytes) {
            Step s = add(r, "S0", "读取输入");
            s.status = "fail";
            s.note = "文件超过上限 " + o.maxBytes + " 字节：" + f.length();
            return r;
        }
        byte[] d;
        try {
            d = readAll(f);
        } catch (IOException e) {
            Step s = add(r, "S0", "读取输入");
            s.status = "fail";
            s.note = "读取失败：" + e.getMessage();
            return r;
        }
        return run(d, f.getName(), o);
    }

    public static Report run(byte[] d, String name, Options o) {
        return runInternal(d, name, o, 0);
    }

    private static Report runInternal(byte[] d, String name, Options o, int depth) {
        Report r = new Report();
        r.name = name;
        r.size = d == null ? 0 : d.length;
        r.filesScanned = 1;
        if (d == null || d.length == 0) {
            Step s = add(r, "S0", "读取输入");
            s.status = "fail";
            s.note = "空数据";
            return r;
        }

        // S1 结构化解析
        Step s1 = add(r, "S1", "RKRS 字符串表");
        long t0 = System.currentTimeMillis();
        StringTable.Hit best = null;
        try {
            best = StringTable.best(d, 64);
            if (best.ok()) {
                s1.status = "hit";
                s1.hits = best.entries.size();
                r.structured.addAll(best.entries);
                s1.note = best.describe();
            } else {
                s1.status = "miss";
                s1.note = "未找到自洽的 RKRS 字段组";
            }
        } catch (Throwable t) {
            s1.status = "error";
            s1.note = t.toString();
        }
        s1.ms = System.currentTimeMillis() - t0;

        // S2 基址/偏移切换
        Step s2 = add(r, "S2", "基址与偏移切换");
        t0 = System.currentTimeMillis();
        try {
            List<Long> cands = StringTable.candidateBases(d, 64);
            int tried = 0;
            int hits = 0;
            for (Long b : cands) {
                tried++;
                if (best != null && b.longValue() == best.base && best.ok()) {
                    hits++;
                    continue;
                }
                StringTable.Hit h = StringTable.parse(d, b.longValue());
                if (h.ok() && h.score > 0) {
                    hits++;
                    for (StringsEntry e : h.entries) {
                        e.source = "S2";
                        r.structured.add(e);
                    }
                }
            }
            s2.status = hits > 0 ? "hit" : "miss";
            s2.hits = hits;
            s2.note = "候选基址 " + tried + " 个，有效 " + hits + " 个"
                    + (best != null && best.ok() ? "，最优 " + best.describe() : "");
        } catch (Throwable t) {
            s2.status = "error";
            s2.note = t.toString();
        }
        s2.ms = System.currentTimeMillis() - t0;

        // S3 容器/目录递归：单文件入口无法展开容器，如实标注
        Step s3 = add(r, "S3", "容器/目录递归");
        s3.status = "skip";
        s3.note = "单文件入口；容器由调用方解包为目录后递归执行";

        // S9 加密诊断放在裸扫前，结果写进报告供判定
        Step s9 = add(r, "S9", "加密/压缩诊断");
        t0 = System.currentTimeMillis();
        try {
            r.diag = CryptoDiag.analyze(d);
            s9.status = "done";
            s9.note = r.diag.summary;
        } catch (Throwable t) {
            s9.status = "error";
            s9.note = t.toString();
        }
        s9.ms = System.currentTimeMillis() - t0;

        // S4–S7 裸扫描
        BareScanner.Options bo = new BareScanner.Options();
        bo.minCnLen = o.minCnLen;
        bo.minEnLen = o.minEnLen;
        bo.strategies = o.strategies;
        bo.maxPerStrategy = o.bareLimit;
        BareScanner.Result br = null;
        for (String id : new String[]{"S4", "S5", "S6", "S7"}) {
            Step st = add(r, id, nameOf(id));
            long ts = System.currentTimeMillis();
            try {
                if (br == null) br = BareScanner.scan(d, bo);
                int before = countBy(br.entries, id);
                st.status = before > 0 ? "hit" : "miss";
                st.hits = before;
            } catch (Throwable t) {
                st.status = "error";
                st.note = t.toString();
            }
            st.ms = System.currentTimeMillis() - ts;
        }
        if (br != null) {
            int low = 0;
            for (StringsEntry e : br.entries) {
                if (e.lowConfidence) low++;
                if (o.lowConfidenceInMain || !e.lowConfidence) r.bare.add(e);
            }
            if (low > 0) r.notes.add("裸扫描低置信条目 " + low + " 条（路径/寄存器/hex/填充/哈希样式）"
                    + (o.lowConfidenceInMain ? "，已并入主列表" : "，已单独标记"));
            if (br.filteredAscii > 0) r.notes.add("S7 ASCII 过滤噪声 " + br.filteredAscii + " 段");
            if (br.overlong > 0) r.notes.add("裸扫丢弃超长文本段 " + br.overlong + " 条（上限 " + bo.maxEntryLen + " 字符，多为二进制误判）");
            for (String n : br.notes) r.notes.add(n);
        }

        // S8 解压重试（命中则对解压缓冲重跑 S1/S4-S7，深度 1）
        Step s8 = add(r, "S8", "压缩层解压重试");
        t0 = System.currentTimeMillis();
        try {
            if (depth >= 1) {
                s8.status = "skip";
                s8.note = "递归深度已达上限，不再二次解压";
            } else {
            List<ArchiveProbe.Blob> blobs = ArchiveProbe.tryInflate(d);
            int added = 0;
            for (ArchiveProbe.Blob b : blobs) {
                Report sub = runInternal(b.data, name + "!" + b.kind, o, depth + 1);
                for (StringsEntry e : sub.structured) {
                    e.source = "S8/" + e.source;
                    r.structured.add(e);
                    added++;
                }
                for (StringsEntry e : sub.bare) {
                    e.source = "S8/" + e.source;
                    r.bare.add(e);
                    added++;
                }
            }
            s8.status = added > 0 ? "hit" : "miss";
            s8.hits = added;
            s8.note = blobs.isEmpty() ? "无可解压层（LZMA 无 JDK 内置实现，未尝试）"
                    : "解压层 " + blobs.size() + " 个";
            if (!blobs.isEmpty() && added == 0) r.notes.add("S8 解压成功但内部未命中任何文案策略");
            }
        } catch (Throwable t) {
            s8.status = "error";
            s8.note = t.toString();
        }
        s8.ms = System.currentTimeMillis() - t0;

        // S10 汇总与去重
        Step s10 = add(r, "S10", "汇总与去重");
        t0 = System.currentTimeMillis();
        try {
            int before = r.total();
            if (o.dedup) dedup(r);
            s10.status = "done";
            s10.hits = r.total();
            s10.note = "去重前 " + before + " 条 → 去重后 " + r.total() + " 条";
        } catch (Throwable t) {
            s10.status = "error";
            s10.note = t.toString();
        }
        s10.ms = System.currentTimeMillis() - t0;

        if (o.stripTrailing) {
            for (StringsEntry e : r.all()) {
                e.cn = e.displayCn();
                e.en = e.displayEn();
            }
        }
        return r;
    }

    /** 对目录递归执行（S3），合并子报告。 */
    public static Report runDir(File dir, Options o) {
        Report r = new Report();
        r.name = dir.getAbsolutePath();
        Step s3 = add(r, "S3", "容器/目录递归");
        long t0 = System.currentTimeMillis();
        List<File> files = new ArrayList<File>();
        collect(dir, files, o.maxDepth, 0);
        int hitFiles = 0;
        int budget = o.bareTotalLimit;
        // 裸扫预算按文件大小加权分配，避免个别大文件（音频等）吃满总量后挤掉后续固件
        long remainBytes = 0;
        for (File f : files) remainBytes += Math.max(1L, f.length());
        for (File f : files) {
            try {
                long sz = Math.max(1L, f.length());
                long share = remainBytes > 0 ? (long) budget * sz / remainBytes : 0;
                Options po = o.copy();
                po.bareLimit = (int) Math.max(32L, Math.min(o.bareLimit, share));
                remainBytes -= sz;
                Report sub = runFile(f, po);
                budget -= sub.bare.size();
                if (budget < 0) budget = 0;
                r.filesScanned++;
                int got = sub.total();
                if (got > 0) hitFiles++;
                for (StringsEntry e : sub.structured) {
                    e.source = "S3/" + e.source;
                    r.structured.add(e);
                }
                for (StringsEntry e : sub.bare) {
                    e.source = "S3/" + e.source;
                    r.bare.add(e);
                }
                r.perFile.add(f.getAbsolutePath() + "  " + got + " 条");
            } catch (Throwable t) {
                r.notes.add("递归失败 " + f.getName() + "：" + t);
            }
        }
        s3.status = hitFiles > 0 ? "hit" : "miss";
        s3.hits = hitFiles;
        s3.ms = System.currentTimeMillis() - t0;
        s3.note = "扫描文件 " + files.size() + " 个，命中 " + hitFiles + " 个";
        dedup(r);
        if (r.bare.size() > o.bareTotalLimit) {
            int drop = r.bare.size() - o.bareTotalLimit;
            r.bare.subList(o.bareTotalLimit, r.bare.size()).clear();
            r.notes.add("裸扫候选触及总量预算 " + o.bareTotalLimit + " 条，已按文件大小加权截断 " + drop + " 条（候选表仅供人工筛查，不影响结构化结果）");
        }
        return r;
    }

    private static void collect(File dir, List<File> out, int maxDepth, int depth) {
        if (depth > maxDepth) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out, maxDepth, depth + 1);
            else if (f.isFile() && f.length() > 0) out.add(f);
        }
    }

    // ---------- 工具 ----------

    private static Step add(Report r, String id, String name) {
        Step s = new Step(id, name);
        r.steps.add(s);
        return s;
    }

    private static String nameOf(String id) {
        if ("S4".equals(id)) return "UTF-16LE 裸扫";
        if ("S5".equals(id)) return "GBK 裸扫";
        if ("S6".equals(id)) return "UTF-8 裸扫";
        if ("S7".equals(id)) return "ASCII 裸扫";
        return id;
    }

    private static int countBy(List<StringsEntry> list, String source) {
        int n = 0;
        for (StringsEntry e : list) {
            if (source.equals(e.source)) n++;
        }
        return n;
    }

    /** 结构化优先；同文合并，裸扫里重复的丢弃。 */
    public static void dedup(Report r) {
        Set<String> seen = new LinkedHashSet<String>();
        List<StringsEntry> ns = new ArrayList<StringsEntry>();
        for (StringsEntry e : r.structured) {
            String key = key(e);
            if (seen.add(key)) ns.add(e);
            else {
                for (StringsEntry kept : ns) {
                    if (key(kept).equals(key)) {
                        if (kept.mergedFrom.isEmpty()) kept.mergedFrom = kept.source;
                        if (!kept.mergedFrom.contains(e.source)) kept.mergedFrom += "+" + e.source;
                        break;
                    }
                }
            }
        }
        r.structured.clear();
        r.structured.addAll(ns);

        Map<String, StringsEntry> bareMap = new LinkedHashMap<String, StringsEntry>();
        for (StringsEntry e : r.bare) {
            String key = key(e);
            if (seen.contains(key)) continue;
            if (bareMap.containsKey(key)) {
                StringsEntry kept = bareMap.get(key);
                if (!kept.mergedFrom.contains(e.source)) {
                    if (kept.mergedFrom.isEmpty()) kept.mergedFrom = kept.source;
                    kept.mergedFrom += "+" + e.source;
                }
                continue;
            }
            bareMap.put(key, e);
        }
        r.bare.clear();
        r.bare.addAll(bareMap.values());
    }

    private static String key(StringsEntry e) {
        return e.displayCn() + "\u0001" + e.displayEn();
    }

    public static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            long len = f.length();
            byte[] buf = new byte[(int) Math.min(len, Integer.MAX_VALUE - 8)];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off == buf.length) return buf;
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        } finally {
            in.close();
        }
    }
}
