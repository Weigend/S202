package de.weigend.s202.domain.architecture;

/**
 * A pair of endpoint identifiers (typically FQNs) that two or more
 * violations share after rollup. Used as the key in the aggregated map
 * {@link Architecture#groupUpwardViolations} returns.
 */
public record EndpointPair(String source, String target) {}
