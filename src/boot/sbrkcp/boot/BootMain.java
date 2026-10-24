package sbrkcp.boot;

import java.io.File;
import java.util.List;
import javax.swing.SwingUtilities;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;
import sbrkcp.core.text.StringsRunner;

/** 唯一入口。无参 → GUI；带参 → headless CLI。 */
public final class BootMain {

    public static void main(String[] args) {
        File baseDir = baseDir();
        LogBus bus = new LogBus();
        bus.addSink(System.out::println);

        String moduleId = null;
        String input = null;
        String output = null;
        boolean headless = false;
        boolean listOnly = false;
        boolean flip = false;
        boolean listModules = false;
        boolean selftest = false;
        boolean smoke = false;
        boolean showVersion = false;
        boolean stringsMode = false;
        boolean rockMode = false;
        boolean dedup = false;
        boolean strip = false;
        boolean quiet = false;
        int minLen = 0;
        int bareLimit = 0;
        String stringsIn = null;
        String rockIn = null;
        String format = "csv";
        String lang = "both";
        String strategies = "";
        String samples = null;
        String guiFile = null;
        String err = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--headless":
                    headless = true;
                    break;
                case "--list":
                    listOnly = true;
                    break;
                case "--flip":
                    flip = true;
                    break;
                case "--list-modules":
                    listModules = true;
                    break;
                case "--selftest":
                    selftest = true;
                    break;
                case "--smoke":
                    smoke = true;
                    break;
                case "--version":
                    showVersion = true;
                    break;
                case "--module":
                    if (i + 1 < args.length) moduleId = args[++i];
                    else err = "--module 缺少参数";
                    break;
                case "--in":
                    if (i + 1 < args.length) input = args[++i];
                    else err = "--in 缺少参数";
                    break;
                case "--out":
                    if (i + 1 < args.length) output = args[++i];
                    else err = "--out 缺少参数";
                    break;
                case "--samples":
                    if (i + 1 < args.length) samples = args[++i];
                    else err = "--samples 缺少参数";
                    break;
                case "--format":
                    if (i + 1 < args.length) format = args[++i];
                    else err = "--format 缺少参数";
                    break;
                case "--lang":
                    if (i + 1 < args.length) lang = args[++i];
                    else err = "--lang 缺少参数";
                    break;
                case "--strategies":
                    if (i + 1 < args.length) strategies = args[++i];
                    else err = "--strategies 缺少参数";
                    break;
                case "--min-len":
                    if (i + 1 < args.length) {
                        try {
                            minLen = Integer.parseInt(args[++i].trim());
                        } catch (NumberFormatException nfe) {
                            err = "--min-len 需要整数";
                        }
                    } else {
                        err = "--min-len 缺少参数";
                    }
                    break;
                case "--bare-limit":
                    if (i + 1 < args.length) {
                        try {
                            bareLimit = Integer.parseInt(args[++i].trim());
                        } catch (NumberFormatException nfe) {
                            err = "--bare-limit 需要整数";
                        }
                    } else {
                        err = "--bare-limit 缺少参数";
                    }
                    break;
                case "--dedup":
                    dedup = true;
                    break;
                case "--no-dedup":
                    dedup = false;
                    break;
                case "--strip":
                    strip = true;
                    break;
                case "--quiet":
                    quiet = true;
                    break;
                default:
                    if (a.startsWith("--")) err = "未知参数：" + a;
                    else if ("strings".equalsIgnoreCase(a)) stringsMode = true;
                else if ("rock".equalsIgnoreCase(a)) rockMode = true;
                else if (rockMode && rockIn == null) rockIn = a;
                    else if (stringsMode) stringsIn = a;
                    else guiFile = a;
            }
            if (err != null) break;
        }

        if (err != null) {
            System.err.println(err);
            usage();
            System.exit(2);
        }
        if (!headless && !listModules && !selftest && input == null && (moduleId != null || output != null)) {
            System.err.println("参数不完整：headless 需要 --headless --module <id> --in <文件> --out <目录>");
            usage();
            System.exit(2);
        }

        if (showVersion) {
            System.out.println(Version.title() + "  channel=" + Version.CHANNEL
                    + "  api=" + ModuleLoader.API_VERSION
                    + "  java=" + System.getProperty("java.version"));
            System.exit(0);
        }

        if (rockMode) {
            RockCli.Options ro = new RockCli.Options();
            ro.input = rockIn == null ? null : new File(rockIn);
            ro.out = output == null ? null : new File(output);
            ro.quiet = quiet;
            int rcode = RockCli.run(ro);
            if (!headless) { }
            System.exit(rcode);
        }
        if (stringsMode) {
            List<ModuleLoader.Entry> all = ModuleLoader.load(baseDir);
            StringsRunner.Options so = new StringsRunner.Options();
            so.input = stringsIn == null ? null : new File(stringsIn);
            so.outDir = output == null ? null : new File(output);
            so.module = moduleId == null ? "" : moduleId;
            for (ModuleLoader.Entry e : all) {
                if (e.module != null && e.id != null && e.id.equalsIgnoreCase(so.module)) {
                    so.moduleImpl = e.module;
                    break;
                }
            }
            if (!so.module.isEmpty() && so.moduleImpl == null) {
                System.err.println("模块不存在或不兼容：" + so.module);
                System.exit(3);
            }
            so.formats = format;
            so.lang = lang;
            so.dedup = dedup;
            so.strip = strip;
            so.strategies = strategies;
            so.minLen = minLen;
            so.bareLimit = bareLimit;
            so.quiet = quiet;
            int code = StringsRunner.run(so, new StringsRunner.Log() {
                @Override
                public void line(String s) {
                    System.out.println(s);
                }
            });
            System.exit(code);
        }

        List<ModuleLoader.Entry> entries = ModuleLoader.load(baseDir);

        if (listModules) {
            System.out.println(Version.title() + "  (api " + ModuleLoader.API_VERSION + ")");
            System.out.println("baseDir = " + baseDir.getAbsolutePath());
            for (ModuleLoader.Entry e : entries) {
                if (e.ok()) {
                    System.out.printf("  OK   %-12s %-10s api=%d  %s%n",
                            e.module.info().id, e.module.info().version,
                            e.module.info().apiVersion, e.jar.getName());
                } else {
                    System.out.printf("  FAIL %-12s %s  (%s)%n", "-", e.jar.getName(), e.error);
                }
            }
            System.out.println(ModuleLoader.summary(entries));
            System.exit(0);
        }

        if (selftest) {
            File dir = samples == null ? new File(baseDir, "samples") : new File(samples);
            int code = SelfTest.run(entries, dir);
            System.exit(code);
        }

        if (headless) {
            System.exit(runHeadless(entries, baseDir, moduleId, input, output, listOnly, flip, bus));
        }

        if (smoke) {
            try {
                final String sample = smokeSample(baseDir, input);
                MainFrame.applyLookAndFeel();
                MainFrame[] ref = new MainFrame[1];
                final long[] vis = new long[1];
                SwingUtilities.invokeAndWait(() -> {
                    long t0 = System.currentTimeMillis();
                    MainFrame f = new MainFrame(entries, baseDir, bus);
                    f.setVisible(true);
                    vis[0] = f.isShowing() ? System.currentTimeMillis() - t0 : -1L;
                    f.smokeAndDispose(20, 900, sample);
                    ref[0] = f;
                });
                long t0 = System.currentTimeMillis();
                while (ref[0] != null && ref[0].isDisplayable()
                        && System.currentTimeMillis() - t0 < 20000) {
                    Thread.sleep(150);
                }
                Thread.sleep(300);
                int live = 0;
                StringBuilder leak = new StringBuilder();
                for (Thread th : Thread.getAllStackTraces().keySet()) {
                    if (th.isAlive() && !th.isDaemon() && th != Thread.currentThread()
                            && !isFrameworkThread(th.getName())) {
                        live++;
                        leak.append(live == 1 ? "" : ",").append(th.getName());
                    }
                }
                System.out.println("GUI 冒烟完成：" + ModuleLoader.summary(entries)
                        + " 窗口可见耗时=" + vis[0] + "ms"
                        + " 样本=" + (sample == null ? "无" : new File(sample).getName())
                        + " 残留非守护线程=" + live + (live == 0 ? "" : " [" + leak + "]"));
                System.exit(live == 0 ? 0 : 4);
            } catch (Throwable t) {
                System.err.println("GUI 冒烟失败：" + t);
                System.exit(4);
            }
        }

        final String pre = guiFile;
        SwingUtilities.invokeLater(() -> {
            MainFrame.applyLookAndFeel();
            MainFrame f = new MainFrame(entries, baseDir, bus);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            if (pre != null) f.openFile(new File(pre));
        });
    }

    private static void usage() {
        System.out.println("用法：");
        System.out.println("  java -jar boot.jar                         图形界面");
        System.out.println("  java -jar boot.jar <固件路径>              图形界面并预选文件");
        System.out.println("  java -jar boot.jar --version              打印版本号");
        System.out.println("  java -jar boot.jar --list-modules          列出模块与加载状态");
        System.out.println("  java -jar boot.jar --headless --module <id> --in <固件> --out <目录> [--list] [--flip]");
        System.out.println("  java -jar boot.jar --selftest [--samples <样本目录>]");
        System.out.println("  java -jar boot.jar --headless strings <文件或目录|固件> --out <目录> [--format csv,txt,json,md]");
        System.out.println("        [--lang cn|en|both] [--dedup|--no-dedup] [--strip] [--strategies S1,S4] [--min-len N] [--bare-limit N] [--quiet]");
        System.out.println("  java -jar boot.jar --headless --module fuckrkw strings <a.rkw> --out <目录>");
        System.out.println("  java -jar boot.jar --headless --module fuckrkimg strings <a.img> --out <目录>");
        System.out.println("  java -jar boot.jar --smoke                 图形界面冒烟（自动关闭）");
        System.out.println("退出码：0 成功 / 2 参数错误 / 3 模块不存在或不兼容 / 4 解包或导出失败 / 5 未提取到文案");
    }

    /** JVM / AWT 自带的非守护线程不算应用泄漏，只盯我们自己起的线程。 */
    private static boolean isFrameworkThread(String name) {
        if (name == null) return true;
        String[] prefixes = {"AWT-", "Java2D", "TimerQueue", "Image Fetcher", "DestroyJavaVM",
            "Reference Handler", "Finalizer", "Signal Dispatcher", "Common-Cleaner",
            "Notification Thread", "AppKit", "process reaper"};
        for (String p : prefixes) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    /** 冒烟用样本：优先 --in，其次 dist 同级的 samples/OPPOS9.RKW。 */
    private static String smokeSample(File baseDir, String input) {
        if (input != null && !input.isEmpty()) return input;
        File root = baseDir == null ? null : baseDir.getParentFile();
        if (root != null) {
            File cand = new File(new File(root, "samples"), "OPPOS9.RKW");
            if (cand.isFile()) return cand.getAbsolutePath();
        }
        return null;
    }

    private static int runHeadless(List<ModuleLoader.Entry> entries, File baseDir, String moduleId,
                                   String input, String output, boolean listOnly, boolean flip,
                                   LogBus bus) {
        if (moduleId == null || input == null || output == null) {
            System.err.println("headless 参数不完整");
            usage();
            return 2;
        }
        SbrkcpModule m = ModuleLoader.find(entries, moduleId);
        if (m == null) {
            System.err.println("模块不存在或不兼容：" + moduleId);
            for (ModuleLoader.Entry e : entries) {
                if (!e.ok()) System.err.println("  " + e.jar.getName() + " -> " + e.error);
            }
            return 3;
        }
        File in = new File(input);
        File out = new File(output);
        if (!in.isFile()) {
            System.err.println("输入文件不存在：" + in);
            return 2;
        }
        out.mkdirs();

        UnpackOptions opt = new UnpackOptions();
        opt.listOnly = listOnly;
        opt.flip = flip;

        ProgressListener p = new ProgressListener() {
            @Override
            public void log(String line) {
                bus.log(line);
            }

            @Override
            public void progress(int percent, String message) {
                if (message != null && percent % 10 == 0) bus.log("[" + percent + "%] " + message);
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        UnpackResult r;
        try {
            r = m.unpack(in, out, p, opt);
        } catch (Throwable t) {
            System.err.println("解包异常：" + t);
            t.printStackTrace();
            return 4;
        }
        if (r == null) {
            System.err.println("模块返回 null");
            return 4;
        }
        if (!listOnly) {
            File rep = new File(out, "unpack_report.txt");
            if (rep.isFile()) bus.log("报告: " + rep.getAbsolutePath());
        }
        bus.log("---- 结果 ----");
        bus.log("模块=" + r.moduleId + " 识别=" + r.kind + " PNG=" + r.pngCount
                + " 文件=" + r.fileCount + " 加密=" + r.encrypted + " 中断=" + r.cancelled);
        for (String n : r.notes) bus.log("  · " + n);
        return r.exitCode;
    }

    /** boot.jar 所在目录；开发期可用 -Dsbrkcp.base 覆盖。 */
    private static File baseDir() {
        String override = System.getProperty("sbrkcp.base");
        if (override != null && !override.isEmpty()) return new File(override);
        try {
            File loc = new File(BootMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File d = loc.isFile() ? loc.getParentFile() : loc;
            File probe = d;
            for (int i = 0; i < 4 && probe != null; i++) {
                if (new File(probe, "main").isDirectory() || new File(probe, "other").isDirectory()) return probe;
                probe = probe.getParentFile();
            }
            return d;
        } catch (Exception e) {
            return new File(".");
        }
    }
}
