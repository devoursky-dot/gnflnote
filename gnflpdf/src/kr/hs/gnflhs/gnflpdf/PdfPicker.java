package kr.hs.gnflhs.gnflpdf;

import android.net.Uri;

/**
 * Lightweight PdfSourceRef container used by PdfLinkQrManager.
 */
public final class PdfPicker {
    private PdfPicker() {}

    public static final class PdfSourceRef {
        public final Uri uri;
        public final String filePath;
        public final int pageIndex;

        public PdfSourceRef(Uri uri, String filePath, int pageIndex) {
            this.uri = uri;
            this.filePath = filePath;
            this.pageIndex = pageIndex;
        }
    }

    public static PdfSourceRef findPdfSourceForPath(String path) {
        return null;
    }
}
