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
package de.weigend.s202.ui.views.threed;

import de.weigend.s202.analysis.metrics.MetricMapping;
import javafx.scene.paint.Color;

/** The colours of the palettes, from the smallest value to the largest. */
final class Palettes {

    private static final Color[] GREEN_RED = {
            Color.web("#2e7d32"), Color.web("#9ccc65"), Color.web("#fdd835"), Color.web("#fb8c00"), Color.web("#c62828")};
    /** Readable without telling red from green, and in grey too. */
    private static final Color[] VIRIDIS = {
            Color.web("#440154"), Color.web("#3b528b"), Color.web("#21908d"), Color.web("#5dc963"), Color.web("#fde725")};
    private static final Color[] BLUE_RED = {
            Color.web("#2166ac"), Color.web("#92c5de"), Color.web("#f7f7f7"), Color.web("#f4a582"), Color.web("#b2182b")};

    private Palettes() {
    }

    /** The colour at {@code t}, 0 to 1, of the palette. */
    static Color color(MetricMapping.Palette palette, double t, boolean reversed) {
        Color[] stops = switch (palette) {
            case GREEN_RED -> GREEN_RED;
            case VIRIDIS -> VIRIDIS;
            case BLUE_RED -> BLUE_RED;
        };
        double at = Math.clamp(reversed ? 1 - t : t, 0, 1) * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) Math.floor(at));
        return stops[i].interpolate(stops[i + 1], at - i);
    }
}
