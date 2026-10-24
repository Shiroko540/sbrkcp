package sbrkcp.core.text;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.UnpackOptions;
import sbrkcp.core.Engine;

/**
 * 界面文案提取：CLI 与 GUI 共用的唯一入口（同一实现，保证输出一致）。
 * 任意路径一律尝试；未命中结构化表时按模块解包再递归，最后才报"未提取到文案"。
 */
public final class StringsRunner {

    /** 日志出口：CLI 打到 stdout，GUI 打到面板。 */
    public interface Log {
        void line(String s);
    }

    public static final class Options {
        public File input;
        public File outDir;
        public String module = "";
        public String formats = "csv";
        public String lang = "both";
        public boolean dedup = false;
        public boolean strip = false;
        public String strategies = "";
        public int minLen = 0;
        public int bareLimit = 0;
        public boolean quiet = false;
        public StringsProbe.Options probe = new StringsProbe.Options();
        /** 显式指定模块时用它的解包实现，否则退回 Engine.auto 自动识别。 */
        public sbrkcp.api.SbrkcpModule moduleImpl;
        /** S1 未命中且输入是文件时，自动按容器解包后递归。 */
        public boolean autoUnpack = true;

        public static Options of(File in) {
            Options o = new Options();
            o.input = in;
            return o;
        }
    }

    private StringsRunner() {
    }

    // ---------- 探测 ----------

    public static StringsProbe.Report probe(Options o, Log log) {
        if (o.probe == null) o.probe = new StringsProbe.Options();
        StringsProbe.Options po = o.probe;
        po.dedup = o.dedup;
        po.stripTrailing = o.strip;
        if (o.strategies != null && !o.strategies.isEmpty()) po.strategies = o.strategies;
        if (o.bareLimit > 0) po.bareLimit = o.bareLimit;
        if (o.minLen > 0) {
            po.minCnLen = o.minLen;
            po.minEnLen = Math.max(o.minLen, 4);
        }
        File in = o.input;
        String label = in == null ? "(null)" : in.getAbsolutePath();
        emit(log, "输入：" + label + (in != null && in.isDirectory() ? "（目录）" : ""), o.quiet);

        StringsProbe.Report r;
        if (in != null && in.isDirectory()) r = StringsProbe.runDir(in, po);
        else r = StringsProbe.runFile(in, po);

        boolean canUnpack = in != null && in.isFile() && in.length() > 0;
        if (r.structured.isEmpty() && canUnpack
                && (o.moduleImpl != null || (o.autoUnpack && in.length() < 256L * 1024 * 1024))) {
            emit(log, "内嵌字符串表未命中，按容器解包后递归（" + (o.moduleImpl == null ? "自动识别" : o.moduleImpl.info().id) + "）", o.quiet);
            StringsProbe.Report sub = unpackAndProbe(in, o, po, log);
            if (sub != null) {
                merge(r, sub, "M/");
                r.notes.add("结构化结果来自容器解包后的递归扫描（" + sub.filesScanned + " 个文件）");
            }
        }
        if (r.empty()) {
            emit(log, "未提取到文案；已尝试策略见 probe_report.txt", o.quiet);
        }
        return r;
    }

    private static StringsProbe.Report unpackAndProbe(final File in, final Options o, StringsProbe.Options po, final Log log) {
        if (in == null || !in.isFile()) return null;
        File base = o.outDir == null ? new File("output") : o.outDir;
        File tmp = new File(base, "_probe_unpack");
        deleteTree(tmp);
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            emit(log, "无法创建解包临时目录：" + tmp, o.quiet);
            return null;
        }
        try {
            UnpackOptions uo = UnpackOptions.defaults();
            ProgressListener pl = new ProgressListener() {
                @Override
                public void log(String line) {
                    emit(log, "  " + line, o.quiet);
                }

                @Override
                public void progress(int percent, String message) {
                    // 非交互场景不刷进度条
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };
            if (o.moduleImpl != null) {
                sbrkcp.api.UnpackResult ur = o.moduleImpl.unpack(in, tmp, pl, uo);
                emit(log, "模块 " + o.moduleImpl.info().id + " 解包完成：文件 " + ur.fileCount
                        + " 条目 " + ur.entries.size() + " 说明 " + ur.notes, o.quiet);
            } else {
                Engine.auto(in, tmp, pl, uo);
            }
        } catch (Throwable t) {
            emit(log, "解包失败（继续对原文件结果出报告）：" + t, o.quiet);
            return null;
        }
        File sid = findStringId(tmp);
        StringsProbe.Report sub;
        if (sid != null) {
            emit(log, "定位到字符串表文件：" + sid.getAbsolutePath(), o.quiet);
            sub = StringsProbe.runFile(sid, po);
            if (sub.structured.isEmpty()) {
                StringsProbe.Report dir = StringsProbe.runDir(tmp, po);
                merge(sub, dir, "S3/");
            }
        } else {
            emit(log, "未定位到 STRINGID.BIN，改为整目录递归", o.quiet);
            sub = StringsProbe.runDir(tmp, po);
        }
        return sub;
    }

    /** 文件名大小写不敏感地找 STRINGID.BIN，优先 RESOURCE/SID 路径。 */
    private static File findStringId(File dir) {
        List<File> hits = new ArrayList<File>();
        collectByName(dir, "stringid.bin", hits, 0);
        if (hits.isEmpty()) return null;
        for (File f : hits) {
            String p = f.getAbsolutePath().toLowerCase();
            if (p.contains("resource") && p.contains("sid")) return f;
        }
        return hits.get(0);
    }

    private static void collectByName(File dir, String lowerName, List<File> out, int depth) {
        if (depth > 6) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectByName(f, lowerName, out, depth + 1);
            else if (f.getName().toLowerCase().equals(lowerName)) out.add(f);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) {
            for (File c : fs) deleteTree(c);
        }
        f.delete();
    }

    private static void merge(StringsProbe.Report dst, StringsProbe.Report src, String prefix) {
        for (StringsEntry e : src.structured) {
            e.source = prefix + e.source;
            dst.structured.add(e);
        }
        for (StringsEntry e : src.bare) {
            e.source = prefix + e.source;
            dst.bare.add(e);
        }
        for (StringsProbe.Step s : src.steps) dst.steps.add(s);
        for (String n : src.notes) dst.notes.add(n);
        dst.filesScanned += src.filesScanned;
        dst.perFile.addAll(src.perFile);
        if (dst.diag == null) dst.diag = src.diag;
        if (dst.steps.size() > 0) {
            StringsProbe.Step last = dst.steps.get(dst.steps.size() - 1);
            if (last != null && "S10".equals(last.id)) last.hits = dst.total();
        }
    }

    // ---------- 导出 ----------

    public static List<File> export(StringsProbe.Report r, Options o, Log log) throws java.io.IOException {
        File dir = o.outDir;
        if (dir == null) dir = new File("output");
        dir.mkdirs();
        String stem = stem(o.input);
        List<File> out = new ArrayList<File>();
        StringsProbe.Report view = view(r, o.lang);
        StringsProbe.Report struct = slice(view, true);
        StringsProbe.Report bare = slice(view, false);
        for (String f : o.formats.split(",")) {
            if (f.trim().isEmpty()) continue;
            StringsExport.Format fmt = StringsExport.parse(f);
            if (!struct.structured.isEmpty()) {
                File w = StringsExport.export(struct, dir, stem + "_界面文案_中英对照", fmt);
                out.add(w);
                emit(log, "已导出（结构化 " + struct.structured.size() + " 条）：" + w.getAbsolutePath(), o.quiet);
            }
            if (!bare.bare.isEmpty()) {
                File w = StringsExport.export(bare, dir, stem + "_裸扫候选", fmt);
                out.add(w);
                emit(log, "已导出（裸扫 " + bare.bare.size() + " 条）：" + w.getAbsolutePath(), o.quiet);
            }
        }
        File rep = StringsExport.writeReport(r, dir);
        out.add(rep);
        emit(log, "探测报告：" + rep.getAbsolutePath(), o.quiet);
        return out;
    }

    /** 结构化与裸扫必须分表，条数永不混算。 */
    private static StringsProbe.Report slice(StringsProbe.Report r, boolean structured) {
        StringsProbe.Report v = new StringsProbe.Report();
        v.name = r.name;
        v.size = r.size;
        v.diag = r.diag;
        v.filesScanned = r.filesScanned;
        v.steps.addAll(r.steps);
        v.notes.addAll(r.notes);
        v.perFile.addAll(r.perFile);
        if (structured) v.structured.addAll(r.structured);
        else v.bare.addAll(r.bare);
        return v;
    }

    /** --lang 只影响导出列，不影响提取结果。 */
    public static StringsProbe.Report view(StringsProbe.Report r, String lang) {
        String mode = lang == null ? "both" : lang.trim().toLowerCase();
        if (mode.equals("both") || mode.isEmpty()) return r;
        StringsProbe.Report v = new StringsProbe.Report();
        v.name = r.name;
        v.size = r.size;
        v.diag = r.diag;
        v.filesScanned = r.filesScanned;
        v.steps.addAll(r.steps);
        v.notes.addAll(r.notes);
        v.perFile.addAll(r.perFile);
        for (StringsEntry e : r.all()) {
            StringsEntry c = copy(e);
            if (mode.equals("cn")) c.en = "";
            else if (mode.equals("en")) c.cn = "";
            if (e.id >= 0) v.structured.add(c);
            else v.bare.add(c);
        }
        return v;
    }

    private static StringsEntry copy(StringsEntry e) {
        StringsEntry c = new StringsEntry();
        c.id = e.id;
        c.offset = e.offset;
        c.cn = e.cn;
        c.en = e.en;
        c.source = e.source;
        c.encoding = e.encoding;
        c.byteLength = e.byteLength;
        c.lowConfidence = e.lowConfidence;
        c.noiseReason = e.noiseReason;
        c.mergedFrom = e.mergedFrom;
        return c;
    }

    private static String stem(File f) {
        if (f == null) return "strings";
        String n = f.getName();
        if (n.isEmpty()) n = "strings";
        int i = n.lastIndexOf('.');
        if (i > 0) n = n.substring(0, i);
        return n;
    }

    // ---------- CLI 执行 ----------

    public static int run(Options o, Log log) {
        if (o.input == null) {
            System.out.println("strings 缺少输入路径");
            return 2;
        }
        if (o.outDir == null) {
            o.outDir = new File("output", stem(o.input) + "_界面文案");
        }
        StringsProbe.Report r = probe(o, log);
        try {
            export(r, o, log);
        } catch (java.io.IOException e) {
            System.out.println("导出失败：" + e);
            return 4;
        }
        System.out.println("---- 结果 ----");
        System.out.println("文件=" + r.name + " 大小=" + r.size + " 字节"
                + " 条目=" + r.total() + "（结构化 " + r.structured.size() + " / 裸扫 " + r.bare.size() + "）");
        for (StringsProbe.Step s : r.steps) {
            System.out.println("  " + s.line());
        }
        for (String n : r.notes) System.out.println("  · " + n);
        return r.empty() ? 5 : 0;
    }

    private static void emit(Log log, String s, boolean quiet) {
        if (quiet) return;
        if (log == null) {
            if (!quiet) System.out.println(s);
            return;
        }
        log.line(s);
    }
}
