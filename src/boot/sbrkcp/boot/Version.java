package sbrkcp.boot;

/** 版本号单一来源：GUI 标题、关于框、状态栏、--version 输出统一取自此处。 */
public final class Version {

    /** 软件版本。Beta 阶段首版，不保证 CLI 与模块 API 稳定。 */
    public static final String VERSION = "0.0.1Beta";

    /** 版本通道：alpha / beta / release。 */
    public static final String CHANNEL = "beta";

    private Version() {
    }

    /** 形如 sbrkcp 0.0.1Beta。 */
    public static String title() {
        return "sbrkcp " + VERSION;
    }
}
