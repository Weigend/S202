package de.weigend.s202.ui.rendering.circuit;

import java.util.List;

/**
 * Result of routing a single dependency: an ordered list of grid cells forming
 * a rectilinear polyline from source to target plus metadata for rendering.
 */
public final class RoutedEdge {

    public final String sourceName;
    public final String targetName;
    public final boolean incoming;
    /** Grid cells in order from source port to target port. */
    public final List<int[]> path;
    /** End direction (for arrow orientation): {dx, dy} of the last step. */
    public final int[] endDirection;

    public RoutedEdge(String sourceName, String targetName, boolean incoming,
                      List<int[]> path, int[] endDirection) {
        this.sourceName = sourceName;
        this.targetName = targetName;
        this.incoming = incoming;
        this.path = path;
        this.endDirection = endDirection;
    }
}
