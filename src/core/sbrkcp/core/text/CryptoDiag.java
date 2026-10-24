package sbrkcp.core.text;

/** S9 加密/压缩候选诊断：熵、16 字节块重复率、块边界规律性。只诊断，不生成文案。 */
public final class CryptoDiag {

    public final double entropy;
    public final double blockRepeatRate;
    public final boolean blockAligned;
    public final boolean cryptoLike;
    public final String summary;

    private CryptoDiag(double entropy, double blockRepeatRate, boolean blockAligned, boolean cryptoLike,
                       String summary) {
        this.entropy = entropy;
        this.blockRepeatRate = blockRepeatRate;
        this.blockAligned = blockAligned;
        this.cryptoLike = cryptoLike;
        this.summary = summary;
    }

    public static CryptoDiag analyze(byte[] d) {
        if (d == null || d.length < 4096) {
            return new CryptoDiag(0, 0, false, false, "样本过小，未做加密诊断");
        }
        int[] freq = new int[256];
        int n = Math.min(d.length, 8 * 1024 * 1024);
        for (int i = 0; i < n; i++) freq[d[i] & 0xFF]++;
        double h = 0;
        for (int i = 0; i < 256; i++) {
            if (freq[i] == 0) continue;
            double p = (double) freq[i] / n;
            h -= p * (Math.log(p) / Math.log(2));
        }

        int blocks = n / 16;
        int repeats = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        int sampleBlocks = Math.min(blocks, 4096);
        for (int i = 0; i < sampleBlocks; i++) {
            String key = hex(d, i * 16, 16);
            if (!seen.add(key)) repeats++;
        }
        double repeatRate = sampleBlocks == 0 ? 0 : (double) repeats / sampleBlocks;

        boolean aligned = checkAlignment(d, n);
        boolean cryptoLike = h > 7.5 && repeatRate < 0.02;
        String s = String.format(java.util.Locale.ROOT,
                "熵=%.2f bit/B, 16字节块重复率=%.2f%%, 块对齐=%s, 判定=%s",
                h, repeatRate * 100, aligned ? "是" : "否", cryptoLike ? "疑似加密/高压缩" : "非加密特征");
        return new CryptoDiag(h, repeatRate, aligned, cryptoLike, s);
    }

    /** 每 16 字节的尾部零字节比例是否异常高，用于识别块结构。 */
    private static boolean checkAlignment(byte[] d, int n) {
        int blocks = n / 16;
        if (blocks < 16) return false;
        int zeroTail = 0;
        for (int i = 0; i < Math.min(blocks, 2048); i++) {
            if (d[i * 16 + 15] == 0 || d[i * 16 + 15] == 0xFF) zeroTail++;
        }
        return zeroTail * 100 / Math.min(blocks, 2048) > 80;
    }

    private static String hex(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len && off + i < d.length; i++) {
            sb.append(Character.forDigit((d[off + i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(d[off + i] & 0xF, 16));
        }
        return sb.toString();
    }
}
