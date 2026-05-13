package de.weigend.s202.domain.architecture;

/**
 * A single architectural violation as understood by a specific
 * {@link Architecture} style. Whether a given dependency edge counts
 * as a violation depends on the architecture in use — for a
 * {@link HierarchicalLayeredArchitecture}, an edge whose source level
 * is below its target level is an {@link ViolationKind#UPWARD}
 * violation; an edge inside a package-level cycle is a
 * {@link ViolationKind#PACKAGE_TANGLE} member.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code sourceFqn}, {@code targetFqn} — fully-qualified names
 *       at the granularity the architecture detected the violation
 *       on (class-class for upward edges; package-package for tangle
 *       members).</li>
 *   <li>{@code sourceLevel}, {@code targetLevel} — the levels the
 *       architecture computed for source and target, useful for
 *       sorting, grouping, and rendering hints.</li>
 * </ul>
 */
public record Violation(
        String sourceFqn,
        String targetFqn,
        ViolationKind kind,
        int sourceLevel,
        int targetLevel) {

    public Violation {
        if (sourceFqn == null || sourceFqn.isEmpty()) {
            throw new IllegalArgumentException("sourceFqn must be non-empty");
        }
        if (targetFqn == null || targetFqn.isEmpty()) {
            throw new IllegalArgumentException("targetFqn must be non-empty");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
    }
}
