/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Records What-If element moves and supports multi-level undo/redo.
 * Undo works by resetting the view to its original layout and replaying
 * the moves up to the cursor — so no snapshot storage is needed.
 */
public final class WhatIfUndoManager {

    public sealed interface Move permits Move.InRow, Move.AsNewRow {
        String fqn();
        String containerFqn();
        int rowIndex();

        record InRow(String fqn, String containerFqn, int rowIndex, int colIndex) implements Move {}
        record AsNewRow(String fqn, String containerFqn, int rowIndex) implements Move {}
    }

    private final List<Move> history = new ArrayList<>();
    private int cursor = 0;
    private final BooleanProperty canUndo = new SimpleBooleanProperty(false);
    private final BooleanProperty canRedo = new SimpleBooleanProperty(false);

    public void record(Move move) {
        while (history.size() > cursor) {
            history.remove(history.size() - 1);
        }
        history.add(move);
        cursor++;
        sync();
    }

    /** Decrements the cursor and returns the moves to replay, or null if nothing to undo. */
    public List<Move> decrement() {
        if (cursor == 0) return null;
        cursor--;
        sync();
        return List.copyOf(history.subList(0, cursor));
    }

    /** Returns the move to apply for redo, or null if nothing to redo. */
    public Move increment() {
        if (cursor >= history.size()) return null;
        Move m = history.get(cursor);
        cursor++;
        sync();
        return m;
    }

    public void clear() {
        history.clear();
        cursor = 0;
        sync();
    }

    public BooleanProperty canUndoProperty() { return canUndo; }
    public BooleanProperty canRedoProperty() { return canRedo; }

    private void sync() {
        canUndo.set(cursor > 0);
        canRedo.set(cursor < history.size());
    }
}
