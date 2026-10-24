package sbrkcp.modules.strings;

import java.io.File;
import java.util.List;
import javax.swing.JComponent;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.ModuleInfo;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;
import sbrkcp.core.text.StringsProbe;
import sbrkcp.core.text.StringsRunner;

/**
 * 界面文案模块（dist/other/strings.jar）：从任意固件/容器/目录里提取界面文案。
 * 提取与导出逻辑全部走 core 的 {@link StringsRunner}，与 CLI 同一实现。
 */
public final class StringsModule implements SbrkcpModule {

    private static final ModuleInfo INFO = new ModuleInfo(
            "strings", "界面文案", "0.0.2", 1,
            "其他功能", new String[0], 90,
            "从任意固件/容器/目录里提取界面文案（中英对照），S1–S10 全跑");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    /** headless 走 BootMain 的 strings 分支；此处保留可编程入口。 */
    @Override
    public UnpackResult unpack(File input, File outDir, ProgressListener progress, UnpackOptions options) {
        UnpackResult r = new UnpackResult();
        r.moduleId = INFO.id;
        r.input = input;
        r.outDir = outDir;
        r.kind = "STRINGS";
        StringsRunner.Options o = StringsRunner.Options.of(input);
        o.outDir = outDir;
        if (progress != null) o.quiet = false;
        StringsRunner.Log log = progress == null ? null : new StringsRunner.Log() {
            @Override
            public void line(String s) {
                progress.log(s);
            }
        };
        StringsProbe.Report rep = StringsRunner.probe(o, log);
        try {
            List<File> files = StringsRunner.export(rep, o, log);
            r.fileCount = files.size();
            for (File f : files) r.note("产出：" + f.getName());
        } catch (Throwable t) {
            r.note("导出失败：" + t);
            r.exitCode = 4;
        }
        r.notes.add("界面文案 " + rep.total() + " 条（结构化 " + rep.structured.size()
                + " / 裸扫 " + rep.bare.size() + "）");
        if (rep.empty()) {
            r.notes.add("未提取到文案；尝试清单见 probe_report.txt");
            r.exitCode = 5;
        }
        return r;
    }

    @Override
    public JComponent createPanel(ModuleContext ctx) {
        return new StringsPanel(ctx);
    }
}
