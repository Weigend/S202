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

    /** Architectural level assigned by the level calculator. */
    int level();

    /** Leaf node in the architecture — a single class. */
    record ClassElement(String fqn, int level) implements Element {
        public ClassElement {
            if (fqn == null || fqn.isEmpty()) {
                throw new IllegalArgumentException("fqn must be non-empty");
            }
            if (level < 0) {
                throw new IllegalArgumentException("level must be non-negative");
            }
        }
    }

    /**
     * Interior node in the architecture — a package with its own nested
     * layered structure. The {@code rows} field is the same Rows-of-Cols
     * shape as at top level, applied to the package's own contents.
     */
    record PackageElement(String fqn, int level, List<List<Element>> rows) implements Element {
        public PackageElement {
            if (fqn == null || fqn.isEmpty()) {
                throw new IllegalArgumentException("fqn must be non-empty");
            }
            if (level < 0) {
                throw new IllegalArgumentException("level must be non-negative");
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
