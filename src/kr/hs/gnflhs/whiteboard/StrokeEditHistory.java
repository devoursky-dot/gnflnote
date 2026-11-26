package kr.hs.gnflhs.whiteboard;

import java.util.ArrayList;
import java.util.List;

/**
 * [v2.20.0] 화면 비트맵을 복사하지 않는 되돌리기/다시 실행 모델.
 *
 * - 한 Stroke를 추가할 때는 참조만 기록한다.
 * - 획 지우개(Stroke Eraser)로 삭제된 Stroke 목록을 기록 -> 실행 취소 시 원래대로 복구.
 * - 전체삭제는 화면의 모든 Stroke 참조를 한 묶음으로 기록한다.
 * - 전체삭제 -> 실행취소 한 번으로 전체 복원, 다시 실행하면 다시 삭제.
 * - 두 작업 사이의 필기·지우개 획 이력도 순서대로 유지한다.
 * - PDF 페이지 이동 시 호출자가 reset() 한다.
 * 모든 호출은 UI 스레드에서 직렬 실행해야 한다.
 */
final class StrokeEditHistory<T> {
    private static final class Edit<T> {
        final T added;
        final ArrayList<T> cleared;
        final ArrayList<T> removed;
        private Edit(T added, ArrayList<T> cleared, ArrayList<T> removed) {
            this.added = added;
            this.cleared = cleared;
            this.removed = removed;
        }
        static <T> Edit<T> add(T item) { return new Edit<>(item, null, null); }
        static <T> Edit<T> clear(ArrayList<T> items) { return new Edit<>(null, items, null); }
        static <T> Edit<T> remove(ArrayList<T> items) { return new Edit<>(null, null, items); }
        boolean isClear() { return cleared != null; }
        boolean isRemove() { return removed != null; }
    }

    private final ArrayList<Edit<T>> undo = new ArrayList<>();
    private final ArrayList<Edit<T>> redo = new ArrayList<>();

    /** v2.11 올가미 작업의 체크포인트. 이력 항목은 불변이므로 리스트만 복제한다. */
    static final class Snapshot<T> {
        final ArrayList<Edit<T>> undo, redo;
        Snapshot(ArrayList<Edit<T>> undo, ArrayList<Edit<T>> redo) {
            this.undo = undo;
            this.redo = redo;
        }
    }

    Snapshot<T> snapshot() {
        return new Snapshot<>(new ArrayList<>(undo), new ArrayList<>(redo));
    }
    void restore(Snapshot<T> checkpoint) {
        if (checkpoint == null) return;
        undo.clear(); undo.addAll(checkpoint.undo);
        redo.clear(); redo.addAll(checkpoint.redo);
    }

    void reset() { undo.clear(); redo.clear(); }

    /** 새로운 획이 그려지면 이전 '다시 실행' 기록은 무효가 된다. */
    void recordAdd(T stroke) {
        undo.add(Edit.add(stroke));
        redo.clear();
    }

    /** 현재 화면의 획들을 메모리에 참조만 남긴다. */
    boolean recordClear(List<T> current) {
        if (current.isEmpty()) return false;
        undo.add(Edit.clear(new ArrayList<>(current)));
        redo.clear();
        return true;
    }

    /** 획 지우개로 삭제된 획들을 기록하여 실행 취소로 복원 가능하게 한다. */
    boolean recordRemove(List<T> removedList) {
        if (removedList == null || removedList.isEmpty()) return false;
        undo.add(Edit.remove(new ArrayList<>(removedList)));
        redo.clear();
        return true;
    }

    boolean undo(List<T> current) {
        if (undo.isEmpty()) return false;
        Edit<T> step = undo.remove(undo.size() - 1);
        if (step.isClear()) {
            current.clear();
            current.addAll(step.cleared);
        } else if (step.isRemove()) {
            current.addAll(step.removed);
        } else if (!current.isEmpty()) {
            current.remove(current.size() - 1);
        }
        redo.add(step);
        return true;
    }

    boolean redo(List<T> current) {
        if (redo.isEmpty()) return false;
        Edit<T> step = redo.remove(redo.size() - 1);
        if (step.isClear()) {
            current.clear();
        } else if (step.isRemove()) {
            current.removeAll(step.removed);
        } else if (step.added != null) {
            current.add(step.added);
        }
        undo.add(step);
        return true;
    }

    /** 기존 올가미 포커스의 '되돌려진 획' 클리핑 안전장치 유지. */
    ArrayList<T> referencedByRedo() {
        ArrayList<T> strokes = new ArrayList<>();
        for (Edit<T> step : redo) {
            if (step.isClear()) strokes.addAll(step.cleared);
            else if (step.isRemove()) strokes.addAll(step.removed);
            else if (step.added != null) strokes.add(step.added);
        }
        return strokes;
    }
}
