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
package de.weigend.s202.ui.core.events;

import de.weigend.s202.domain.DependencyEdge;

import java.util.Collection;
import java.util.EventObject;
import java.util.Set;

/**
 * Published when several recommended tangle cut edges are applied at once.
 */
public class CutTangleEdgesEvent extends EventObject {

    private final Set<DependencyEdge> edges;

    public CutTangleEdgesEvent(Collection<DependencyEdge> edges, Object source) {
        super(source);
        this.edges = edges == null ? Set.of() : Set.copyOf(edges);
    }

    public Set<DependencyEdge> getEdges() {
        return edges;
    }
}
