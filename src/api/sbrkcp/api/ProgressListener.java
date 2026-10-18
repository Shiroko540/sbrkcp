package sbrkcp.api;

/** 解包进度与日志回调。GUI 实现必须把回调切回 EDT；headless 实现直接写 stdout。 */
public interface ProgressListener {

    void log(String line);

    void progress(int percent, String stage);

    boolean isCancelled();

    default void warn(String line) {
        log("[WARN] " + line);
    }

    /** 不关心进度与取消。 */
    ProgressListener NONE = new ProgressListener() {
        @Override public void log(String line) { }
        @Override public void progress(int percent, String stage) { }
        @Override public boolean isCancelled() { return false; }
    };
}
