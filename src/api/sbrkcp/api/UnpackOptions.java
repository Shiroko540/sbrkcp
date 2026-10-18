package sbrkcp.api;

/** 解包选项：GUI 勾选项与命令行开关共用同一份。 */
public final class UnpackOptions {

    /** 仅列出条目，不写盘。 */
    public boolean listOnly;
    /** 垂直翻转输出（部分固件行序自下而上）。默认关闭，不按文件硬编码。 */
    public boolean flip;
    /** FAT 导出时同时写 dump_manifest.csv（路径 / 大小 / 首簇 / 实读字节 / MD5）。 */
    public boolean fatManifest;
    /** FAT 导出时把 .txt/.ime/.cfg/.rkz 转成 UTF-8 副本，写到 text/ 下，原文件保留。 */
    public boolean fatTranscodeText;
    /** BMP0 导出时同时写 bmp0_manifest.csv（逐条像素 MD5 / 尺寸 / comp / 头长）。 */
    public boolean bmp0Manifest;
    /** BMP0 导出时写 bmp0_diag.txt（头长、comp、comp=5 的 alpha 分布与失败原因）。 */
    public boolean bmp0Diag;

    public static UnpackOptions defaults() {
        return new UnpackOptions();
    }
}
