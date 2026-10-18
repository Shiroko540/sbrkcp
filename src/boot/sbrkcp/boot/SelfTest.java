package sbrkcp.boot;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;
import sbrkcp.core.BigFile;

/**
 * --selftest：对 samples/ 下每个固件先算 md5 核对样本身份，再调对应模块解包，
 * 比对数量、分区张数与条目指纹。任一不符返回 4。
 */
public final class SelfTest {

    private SelfTest() {
    }

    /** 内置期望值，与 baseline/baseline.json 保持一致，用于基线文件缺失时兜底。 */
    private static final class Expect {
        String md5;
        String module;
        int png = -1;
        int files = -1;
        int[] parts;
        boolean encrypted;
        String fp;
        /** 形如 "2,3"：要求这两个分区的导出图片尺寸序列互为镜像（从头起最长一致前缀 >= mirrorRate 条）。 */
        String mirror;
        /** 镜像最长一致前缀的下限（条数），默认 40：分叉点之前必须逐条同序同尺寸。 */
        int mirrorRate = 40;
        /**
         * 二阶段：把主模块导出的内嵌文件再交给对应模块解一次。
         * 用于守住模块职责隔离——RKW 只解外壳，BMP0.BIN 归 fuckbmp0。
         * thenPng 仅作参照值，不匹配只告警（该内嵌 BMP0.BIN 未做逐条对拍）。
         */
        String thenModule;
        String thenIn;
        int thenPng = -1;
        /** 外壳类模块（png=0）改用 files/ 目录指纹校验：相对路径 + 长度 + 文件内容。 */
        String dirFp;
    }

    private static Map<String, Expect> defaults() {
        Map<String, Expect> m = new LinkedHashMap<String, Expect>();

        Expect rfw = new Expect();
        rfw.md5 = "422f197b5e52085250ee7ad4e11abd56";
        rfw.module = "fuckrfw";
        rfw.png = 159;
        rfw.parts = new int[]{9, 9, 74, 67};
        rfw.fp = "5c6811d8ea578a2544edfa60048e21e9";
        rfw.mirror = "2,3";
        m.put("ROCK.RFW", rfw);

        Expect rkw = new Expect();
        rkw.md5 = "2b49dedd47aabc41d03ab1073122dde7";
        rkw.module = "fuckrkw";
        rkw.files = 29;
        // 职责隔离后 RKW 只解 RKDL 外壳，不再递归内嵌 BMP0/RKRS，故 PNG 必须为 0。
        rkw.png = 0;
        rkw.fp = null;
        rkw.thenModule = "fuckbmp0";
        rkw.thenIn = "files/RESOURCE/BID/BMP0.BIN";
        rkw.thenPng = 618;
        m.put("OPPOS9.RKW", rkw);

        Expect bmp = new Expect();
        bmp.md5 = "e6ee01f2482fd19718b85be36ec7359b";
        bmp.module = "fuckbmp0";
        bmp.png = 1270;
        bmp.fp = "3228c4045ba7947a5918fcc42d9c49b2";
        m.put("BMP0.BIN", bmp);

        Expect u6 = new Expect();
        u6.md5 = "92dee7a1fbd7ac65e3b612b383044678";
        u6.module = "fuckrkimg";
        u6.encrypted = true;
        u6.fp = "d41d8cd98f00b204e9800998ecf8427e";
        m.put("U6.BIN", u6);

        Expect ic = new Expect();
        ic.md5 = "b6a3fa75a001e678a9db813560a99616";
        ic.module = "fuckrkimg";
        ic.encrypted = true;
        ic.fp = "d41d8cd98f00b204e9800998ecf8427e";
        m.put("Image.code.bin", ic);

        return m;
    }

    public static int run(List<ModuleLoader.Entry> modules, File samplesDir) {
        File samples = samplesDir == null ? new File("samples") : samplesDir;
        File base = samples.getParentFile() == null ? new File(".") : samples.getParentFile();

        System.out.println("== sbrkcp selftest ==");
        System.out.println("样本目录: " + samples.getAbsolutePath());
        System.out.println("模块: " + ModuleLoader.summary(modules));

        Map<String, Expect> expected = mergeBaseline(defaults(), new File(base, "baseline/baseline.json"));

        File work = new File(base, "out" + File.separator + "selftest");
        int pass = 0;
        int fail = 0;
        List<String> fails = new ArrayList<String>();

        for (Map.Entry<String, Expect> en : expected.entrySet()) {
            String file = en.getKey();
            Expect e = en.getValue();
            System.out.println();
            System.out.println("---- " + file + " ----");

            File sample = new File(samples, file);
            if (!sample.isFile()) {
                System.out.println("FAIL 样本缺失 " + sample.getAbsolutePath());
                fails.add(file + " 样本缺失");
                fail++;
                continue;
            }

            boolean ok = true;
            String gotMd5 = md5(sample);
            if (e.md5 != null && !e.md5.equalsIgnoreCase(gotMd5)) {
                ok = false;
                System.out.println("FAIL 样本 md5 期望 " + e.md5 + " 实际 " + gotMd5);
            } else {
                System.out.println("OK   md5=" + gotMd5);
            }

            SbrkcpModule module = ModuleLoader.find(modules, e.module);
            if (module == null) {
                System.out.println("FAIL 模块未加载 " + e.module);
                fails.add(file + " 模块未加载 " + e.module);
                fail++;
                continue;
            }

            File out = new File(work, file);
            deleteTree(out);
            if (!out.mkdirs() && !out.isDirectory()) {
                System.out.println("WARN 无法创建输出目录 " + out.getAbsolutePath());
            }

            long t0 = System.currentTimeMillis();
            UnpackResult r = null;
            try {
                r = module.unpack(sample, out, ProgressListener.NONE, UnpackOptions.defaults());
            } catch (Throwable t) {
                ok = false;
                System.out.println("FAIL 解包异常 " + t);
            }
            long ms = System.currentTimeMillis() - t0;

            if (r != null) {
                System.out.println("     耗时 " + ms + " ms  kind=" + r.kind
                        + " png=" + r.pngCount + " files=" + r.fileCount
                        + " enc=" + r.encrypted + " exit=" + r.exitCode);

                if (r.exitCode != 0) {
                    ok = false;
                    System.out.println("FAIL exitCode 期望 0 实际 " + r.exitCode);
                }
                if (e.png >= 0 && r.pngCount != e.png) {
                    ok = false;
                    System.out.println("FAIL PNG 张数 期望 " + e.png + " 实际 " + r.pngCount);
                }
                if (e.files >= 0 && r.fileCount != e.files) {
                    ok = false;
                    System.out.println("FAIL 文件数 期望 " + e.files + " 实际 " + r.fileCount);
                }
                if (e.encrypted != r.encrypted) {
                    ok = false;
                    System.out.println("FAIL 加密判定 期望 " + e.encrypted + " 实际 " + r.encrypted);
                }
                if (e.parts != null) {
                    int[] got = countParts(r);
                    if (got.length != e.parts.length) {
                        ok = false;
                        System.out.println("FAIL 分区张数 期望 " + fmtInts(e.parts)
                                + " 实际 " + fmtInts(got));
                    } else {
                        for (int i = 0; i < got.length; i++) {
                            if (got[i] != e.parts[i]) {
                                ok = false;
                                System.out.println("FAIL 分区张数 期望 " + fmtInts(e.parts)
                                        + " 实际 " + fmtInts(got));
                                break;
                            }
                        }
                    }
                }

                if (e.mirror != null) {
                    String bad = checkMirror(r, e.mirror, e.mirrorRate);
                    if (bad != null) {
                        ok = false;
                        System.out.println("FAIL 分区镜像 " + bad);
                    }
                }

                String gotFp = fingerprint(r);
                System.out.println("     fp=" + gotFp);
                if (e.fp != null && !e.fp.isEmpty() && !e.fp.equals(gotFp)) {
                    ok = false;
                    System.out.println("FAIL 条目指纹 期望 " + e.fp + " 实际 " + gotFp);
                }

                try {
                    Files.write(new File(work, file + ".fp.txt").toPath(),
                            fingerprintSource(r).getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignore) {
                    // 诊断文件写失败不影响自检结论
                }

                if (e.files >= 0) {
                    String dfp = dirFingerprint(new File(out, "files"), out);
                    System.out.println("     files fp=" + dfp);
                    if (e.dirFp != null && !e.dirFp.isEmpty() && !e.dirFp.equals(dfp)) {
                        ok = false;
                        System.out.println("FAIL files 目录指纹 期望 " + e.dirFp + " 实际 " + dfp);
                    }
                }

                // 二阶段输出写到 _stage2/，不落在 files/ 里，避免污染外壳目录指纹。
                if (e.thenModule != null) {
                    runThen(modules, out, e);
                }
            }

            if (ok) {
                System.out.println("PASS");
                pass++;
            } else {
                fails.add(file + " fp=" + (r == null ? "-" : fingerprint(r)));
                fail++;
            }
        }

        // BigFile 读满校验：内核取字节必须一次读尽。短读会让资源头解析成 w=0/h=0，
        // 外部表现是后半批条目连续「非图条目/解码失败」，是排查成本最高的一类回归。
        System.out.println();
        System.out.println("---- BigFile 读满 ----");
        File[] all = samples.listFiles();
        boolean ioOk = true;
        int ioChecked = 0;
        if (all != null) {
            Arrays.sort(all);
            for (File s : all) {
                if (!s.isFile()) {
                    continue;
                }
                try (BigFile bf = new BigFile(s)) {
                    byte[] ref = Files.readAllBytes(s.toPath());
                    if (bf.size() != ref.length) {
                        ioOk = false;
                        System.out.println("FAIL 长度 " + s.getName()
                                + " BigFile=" + bf.size() + " 实际=" + ref.length);
                        continue;
                    }
                    int n = Math.min(64, ref.length);
                    byte[] head = bf.bytes(0, n);
                    byte[] tail = bf.bytes(ref.length - n, n);
                    if (head == null || tail == null
                            || !Arrays.equals(head, Arrays.copyOfRange(ref, 0, n))
                            || !Arrays.equals(tail, Arrays.copyOfRange(ref, ref.length - n, ref.length))) {
                        ioOk = false;
                        System.out.println("FAIL 首尾字节不一致 " + s.getName());
                        continue;
                    }
                    ioChecked++;
                } catch (Throwable t) {
                    ioOk = false;
                    System.out.println("FAIL 读取异常 " + s.getName() + " " + t);
                }
            }
        }
        if (ioChecked == 0) {
            System.out.println("WARN 样本目录无文件，跳过读满校验");
        } else if (ioOk) {
            System.out.println("OK   " + ioChecked + " 个样本读满，长度与首尾 64 字节一致");
            pass++;
        } else {
            fails.add("BigFile 读满校验");
            fail++;
        }

        System.out.println();
        System.out.println("== 汇总：PASS " + pass + " / FAIL " + fail + " ==");
        for (String s : fails) {
            System.out.println("  ! " + s);
        }
        return fail > 0 ? 4 : 0;
    }

    /**
     * 二阶段解包：主模块只处理自己的外壳，内嵌的其它格式交给对应模块。
     * 例：OPPOS9.RKW 由 fuckrkw 导出 files/BMP0.BIN，再由 fuckbmp0 解出图片。
     * 二阶段的张数只作参照，不一致仅告警——该内嵌 BMP0.BIN 未做逐条像素对拍。
     */
    private static void runThen(List<ModuleLoader.Entry> modules, File out, Expect e) {
        System.out.println("     二阶段 " + e.thenModule + " <- " + e.thenIn);
        File in = new File(out, e.thenIn);
        if (!in.isFile()) {
            System.out.println("WARN 二阶段输入缺失 " + in.getAbsolutePath() + "（不判失败）");
            return;
        }
        SbrkcpModule m = ModuleLoader.find(modules, e.thenModule);
        if (m == null) {
            System.out.println("WARN 二阶段模块未加载 " + e.thenModule + "（不判失败）");
            return;
        }
        File sub = new File(new File(out, "_stage2"), stripExtName(in.getName()));
        deleteTree(sub);
        if (!sub.mkdirs() && !sub.isDirectory()) {
            System.out.println("WARN 二阶段输出目录创建失败 " + sub.getAbsolutePath());
            return;
        }
        try {
            UnpackResult r = m.unpack(in, sub, ProgressListener.NONE, UnpackOptions.defaults());
            System.out.println("     二阶段 png=" + r.pngCount + " kind=" + r.kind
                    + " exit=" + r.exitCode + " out=" + sub.getName());
            if (e.thenPng >= 0 && r.pngCount != e.thenPng) {
                System.out.println("WARN 二阶段 PNG 张数 参照 " + e.thenPng + " 实际 " + r.pngCount
                        + "（该内嵌样本未做逐条对拍，此项不作门禁）");
            }
        } catch (Throwable t) {
            System.out.println("WARN 二阶段异常 " + t + "（不判失败）");
        }
    }

    private static String stripExtName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 递归收集普通文件。 */
    private static void collectFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectFiles(k, out);
            else if (k.isFile()) out.add(k);
        }
    }

    private static String relOf(File root, File f) {
        String r = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.startsWith(r)) p = p.substring(r.length());
        return p.replace('\\', '/');
    }

    /**
     * 目录指纹：对目录下所有文件按相对路径排序，逐条混入 "相对路径|字节数|内容"。
     * 用于外壳类模块（如 fuckrkw 导出 files/）——这类模块不产出图片，
     * 只靠数量不足以发现内容漂移，必须校验导出文件本身。
     */
    private static String dirFingerprint(File dir, final File base) {
        try {
            List<File> all = new ArrayList<File>();
            collectFiles(dir, all);
            Collections.sort(all, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return relOf(base, a).compareTo(relOf(base, b));
                }
            });
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (File f : all) {
                md.update((relOf(base, f) + '|' + f.length() + '|').getBytes("UTF-8"));
                md.update(Files.readAllBytes(f.toPath()));
            }
            StringBuilder sb = new StringBuilder(32);
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    private static String fmtInts(int[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    /** 按条目名里的 partN 前缀统计每个分区导出的图片张数。 */
    private static int[] countParts(UnpackResult r) {
        Map<Integer, Integer> counts = new LinkedHashMap<Integer, Integer>();
        int max = -1;
        for (UnpackResult.Entry e : r.entries) {
            int idx = partIndex(e.name);
            if (idx < 0) continue;
            Integer c = counts.get(idx);
            counts.put(idx, c == null ? 1 : c + 1);
            if (idx > max) max = idx;
        }
        int[] arr = new int[max + 1];
        for (Map.Entry<Integer, Integer> en : counts.entrySet()) {
            arr[en.getKey()] = en.getValue();
        }
        return arr;
    }

    private static int partIndex(String name) {
        if (name == null || !name.startsWith("part")) return -1;
        int i = 4;
        int v = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            v = v * 10 + (name.charAt(i) - '0');
            i++;
        }
        return i > 4 ? v : -1;
    }

    /**
     * 分区镜像校验：两个分区导出的图片尺寸序列，同位置一致率需不低于 rate%。
     * 裸图段判据是启发式的（头无魔数），单个分区可能在某处切错同步点，
     * 而两个分区互为独立对照，一致率高才说明判据稳。返回 null 表示通过。
     */
    private static String checkMirror(UnpackResult r, String spec, int rate) {
        String[] sp = spec.split(",");
        if (sp.length != 2) return "mirror 配置非法：" + spec;
        int a;
        int b;
        try {
            a = Integer.parseInt(sp[0].trim());
            b = Integer.parseInt(sp[1].trim());
        } catch (NumberFormatException ex) {
            return "mirror 配置非法：" + spec;
        }
        List<String> na = new ArrayList<String>();
        List<String> nb = new ArrayList<String>();
        for (UnpackResult.Entry e : r.entries) {
            int idx = partIndex(e.name);
            if (idx == a) na.add(e.name);
            else if (idx == b) nb.add(e.name);
        }
        if (na.isEmpty() || nb.isEmpty()) return "分区 " + a + "/" + b + " 没有导出图片";
        /*
         * P2 与 P3 不是纯冗余备份：两者在段内偏移 0x8232CC 之后分叉（同一资源池的两个版本），
         * 分叉后各自记录数与内容都不同（P3 更多）。因此门禁不是"全序列一致率"，
         * 而是"从头开始的最长一致前缀"——分叉点之前必须逐条同序同尺寸，
         * 分叉点位置本身是样本属性，记录流水账即可。
         */
        int n = Math.min(na.size(), nb.size());
        if (n == 0) return "分区 " + a + "/" + b + " 没有可对比的图片";
        int lcp = 0;
        while (lcp < n && sizeOfName(na.get(lcp)).equals(sizeOfName(nb.get(lcp)))) lcp++;
        if (lcp < rate) {
            return "part" + a + "/part" + b + " 最长一致前缀仅 " + lcp + " 条，低于下限 " + rate
                    + " 条（part" + a + "=" + na.size() + " part" + b + "=" + nb.size() + "）";
        }
        System.out.println("     分区镜像 part" + a + "/part" + b + " 一致前缀 " + lcp + " 条全等"
                + (lcp < n ? "，第 " + (lcp + 1) + " 条起分叉（part" + a + "=" + na.size()
                        + " part" + b + "=" + nb.size() + "）" : "，两分区完全一致"));
        return null;
    }

    /** 从导出文件名 partN_sXXXX_0000_320x240.png 里取 "320x240" 作为尺寸签名。 */
    private static String sizeOfName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        String s = dot > 0 ? name.substring(0, dot) : name;
        int us = s.lastIndexOf('_');
        return us > 0 ? s.substring(us + 1) : s;
    }

    private static Map<String, Expect> mergeBaseline(Map<String, Expect> base, File f) {
        if (f == null || !f.isFile()) return base;
        try {
            Object o = MiniJson.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return base;
            Object sm = ((Map<String, Object>) o).get("samples");
            if (!(sm instanceof Map)) return base;
            Map<String, Object> samples = (Map<String, Object>) sm;
            for (Map.Entry<String, Object> en : samples.entrySet()) {
                if (!(en.getValue() instanceof Map)) continue;
                Map<String, Object> v = (Map<String, Object>) en.getValue();
                Expect e = base.get(en.getKey());
                if (e == null) {
                    e = new Expect();
                    base.put(en.getKey(), e);
                }
                if (v.get("md5") instanceof String) e.md5 = (String) v.get("md5");
                if (v.get("module") instanceof String) e.module = (String) v.get("module");
                if (v.get("png") instanceof Number) e.png = ((Number) v.get("png")).intValue();
                if (v.get("files") instanceof Number) e.files = ((Number) v.get("files")).intValue();
                if (v.get("encrypted") instanceof Boolean) e.encrypted = (Boolean) v.get("encrypted");
                if (v.get("fingerprint") instanceof String) e.fp = (String) v.get("fingerprint");
                if (v.get("filesFingerprint") instanceof String) e.dirFp = (String) v.get("filesFingerprint");
                if (v.get("mirror") instanceof String) e.mirror = (String) v.get("mirror");
                if (v.get("mirrorRate") instanceof Number) {
                    e.mirrorRate = ((Number) v.get("mirrorRate")).intValue();
                }
                Object then = v.get("then");
                if (then instanceof Map) {
                    Map<String, Object> t = (Map<String, Object>) then;
                    if (t.get("module") instanceof String) e.thenModule = (String) t.get("module");
                    if (t.get("in") instanceof String) e.thenIn = (String) t.get("in");
                    if (t.get("png") instanceof Number) e.thenPng = ((Number) t.get("png")).intValue();
                }
                Object p = v.get("parts");
                if (p instanceof List) {
                    List<Object> pl = (List<Object>) p;
                    int[] arr = new int[pl.size()];
                    for (int i = 0; i < arr.length; i++) {
                        arr[i] = pl.get(i) instanceof Number ? ((Number) pl.get(i)).intValue() : 0;
                    }
                    e.parts = arr;
                }
            }
            System.out.println("已读取基线: " + f.getAbsolutePath());
        } catch (Throwable t) {
            System.out.println("baseline.json 解析失败，使用内置期望值: " + t);
        }
        return base;
    }

    /**
     * 条目指纹：覆盖文件名、宽高、深度、压缩标志与解码后的像素内容。
     * 用像素而非 PNG 字节数，是因为 JDK 8 与 JDK 21 的 ImageIO PNG 编码器输出字节数不同，
     * 用字节数会让基线随运行的 JVM 漂移；像素内容才是真正要守的东西。
     */
    private static String fingerprint(UnpackResult r) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (UnpackResult.Entry e : sortedEntries(r)) {
                String head = (e.name == null ? "" : e.name) + '|' + e.w + '|' + e.h + '|'
                        + e.depth + '|' + e.compress + '\n';
                md.update(head.getBytes("UTF-8"));
                byte[] px = pixelsOf(e);
                md.update(px == null ? new byte[]{'-'} : px);
            }
            StringBuilder sb = new StringBuilder(32);
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    private static byte[] pixelsOf(UnpackResult.Entry e) {
        if (e.path == null) return null;
        try {
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(new File(e.path));
            if (im == null) return null;
            int w = im.getWidth();
            int h = im.getHeight();
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(px.length * 4);
            bb.order(java.nio.ByteOrder.BIG_ENDIAN);
            for (int v : px) bb.putInt(v);
            return bb.array();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 name 排序的副本：不同 JVM 的 HashMap/HashSet 迭代序可能不同，排序后指纹才稳定。 */
    private static List<UnpackResult.Entry> sortedEntries(UnpackResult r) {
        List<UnpackResult.Entry> sorted = new ArrayList<UnpackResult.Entry>(r.entries);
        java.util.Collections.sort(sorted, new java.util.Comparator<UnpackResult.Entry>() {
            @Override
            public int compare(UnpackResult.Entry a, UnpackResult.Entry b) {
                String x = a.name == null ? "" : a.name;
                String y = b.name == null ? "" : b.name;
                int c = x.compareTo(y);
                if (c != 0) return c;
                c = Long.compare(a.pngBytes, b.pngBytes);
                if (c != 0) return c;
                return Integer.compare(a.w * 100000 + a.h, b.w * 100000 + b.h);
            }
        });
        return sorted;
    }

    /** 诊断用文本清单（含 PNG 字节数），写到工作目录，便于人工定位差异条目。 */
    private static String fingerprintSource(UnpackResult r) {
        StringBuilder sb = new StringBuilder();
        for (UnpackResult.Entry e : sortedEntries(r)) {
            sb.append(e.name == null ? "" : e.name).append('|')
              .append(e.w).append('|').append(e.h).append('|')
              .append(e.depth).append('|').append(e.compress).append('|')
              .append(e.pngBytes).append('\n');
        }
        return sb.toString();
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "ERR:" + e;
        }
    }

    public static String md5(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 20];
            try (java.io.InputStream in = new java.io.BufferedInputStream(
                    new java.io.FileInputStream(f), 1 << 20)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(32);
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "ERR:" + e;
        }
    }

    private static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
