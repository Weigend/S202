package de.weigend.s202.domain.architecture;

import java.util.List;

/**
 * Domain-level architectural model of a project — the polymorphic
 * representation the UI ultimately renders. An {@code Architecture}
 * carries both its structural shape (subtype-specific, e.g. layered
 * rows) and the {@link Violation}s that are defined for the chosen
 * architectural style.
 *
 * <p>Currently sealed to {@link HierarchicalLayeredArchitecture}.
 * Additional styles (e.g. interface-above-implementation) plug in by
 * implementing this interface with their own structural payload and
 * their own definition of what counts as a violation.
 */
public sealed interface Architecture permits HierarchicalLayeredArchitecture {

    /**
     * Edge-level architectural violations the chosen style detected on
     * the underlying dependency graph (e.g. an UPWARD class-to-class
     * dep). Empty when the model satisfies the style's expectations.
     */
    List<Violation> violations();

    /**
     * Group-level architectural problems — strongly-connected
     * components of size {@literal >} 1 in the package graph. Each
     * tangle reports the member packages so consumers can render or
     * inspect the cycle as a whole.
     */
    List<Tangle> tangles();
}
