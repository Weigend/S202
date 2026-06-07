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

    void setSelectedFullName(String selectedFullName);

    void drawDependencyArrows(ArchitectureNode rootNode);

    void clearDependencyArrows();

    boolean isDependencyLinesDrawn();
}
