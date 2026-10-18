package sbrkcp.modules.bmp0;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import sbrkcp.api.AbstractModulePanel;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;

/** BMP0 面板：透明度由品红色键自动处理；其他功能只留翻转、列清单与对拍/诊断开关。 */
public final class Bmp0Panel extends AbstractModulePanel {

    private final JCheckBox listOnly = new JCheckBox("只列出图片清单");
    private final JCheckBox flip = new JCheckBox("输出垂直翻转");
    private final JCheckBox manifest = new JCheckBox("导出清单");
    private final JCheckBox diag = new JCheckBox("诊断报告");

    public Bmp0Panel(SbrkcpModule module, ModuleContext ctx) {
        super(module, ctx);

        JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        extra.setBorder(BorderFactory.createTitledBorder("其他功能"));
        extra.add(new JLabel("品红 FF00FF 自动透明"));
        extra.add(listOnly);
        extra.add(flip);
        extra.add(manifest);
        extra.add(diag);
        add(extra, BorderLayout.SOUTH);

        manifest.setToolTipText("bmp0_manifest.csv：逐条像素 MD5 / 尺寸 / comp / 块头长度，可直接喂给 tools\\bmp0_ref.py --verify");
        diag.setToolTipText("bmp0_diag.txt：块头 8/14 分布、comp 分布、comp=5 的 alpha 梯度与失败原因");
        appendLog(module.info().description);
    }

    @Override
    protected UnpackOptions collectOptions() {
        UnpackOptions o = UnpackOptions.defaults();
        o.listOnly = listOnly.isSelected();
        o.flip = flip.isSelected();
        o.bmp0Manifest = manifest.isSelected();
        o.bmp0Diag = diag.isSelected();
        return o;
    }
}
