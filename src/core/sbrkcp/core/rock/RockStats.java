package sbrkcp.core.rock;

import java.util.HashMap;
import java.util.Map;

/**
 * 字节统计工具：熵、零占比、块重复率、可打印率、周期自相似。
 * 全部按"给定区间"计算，不假设任何格式。
 */
public final class RockStats {

    private RockStats() {
    }

    /** 香农熵（bit/byte，0..8）。 */
    public static double entropy(byte[] d, int off, int len) {
        if (d == null || len <= 0 || off < 0 || off + len > d.length) {
            return 0.0;
        }
        int[] hist = new int[256];
        for (int i = 0; i < len; i++) {
            hist[d[off + i] & 0xff]++;
        }
        double e = 0.0;
        for (int i = 0; i < 256; i++) {
            if (hist[i] == 0) {
                continue;
            }
            double p = (double) hist[i] / (double) len;
            e -= p * (Math.log(p) / Math.log(2.0));
        }
        return e;
    }

    /** 零字节占比 0..1。 */
    public static double zeroRatio(byte[] d, int off, int len) {
        if (len <= 0) {
            return 0.0;
        }
        int z = 0;
        for (int i = 0; i < len; i++) {
            if (d[off + i] == 0) {
                z++;
            }
        }
        return (double) z / (double) len;
    }

    /** 可打印 ASCII + 常用空白占比。 */
    public static double printableRatio(byte[] d, int off, int len) {
        if (len <= 0) {
            return 0.0;
        }
        int p = 0;
        for (int i = 0; i < len; i++) {
            int b = d[off + i] & 0xff;
            if ((b >= 0x20 && b < 0x7f) || b == 0x09 || b == 0x0a || b == 0x0d) {
                p++;
            }
        }
        return (double) p / (double) len;
    }

    /** 8bit 位密度 0..1（点阵字分析用）。 */
    public static double bitDensity(byte[] d, int off, int len) {
        if (len <= 0) {
            return 0.0;
        }
        int ones = 0;
        for (int i = 0; i < len; i++) {
            ones += Integer.bitCount(d[off + i] & 0xff);
        }
        return (double) ones / ((double) len * 8.0);
    }

    /**
     * 定长块重复率：按 block 切块，统计"出现次数 >= 2 的块"占比。
     * 用于识别空洞填充（重复块多）与真实数据（几乎无重复）。
     */
    public static double blockRepeat(byte[] d, int off, int len, int block) {
        if (block <= 0 || len < block) {
            return 0.0;
        }
        int n = len / block;
        if (n == 0) {
            return 0.0;
        }
        Map<Long, Integer> seen = new HashMap<Long, Integer>(Math.max(16, n * 2));
        int dup = 0;
        for (int i = 0; i < n; i++) {
            long h = fnv(d, off + i * block, block);
            Integer c = seen.get(Long.valueOf(h));
            if (c == null) {
                seen.put(Long.valueOf(h), Integer.valueOf(1));
            } else {
                seen.put(Long.valueOf(h), Integer.valueOf(c.intValue() + 1));
                dup++;
            }
        }
        return (double) dup / (double) n;
    }

    /** 周期自相似率：计算 lag 处字节相等比例（取最大 64KB 采样）。 */
    public static double selfSim(byte[] d, int off, int len, int lag) {
        if (lag <= 0 || len <= lag + 16) {
            return 0.0;
        }
        int n = Math.min(len - lag, 65536);
        int same = 0;
        for (int i = 0; i < n; i++) {
            if (d[off + i] == d[off + i + lag]) {
                same++;
            }
        }
        return (double) same / (double) n;
    }

    /** FNV-1a 64 位。 */
    public static long fnv(byte[] d, int off, int len) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < len; i++) {
            h ^= (d[off + i] & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** 最长可打印串长度。 */
    public static int longestPrintable(byte[] d, int off, int len, int min) {
        int best = 0;
        int cur = 0;
        for (int i = 0; i < len; i++) {
            int b = d[off + i] & 0xff;
            boolean pr = (b >= 0x20 && b < 0x7f) || b == 0x09 || b == 0x0a || b == 0x0d;
            if (pr) {
                cur++;
                if (cur > best) {
                    best = cur;
                }
            } else {
                cur = 0;
            }
        }
        return best >= min ? best : 0;
    }
}
