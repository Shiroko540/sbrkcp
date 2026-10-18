package sbrkcp.modules.rfw;

import java.io.File;
import javax.swing.JComponent;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.ModuleInfo;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;
import sbrkcp.core.Engine;

/** .RFW 整机固件：WFKR 分区表 → 逐分区落盘 → 分区内再走 RKRS / 裸图段。 */
public final class RfwModule implements SbrkcpModule {

    private static final ModuleInfo INFO = new ModuleInfo(
            "fuckrfw", "RFW 整机固件解包", "0.0.1Beta", 1,
            "整机固件", new String[]{"rfw"}, 20,
            "解出 WFKR 各分区原始文件，并在分区内自动识别 RKRS 容器或 0x200 头裸图段导出 PNG。");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public UnpackResult unpack(File input, File outDir, ProgressListener progress, UnpackOptions options)
            throws Exception {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        UnpackOptions opt = options == null ? UnpackOptions.defaults() : options;
        UnpackResult r = Engine.runRfw(input, outDir, pl, opt);
        if (!opt.listOnly) {
            Engine.writeReport(r, outDir);
        }
        pl.progress(100, "完成");
        return r;
    }

    @Override
    public JComponent createPanel(ModuleContext ctx) {
        return new RfwPanel(this, ctx);
    }
}
