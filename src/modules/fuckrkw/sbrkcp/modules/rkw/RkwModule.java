package sbrkcp.modules.rkw;

import java.io.File;
import javax.swing.JComponent;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.ModuleInfo;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;
import sbrkcp.core.Engine;

/** OPPO S9 一类的 .RKW 资源包（RKDL 头 + 文件表）。只解外壳，不解释内嵌的其它格式。 */
public final class RkwModule implements SbrkcpModule {

    private static final ModuleInfo INFO = new ModuleInfo(
            "fuckrkw", "RKW 资源包解包", "0.0.2", 1,
            "资源包", new String[]{"rkw"}, 10,
            "解析 RKDL 文件表，按原路径导出全部内嵌文件。只解 RKW 外壳，"
                    + "内嵌的 BMP0.BIN 等原样落盘不解析，需要图片请交给 fuckbmp0 单独解。");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public UnpackResult unpack(File input, File outDir, ProgressListener progress, UnpackOptions options)
            throws Exception {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        UnpackOptions opt = options == null ? UnpackOptions.defaults() : options;
        UnpackResult r = Engine.runRkw(input, outDir, pl, opt);
        if (!opt.listOnly) {
            Engine.writeReport(r, outDir);
        }
        pl.progress(100, "完成");
        return r;
    }

    @Override
    public JComponent createPanel(ModuleContext ctx) {
        return new RkwPanel(this, ctx);
    }
}
