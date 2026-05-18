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
package de.weigend.s202.ui.zoom;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.scene.layout.Pane;

import java.util.Objects;

/**
 * Controls zoom functionality for the architecture view.
 * Manages zoom level, dynamic stepping, and coordinate transformations.
 *
 * <p>Features:
 * <ul>
 *   <li>Zoom range: 2% to 300% (for large architectures like Minecraft)</li>
 *   <li>Dynamic stepping: smaller steps at lower zoom levels for finer control</li>
 *   <li>Automatic label updates</li>
 *   <li>Callback notification on zoom changes</li>
 * </ul>
 */
public class ZoomController {

    private static final double ZOOM_MIN = 0.02;  // 2% - für sehr große Architekturen wie Minecraft
    private static final double ZOOM_MAX = 3.0;   // 300%
    private static final double ZOOM_STEP = 0.1;  // 10% default step

    private final ReadOnlyDoubleWrapper zoomFactorProperty = new ReadOnlyDoubleWrapper(1.0);
    private final Pane zoomableContent;
    private final Runnable onZoomChanged;

    /**
     * Creates a new ZoomController.
     *
     * @param zoomableContent Pane to apply zoom transformations to
     * @param onZoomChanged Callback invoked after zoom changes (for line invalidation)
     */
    public ZoomController(Pane zoomableContent, Runnable onZoomChanged) {
        this.zoomableContent = Objects.requireNonNull(zoomableContent, "zoomableContent cannot be null");
        this.onZoomChanged = onZoomChanged; // May be null
    }

    /**
     * Observable zoom factor (1.0 = 100%). Bind UI labels/displays to this.
     */
    public ReadOnlyDoubleProperty zoomFactorProperty() {
        return zoomFactorProperty.getReadOnlyProperty();
    }

    /**
     * Zooms in by dynamic step (smaller steps at lower zoom levels).
     */
    public void zoomIn() {
        setZoom(zoomFactorProperty.get() + getDynamicZoomStep());
    }

    /**
     * Zooms out by dynamic step (smaller steps at lower zoom levels).
     */
    public void zoomOut() {
        setZoom(zoomFactorProperty.get() - getDynamicZoomStep());
    }

    /**
     * Resets zoom to 100%.
     */
    public void resetZoom() {
        setZoom(1.0);
    }

    /**
     * Sets the zoom factor to a specific value.
     * Value will be clamped to valid range [ZOOM_MIN, ZOOM_MAX].
     *
     * @param newZoom Desired zoom factor (1.0 = 100%)
     */
    public void setZoom(double newZoom) {
        // Clamp to valid range
        double zoomFactor = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, newZoom));
        zoomFactorProperty.set(zoomFactor);

        // Apply scale via CSS transform on the content
        zoomableContent.setScaleX(zoomFactor);
        zoomableContent.setScaleY(zoomFactor);

        // Adjust translation to keep top-left corner anchored
        double width = zoomableContent.getBoundsInLocal().getWidth();
        double height = zoomableContent.getBoundsInLocal().getHeight();
        zoomableContent.setTranslateX((zoomFactor - 1) * width / 2);
        zoomableContent.setTranslateY((zoomFactor - 1) * height / 2);

        // Notify callback (for line invalidation/redrawing)
        if (onZoomChanged != null) {
            Platform.runLater(onZoomChanged);
        }
    }

    /**
     * Returns the current zoom factor.
     *
     * @return Zoom factor (1.0 = 100%)
     */
    public double getZoomFactor() {
        return zoomFactorProperty.get();
    }

    /**
     * Calculates dynamic zoom step based on current zoom level.
     * Smaller steps at lower zoom levels for finer control.
     *
     * @return Step size for zoom in/out operations
     */
    private double getDynamicZoomStep() {
        double f = zoomFactorProperty.get();
        if (f <= 0.1) {
            return 0.02;  // 2% steps when very zoomed out
        } else if (f <= 0.3) {
            return 0.05;  // 5% steps
        } else {
            return ZOOM_STEP;  // 10% steps at normal zoom
        }
    }
}
