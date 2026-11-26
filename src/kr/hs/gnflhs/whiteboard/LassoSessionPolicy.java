package kr.hs.gnflhs.whiteboard;

/** Pure mode policy: independently testable without Android classes. */
final class LassoSessionPolicy {
    static final int KEEP = 0;
    static final int DISCARD = 1;
    static final int NEW_PAGE = 2;

    static int normalize(int value) {
        return value >= KEEP && value <= NEW_PAGE ? value : KEEP;
    }
    static boolean createNewPageOnSelect(int mode) {
        return mode == NEW_PAGE;
    }
    static boolean keepInkOnFinish(int mode) {
        return mode == KEEP;
    }
    private LassoSessionPolicy() {}
}
