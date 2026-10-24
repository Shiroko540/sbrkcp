package sbrkcp.core.text;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * 裸字符串扫描（S4–S7）。完全不依赖容器结构，任何字节流都能跑。
 * 结果按高/低置信分组：低置信（路径、寄存器名、hex、重复填充、哈希/Base64 样式）单独标记。
 */
public final class BareScanner {

    public static final class Options {
        public int minCnLen = 2;
        public int minEnLen = 4;
        public boolean utf16 = true;
        public boolean gbk = true;
        public boolean utf8 = true;
        public boolean ascii = true;
        /** 每个策略最多收集条数，防止大固件扫爆内存。 */
        public int maxPerStrategy = 200000;
        /** 单条候选文本长度上限（字符）；超长多为二进制误判，不是界面文案。 */
        public int maxEntryLen = 512;
        public String strategies = ""; // 空=全跑；否则形如 "S4,S7"
    }

    public static final class Result {
        public final List<StringsEntry> entries = new ArrayList<StringsEntry>();
        public int filteredAscii;
        public int overlong;
        public final List<String> notes = new ArrayList<String>();
    }

    private BareScanner() {
    }

    private static boolean accept(StringsEntry e, Options o, Result r) {
        if (o.maxEntryLen <= 0) return true;
        int len = Math.max(e.cn == null ? 0 : e.cn.length(), e.en == null ? 0 : e.en.length());
        if (len > o.maxEntryLen) {
            r.overlong++;
            return false;
        }
        return true;
    }

    public static boolean enabled(String strategies, String id) {
        if (strategies == null || strategies.trim().isEmpty()) return true;
        String s = "," + strategies.replace(" ", "").toUpperCase() + ",";
        return s.contains("," + id + ",") || s.contains("," + id + ".");
    }

    public static Result scan(byte[] d, Options o) {
        Result r = new Result();
        if (o.maxPerStrategy <= 0) {
            r.notes.add("裸扫未启用（单策略预算为 0）");
            return r;
        }
        if (o.utf16 && enabled(o.strategies, "S4")) scanUtf16(d, o, r);
        if (o.gbk && enabled(o.strategies, "S5")) scanGbk(d, o, r);
        if (o.utf8 && enabled(o.strategies, "S6")) scanUtf8(d, o, r);
        if (o.ascii && enabled(o.strategies, "S7")) scanAscii(d, o, r);
        return r;
    }

    // ---------- S4 UTF-16LE ----------

    private static void scanUtf16(byte[] d, Options o, Result r) {
        int n = 0;
        for (int phase = 0; phase < 2; phase++) {
            int i = phase;
            while (i + 1 < d.length) {
                int cp = (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
                if (!isCjk(cp)) {
                    i += 2;
                    continue;
                }
                int start = i;
                int chars = 0;
                while (i + 1 < d.length) {
                    int c = (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
                    if (!isCjk(c) && !isCjkPunct(c)) break;
                    chars++;
                    i += 2;
                }
                if (chars >= o.minCnLen) {
                    String s = new String(d, start, (i - start) & ~1, charset("UTF-16LE"));
                    StringsEntry e = new StringsEntry(-1, start, s, "", "S4", "UTF-16LE");
                    e.byteLength = (i - start) & ~1;
                    classify(e);
                    if (!accept(e, o, r)) continue;
                    r.entries.add(e);
                    if (++n >= o.maxPerStrategy) {
                        r.notes.add("S4 达到单策略上限 " + o.maxPerStrategy + " 条，后续截断");
                        return;
                    }
                }
            }
        }
    }

    // ---------- S5 GBK ----------

    private static void scanGbk(byte[] d, Options o, Result r) {
        int n = 0;
        int i = 0;
        while (i + 1 < d.length) {
            if (!isGbkLead(d[i] & 0xFF) || !isGbkTrail(d[i + 1] & 0xFF)) {
                i++;
                continue;
            }
            int start = i;
            int pairs = 0;
            while (i + 1 < d.length && isGbkLead(d[i] & 0xFF) && isGbkTrail(d[i + 1] & 0xFF)) {
                pairs++;
                i += 2;
            }
            if (pairs >= o.minCnLen) {
                String s;
                try {
                    s = new String(d, start, i - start, "GBK");
                } catch (UnsupportedEncodingException ex) {
                    s = "";
                }
                if (s.length() >= o.minCnLen && s.indexOf('\uFFFD') < 0 && countCjk(s) >= o.minCnLen) {
                    StringsEntry e = new StringsEntry(-1, start, s, "", "S5", "GBK");
                    e.byteLength = i - start;
                    classify(e);
                    if (!accept(e, o, r)) continue;
                    r.entries.add(e);
                    if (++n >= o.maxPerStrategy) {
                        r.notes.add("S5 达到单策略上限 " + o.maxPerStrategy + " 条，后续截断");
                        return;
                    }
                }
            }
        }
    }

    // ---------- S6 UTF-8 ----------

    private static void scanUtf8(byte[] d, Options o, Result r) {
        int n = 0;
        int i = 0;
        while (i < d.length) {
            int start = i;
            StringBuilder sb = new StringBuilder();
            int cjk = 0;
            while (i < d.length) {
                int b = d[i] & 0xFF;
                int need;
                int cp;
                if (b < 0x80) {
                    break;
                } else if ((b & 0xE0) == 0xC0) {
                    need = 1;
                    cp = b & 0x1F;
                } else if ((b & 0xF0) == 0xE0) {
                    need = 2;
                    cp = b & 0x0F;
                } else if ((b & 0xF8) == 0xF0) {
                    need = 3;
                    cp = b & 0x07;
                } else {
                    break;
                }
                if (i + need >= d.length) break;
                boolean good = true;
                for (int k = 1; k <= need; k++) {
                    int t = d[i + k] & 0xFF;
                    if ((t & 0xC0) != 0x80) {
                        good = false;
                        break;
                    }
                    cp = (cp << 6) | (t & 0x3F);
                }
                if (!good || !isCjk(cp) && !isCjkPunct(cp)) break;
                if (isCjk(cp)) cjk++;
                sb.appendCodePoint(cp);
                i += need + 1;
            }
            if (cjk >= o.minCnLen && sb.length() > 0) {
                StringsEntry e = new StringsEntry(-1, start, sb.toString(), "", "S6", "UTF-8");
                e.byteLength = i - start;
                classify(e);
                if (!accept(e, o, r)) continue;
                r.entries.add(e);
                if (++n >= o.maxPerStrategy) {
                    r.notes.add("S6 达到单策略上限 " + o.maxPerStrategy + " 条，后续截断");
                    return;
                }
            } else {
                i = Math.max(i, start + 1);
            }
        }
    }

    // ---------- S7 ASCII ----------

    private static void scanAscii(byte[] d, Options o, Result r) {
        int n = 0;
        int i = 0;
        while (i < d.length) {
            if (!isPrintable(d[i] & 0xFF)) {
                i++;
                continue;
            }
            int start = i;
            while (i < d.length && isPrintable(d[i] & 0xFF)) i++;
            int len = i - start;
            if (len >= o.minEnLen) {
                String s = new String(d, start, len, charset("US-ASCII"));
                if (looksLikeWord(s)) {
                    StringsEntry e = new StringsEntry(-1, start, "", s, "S7", "ASCII");
                    e.byteLength = len;
                    classify(e);
                    if (e.lowConfidence) r.filteredAscii++;
                    if (!accept(e, o, r)) continue;
                    r.entries.add(e);
                    if (++n >= o.maxPerStrategy) {
                        r.notes.add("S7 达到单策略上限 " + o.maxPerStrategy + " 条，后续截断");
                        return;
                    }
                } else {
                    r.filteredAscii++;
                }
            }
        }
    }

    /** 保留含空格或首字母大写的词组，滤掉纯符号/纯数字噪声。 */
    private static boolean looksLikeWord(String s) {
        boolean hasSpace = false;
        boolean hasUpper = false;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') hasSpace = true;
            if (c >= 'A' && c <= 'Z') hasUpper = true;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) letters++;
        }
        if (letters < 3) return false;
        return hasSpace || hasUpper;
    }

    // ---------- 噪声判定 ----------

    static void classify(StringsEntry e) {
        String s = e.source.equals("S7") ? e.displayEn() : e.displayCn();
        if (s.isEmpty()) {
            e.lowConfidence = true;
            e.noiseReason = "空串";
            return;
        }
        if (isRepeatedFill(s)) {
            e.lowConfidence = true;
            e.noiseReason = "重复填充";
            return;
        }
        if (isPathLike(s)) {
            e.lowConfidence = true;
            e.noiseReason = "路径片段";
            return;
        }
        if (isHexLike(s)) {
            e.lowConfidence = true;
            e.noiseReason = "十六进制串";
            return;
        }
        if (isRegisterLike(s)) {
            e.lowConfidence = true;
            e.noiseReason = "寄存器/宏名";
            return;
        }
        if (isHashLike(s)) {
            e.lowConfidence = true;
            e.noiseReason = "哈希/Base64 样式";
            return;
        }
    }

    private static boolean isRepeatedFill(String s) {
        if (s.length() < 8) return false;
        char c = s.charAt(0);
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != c) return false;
        }
        return true;
    }

    private static boolean isPathLike(String s) {
        if (s.indexOf('\\') >= 0 || s.indexOf('/') >= 0) return true;
        return s.matches("^[A-Za-z]:.*");
    }

    private static boolean isHexLike(String s) {
        String t = s.replace(" ", "");
        if (t.length() < 6) return false;
        return t.matches("^(0x)?[0-9A-Fa-f]+$") && s.matches(".*[0-9].*");
    }

    private static boolean isRegisterLike(String s) {
        if (!s.matches("^[A-Z][A-Z0-9_]{3,}$")) return false;
        boolean under = s.indexOf('_') >= 0;
        boolean digit = s.matches(".*[0-9].*");
        return under || digit;
    }

    private static boolean isHashLike(String s) {
        String t = s.trim();
        if (t.length() < 16 || t.indexOf(' ') >= 0) return false;
        if (!t.matches("^[A-Za-z0-9+/=_-]+$")) return false;
        boolean upper = t.matches(".*[A-Z].*");
        boolean lower = t.matches(".*[a-z].*");
        boolean digit = t.matches(".*[0-9].*");
        return upper && lower && digit;
    }

    // ---------- 字符判定 ----------

    public static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF);
    }

    public static boolean isCjkPunct(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    private static boolean isGbkLead(int b) {
        return b >= 0x81 && b <= 0xFE;
    }

    private static boolean isGbkTrail(int b) {
        return (b >= 0x40 && b <= 0xFE) && b != 0x7F;
    }

    private static boolean isPrintable(int b) {
        return b >= 0x20 && b <= 0x7E;
    }

    private static int countCjk(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isCjk(s.charAt(i))) n++;
        }
        return n;
    }

    private static java.nio.charset.Charset charset(String name) {
        try {
            return java.nio.charset.Charset.forName(name);
        } catch (Exception e) {
            return java.nio.charset.Charset.forName("UTF-8");
        }
    }
}
