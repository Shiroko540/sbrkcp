package sbrkcp.modules.bmp0;

import java.io.File;
import javax.swing.JComponent;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.ModuleInfo;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;
import sbrkcp.core.Engine;

/** BMP0.BIN 等 RKRS 资源归档：索引表定界 + 8 字节块头 + BGR32/RLE 解码。 */
public final class Bmp0Module implements SbrkcpModule {

    private static final ModuleInfo INFO = new ModuleInfo(
            "fuckbmp0", "BMP0 / RKRS 图片解包", "0.0.1Beta", 1,
            "图片资源", new String[]{"bin", "rkz", "rkrs", "bmp0"}, 40,
            "解析 0x28+RKRS 头与 8 字节索引项，导出全部图片为 PNG；品红键控为透明，RLE 支持 0xFF 扩展游程。");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public UnpackResult unpack(File input, File outDir, ProgressListener progress, UnpackOptions options)
            throws Exception {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        UnpackOptions opt = options == null ? UnpackOptions.defaults() : options;
        UnpackResult r = Engine.runRkrs(input, outDir, pl, opt);
        r.moduleId = INFO.id;
        if (!opt.listOnly) {
            Engine.writeReport(r, outDir);
        }
        pl.progress(100, "完成");
        return r;
    }

    @Override
    public JComponent createPanel(ModuleContext ctx) {
        return new Bmp0Panel(this, ctx);
    }
}
