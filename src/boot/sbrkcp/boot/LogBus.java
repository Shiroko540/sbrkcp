package sbrkcp.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 极简日志总线：GUI 日志区与 headless 的 stdout 共用同一个出口。 */
public final class LogBus {

    private final List<Consumer<String>> sinks = new ArrayList<>();

    public void addSink(Consumer<String> sink) {
        sinks.add(sink);
    }

    public void log(String line) {
        for (Consumer<String> s : sinks) {
            try {
                s.accept(line);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
