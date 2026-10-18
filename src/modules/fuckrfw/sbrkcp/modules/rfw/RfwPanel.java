package sbrkcp.modules.rfw;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import sbrkcp.api.AbstractModulePanel;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;

/** RFW 面板：分区原像是主产物，图片是附加产物。 */
public final class RfwPanel extends AbstractModulePanel {

    private final JCheckBox listOnly = new JCheckBox("只列分区信息");
    private final JCheckBox flip = new JCheckBox("输出垂直翻转");

    public RfwPanel(SbrkcpModule module, ModuleContext ctx) {
        super(module, ctx);
        JPanel extra = new JPanel();
        extra.add(new JLabel("分区原像按 partN_偏移.bin 落盘"));
        extra.add(listOnly);
        extra.add(flip);
        add(extra, java.awt.BorderLayout.SOUTH);
        appendLog(module.info().description);
    }

    @Override
    protected UnpackOptions collectOptions() {
        UnpackOptions o = UnpackOptions.defaults();
        o.listOnly = listOnly.isSelected();
        o.flip = flip.isSelected();
        return o;
    }
}
