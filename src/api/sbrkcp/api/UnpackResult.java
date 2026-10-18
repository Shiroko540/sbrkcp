package sbrkcp.api;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 一次解包的结论。数量、条目明细、是否加密都在这里，报告由 Engine 汇总写出。 */
public final class UnpackResult {

    public String moduleId = "-";
    public File input;
    public File outDir;
    public int pngCount;
    public int fileCount;
    public boolean encrypted;
    public boolean unknown;
    public boolean cancelled;
    public String kind = "UNKNOWN";
    public final List<String> notes = new ArrayList<>();
    public final List<Entry> entries = new ArrayList<>();
    public int exitCode = 0;

    public void note(String s) {
        notes.add(s);
    }

    /** 一条导出资源（图片）：用于基线比对与视觉抽检。 */
    public static final class Entry {
        public String name;
        public String path;
        public int w;
        public int h;
        public int depth;
        public int compress;
        public long pngBytes;

        public Entry(String name, String path, int w, int h, int depth, int compress, long pngBytes) {
            this.name = name;
            this.path = path;
            this.w = w;
            this.h = h;
            this.depth = depth;
            this.compress = compress;
            this.pngBytes = pngBytes;
        }

        @Override
        public String toString() {
            return name + " " + w + "x" + h + " d" + depth + " c" + compress + " " + pngBytes + "B";
        }
    }
}
