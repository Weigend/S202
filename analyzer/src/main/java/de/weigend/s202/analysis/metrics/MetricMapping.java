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
package de.weigend.s202.analysis.metrics;

/**
 * How metrics show in the tilted picture: one as the height of the boxes, one
 * as the colour of their sides. The tops stay as in 2D.
 *
 * @param height    what the height of a class or closed package says
 * @param scale     how values become heights: a few large values flatten the rest when linear
 * @param heightPercent height of the largest value in percent of the size of the picture,
 *                  the square root of its width times its height: the same setting fits a
 *                  small system and a large one
 * @param color     what the colour of the sides says; {@code null} for the colours of 2D
 * @param colorMin  value at the start of the palette; {@code null} for the smallest value shown
 * @param colorMax  value at the end of the palette; {@code null} for the largest value shown
 * @param palette   the colours from the smallest to the largest value
 * @param reversed  the palette runs the other way: for metrics where small is bad
 */
public record MetricMapping(Metric height, Scale scale, double heightPercent, Metric color,
                            Double colorMin, Double colorMax, Palette palette, boolean reversed) {

    /** As the picture was before metrics: height by level, sides in the colours of the boxes. */
    public static final MetricMapping DEFAULT =
            new MetricMapping(Metric.LEVEL, Scale.LINEAR, 25, null, null, null, Palette.GREEN_RED, false);

    public static final double MIN_HEIGHT_PERCENT = 2;
    public static final double MAX_HEIGHT_PERCENT = 100;

    public MetricMapping {
        height = height == null ? Metric.LEVEL : height;
        scale = scale == null ? Scale.LINEAR : scale;
        heightPercent = Math.clamp(heightPercent, MIN_HEIGHT_PERCENT, MAX_HEIGHT_PERCENT);
        palette = palette == null ? Palette.GREEN_RED : palette;
    }

    public enum Scale {
        LINEAR("lin"), SQRT("√"), LOG("log");

        private final String label;

        Scale(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Where {@code value} lies between {@code min} and {@code max}, 0 to 1. */
        public double position(double value, double min, double max) {
            if (Double.isNaN(value)) {
                return 0;
            }
            if (max <= min) {
                // All shown values are the same: full if there is something, nothing if not.
                return value > 0 ? 1 : 0;
            }
            double t = Math.clamp((value - min) / (max - min), 0, 1);
            return switch (this) {
                case LINEAR -> t;
                case SQRT -> Math.sqrt(t);
                case LOG -> Math.log1p(t * (max - min)) / Math.log1p(max - min);
            };
        }
    }

    public enum Palette {
        GREEN_RED("Green → red"), VIRIDIS("Viridis"), BLUE_RED("Blue → red");

        private final String label;

        Palette(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public MetricMapping withHeight(Metric metric) {
        return new MetricMapping(metric, scale, heightPercent, color, colorMin, colorMax, palette, reversed);
    }

    public MetricMapping withScale(Scale newScale) {
        return new MetricMapping(height, newScale, heightPercent, color, colorMin, colorMax, palette, reversed);
    }

    public MetricMapping withHeightPercent(double newHeightPercent) {
        return new MetricMapping(height, scale, newHeightPercent, color, colorMin, colorMax, palette, reversed);
    }

    /** Another metric for the colour; its own range, so the limits start out free. */
    public MetricMapping withColor(Metric metric) {
        return new MetricMapping(height, scale, heightPercent, metric, null, null, palette, reversed);
    }

    public MetricMapping withColorRange(Double min, Double max) {
        return new MetricMapping(height, scale, heightPercent, color, min, max, palette, reversed);
    }

    public MetricMapping withPalette(Palette newPalette) {
        return new MetricMapping(height, scale, heightPercent, color, colorMin, colorMax, newPalette, reversed);
    }

    public MetricMapping withReversed(boolean newReversed) {
        return new MetricMapping(height, scale, heightPercent, color, colorMin, colorMax, palette, newReversed);
    }
}
