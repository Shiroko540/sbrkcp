package sbrkcp.api;

import java.util.Arrays;

/** 模块自描述信息。显示名/分类/排序/扩展名全部由 {@link SbrkcpModule#info()} 返回，不另维护清单。 */
public final class ModuleInfo {

    public final String id;
    public final String name;
    public final String version;
    public final int apiVersion;
    public final String category;
    public final String[] extensions;
    public final int order;
    public final String description;

    public ModuleInfo(String id, String name, String version, int apiVersion,
                      String category, String[] extensions, int order, String description) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.apiVersion = apiVersion;
        this.category = category;
        this.extensions = extensions == null ? new String[0] : extensions.clone();
        this.order = order;
        this.description = description;
    }

    public boolean supports(String ext) {
        if (ext == null) return false;
        String e = ext.toLowerCase();
        for (String x : extensions) {
            if (x.equalsIgnoreCase(e)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return name + " v" + version + " (api " + apiVersion + ", " + category
                + ", " + Arrays.toString(extensions) + ")";
    }
}
