package de.weigend.s202.ui.rendering;

import de.weigend.s202.ui.model.ArchitectureNode;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;

/**
 * Strategy interface for rendering dependency arrows between architecture elements.
 * Allows switching between different visualization styles (e.g. straight lines vs.
 * circuit-board / street-map style routing) without touching the surrounding view.
 */
public interface DependencyRendererStrategy {

    void setCoordinateContext(Pane zoomableContent, Pane overlayPane, ScrollPane scrollPane);

    void drawDependencyArrows(ArchitectureNode rootNode);

    void clearDependencyArrows();

    boolean isDependencyLinesDrawn();
}
