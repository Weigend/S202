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
package de.weigend.s202.domain;

import java.util.List;

/**
 * The way back that makes a dependency {@code A → B} part of a cycle: the
 * shortest path from B to A.
 *
 * @param packageLevel true when the path connects siblings of one container —
 *                     packages or classes — rather than single classes: the
 *                     cycle is then one of packages only
 */
public record CycleReturnPath(boolean packageLevel, List<Step> steps) {

    /** One hop, with the class dependencies that carry it. */
    public record Step(String from, String to, List<DependencyEdge> edges) {
        public Step {
            edges = List.copyOf(edges);
        }
    }

    /** On no cycle. */
    public static final CycleReturnPath NONE = new CycleReturnPath(false, List.of());

    public CycleReturnPath {
        steps = List.copyOf(steps);
    }

    public boolean exists() {
        return !steps.isEmpty();
    }
}
