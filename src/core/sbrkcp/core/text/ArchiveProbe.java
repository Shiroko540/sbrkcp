package sbrkcp.core.text;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * S8 压缩层解压重试。解出来的缓冲重新走完整策略链（由调度器负责递归）。
 * 支持 zlib（含裸 deflate）、gzip、RLE；LZMA 无 JDK 内置实现，如实记为"不支持"。
 */
public final class ArchiveProbe {

    public static final class Blob {
        public final String kind;
        public final byte[] data;

        Blob(String kind, byte[] data) {
            this.kind = kind;
            this.data = data;
        }
    }

    private ArchiveProbe() {
    }

    public static List<Blob> tryInflate(byte[] d) {
        List<Blob> out = new ArrayList<Blob>();
        byte[] z = inflate(d, false);
        if (z != null && z.length >= 1024) out.add(new Blob("zlib", z));
        byte[] raw = inflate(d, true);
        if (raw != null && raw.length >= 1024 && !sameAs(raw, z)) out.add(new Blob("deflate", raw));
        byte[] gz = gunzip(d);
        if (gz != null && gz.length >= 1024) out.add(new Blob("gzip", gz));
        byte[] rle = unrle(d);
        if (rle != null && rle.length >= 1024) out.add(new Blob("rle", rle));
        return out;
    }

    private static boolean sameAs(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static byte[] inflate(byte[] d, boolean nowrap) {
        Inflater inf = new Inflater(nowrap);
        try {
            inf.setInput(d);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, d.length * 4));
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            while (!inf.finished() && total < 256L * 1024 * 1024) {
                int n;
                try {
                    n = inf.inflate(buf);
                } catch (DataFormatException e) {
                    return null;
                }
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                } else {
                    bos.write(buf, 0, n);
                    total += n;
                }
            }
            if (total <= 0) return null;
            return bos.toByteArray();
        } finally {
            inf.end();
        }
    }

    private static byte[] gunzip(byte[] d) {
        if (d.length < 18 || (d[0] & 0xFF) != 0x1F || (d[1] & 0xFF) != 0x8B) return null;
        try {
            GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(d));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0 && total < 256L * 1024 * 1024) {
                bos.write(buf, 0, n);
                total += n;
            }
            in.close();
            return total > 0 ? bos.toByteArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 简化 RLE：值 + 单字节计数（1..255）。只在重复度足够高时尝试，降低假阳性。 */
    private static byte[] unrle(byte[] d) {
        if (d.length < 512) return null;
        long repeats = 0;
        for (int i = 1; i < d.length; i++) {
            if (d[i] == d[i - 1]) repeats++;
        }
        if (repeats * 100L / d.length < 40) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(d.length * 3);
        for (int i = 0; i + 1 < d.length && bos.size() < 64 * 1024 * 1024; ) {
            int v = d[i] & 0xFF;
            int cnt = d[i + 1] & 0xFF;
            if (cnt == 0) {
                i += 2;
                continue;
            }
            for (int k = 0; k < cnt && bos.size() < 64 * 1024 * 1024; k++) bos.write(v);
            i += 2;
        }
        return bos.size() >= 1024 ? bos.toByteArray() : null;
    }
}
