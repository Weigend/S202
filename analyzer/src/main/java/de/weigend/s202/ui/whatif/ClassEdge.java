package de.weigend.s202.ui.whatif;

/**
 * A single static class-to-class dependency edge with its method-call
 * weight. Source and target are fully-qualified class names from the
 * unchanged code analysis — they are never affected by What-If overrides.
 *
 * <p>{@code weight} carries the total method-call count from {@code source}
 * to {@code target} so the aggregator can produce both class-edge counts
 * (for "↑ N" badges) and call-count totals (for the level back-edge
 * heuristic).
 */
public record ClassEdge(String source, String target, int weight) {

    public ClassEdge {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source must be non-empty");
        }
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("target must be non-empty");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be non-negative");
        }
    }
}
