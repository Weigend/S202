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
package de.weigend.s202.domain.architecture;

import java.util.List;

/**
 * A single node in an {@link Architecture}'s structure — either a class
 * or a package. Packages are recursive: each carries its own inner
 * Rows-of-Cols layout, mirroring the nested layered view the UI shows
 * when a package is expanded.
 */
public sealed interface Element {

    /** Fully qualified name (class fqcn or package fqcn). */
    String fqn();

    /**
     * Global architectural depth assigned by the level calculator —
     * derived from the dependency-chain longest path. Separate from any
     * layout/rendering position; see ADR_ARCHITECTURE_LEVEL_VS_LOCAL_LAYER_INDEX.
     */
    int architectureLevel();

    /**
     * Position of this element within its direct parent container's
     * layered rows — populated by the layout step from a sibling-only
     * dependency graph. Higher value sits visually higher within the
     * parent's box. Currently always 0 until the LocalLevelCalculator
     * is wired in.
     */
    int localLevel();

    /** Leaf node in the architecture — a single class. */
    record ClassElement(String fqn, int architectureLevel, int localLevel) implements Element {
        public ClassElement {
            if (fqn == null || fqn.isEmpty()) {
                throw new IllegalArgumentException("fqn must be non-empty");
            }
            if (architectureLevel < 0) {
                throw new IllegalArgumentException("architectureLevel must be non-negative");
            }
            if (localLevel < 0) {
                throw new IllegalArgumentException("localLevel must be non-negative");
            }
        }
    }

    /**
     * Interior node in the architecture — a package with its own nested
     * layered structure. The {@code rows} field is the same Rows-of-Cols
     * shape as at top level, applied to the package's own contents.
     */
    record PackageElement(String fqn, int architectureLevel, int localLevel,
                          List<List<Element>> rows) implements Element {
        public PackageElement {
            if (fqn == null || fqn.isEmpty()) {
                throw new IllegalArgumentException("fqn must be non-empty");
            }
            if (architectureLevel < 0) {
                throw new IllegalArgumentException("architectureLevel must be non-negative");
            }
            if (localLevel < 0) {
                throw new IllegalArgumentException("localLevel must be non-negative");
            }
            rows = copyDeepImmutable(rows);
        }

        private static List<List<Element>> copyDeepImmutable(List<List<Element>> rows) {
            if (rows == null) {
                return List.of();
            }
            List<List<Element>> outer = new java.util.ArrayList<>(rows.size());
            for (List<Element> row : rows) {
                outer.add(List.copyOf(row));
            }
            return List.copyOf(outer);
        }
    }
}
