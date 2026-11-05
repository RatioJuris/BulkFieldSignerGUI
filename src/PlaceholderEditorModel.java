// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Small immutable edit snapshots. A drag becomes one undoable edit on release. */
final class PlaceholderEditorModel {
    private record State(List<SignaturePlaceholder> areas, int selected) { }
    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();
    private State state;

    PlaceholderEditorModel(List<SignaturePlaceholder> areas) {
        if (areas.size() > PlaceholderStore.MAX_COUNT) throw new IllegalArgumentException("Too many signature areas.");
        state = new State(List.copyOf(areas), -1);
    }

    List<SignaturePlaceholder> areas() { return state.areas(); }
    int selected() { return state.selected(); }
    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }
    void select(int index) { state = new State(state.areas(), index >= 0 && index < state.areas().size() ? index : -1); }

    void add(SignaturePlaceholder area) {
        if (state.areas().size() >= PlaceholderStore.MAX_COUNT) throw new IllegalArgumentException("At most " + PlaceholderStore.MAX_COUNT + " stamp areas are supported.");
        List<SignaturePlaceholder> updated = new ArrayList<>(state.areas());
        updated.add(area);
        edit(updated, updated.size() - 1);
    }

    /** Adds several areas as one undoable edit; the selection is kept. Returns how many were added. */
    int addAll(List<SignaturePlaceholder> areas) {
        List<SignaturePlaceholder> updated = new ArrayList<>(state.areas());
        int added = 0;
        for (SignaturePlaceholder area : areas) {
            if (updated.size() >= PlaceholderStore.MAX_COUNT) break;
            if (!updated.contains(area)) { updated.add(area); added++; }
        }
        if (added > 0) edit(updated, selected());
        return added;
    }

    void replaceSelected(SignaturePlaceholder area) {
        if (selected() < 0 || state.areas().get(selected()).equals(area)) return;
        List<SignaturePlaceholder> updated = new ArrayList<>(state.areas());
        updated.set(selected(), area);
        edit(updated, selected());
    }

    void deleteSelected() {
        if (selected() < 0) return;
        List<SignaturePlaceholder> updated = new ArrayList<>(state.areas());
        updated.remove(selected());
        edit(updated, -1);
    }

    void clearPage(int page) {
        List<SignaturePlaceholder> updated = state.areas().stream().filter(area -> area.page() != page).toList();
        if (updated.size() != state.areas().size()) edit(updated, -1);
    }

    void undo() { if (canUndo()) { redo.addLast(state); state = undo.removeLast(); } }
    void redo() { if (canRedo()) { undo.addLast(state); state = redo.removeLast(); } }

    private void edit(List<SignaturePlaceholder> areas, int selected) {
        undo.addLast(state);
        if (undo.size() > 50) undo.removeFirst();
        redo.clear();
        state = new State(List.copyOf(areas), selected);
    }
}
