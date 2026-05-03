package de.weigend.s202.reader;

/**
 * Classification of a class-to-class dependency edge as captured by the
 * bytecode reader. A single (source, target) pair can carry multiple kinds —
 * e.g. {@code A extends B} and {@code A} also calls a method on {@code B}.
 */
public enum EdgeKind {
    // Order is also the natural display order — structural relationships
    // (extends/implements) before construction, calls last because they
    // expand into one row per method name.
    /** {@code class A extends B} */
    EXTENDS,
    /** {@code class A implements B} (or interface extension) */
    IMPLEMENTS,
    /** Constructor invocation — {@code new T(...)} (INVOKESPECIAL on {@code <init>}). */
    INSTANTIATES,
    /** Method invocation on the target class (excluding constructor calls). */
    CALLS;

    /** Lower-case label suitable for compact UI rendering ("calls", "extends", …). */
    public String label() {
        return name().toLowerCase();
    }
}
