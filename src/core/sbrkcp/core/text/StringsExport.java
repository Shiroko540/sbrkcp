package sbrkcp.core.text;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.List;

/** 导出：CSV / TXT / JSON / Markdown，以及 probe_report.txt。全部 UTF-8，CSV 带 BOM。 */
public final class StringsExport {

    public enum Format {
        CSV, TXT, JSON, MD
    }

    private StringsExport() {
    }

    public static Format parse(String s) {
        if (s == null) return Format.CSV;
        String t = s.trim().toUpperCase();
        if (t.startsWith(".")) t = t.substring(1);
        try {
            return Format.valueOf(t);
        } catch (Exception e) {
            return Format.CSV;
        }
    }

    public static String ext(Format f) {
        switch (f) {
            case TXT:
                return "txt";
            case JSON:
                return "json";
            case MD:
                return "md";
            default:
                return "csv";
        }
    }

    public static File export(StringsProbe.Report r, File outDir, String baseName, Format f) throws IOException {
        outDir.mkdirs();
        File out = new File(outDir, baseName + "." + ext(f));
        write(out, render(r, f), f == Format.CSV);
        return out;
    }

    public static String render(StringsProbe.Report r, Format f) {
        List<StringsEntry> list = r.all();
        switch (f) {
            case TXT:
                return renderTxt(list, r);
            case JSON:
                return renderJson(list, r);
            case MD:
                return renderMd(list, r);
            default:
                return renderCsv(list);
        }
    }

    private static String renderCsv(List<StringsEntry> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,offset,cn,en,source,encoding,low_confidence,noise_reason\n");
        for (StringsEntry e : list) {
            sb.append(e.id).append(',')
                    .append("0x").append(Long.toHexString(e.offset)).append(',')
                    .append(q(e.displayCn())).append(',')
                    .append(q(e.displayEn())).append(',')
                    .append(q(e.source)).append(',')
                    .append(q(e.encoding)).append(',')
                    .append(e.lowConfidence ? 1 : 0).append(',')
                    .append(q(e.noiseReason)).append('\n');
        }
        return sb.toString();
    }

    private static String renderTxt(List<StringsEntry> list, StringsProbe.Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(r.name).append("  共 ").append(list.size()).append(" 条\n");
        sb.append("# id\toffset\tcn\ten\tsource\tencoding\n");
        for (StringsEntry e : list) {
            sb.append(e.id).append('\t')
                    .append("0x").append(Long.toHexString(e.offset)).append('\t')
                    .append(e.displayCn()).append('\t')
                    .append(e.displayEn()).append('\t')
                    .append(e.source).append('\t')
                    .append(e.encoding).append('\n');
        }
        return sb.toString();
    }

    private static String renderJson(List<StringsEntry> list, StringsProbe.Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"file\": ").append(j(r.name)).append(",\n");
        sb.append("  \"size\": ").append(r.size).append(",\n");
        sb.append("  \"total\": ").append(list.size()).append(",\n");
        sb.append("  \"entries\": [\n");
        for (int i = 0; i < list.size(); i++) {
            StringsEntry e = list.get(i);
            sb.append("    {\"id\": ").append(e.id)
                    .append(", \"offset\": \"0x").append(Long.toHexString(e.offset)).append("\"")
                    .append(", \"cn\": ").append(j(e.displayCn()))
                    .append(", \"en\": ").append(j(e.displayEn()))
                    .append(", \"source\": ").append(j(e.source))
                    .append(", \"encoding\": ").append(j(e.encoding))
                    .append(", \"low_confidence\": ").append(e.lowConfidence)
                    .append(", \"noise_reason\": ").append(j(e.noiseReason))
                    .append("}").append(i + 1 < list.size() ? "," : "").append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String renderMd(List<StringsEntry> list, StringsProbe.Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 界面文案提取报告\n\n");
        sb.append("- 文件：`").append(r.name).append("`\n");
        sb.append("- 大小：").append(r.size).append(" 字节\n");
        sb.append("- 条目：").append(list.size()).append(" 条（结构化 ")
                .append(r.structured.size()).append(" / 裸扫 ").append(r.bare.size()).append("）\n");
        if (r.diag != null) sb.append("- 诊断：").append(r.diag.summary).append('\n');
        sb.append("\n## 策略台账\n\n| 策略 | 状态 | 命中 | 耗时 | 备注 |\n|---|---|---|---|---|\n");
        for (StringsProbe.Step s : r.steps) {
            sb.append("| ").append(s.id).append(' ').append(s.name).append(" | ").append(s.status)
                    .append(" | ").append(s.hits).append(" | ").append(s.ms).append("ms | ")
                    .append(s.note.replace("|", "\\|")).append(" |\n");
        }
        if (!r.notes.isEmpty()) {
            sb.append("\n## 备注\n\n");
            for (String n : r.notes) sb.append("- ").append(n).append('\n');
        }
        sb.append("\n## 文案\n\n| 编号 | 偏移 | 中文 | 英文 | 来源 |\n|---|---|---|---|---|\n");
        for (StringsEntry e : list) {
            sb.append("| ").append(e.id < 0 ? "" : String.valueOf(e.id)).append(" | 0x")
                    .append(Long.toHexString(e.offset)).append(" | ")
                    .append(esc(e.displayCn())).append(" | ").append(esc(e.displayEn())).append(" | ")
                    .append(e.source).append(" |\n");
        }
        return sb.toString();
    }

    /** probe_report.txt：尝试清单 + 策略台账 + 结论。 */
    public static File writeReport(StringsProbe.Report r, File outDir) throws IOException {
        outDir.mkdirs();
        File f = new File(outDir, "probe_report.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("sbrkcp 界面文案提取 · 探测报告\n");
        sb.append("文件: ").append(r.name).append('\n');
        sb.append("大小: ").append(r.size).append(" 字节\n");
        if (r.filesScanned > 1) sb.append("扫描文件数: ").append(r.filesScanned).append('\n');
        sb.append("结果: ").append(r.total()).append(" 条（结构化 ").append(r.structured.size())
                .append(" / 裸扫 ").append(r.bare.size()).append("）\n\n");
        sb.append("尝试清单:\n");
        for (StringsProbe.Step s : r.steps) {
            sb.append("  ").append(s.id).append(' ').append(s.name)
                    .append("  = ").append(s.status)
                    .append("  hits=").append(s.hits)
                    .append("  ").append(s.ms).append("ms")
                    .append(s.note.isEmpty() ? "" : "  [" + s.note + "]")
                    .append('\n');
        }
        if (!r.notes.isEmpty()) {
            sb.append("\n备注:\n");
            for (String n : r.notes) sb.append("  · ").append(n).append('\n');
        }
        if (!r.perFile.isEmpty()) {
            sb.append("\n逐文件:\n");
            int lim = Math.min(r.perFile.size(), 200);
            for (int i = 0; i < lim; i++) sb.append("  ").append(r.perFile.get(i)).append('\n');
            if (r.perFile.size() > lim) sb.append("  ... 另有 ").append(r.perFile.size() - lim).append(" 个文件\n");
        }
        if (r.empty()) {
            sb.append("\n结论: 未提取到文案。以上为全部尝试的策略与结果，未做静默跳过。\n");
        } else {
            sb.append("\n结论: 提取成功，共 ").append(r.total()).append(" 条。\n");
        }
        write(f, sb.toString(), false);
        return f;
    }

    private static void write(File f, String text, boolean bom) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            if (bom) fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            Writer w = new OutputStreamWriter(fos, Charset.forName("UTF-8"));
            w.write(text);
            w.flush();
        } finally {
            fos.close();
        }
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String j(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
