/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui.rendering;

import de.weigend.s202.domain.DependencyEdge;
import javafx.geometry.Bounds;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Routes tangle edges along the lane grid: grid routing first, then a
 * fallback search that tolerates shared tracks. Produces geometry only;
 * painting the resulting routes is the caller's job.
 */
final class TangleRouter {

    private static final double TRACK_REUSE_PENALTY = TangleGeometry.LANE_SPACING_PX * 2.0;
    private static final double BRIDGE_RADIUS = TangleGeometry.LANE_SPACING_PX / 2.5;

    private TangleRouter() {
    }

    static TangleGeometry.RoutingResult routeEdges(List<DependencyEdge> edges, TangleGeometry.LaneLayout laneLayout) {
        List<TangleGeometry.RoutedTangleEdge> routed = new ArrayList<>();
        List<TangleGeometry.UnroutedEdge> unrouted = new ArrayList<>();
        for (DependencyEdge edge : edges) {
            Bounds source = laneLayout.classBoundsByName.get(edge.from());
            Bounds target = laneLayout.classBoundsByName.get(TangleLaneLayout.targetClassOf(edge.to()));
            if (source == null || target == null) {
                unrouted.add(new TangleGeometry.UnroutedEdge(edge, null, null, null));
                continue;
            }

            TangleGeometry.RoutedTangleEdge routedEdge = routeEdge(edge, source, target, laneLayout);
            if (routedEdge != null) {
                routed.add(routedEdge);
            } else {
                TangleGeometry.FallbackPath fallback = findFallbackPath(source, target, laneLayout);
                if (fallback == null) {
                    fallback = findFallbackPathForced(source, target, laneLayout);
                }
                unrouted.add(new TangleGeometry.UnroutedEdge(edge, source, target, fallback));
            }
        }
        return new TangleGeometry.RoutingResult(routed, unrouted);
    }

    private static TangleGeometry.RoutedTangleEdge routeEdge(DependencyEdge edge, Bounds source, Bounds target,
                                                             TangleGeometry.LaneLayout laneLayout) {
        double idealY = (source.getCenterY() + target.getCenterY()) / 2.0;
        List<TangleGeometry.HorizontalTrack> horizontalCandidates = new ArrayList<>(laneLayout.horizontalTracks);
        horizontalCandidates.sort(Comparator.comparingDouble(h -> horizontalScore(h, idealY)));

        for (TangleGeometry.HorizontalTrack horizontal : horizontalCandidates) {
            List<TangleGeometry.VerticalTrack> sourceTracks = candidateVerticalTracks(laneLayout.verticalTracks.get(source), horizontal.y);
            List<TangleGeometry.VerticalTrack> targetTracks = candidateVerticalTracks(laneLayout.verticalTracks.get(target), horizontal.y);
            for (TangleGeometry.VerticalTrack sourceTrack : sourceTracks) {
                for (TangleGeometry.VerticalTrack targetTrack : targetTracks) {
                    if (!horizontal.canOccupy(sourceTrack.x, targetTrack.x)) continue;
                    if (horizontalSegmentHitsClass(horizontal.y, sourceTrack.x, targetTrack.x, laneLayout.classBounds)) continue;

                    horizontal.occupy(sourceTrack.x, targetTrack.x);
                    sourceTrack.occupy(horizontal.y);
                    targetTrack.occupy(horizontal.y);
                    return new TangleGeometry.RoutedTangleEdge(edge, source, target, sourceTrack, horizontal, targetTrack);
                }
            }
        }
        return null;
    }

    private static List<TangleGeometry.VerticalTrack> candidateVerticalTracks(List<TangleGeometry.VerticalTrack> tracks, double y) {
        if (tracks == null) return List.of();
        return tracks.stream()
                .filter(track -> track.canOccupy(y))
                .sorted(Comparator.comparingDouble(TangleRouter::verticalScore))
                .toList();
    }

    /**
     * Like {@link #candidateVerticalTracks} but skips the occupancy check so
     * already-used tracks remain candidates for forced (double-occupancy) routing.
     * Physical reachability (track must span the horizontal Y) is still enforced.
     */
    private static List<TangleGeometry.VerticalTrack> candidateVerticalTracksForced(List<TangleGeometry.VerticalTrack> tracks, double y) {
        if (tracks == null) return List.of();
        return tracks.stream()
                .filter(track -> y >= track.topY && y <= track.bottomY)
                .sorted(Comparator.comparingDouble(TangleRouter::verticalScore))
                .toList();
    }

    private static double horizontalScore(TangleGeometry.HorizontalTrack track, double idealY) {
        return Math.abs(track.y - idealY) + track.useCount() * TRACK_REUSE_PENALTY;
    }

    private static double verticalScore(TangleGeometry.VerticalTrack track) {
        return Math.abs(track.x - track.owner.getCenterX()) + track.useCount() * TRACK_REUSE_PENALTY;
    }

    static boolean horizontalSegmentHitsClass(double y, double x1, double x2, List<Bounds> classBounds) {
        return horizontalSegmentHitsClass(y, x1, x2, classBounds, null, null);
    }

    static boolean horizontalSegmentHitsClass(double y, double x1, double x2, List<Bounds> classBounds,
                                             Bounds allowedA, Bounds allowedB) {
        TangleGeometry.Range xRange = TangleGeometry.Range.of(x1, x2).inflate(TangleGeometry.TRACK_CLEARANCE_PX);
        for (Bounds box : classBounds) {
            if (box == allowedA || box == allowedB) {
                continue;
            }
            if (y <= box.getMinY() - TangleGeometry.TRACK_CLEARANCE_PX || y >= box.getMaxY() + TangleGeometry.TRACK_CLEARANCE_PX) {
                continue;
            }
            TangleGeometry.Range boxRange = TangleGeometry.Range.of(
                    box.getMinX() - TangleGeometry.TRACK_CLEARANCE_PX,
                    box.getMaxX() + TangleGeometry.TRACK_CLEARANCE_PX);
            if (xRange.overlaps(boxRange)) {
                return true;
            }
        }
        return false;
    }

    static boolean verticalSegmentHitsClass(double x, double y1, double y2, List<Bounds> classBounds,
                                            Bounds allowedA, Bounds allowedB) {
        TangleGeometry.Range yRange = TangleGeometry.Range.of(y1, y2).inflate(TangleGeometry.TRACK_CLEARANCE_PX);
        for (Bounds box : classBounds) {
            if (box == allowedA || box == allowedB) {
                continue;
            }
            if (x <= box.getMinX() - TangleGeometry.TRACK_CLEARANCE_PX || x >= box.getMaxX() + TangleGeometry.TRACK_CLEARANCE_PX) {
                continue;
            }
            TangleGeometry.Range boxRange = TangleGeometry.Range.of(
                    box.getMinY() - TangleGeometry.TRACK_CLEARANCE_PX,
                    box.getMaxY() + TangleGeometry.TRACK_CLEARANCE_PX);
            if (yRange.overlaps(boxRange)) {
                return true;
            }
        }
        return false;
    }

    private static TangleGeometry.FallbackPath findFallbackPath(Bounds source, Bounds target,
                                                                TangleGeometry.LaneLayout laneLayout) {
        double idealY = (source.getCenterY() + target.getCenterY()) / 2.0;
        List<TangleGeometry.HorizontalTrack> horizontalCandidates = new ArrayList<>(laneLayout.horizontalTracks);
        horizontalCandidates.sort(Comparator.comparingDouble(h -> horizontalScore(h, idealY)));

        for (TangleGeometry.HorizontalTrack horizontal : horizontalCandidates) {
            List<TangleGeometry.VerticalTrack> sourceTracks = candidateVerticalTracks(laneLayout.verticalTracks.get(source), horizontal.y);
            List<TangleGeometry.VerticalTrack> targetTracks = candidateVerticalTracks(laneLayout.verticalTracks.get(target), horizontal.y);
            for (TangleGeometry.VerticalTrack sourceTrack : sourceTracks) {
                TangleGeometry.Point sourceDock = TangleGeometry.dockPoint(source, sourceTrack.x, horizontal.y);
                if (verticalSegmentHitsClass(sourceTrack.x, sourceDock.y(), horizontal.y,
                        laneLayout.classBounds, source, target)) {
                    continue;
                }
                for (TangleGeometry.VerticalTrack targetTrack : targetTracks) {
                    TangleGeometry.Point targetDock = TangleGeometry.dockPoint(target, targetTrack.x, horizontal.y);
                    if (!horizontal.canOccupy(sourceTrack.x, targetTrack.x)) {
                        continue;
                    }
                    if (verticalSegmentHitsClass(targetTrack.x, horizontal.y, targetDock.y(),
                            laneLayout.classBounds, source, target)) {
                        continue;
                    }
                    if (horizontalSegmentHitsClass(horizontal.y, sourceTrack.x, targetTrack.x,
                            laneLayout.classBounds, source, target)) {
                        continue;
                    }
                    horizontal.occupy(sourceTrack.x, targetTrack.x);
                    sourceTrack.occupy(horizontal.y);
                    targetTrack.occupy(horizontal.y);
                    return new TangleGeometry.FallbackPath(sourceDock, targetDock, sourceTrack.x, targetTrack.x, horizontal.y);
                }
            }
        }
        return null;
    }

    /**
     * Like {@link #findFallbackPath} but skips the {@code canOccupy} checks on
     * both horizontal and vertical tracks, allowing already-used tracks to be
     * shared (double-occupancy). Class-box hit detection is still enforced so
     * lines never run through boxes. {@code occupy()} is still called so the
     * use-count score steers subsequent edges away from the busiest tracks.
     */
    private static TangleGeometry.FallbackPath findFallbackPathForced(Bounds source, Bounds target,
                                                                      TangleGeometry.LaneLayout laneLayout) {
        double idealY = (source.getCenterY() + target.getCenterY()) / 2.0;
        List<TangleGeometry.HorizontalTrack> horizontalCandidates = new ArrayList<>(laneLayout.horizontalTracks);
        horizontalCandidates.sort(Comparator.comparingDouble(h -> horizontalScore(h, idealY)));

        for (TangleGeometry.HorizontalTrack horizontal : horizontalCandidates) {
            List<TangleGeometry.VerticalTrack> sourceTracks = candidateVerticalTracksForced(laneLayout.verticalTracks.get(source), horizontal.y);
            List<TangleGeometry.VerticalTrack> targetTracks = candidateVerticalTracksForced(laneLayout.verticalTracks.get(target), horizontal.y);
            for (TangleGeometry.VerticalTrack sourceTrack : sourceTracks) {
                TangleGeometry.Point sourceDock = TangleGeometry.dockPoint(source, sourceTrack.x, horizontal.y);
                if (verticalSegmentHitsClass(sourceTrack.x, sourceDock.y(), horizontal.y,
                        laneLayout.classBounds, source, target)) {
                    continue;
                }
                for (TangleGeometry.VerticalTrack targetTrack : targetTracks) {
                    TangleGeometry.Point targetDock = TangleGeometry.dockPoint(target, targetTrack.x, horizontal.y);
                    if (verticalSegmentHitsClass(targetTrack.x, horizontal.y, targetDock.y(),
                            laneLayout.classBounds, source, target)) {
                        continue;
                    }
                    if (horizontalSegmentHitsClass(horizontal.y, sourceTrack.x, targetTrack.x,
                            laneLayout.classBounds, source, target)) {
                        continue;
                    }
                    horizontal.occupy(sourceTrack.x, targetTrack.x);
                    sourceTrack.occupy(horizontal.y);
                    targetTrack.occupy(horizontal.y);
                    return new TangleGeometry.FallbackPath(sourceDock, targetDock, sourceTrack.x, targetTrack.x, horizontal.y);
                }
            }
        }
        return null;
    }

    static void emitHorizontalWithBridges(Path path, double xFrom, double xTo, double y,
                                          TangleGeometry.RoutedTangleEdge owner,
                                          List<TangleGeometry.VerticalSegment> verticalSegments) {
        double lo = Math.min(xFrom, xTo);
        double hi = Math.max(xFrom, xTo);
        int direction = xTo >= xFrom ? 1 : -1;

        List<Double> crossings = new ArrayList<>();
        for (TangleGeometry.VerticalSegment vertical : verticalSegments) {
            if (vertical.owner == owner) continue;
            if (vertical.x <= lo || vertical.x >= hi) continue;
            if (vertical.containsY(y)) {
                crossings.add(vertical.x);
            }
        }
        crossings.sort(direction > 0 ? Comparator.naturalOrder() : Comparator.reverseOrder());

        for (double x : crossings) {
            double before = x - direction * BRIDGE_RADIUS;
            double after = x + direction * BRIDGE_RADIUS;
            path.getElements().add(new LineTo(before, y));
            ArcTo arc = new ArcTo();
            arc.setX(after);
            arc.setY(y);
            arc.setRadiusX(BRIDGE_RADIUS);
            arc.setRadiusY(BRIDGE_RADIUS);
            arc.setLargeArcFlag(false);
            arc.setSweepFlag(direction > 0);
            path.getElements().add(arc);
        }
        path.getElements().add(new LineTo(xTo, y));
    }
}
