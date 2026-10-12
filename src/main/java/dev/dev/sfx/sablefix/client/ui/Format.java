package dev.sfx.sablefix.client.ui;

/** 数值/单位格式化。 */
public final class Format {

    private Format() {}

    public static String fmtSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
