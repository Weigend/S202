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
package de.weigend.s202.ui.core.spi;

import de.weigend.s202.domain.DependencyEdge;
import javafx.scene.layout.Pane;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Vertrag des angepinnten Kanten-Overlays auf dem Canvas (heute: die
 * Tangle-Kanten-Visualisierung). Der Kern hält Zustand und Delegation über
 * dieses Interface; die konkrete Renderer-Pipeline liefert die
 * Tangle-Komponente über die {@link EdgeOverlayRendererFactory}.
 */
public interface EdgeOverlayRenderer {

    void setCoordinateContext(Pane zoomableContent, Pane overlayPane);

    void setOnEdgeClicked(BiConsumer<String, String> handler);

    void setOnEdgeCut(BiConsumer<String, String> handler);

    void setOnEdgeRestore(BiConsumer<String, String> handler);

    void setEdges(List<DependencyEdge> edges);

    void setSelectedEdge(String from, String to);

    void setCycleBreakEdges(Set<DependencyEdge> cycleBreakEdges);

    void setAppliedCutEdges(Set<DependencyEdge> appliedCutEdges);

    void setShowDebugLines(boolean showDebugLines);

    void clear();
}
