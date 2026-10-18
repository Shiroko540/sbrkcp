package sbrkcp.modules.img;

import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import sbrkcp.api.AbstractModulePanel;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;

/** 镜像面板：L1 解包主体 + “其他功能”勾选项（清单、文本转码）。 */
public final class ImgPanel extends AbstractModulePanel {

    private final JCheckBox manifest = new JCheckBox("导出清单 dump_manifest.csv（路径/大小/首簇/实读字节/MD5）");
    private final JCheckBox transcode = new JCheckBox("文本类转 UTF-8 副本（.txt/.ime/.cfg/.rkz → text/，原文件保留）");

    public ImgPanel(SbrkcpModule module, ModuleContext ctx) {
        super(module, ctx);
        JPanel extra = new JPanel(new GridLayout(0, 1));
        extra.setBorder(BorderFactory.createTitledBorder("其他功能"));
        extra.add(new JLabel("解包主体：FAT12/FAT16/FAT32 → 整盘解出 files/；无结构镜像 → 只输出加密/未知判定"));
        extra.add(manifest);
        extra.add(transcode);
        add(extra, java.awt.BorderLayout.SOUTH);
        appendLog(module.info().description);
    }

    @Override
    protected UnpackOptions collectOptions() {
        UnpackOptions o = UnpackOptions.defaults();
        o.fatManifest = manifest.isSelected();
        o.fatTranscodeText = transcode.isSelected();
        return o;
    }
}
