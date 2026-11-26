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

import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;

/**
 * What colour and pattern of a line mean, the same in every view.
 *
 * <table>
 * <caption>Colours</caption>
 * <tr><td>grey</td><td>a dependency</td></tr>
 * <tr><td>green</td><td>a dependency on what is selected</td></tr>
 * <tr><td>blue</td><td>the selected line</td></tr>
 * <tr><td>red</td><td>runs against the layers: a back-edge, or what a move turned round</td></tr>
 * <tr><td>purple</td><td>lies on a class cycle</td></tr>
 * <tr><td>orange</td><td>lies on a package cycle</td></tr>
 * <tr><td>black</td><td>breaks a rule of the Component or the Hexagonal View</td></tr>
 * <tr><td>light green</td><td>is cut</td></tr>
 * </table>
 *
 * Solid is the rule; dashed lies on a package cycle only, dotted exists in the
 * plan only - a cut, or a violation made by a move.
 */
public final class LineColors {

    public static final Color USES = Color.rgb(90, 94, 98);
    public static final Color USED_BY = Color.rgb(0, 128, 0);
    public static final Color SELECTED = Color.web("#1565c0");
    public static final Color AGAINST_LAYERS = Color.web("#d32f2f");
    public static final Color CLASS_CYCLE = Color.web("#8e24aa");
    public static final Color PACKAGE_CYCLE = Color.web("#ff8c00");
    public static final Color BROKEN_RULE = Color.BLACK;
    public static final Color CUT = Color.web("#2ecc71");

    private LineColors() {
    }

    /** On a package cycle only. */
    public static void dash(Shape line) {
        line.getStrokeDashArray().setAll(4.0, 2.5);
    }

    /** Not in the code, only in the plan. */
    public static void dot(Shape line) {
        line.getStrokeDashArray().setAll(1.2, 2.8);
    }
}
