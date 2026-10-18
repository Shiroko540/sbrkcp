package sbrkcp.modules.rkw;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import sbrkcp.api.AbstractModulePanel;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.SbrkcpModule;
import sbrkcp.api.UnpackOptions;

/** RKW 面板：仅额外提供"只列清单"与"垂直翻转"。本模块不提供任何内嵌资源递归开关。 */
public final class RkwPanel extends AbstractModulePanel {

    private final JCheckBox listOnly = new JCheckBox("只列出条目（不写盘）");
    private final JCheckBox flip = new JCheckBox("输出垂直翻转");

    public RkwPanel(SbrkcpModule module, ModuleContext ctx) {
        super(module, ctx);
        JPanel extra = new JPanel();
        extra.add(listOnly);
        extra.add(flip);
        add(extra, java.awt.BorderLayout.SOUTH);
        appendLog(module.info().description);
        appendLog("提示：本模块只解 RKW 外壳，导出后 files/ 下保留原条目结构，"
                + "内嵌的 BMP0.BIN 等一律原样落盘不解析。需要图片请把对应文件单独交给 fuckbmp0。");
    }

    @Override
    protected UnpackOptions collectOptions() {
        UnpackOptions o = UnpackOptions.defaults();
        o.listOnly = listOnly.isSelected();
        o.flip = flip.isSelected();
        return o;
    }
}
