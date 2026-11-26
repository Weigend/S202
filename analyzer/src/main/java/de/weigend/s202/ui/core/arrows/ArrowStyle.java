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

import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import javafx.scene.paint.Color;

/**
 * How a tangle view draws one dependency arrow. Two independent channels:
 * <ul>
 *   <li><b>colour and weight</b> — a back-edge is red and heavy, every other
 *       dependency grey and thin;</li>
 *   <li><b>line pattern</b> — solid on a class cycle, dashed on a package
 *       cycle only.</li>
 * </ul>
 * A dependency on no cycle recedes into a light grey.
 */
public record ArrowStyle(Color color, double width, boolean dashed, double opacity) {

    public static final Color BACK_EDGE_COLOR = LineColors.AGAINST_LAYERS;
    public static final Color FORWARD_COLOR = LineColors.USES;
    public static final Color CONTEXT_COLOR = Color.rgb(176, 180, 184);

    public static final double BACK_EDGE_WIDTH = 1.3;
    public static final double FORWARD_WIDTH = 0.6;
    public static final double CONTEXT_WIDTH = 0.45;
    private static final double DIMMED_OPACITY = 0.12;

    /** A dependency that is shown for orientation only and is not part of the tangle. */
    public static final ArrowStyle CONTEXT = new ArrowStyle(CONTEXT_COLOR, CONTEXT_WIDTH, false, 1.0);

    public static ArrowStyle of(boolean backEdge, CycleKind cycle) {
        boolean dashed = cycle == CycleKind.PACKAGE_CYCLE;
        if (backEdge) {
            return new ArrowStyle(BACK_EDGE_COLOR, BACK_EDGE_WIDTH, dashed, 1.0);
        }
        if (cycle == CycleKind.NONE) {
            return CONTEXT;
        }
        return new ArrowStyle(FORWARD_COLOR, FORWARD_WIDTH, dashed, 1.0);
    }

    /** The same arrow pushed into the background, for dependencies a highlight does not select. */
    public ArrowStyle dimmed() {
        return new ArrowStyle(color, width, dashed, DIMMED_OPACITY);
    }
}
