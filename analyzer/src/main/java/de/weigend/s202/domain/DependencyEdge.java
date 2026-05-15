package de.weigend.s202.domain;

/**
 * Directed dependency edge between two fully-qualified model elements.
 */
public record DependencyEdge(String from, String to) {

    public DependencyEdge {
        if (from == null || from.isEmpty()) {
            throw new IllegalArgumentException("from must be non-empty");
        }
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("to must be non-empty");
        }
    }
}
