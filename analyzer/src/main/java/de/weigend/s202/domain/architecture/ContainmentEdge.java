package de.weigend.s202.domain.architecture;

/**
 * A dependency edge that the level-calculation algorithm suppresses
 * because the source's package strictly contains the target's package —
 * the classic "parent module knows about a child module" reference, e.g.
 * a top-level wiring class touching one of its sub-package modules.
 *
 * <p>These edges are deliberately excluded from the weighted package
 * graph (and therefore from package levels, violations, and the regular
 * dependency renderer), since otherwise every normal Avaje/SPI-style
 * containment would explode into spurious tangles. Keeping them as a
 * first-class collection on {@link Architecture} lets the UI render
 * them differently (e.g. dashed) without losing the information that
 * they exist.
 *
 * <p>{@link EdgeScope#CLASS} edges carry the actual class FQNs;
 * {@link EdgeScope#PACKAGE} edges carry the source and target package
 * FQNs and represent the aggregate over all class-level containment
 * references between those two packages.
 */
public record ContainmentEdge(String sourceFqn, String targetFqn, EdgeScope scope) {

    public ContainmentEdge {
        if (sourceFqn == null || sourceFqn.isEmpty()) {
            throw new IllegalArgumentException("sourceFqn must be non-empty");
        }
        if (targetFqn == null || targetFqn.isEmpty()) {
            throw new IllegalArgumentException("targetFqn must be non-empty");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
    }
}
