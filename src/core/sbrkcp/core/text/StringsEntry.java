package sbrkcp.core.text;

/** 单条界面文案。结构化提取带编号，裸扫描编号为 -1。 */
public final class StringsEntry {

    public int id = -1;
    public long offset;
    public String cn = "";
    public String en = "";
    public String source = "";
    public String encoding = "";
    public int byteLength;
    /** 低置信：路径片段、寄存器名、十六进制串、重复填充、哈希/Base64 样式。 */
    public boolean lowConfidence;
    /** 低置信原因，高置信为空串。 */
    public String noiseReason = "";
    /** 合并视图里保留来源标记。 */
    public String mergedFrom = "";

    public StringsEntry() {
    }

    public StringsEntry(int id, long offset, String cn, String en, String source, String encoding) {
        this.id = id;
        this.offset = offset;
        this.cn = cn == null ? "" : cn;
        this.en = en == null ? "" : en;
        this.source = source;
        this.encoding = encoding;
    }

    /** 展示用：去掉尾部 \0 与对齐空格；导出保留原始字节走 rawCn/rawEn。 */
    public String displayCn() {
        return trimTail(cn);
    }

    public String displayEn() {
        return trimTail(en);
    }

    public static String trimTail(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\u0000' || c == ' ' || c == '\t' || c == '\r' || c == '\n') end--;
            else break;
        }
        return s.substring(0, end);
    }

    public boolean hasCn() {
        return displayCn().length() > 0;
    }

    public boolean hasEn() {
        return displayEn().length() > 0;
    }

    @Override
    public String toString() {
        return (id >= 0 ? id + "\t" : "\t") + "0x" + Long.toHexString(offset) + "\t"
                + displayCn() + "\t" + displayEn();
    }
}
