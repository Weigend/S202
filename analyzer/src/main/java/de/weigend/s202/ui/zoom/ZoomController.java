package de.weigend.s202.ui.zoom;

import javafx.application.Platform;
import javafx.scene.control.Label;
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

    private double zoomFactor = 1.0;

    private static final double ZOOM_MIN = 0.02;  // 2% - für sehr große Architekturen wie Minecraft
    private static final double ZOOM_MAX = 3.0;   // 300%
    private static final double ZOOM_STEP = 0.1;  // 10% default step

    private final Label zoomLabel;
    private final Pane zoomableContent;
    private final Runnable onZoomChanged;

    /**
     * Creates a new ZoomController.
     *
     * @param zoomLabel Label to display current zoom percentage
     * @param zoomableContent Pane to apply zoom transformations to
     * @param onZoomChanged Callback invoked after zoom changes (for line invalidation)
     */
    public ZoomController(Label zoomLabel, Pane zoomableContent, Runnable onZoomChanged) {
        this.zoomLabel = Objects.requireNonNull(zoomLabel, "zoomLabel cannot be null");
        this.zoomableContent = Objects.requireNonNull(zoomableContent, "zoomableContent cannot be null");
        this.onZoomChanged = onZoomChanged; // May be null
    }

    /**
     * Zooms in by dynamic step (smaller steps at lower zoom levels).
     */
    public void zoomIn() {
        setZoom(zoomFactor + getDynamicZoomStep());
    }

    /**
     * Zooms out by dynamic step (smaller steps at lower zoom levels).
     */
    public void zoomOut() {
        setZoom(zoomFactor - getDynamicZoomStep());
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
        zoomFactor = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, newZoom));

        // Apply scale via CSS transform on the content
        zoomableContent.setScaleX(zoomFactor);
        zoomableContent.setScaleY(zoomFactor);

        // Adjust translation to keep top-left corner anchored
        double width = zoomableContent.getBoundsInLocal().getWidth();
        double height = zoomableContent.getBoundsInLocal().getHeight();
        zoomableContent.setTranslateX((zoomFactor - 1) * width / 2);
        zoomableContent.setTranslateY((zoomFactor - 1) * height / 2);

        // Update label
        zoomLabel.setText(String.format("%d%%", Math.round(zoomFactor * 100)));

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
        return zoomFactor;
    }

    /**
     * Calculates dynamic zoom step based on current zoom level.
     * Smaller steps at lower zoom levels for finer control.
     *
     * @return Step size for zoom in/out operations
     */
    private double getDynamicZoomStep() {
        if (zoomFactor <= 0.1) {
            return 0.02;  // 2% steps when very zoomed out
        } else if (zoomFactor <= 0.3) {
            return 0.05;  // 5% steps
        } else {
            return ZOOM_STEP;  // 10% steps at normal zoom
        }
    }
}
