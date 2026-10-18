package sbrkcp.boot;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ServiceLoader;
import sbrkcp.api.SbrkcpModule;

/**
 * 模块扫描与加载。每个 jar 一个独立 URLClassLoader，父委托到 boot 的加载器，
 * 这样一个坏 jar 不会拖垮同批次其他模块。
 */
public final class ModuleLoader {

    /** API 主版本：只在接口不兼容变更时 +1。 */
    public static final int API_VERSION = 1;

    public static final class Entry {
        public final File jar;
        public SbrkcpModule module;   // null = 加载失败
        public String id = "-";
        public String name;
        public String error;

        Entry(File jar) {
            this.jar = jar;
            this.name = jar.getName();
        }

        public boolean ok() {
            return module != null;
        }
    }

    private ModuleLoader() {
    }

    public static List<Entry> load(File baseDir) {
        List<Entry> out = new ArrayList<>();
        for (String sub : new String[]{"main", "other"}) {
            File dir = new File(baseDir, sub);
            File[] jars = dir.listFiles(f -> f.isFile() && !f.isHidden()
                    && f.getName().toLowerCase().endsWith(".jar"));
            if (jars == null) continue;
            Arrays.sort(jars);   // 稳定顺序，不依赖文件系统返回序
            for (File jar : jars) out.addAll(loadOne(jar));
        }
        // 重复 id：后加载的标冲突
        Set<String> ids = new HashSet<>();
        for (Entry e : out) {
            if (!e.ok()) continue;
            String id = e.module.info().id;
            if (!ids.add(id)) {
                e.error = "模块 id 冲突，已忽略（" + e.jar.getName() + " 与先前模块同为 " + id + "）";
                e.module = null;
            }
        }
        return out;
    }

    private static List<Entry> loadOne(File jar) {
        Entry e = new Entry(jar);
        try {
            ClassLoader cl = new URLClassLoader(
                    new URL[]{jar.toURI().toURL()},
                    SbrkcpModule.class.getClassLoader());
            List<SbrkcpModule> list = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (SbrkcpModule m : ServiceLoader.load(SbrkcpModule.class, cl)) {
                // 同一 provider 可能被 parent（app classpath）与 URLClassLoader 各发现一次：
                // 打成 EXE 时 jpackage 会把 app 目录下所有 jar 都塞进 app.classpath，若不去重，
                // 每个模块会被重复登记，后半批全部判成「模块 id 冲突」。
                if (seen.add(m.getClass().getName())) list.add(m);
            }
            if (list.isEmpty()) {
                e.error = "未声明 META-INF/services/sbrkcp.api.SbrkcpModule";
                return java.util.Collections.singletonList(e);
            }
            List<Entry> many = new ArrayList<>();
            for (SbrkcpModule m : list) {
                Entry x = new Entry(jar);
                x.module = m;
                x.id = m.info().id;
                x.name = m.info().name;
                if (m.info().apiVersion != API_VERSION) {
                    x.error = "此模块需要新版 boot（模块 apiVersion=" + m.info().apiVersion
                            + "，boot=" + API_VERSION + "）";
                    x.module = null;
                }
                many.add(x);
            }
            return many;
        } catch (Throwable t) {   // ServiceConfigurationError 是 Error，必须 catch Throwable
            e.error = "加载失败：" + t;
            e.module = null;
            return java.util.Collections.singletonList(e);
        }
    }

    public static SbrkcpModule find(List<Entry> entries, String id) {
        for (Entry e : entries) {
            if (e.ok() && e.module.info().id.equalsIgnoreCase(id)) return e.module;
        }
        return null;
    }

    public static String summary(List<Entry> entries) {
        int ok = 0;
        int bad = 0;
        for (Entry e : entries) {
            if (e.ok()) ok++;
            else bad++;
        }
        return "已加载 " + ok + " / 失败 " + bad;
    }
}
