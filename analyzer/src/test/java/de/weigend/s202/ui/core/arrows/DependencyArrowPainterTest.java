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
package de.weigend.s202.ui.core.arrows;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyArrowPainterTest {

    private final Pane dependencyPane = new Pane();
    private final Rectangle left = new Rectangle(10, 10, 40, 20);
    private final Rectangle right = new Rectangle(100, 12, 40, 20);
    private final Rectangle below = new Rectangle(100, 100, 40, 20);
    private final Pane content = new Pane(left, right, below, dependencyPane);
    private final DependencyArrowPainter painter = new DependencyArrowPainter(dependencyPane, status -> { });

    @Test
    void neighboursInOneRowAreConnectedFromSideToSide() {
        Line rightward = draw(left, right, false);
        assertEquals(50.0, rightward.getStartX(), 0.001, "leaves at the side that faces the target");
        assertEquals(20.0, rightward.getStartY(), 0.001, "half way up the source");
        assertEquals(100.0, rightward.getEndX(), 0.001);
        assertEquals(22.0, rightward.getEndY(), 0.001);

        Line leftward = draw(right, left, false);
        assertEquals(100.0, leftward.getStartX(), 0.001);
        assertEquals(50.0, leftward.getEndX(), 0.001);
    }

    @Test
    void theTwoArrowsBetweenNeighboursInOneRowRunAboveEachOther() {
        Line rightward = draw(left, right, true);
        Line leftward = draw(right, left, true);

        assertEquals(50.0, rightward.getStartX(), 0.001, "both keep the facing sides");
        assertEquals(50.0, leftward.getEndX(), 0.001);
        assertTrue(leftward.getEndY() - rightward.getStartY() >= 6.0, "at the left box they are a gap apart");
        assertTrue(leftward.getStartY() - rightward.getEndY() >= 6.0, "and at the right box");
        assertTrue(rightward.getStartY() >= 10 && leftward.getEndY() <= 30, "neither leaves the box edge");
    }

    @Test
    void boxesInDifferentRowsKeepTheirTopAndBottomEdges() {
        Line down = draw(left, below, false);

        assertEquals(30.0, down.getStartX(), 0.001);
        assertEquals(30.0, down.getStartY(), 0.001);
        assertEquals(120.0, down.getEndX(), 0.001);
        assertEquals(100.0, down.getEndY(), 0.001);
    }

    @Test
    void sideBySideNeedsOneRowAndNoHorizontalOverlap() {
        assertTrue(DependencyArrowPainter.sideBySide(new double[]{0, 0, 10, 10}, new double[]{20, 4, 30, 14}));
        assertFalse(DependencyArrowPainter.sideBySide(new double[]{0, 0, 10, 10}, new double[]{20, 8, 30, 18}),
                "a box whose centre lies below the other is in the next row");
        assertFalse(DependencyArrowPainter.sideBySide(new double[]{0, 0, 10, 10}, new double[]{5, 0, 15, 10}),
                "overlapping boxes have no facing sides");
    }

    /** Draws {@code source → target} and returns its shaft; arrowhead segments carry no click handler. */
    private Line draw(Node source, Node target, boolean opposed) {
        dependencyPane.getChildren().clear();
        painter.draw(new DependencyArrowPainter.Arrow(source, target, "a.Source", "a.Target",
                false, true, null, false, null, null), opposed, Map.of());
        assertTrue(content.getChildren().contains(dependencyPane));
        return dependencyPane.getChildren().stream()
                .filter(node -> node instanceof Line line && line.getOnMouseClicked() != null)
                .map(Line.class::cast)
                .findFirst().orElseThrow();
    }
}
