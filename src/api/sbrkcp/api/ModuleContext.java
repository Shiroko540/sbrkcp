package sbrkcp.api;

import java.io.File;

/** 宿主提供给面板的环境能力（不暴露 boot 内部实现）。 */
public interface ModuleContext {

    /** 默认输出目录（boot.jar 同级的 unpacked）。 */
    File defaultOutputDir();

    /** 最近一次打开固件所在目录。 */
    File lastInputDir();

    /** 输出一行日志到主界面日志区。 */
    void log(String line);

    /** 平台名，用于"关于/诊断"。 */
    String hostName();
}
