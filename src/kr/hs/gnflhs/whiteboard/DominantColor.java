package kr.hs.gnflhs.whiteboard;

/**
 * Sampling color histogram for dark/white/page backgrounds.
 * 4-bit RGB bins prevent anti-aliasing and JPEG/PDF pixel noise from
 * making an almost uniform area appear to have many unrelated colors.
 * The maximum memory use is fixed at ~64 KiB regardless of image size.
 */
final class DominantColor {
    private final int[] counts = new int[4096];
    private final int[] sumR = new int[4096];
    private final int[] sumG = new int[4096];
    private final int[] sumB = new int[4096];
    private int bestBin = -1;
    private int bestCount;

    void add(int color) {
        // Ignore transparent pixels outside the selected original content.
        if (((color >>> 24) & 255) < 240) return;
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        int bin = ((r >>> 4) << 8) | ((g >>> 4) << 4) | (b >>> 4);
        int count = ++counts[bin];
        sumR[bin] += r; sumG[bin] += g; sumB[bin] += b;
        if (count > bestCount) { bestCount = count; bestBin = bin; }
    }

    int result(int fallback) {
        if (bestBin < 0 || bestCount == 0) return fallback | 0xFF000000;
        int r = sumR[bestBin] / bestCount;
        int g = sumG[bestBin] / bestCount;
        int b = sumB[bestBin] / bestCount;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
