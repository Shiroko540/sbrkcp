package sbrkcp.core.rock;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 「ROCK 头 + 16 字节目录表」容器。
 *
 * 不做任何型号 / 文件名判定：
 *  - 签名只认 'ROCK' 四字节前缀；
 *  - 表起点由"自洽搜索"推出，不写死任何偏移；
 *  - 表尾必须恰好等于首条数据 offset（本格式的核心自洽条件）。
 */
public final class RockTable {

    /** 头部版本串最大长度。 */
    public static final int HEAD_LEN = 0x400;
    /** 单条目录记录长度。 */
    public static final int REC_LEN = 16;
    /** 自洽搜索的条目数下限。 */
    public static final int MIN_ENTRIES = 8;

    public long fileLen;
    public String signature = "";
    public String version = "";
    public int tableOffset = -1;
    public int entryCount = 0;
    public final List<Entry> entries = new ArrayList<Entry>();
    public final List<String> notes = new ArrayList<String>();

    /** 一条目录记录 + 统计结果。 */
    public static final class Entry {
        public int index;
        public long recOffset;
        public long type;
        public long offset;
        public long size;
        public long flag;

        public double entropy;
        public double zeroRatio;
        public double repeat16;
        public double repeat512;
        public double printable;
        public double selfSim0x200;
        public int longestText;
        public String category = "未判定";
        public String evidence = "";

        public boolean isEmpty() {
            return type == 0 || size <= 0;
        }
    }

    public boolean valid() {
        return tableOffset >= 0 && entryCount > 0 && !entries.isEmpty();
    }

    /** 签名判定：只要求 ROCK 前缀。 */
    public static boolean looksLikeRock(byte[] head) {
        if (head == null || head.length < 4) {
            return false;
        }
        return head[0] == 'R' && head[1] == 'O' && head[2] == 'C' && head[3] == 'K';
    }

    /** 读取文件头 + 全文（超过 512MB 拒绝，避免 OOM）。 */
    public static byte[] readAll(File f, long maxBytes) throws IOException {
        long n = f.length();
        if (n > maxBytes) {
            throw new IOException("文件过大，超出单次读取上限：" + n);
        }
        byte[] d = new byte[(int) n];
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.readFully(d);
        } finally {
            raf.close();
        }
        return d;
    }

    /**
     * 自洽搜索目录表起点。
     * 判据：连续 N(>=MIN_ENTRIES) 条记录满足
     *   1) type ∈ {0,1}；
     *   2) type=0 时 offset=size=0；
     *   3) type=1 时 offset >= 表尾、size>0、offset+size<=文件长；
     *   4) offset 非递减；
     *   5) 表尾（start + N*16）恰等于首个非零 offset。
     */
    public static RockTable probe(byte[] d) {
        RockTable t = new RockTable();
        t.fileLen = d.length;
        if (!looksLikeRock(d)) {
            return t;
        }
        t.signature = "ROCK";
        int z = 0;
        while (z < 0x40 && z < d.length && d[z] != 0) {
            z++;
        }
        int vs = 4;
        while (vs < z && (d[vs] < 0x20 || d[vs] > 0x7e)) {
            vs++;
        }
        t.version = z > vs ? new String(d, vs, z - vs) : "";
        if (!t.version.startsWith("ROCK")) {
            int k = t.version.indexOf("ROCK");
            if (k >= 0) {
                t.version = t.version.substring(k);
            }
        }

        int bestStart = -1;
        int bestCount = 0;
        int hi = Math.min(0x1000, Math.max(0, d.length - REC_LEN * MIN_ENTRIES));
        for (int start = 0x20; start <= hi; start += 16) {
            int n = 0;
            long prevOff = -1;
            long firstOff = -1;
            long tail = start;
            boolean ok = true;
            while (true) {
                int o = start + n * REC_LEN;
                if (o + REC_LEN > d.length) {
                    break;
                }
                long ty = u32(d, o);
                long off = u32(d, o + 4);
                long sz = u32(d, o + 8);
                if (ty != 0 && ty != 1) {
                    break;
                }
                if (ty == 0) {
                    if (off != 0 || sz != 0) {
                        break;
                    }
                } else {
                    if (sz <= 0) {
                        // type=1 且 size=0 是合法条目（工作表实测存在），只要 offset 单调
                        if (off < prevOff && prevOff >= 0) {
                            break;
                        }
                    }
                    if (off < 0x20 || off + sz > d.length) {
                        break;
                    }
                    if (prevOff >= 0 && off < prevOff) {
                        break;
                    }
                    prevOff = off;
                    if (firstOff < 0) {
                        firstOff = off;
                    }
                }
                n++;
                tail = start + n * REC_LEN;
                if (n > 4096) {
                    break;
                }
            }
            if (n < MIN_ENTRIES) {
                continue;
            }
            // 表尾自洽：首条数据必须紧接表尾（允许 0 / 16 字节余量）
            boolean selfConsistent = firstOff >= 0
                    && (firstOff == tail || firstOff == tail + 16L || firstOff == tail + 0x10L);
            boolean better = n > bestCount || (n == bestCount && selfConsistent);
            if (selfConsistent && (bestStart < 0 || better)) {
                bestStart = start;
                bestCount = n;
            } else if (bestStart < 0 && better && n >= MIN_ENTRIES * 2) {
                // 找不到严格自洽时，接受最长的宽松候选，但记录告警
                bestStart = start;
                bestCount = n;
            }
        }

        if (bestStart < 0) {
            t.notes.add("未找到自洽的 16 字节目录表候选（已扫描 0x20–0x" + Integer.toHexString(hi) + "）");
            return t;
        }
        t.tableOffset = bestStart;
        t.entryCount = bestCount;
        long firstOff = -1;
        for (int i = 0; i < bestCount; i++) {
            int o = bestStart + i * REC_LEN;
            Entry e = new Entry();
            e.index = i;
            e.recOffset = o;
            e.type = u32(d, o);
            e.offset = u32(d, o + 4);
            e.size = u32(d, o + 8);
            e.flag = u32(d, o + 12);
            t.entries.add(e);
            if (e.type == 1 && e.size > 0 && firstOff < 0) {
                firstOff = e.offset;
            }
        }
        long tail = bestStart + (long) bestCount * REC_LEN;
        if (firstOff >= 0 && firstOff != tail) {
            t.notes.add("表尾 " + hex(tail) + " 与首条数据 " + hex(firstOff) + " 不相等，疑有额外头字段");
        }
        return t;
    }

    /** 条目边界校验（T-103）：不满足只告警，不中断。 */
    public List<String> validate() {
        List<String> warn = new ArrayList<String>();
        List<Entry> filled = new ArrayList<Entry>();
        for (Entry e : entries) {
            if (e.type == 1) {
                filled.add(e);
            }
        }
        for (int i = 0; i + 1 < filled.size(); i++) {
            Entry a = filled.get(i);
            Entry b = filled.get(i + 1);
            if (a.size > 0 && a.offset + a.size > b.offset) {
                warn.add("#" + a.index + " 越界：" + hex(a.offset + a.size) + " > " + hex(b.offset));
            }
        }
        Entry last = filled.isEmpty() ? null : filled.get(filled.size() - 1);
        if (last != null && last.offset + last.size > fileLen) {
            warn.add("末条 #" + last.index + " 超出文件长度");
        }
        if (last != null && last.offset + last.size < fileLen) {
            warn.add("末条终点 " + hex(last.offset + last.size) + " 之后余 " + (fileLen - last.offset - last.size) + " 字节未描述");
        }
        return warn;
    }

    public static long u32(byte[] d, int o) {
        if (o + 4 > d.length) {
            return 0;
        }
        return ((long) (d[o] & 0xff))
                | ((long) (d[o + 1] & 0xff) << 8)
                | ((long) (d[o + 2] & 0xff) << 16)
                | ((long) (d[o + 3] & 0xff) << 24);
    }

    public static String hex(long v) {
        return "0x" + Long.toHexString(v).toUpperCase();
    }
}
