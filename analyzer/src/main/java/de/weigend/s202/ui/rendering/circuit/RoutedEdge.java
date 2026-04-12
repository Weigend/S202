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
    /** World coordinate on the source box edge where the path should visually start. */
    public final double sourceStubX;
    public final double sourceStubY;
    /** World coordinate on the target box edge where the arrowhead should point. */
    public final double targetStubX;
    public final double targetStubY;

    public RoutedEdge(String sourceName, String targetName, boolean incoming,
                      List<int[]> path, int[] endDirection,
                      double sourceStubX, double sourceStubY,
                      double targetStubX, double targetStubY) {
        this.sourceName = sourceName;
        this.targetName = targetName;
        this.incoming = incoming;
        this.path = path;
        this.endDirection = endDirection;
        this.sourceStubX = sourceStubX;
        this.sourceStubY = sourceStubY;
        this.targetStubX = targetStubX;
        this.targetStubY = targetStubY;
    }
}
