package de.weigend.s202.domain.architecture;

/**
 * The kind of architectural violation a specific {@link Architecture}
 * style is reporting on an edge. Each style picks the subset that
 * applies; the enum is shared so cross-style consumers (renderers,
 * panels, tests) speak one vocabulary.
 */
public enum ViolationKind {

    /**
     * The dependency goes against the architecture's layer direction
     * — source sits below target in the layered model.
     */
    UPWARD
}
