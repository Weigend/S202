package de.weigend.s202.domain.architecture;

/**
 * Granularity at which an architectural edge is reported — either a
 * specific class-to-class reference or its aggregate package-to-package
 * counterpart.
 */
public enum EdgeScope {
    CLASS,
    PACKAGE
}
