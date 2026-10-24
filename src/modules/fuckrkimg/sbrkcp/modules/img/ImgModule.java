package sbrkcp.modules.img;

import java.io.File;
import javax.swing.JComponent;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.ModuleInfo;
import sbrkcp.api.ProgressListener;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;
import sbrkcp.api.UnpackResult;
import sbrkcp.core.BigFile;
import sbrkcp.core.Engine;
import sbrkcp.core.Firmware;

/** 裸镜像：FAT16/FAT32 解出文件；加密或无结构镜像给出判定结论并以 exit 0 结束。 */
public final class ImgModule implements SbrkcpModule {

    private static final ModuleInfo INFO = new ModuleInfo(
            "fuckrkimg", "磁盘镜像 / 加密判定", "0.0.2", 1,
            "镜像", new String[]{"img"}, 30,
            "识别 FAT16/FAT32 并解出全部文件；无结构镜像输出熵、块重复率、可打印率，判定是否疑似加密。");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public UnpackResult unpack(File input, File outDir, ProgressListener progress, UnpackOptions options)
            throws Exception {
        ProgressListener pl = progress == null ? ProgressListener.NONE : progress;
        UnpackOptions opt = options == null ? UnpackOptions.defaults() : options;
        UnpackResult r = new UnpackResult();
        r.moduleId = INFO.id;
        r.input = input;
        r.outDir = outDir;
        try (BigFile f = new BigFile(input)) {
            Firmware.Detection d = Firmware.detect(f);
            r.kind = d.kind.name();
            pl.log("镜像判定：" + d.kind + (d.detail == null ? "" : "，" + d.detail));
            if (d.kind == Firmware.Kind.FAT16 || d.kind == Firmware.Kind.FAT32) {
                r = Engine.runFat(input, outDir, pl, opt);
                r.moduleId = INFO.id;
            } else {
                // U6.BIN / Image.code.bin 这类：不硬解，只给判定结论
                Engine.analyseUnknown(f, r);
                if (r.encrypted) r.kind = "ENCRYPTED";
                r.exitCode = 0;
            }
        }
        if (!opt.listOnly) {
            Engine.writeReport(r, outDir);
        }
        pl.progress(100, "完成");
        return r;
    }

    @Override
    public JComponent createPanel(ModuleContext ctx) {
        return new ImgPanel(this, ctx);
    }
}
