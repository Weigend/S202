package de.weigend.s202.ui.rendering.circuit;

import java.util.HashMap;
import java.util.Map;

/**
 * Sparse channel/corridor lattice used for orthogonal edge routing.
 *
 * <p>The graph is the cross-product of two ordered track sets:
 * <ul>
 *   <li><b>H-tracks</b> — horizontal tracks at fixed Y. Indexed by ascending Y.</li>
 *   <li><b>V-tracks</b> — vertical tracks at fixed X. Indexed by ascending X.</li>
 * </ul>
 * A node is the intersection {@code (hIdx, vIdx)}; A* moves from a node to one
 * of its four orthogonal neighbours, traversing a single track segment per step.
 *
 * <p>Tracks are placed exclusively in <em>free strips</em> between class-box
 * edges, so any horizontal track is uninterrupted in X and any vertical track
 * is uninterrupted in Y — the lattice is fully passable, boxes sit in the
 * non-strip "wallpaper" regions outside it.
 *
 * <p>Per-cell usage counters are kept sparsely (HashMap on packed long keys)
 * so the structure scales with the number of routed edges, not with track
 * count squared. The router uses these for OVERLAP / CROSS / PARALLEL costing
 * — same shape as {@link AStarEdgeRouter} but on this lattice.
 */
public final class ChannelGraph {

    private final double[] hTrackY;
    private final double[] vTrackX;
    /** Per-track distance from the strip centre, in track-index units. {@code 0}
     *  for the central track of its strip, {@code 1} for the next ring out, …
     *  Lets the router prefer central tracks first and grow bundles outward. */
    private final int[] hTrackPref;
    private final int[] vTrackPref;
    private final Map<Long, Integer> hPass = new HashMap<>();
    private final Map<Long, Integer> vPass = new HashMap<>();

    public ChannelGraph(double[] hTrackY, int[] hTrackPref,
                        double[] vTrackX, int[] vTrackPref) {
        this.hTrackY = hTrackY;
        this.vTrackX = vTrackX;
        this.hTrackPref = hTrackPref;
        this.vTrackPref = vTrackPref;
    }

    public int nH() { return hTrackY.length; }
    public int nV() { return vTrackX.length; }
    public double hY(int i) { return hTrackY[i]; }
    public double vX(int j) { return vTrackX[j]; }
    public int hPref(int i) {
        return (i < 0 || i >= hTrackPref.length) ? 0 : hTrackPref[i];
    }
    public int vPref(int j) {
        return (j < 0 || j >= vTrackPref.length) ? 0 : vTrackPref[j];
    }

    public boolean inBounds(int h, int v) {
        return h >= 0 && h < hTrackY.length && v >= 0 && v < vTrackX.length;
    }

    /** Largest h with {@code hY[h] < y}. {@code -1} if no track sits above {@code y}. */
    public int hBelow(double y) {
        int lo = 0, hi = hTrackY.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (hTrackY[mid] < y) lo = mid + 1;
            else hi = mid;
        }
        return lo - 1;
    }

    /** Smallest h with {@code hY[h] > y}. {@code nH} if no track sits below {@code y}. */
    public int hAbove(double y) {
        int lo = 0, hi = hTrackY.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (hTrackY[mid] > y) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    public int vLeftOf(double x) {
        int lo = 0, hi = vTrackX.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (vTrackX[mid] < x) lo = mid + 1;
            else hi = mid;
        }
        return lo - 1;
    }

    public int vRightOf(double x) {
        int lo = 0, hi = vTrackX.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (vTrackX[mid] > x) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    public int hNearest(double y) {
        if (hTrackY.length == 0) return -1;
        int idx = hAbove(y);
        if (idx >= hTrackY.length) return hTrackY.length - 1;
        if (idx == 0) return 0;
        double dHi = hTrackY[idx] - y;
        double dLo = y - hTrackY[idx - 1];
        return dLo <= dHi ? idx - 1 : idx;
    }

    public int vNearest(double x) {
        if (vTrackX.length == 0) return -1;
        int idx = vRightOf(x);
        if (idx >= vTrackX.length) return vTrackX.length - 1;
        if (idx == 0) return 0;
        double dHi = vTrackX[idx] - x;
        double dLo = x - vTrackX[idx - 1];
        return dLo <= dHi ? idx - 1 : idx;
    }

    /**
     * Number of routed edges that have already been marked as passing
     * horizontally through intersection {@code (h, v)}.
     */
    public int hPass(int h, int v) {
        return hPass.getOrDefault(key(h, v), 0);
    }

    public int vPass(int h, int v) {
        return vPass.getOrDefault(key(h, v), 0);
    }

    public void addHPass(int h, int v) {
        hPass.merge(key(h, v), 1, Integer::sum);
    }

    public void addVPass(int h, int v) {
        vPass.merge(key(h, v), 1, Integer::sum);
    }

    private static long key(int h, int v) {
        return ((long) h << 32) | (v & 0xFFFFFFFFL);
    }
}
