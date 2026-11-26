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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The trace of one canvas: which element it starts from, which way it goes and
 * how many steps it shows. Marks the boxes it reaches, also those a package
 * builds only when it is opened, and works the trace out again when the view
 * or its model changes.
 */
final class TraceController {

    /** What is traced; {@code null} for nothing. */
    record State(Trace trace, int maxSteps) {
    }

    private final Map<String, Node> elementRegistry;
    private final Supplier<ArchitectureNode> root;
    private final Supplier<DomainModel> model;
    private final Runnable marksChanged;
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(null);

    private Set<String> marked = Set.of();
    /** Whether a box may still carry a mark. */
    private boolean anyMarked;

    /**
     * @param marksChanged runs when other boxes are to be marked; the canvas
     *                     calls {@link #applyMarks()} on its next redraw
     */
    TraceController(Map<String, Node> elementRegistry, Supplier<ArchitectureNode> root,
                    Supplier<DomainModel> model, Runnable marksChanged) {
        this.elementRegistry = elementRegistry;
        this.root = root;
        this.model = model;
        this.marksChanged = marksChanged;
    }

    ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** Starts a trace with every step; {@code false} when the view does not show the element. */
    boolean trace(String start, Trace.Direction direction) {
        Trace trace = compute(start, direction);
        if (trace == null) {
            return false;
        }
        show(new State(trace, Trace.ALL));
        return true;
    }

    /** Shows the classes reached in at most {@code maxSteps} steps. */
    void setMaxSteps(int maxSteps) {
        State current = state.get();
        if (current != null && current.maxSteps() != maxSteps) {
            show(new State(current.trace(), maxSteps));
        }
    }

    void clear() {
        show(null);
    }

    /** The view or its model changed: the same question again; gone when the element is. */
    void recompute() {
        State current = state.get();
        if (current == null) {
            return;
        }
        Trace trace = compute(current.trace().start(), current.trace().direction());
        show(trace == null ? null : new State(trace, current.maxSteps()));
    }

    private Trace compute(String start, Trace.Direction direction) {
        ArchitectureNode rootNode = root.get();
        DomainModel shown = model.get();
        return rootNode == null || shown == null || start == null
                ? null : Trace.of(rootNode, shown, start, direction);
    }

    private void show(State next) {
        state.set(next);
        marked = next == null ? Set.of() : next.trace().marked(next.maxSteps());
        marksChanged.run();
    }

    /** Marks what the trace reaches among the boxes there are now and unmarks the rest. */
    void applyMarks() {
        if (marked.isEmpty() && !anyMarked) {
            return;
        }
        elementRegistry.forEach((fqn, node) -> {
            if (node instanceof GraphSelection.Selectable box) {
                box.setTraced(marked.contains(fqn));
            }
        });
        anyMarked = !marked.isEmpty();
    }
}
