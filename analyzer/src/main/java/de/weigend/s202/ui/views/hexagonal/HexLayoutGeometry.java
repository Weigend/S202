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
package de.weigend.s202.ui.views.hexagonal;

import de.weigend.s202.domain.architecture.HexagonalArchitecture;
import javafx.scene.Node;

/**
 * Pure polar/ring geometry of the hexagonal projection: canvas dimensions,
 * ring radii and the mapping from (ring, level, segment) to a position on the
 * radial pane. Holds no state — everything is derived from the constants.
 */
final class HexLayoutGeometry {

    static final double WIDTH = 1160;
    static final double HEIGHT = 980;
    static final double CENTER_X = WIDTH / 2.0;
    static final double CENTER_Y = 490;
    static final double CORE_RADIUS = 145;
    static final double APPLICATION_RADIUS = 285;
    static final double ADAPTER_RADIUS = 420;
    static final double CARD_RADIAL_OFFSET = 72;

    private HexLayoutGeometry() {
    }

    /**
     * Places {@code node} so that the point (radius, angle) sits at the given
     * offset from the node's top-left corner.
     */
    static void placeNode(Node node, double radius, double angleDegrees, double halfWidth, double halfHeight) {
        double radians = Math.toRadians(angleDegrees);
        double x = CENTER_X + Math.cos(radians) * radius - halfWidth;
        double y = CENTER_Y + Math.sin(radians) * radius - halfHeight;
        node.relocate(x, y);
    }

    /**
     * Radius inside the ring band for a group whose normalized level position
     * is {@code levelT} (0 = lowest level in the ring). In the inner rings the
     * shell presents its contact surface outward: low-level packages are the
     * ring's API and sit towards the band's OUTER edge. The ADAPTER ring
     * inverts this — its outward-facing surface is the entry point to the
     * world, so the HIGHEST level (bootstrap, main) sits outermost (see
     * HEXAGONAL_PACKAGE_LEVEL_CONCEPT).
     */
    static double bandRadius(HexagonalArchitecture.RingRole ring, double levelT) {
        double inner;
        double outer;
        switch (ring) {
            case CORE -> {
                inner = CORE_RADIUS * 0.32;
                outer = CORE_RADIUS - 32;
            }
            case APPLICATION -> {
                inner = CORE_RADIUS + 30;
                outer = APPLICATION_RADIUS - 36;
            }
            default -> {
                inner = APPLICATION_RADIUS + 30;
                outer = ADAPTER_RADIUS - 30;
                return inner + levelT * (outer - inner);
            }
        }
        return outer - levelT * (outer - inner);
    }

    static double angleForSegmentBoundary(int index, int segmentCount) {
        return -90.0 + (360.0 / Math.max(1, segmentCount)) * index;
    }

    static double spreadAngle(double startAngle, double endAngle, int index, int count) {
        if (count <= 1) {
            return normalizeAngle((startAngle + endAngle) / 2.0);
        }
        double span = endAngle - startAngle;
        double padding = Math.min(14.0, Math.abs(span) * 0.18);
        double usableStart = startAngle + padding;
        double usableSpan = span - padding * 2.0;
        return normalizeAngle(usableStart + usableSpan * (index + 0.5) / count);
    }

    static double normalizeAngle(double angle) {
        double normalized = angle % 360.0;
        return normalized < -180.0 ? normalized + 360.0 : normalized;
    }
}
