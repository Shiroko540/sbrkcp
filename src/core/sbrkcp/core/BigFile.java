package sbrkcp.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 只读大文件薄封装。所有读取都做边界检查，越界返回 null / -1，绝不把
 * ArrayIndexOutOfBounds 抛给上层。
 */
public final class BigFile implements Closeable {

    private final RandomAccessFile raf;
    private final long size;
    private final File file;
    private static final int CHUNK = 1 << 20;

    public BigFile(File f) throws IOException {
        this.file = f;
        this.raf = new RandomAccessFile(f, "r");
        this.size = raf.length();
    }

    public File file() {
        return file;
    }

    public long size() {
        return size;
    }

    /** 区间必须完全落在文件内。 */
    public boolean inRange(long off, long len) {
        if (off < 0 || len < 0) return false;
        long end = off + len;
        if (end < 0) return false; // 溢出
        return end <= size;
    }

    public int readAt(long off, byte[] buf, int len) {
        if (buf == null || len <= 0 || !inRange(off, len)) return -1;
        try {
            raf.seek(off);
            int done = 0;
            while (done < len) {
                int n = raf.read(buf, done, len - done);
                if (n <= 0) break;   // EOF 或零进展：交给调用方按短读处理，不能死循环
                done += n;
            }
            return done;
        } catch (IOException e) {
            return -1;
        }
    }

    public int u8(long off) {
        if (!inRange(off, 1)) return -1;
        byte[] b = new byte[1];
        return readAt(off, b, 1) == 1 ? (b[0] & 0xFF) : -1;
    }

    public int u16le(long off) {
        if (!inRange(off, 2)) return -1;
        byte[] b = new byte[2];
        if (readAt(off, b, 2) != 2) return -1;
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    public int u16be(long off) {
        if (!inRange(off, 2)) return -1;
        byte[] b = new byte[2];
        if (readAt(off, b, 2) != 2) return -1;
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /** 返回 0..0xFFFFFFFF，越界返回 -1。 */
    public long u32le(long off) {
        if (!inRange(off, 4)) return -1;
        byte[] b = new byte[4];
        if (readAt(off, b, 4) != 4) return -1;
        return ((long) (b[0] & 0xFF))
                | ((long) (b[1] & 0xFF) << 8)
                | ((long) (b[2] & 0xFF) << 16)
                | ((long) (b[3] & 0xFF) << 24);
    }

    public String ascii(long off, int len) {
        byte[] b = bytes(off, len);
        if (b == null) return null;
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    public byte[] bytes(long off, int len) {
        if (len < 0 || !inRange(off, len)) return null;
        byte[] b = new byte[len];
        if (len == 0) return b;
        return readAt(off, b, len) == len ? b : null;
    }

    /** 整读（仅 .rkw 允许：它需要全盘扫描文件表）。 */
    public byte[] readAll(long maxBytes) {
        if (size > maxBytes) return null;
        byte[] b = new byte[(int) size];
        return readAt(0, b, (int) size) == (int) size ? b : null;
    }

    /** 在 [from,to) 内字节级搜索，返回首个匹配绝对偏移，找不到 -1。 */
    public long find(byte[] magic, long from, long to) {
        if (magic == null || magic.length == 0) return -1;
        long start = Math.max(0, from);
        long end = Math.min(to, size);
        long pos = start;
        byte[] buf = new byte[CHUNK + magic.length];
        int keep = 0;
        while (pos < end) {
            int want = (int) Math.min(CHUNK, end - pos);
            if (want <= 0) break;
            int got = readAt(pos, buf, want);
            if (got <= 0) break;
            for (int i = 0; i + magic.length <= got; i++) {
                boolean ok = true;
                for (int j = 0; j < magic.length; j++) {
                    if (buf[i + j] != magic[j]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) return pos + i;
            }
            long advance = Math.max(1, got - magic.length + 1);
            pos += advance;
            keep = got;
        }
        return -1;
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (IOException ignored) {
        }
    }

    /** 小端 u32 写入数组，供扫描与自检使用。 */
    public static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF))
                | ((long) (b[off + 1] & 0xFF) << 8)
                | ((long) (b[off + 2] & 0xFF) << 16)
                | ((long) (b[off + 3] & 0xFF) << 24);
    }
}
