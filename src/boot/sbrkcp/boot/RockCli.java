package sbrkcp.boot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sbrkcp.core.rock.RockAnalyzer;
import sbrkcp.core.rock.RockReport;
import sbrkcp.core.rock.RockTable;

/** `rock` 子命令：ROCK 头 + 目录表容器探测。 */
public final class RockCli {

    public static final long MAX_BYTES = 512L * 1024L * 1024L;

    public static final class Options {
        public File input;
        public File out;
        public boolean quiet;
    }

    private RockCli() {
    }

    public static int run(Options o) {
        List<File> files = new ArrayList<File>();
        if (o.input == null || !o.input.exists()) {
            System.out.println("[rock] 输入不存在：" + (o.input == null ? "(空)" : o.input.getPath()));
            return 2;
        }
        if (o.input.isDirectory()) {
            File[] all = o.input.listFiles();
            if (all != null) {
                Arrays.sort(all);
                for (File f : all) {
                    if (f.isFile()) {
                        files.add(f);
                    }
                }
            }
        } else {
            files.add(o.input);
        }
        if (files.isEmpty()) {
            System.out.println("[rock] 目录内无文件");
            return 2;
        }
        File base = o.out;
        if (base == null) {
            base = new File(files.get(0).getParentFile(), "rock_out");
        }
        if (!base.exists() && !base.mkdirs()) {
            System.out.println("[rock] 无法创建输出目录：" + base.getPath());
            return 2;
        }
        int ok = 0;
        for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            File dir = files.size() == 1 ? base : new File(base, stem);
            if (!dir.exists() && !dir.mkdirs()) {
                System.out.println("[rock] " + name + " 输出目录创建失败，跳过");
                continue;
            }
            long t0 = System.currentTimeMillis();
            byte[] d;
            try {
                d = RockTable.readAll(f, MAX_BYTES);
            } catch (IOException e) {
                System.out.println("[rock] " + name + " 读取失败：" + e.getMessage());
                continue;
            }
            RockTable t = RockTable.probe(d);
            if (!t.valid()) {
                System.out.println("[rock] " + name + " 未命中 ROCK 目录表：" + t.notes);
                continue;
            }
            RockAnalyzer.Ctx c = RockAnalyzer.analyze(d, t);
            List<String> warn = t.validate();
            long ms = System.currentTimeMillis() - t0;
            try {
                RockReport.writeTable(dir, t, c);
                RockReport.writeMap(dir, f, t, c);
                RockReport.writeBlocks(dir, c);
                RockReport.writeTexts(dir, c);
                RockReport.writeFontPng(dir, d, c);
                RockReport.writePixelPng(dir, d, c);
                RockReport.writeReport(dir, f, t, c, warn, ms);
            } catch (IOException e) {
                System.out.println("[rock] " + name + " 落盘失败：" + e.getMessage());
                continue;
            }
            ok++;
            if (!o.quiet) {
                System.out.println("[rock] " + name + " 表=" + RockTable.hex(t.tableOffset)
                        + " 条目=" + t.entryCount + " 字库=" + c.fonts.size()
                        + " 像素=" + c.pixels.size() + " 索引=" + c.indexes.size()
                        + " 明文=" + c.texts.size() + " 用时=" + ms + "ms → " + dir.getPath());
            }
        }
        System.out.println("[rock] 完成 " + ok + "/" + files.size());
        return ok > 0 ? 0 : 1;
    }
}
