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
 * Concrete {@link Architecture} implementation for projects modelled as
 * a hierarchy of layered packages. Structure is Rows-of-Cols, recursive
 * through {@link Element.PackageElement}: the top-level view is a list
 * of rows (each row holds the elements at one architectural level),
 * and every nested package carries its own inner rows.
 *
 * <p>Rows are intended to be sorted from highest level (index 0) to
 * lowest. Within a row, the column order reflects the horizontal-layout
 * decision made by the rendering pipeline. The interpretation of "level"
 * (per-class vs. per-package) is the one the {@code LevelCalculator}
 * assigned to each element.
 *
 * <p>Violations are tracked at this level alongside the structure so a
 * single object answers "how does the visual look" and "what's wrong
 * with it" in one consistent snapshot.
 */
public record HierarchicalLayeredArchitecture(
        List<List<Element>> rows,
        List<Violation> violations,
        List<Tangle> tangles) implements LayeredArchitecture {

    public HierarchicalLayeredArchitecture {
        rows = copyDeepImmutable(rows);
        violations = List.copyOf(violations);
        tangles = List.copyOf(tangles);
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
