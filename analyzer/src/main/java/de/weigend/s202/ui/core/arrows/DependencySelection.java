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
package de.weigend.s202.ui.core.arrows;

import de.weigend.s202.domain.DependencyEdge;

import java.util.List;

/**
 * A selected dependency arrow.
 *
 * @param from  the class or package the arrow starts at
 * @param to    the class or package it points to
 * @param edges the class dependencies the arrow stands for: exactly
 *              {@code from → to} between two classes, possibly many as soon as
 *              a collapsed package is at one end
 */
public record DependencySelection(String from, String to, List<DependencyEdge> edges) {

    /** No arrow is selected. */
    public static final DependencySelection NONE = new DependencySelection(null, null, List.of());

    public DependencySelection {
        edges = List.copyOf(edges);
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    /** True when the arrow connects two classes. */
    public boolean isClassDependency() {
        return edges.size() == 1 && edges.get(0).from().equals(from) && edges.get(0).to().equals(to);
    }
}
