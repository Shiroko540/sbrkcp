package sbrkcp.api;

import java.io.File;
import javax.swing.JComponent;

/**
 * 模块契约。实现类必须有无参构造器，并在 jar 内提供
 * META-INF/services/sbrkcp.api.SbrkcpModule 指向自己。
 */
public interface SbrkcpModule {

    ModuleInfo info();

    /** 无界面能力：GUI 与 --headless 共用同一实现。 */
    UnpackResult unpack(File input, File outDir, ProgressListener progress, UnpackOptions options) throws Exception;

    /** 模块面板；GUI 直接塞进 CardLayout。headless 下不得调用。 */
    JComponent createPanel(ModuleContext ctx);
}
