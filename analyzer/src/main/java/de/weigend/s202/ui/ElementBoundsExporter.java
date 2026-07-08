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
package de.weigend.s202.ui;

import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exportiert die Layout-/Szenen-Bounds aller registrierten Elemente — die
 * Datenquelle für die 3D-Ansicht und die Report-Screenshots. Reiner Leser
 * von elementRegistry und zoomableContent; aus ArchitectureView extrahiert.
 */
final class ElementBoundsExporter {

    private final Map<String, Node> elementRegistry;
    /** Der Content-Knoten wird bei jedem Root-Build neu erzeugt — daher Supplier. */
    private final Supplier<Pane> zoomableContent;
    private final Supplier<Scene> scene;

    ElementBoundsExporter(Map<String, Node> elementRegistry,
                          Supplier<Pane> zoomableContent,
                          Supplier<Scene> scene) {
        this.elementRegistry = elementRegistry;
        this.zoomableContent = zoomableContent;
        this.scene = scene;
    }

    private void forceLayout() {
        Scene s = scene.get();
        if (s != null && s.getRoot() != null) {
            s.getRoot().layout();
        }
    }

    /**
     * Returns the layout bounds of every registered element in the JavaFX
     * scene coordinate space. Forces a layout pass so bounds are valid.
     * Must be called on the JavaFX Application Thread after the scene is shown.
     */
    Map<String, Bounds> elementBoundsInScene() {
        forceLayout();
        var result = new LinkedHashMap<String, Bounds>();
        for (var entry : elementRegistry.entrySet()) {
            var node = entry.getValue();
            if (node.getScene() == null || !node.isVisible()) continue;
            result.put(entry.getKey(), node.localToScene(node.getBoundsInLocal()));
        }
        return result;
    }

    /**
     * Returns 3D footprint bounds for registered package/class boxes. Helper
     * registry entries for transparent parent containers are filtered out.
     */
    Map<String, Bounds> elementFootprintBoundsInScene() {
        forceLayout();
        var result = new LinkedHashMap<String, Bounds>();
        for (var entry : elementRegistry.entrySet()) {
            var node = entry.getValue();
            if (node.getScene() == null || !node.isVisible()) continue;
            Bounds bounds = footprintBoundsInScene(node);
            if (bounds != null) {
                result.put(entry.getKey(), bounds);
            }
        }
        return result;
    }

    /**
     * Returns 3D footprint bounds in the view's unscaled layout coordinate
     * space. Unlike scene coordinates, these stay stable when the 2D
     * ScrollPane moves or the application window changes focus.
     */
    Map<String, Bounds> elementFootprintBoundsInLayout() {
        forceLayout();
        var result = new LinkedHashMap<String, Bounds>();
        for (var entry : elementRegistry.entrySet()) {
            var node = entry.getValue();
            if (node.getScene() == null || !node.isVisible()) continue;
            Bounds bounds = footprintBoundsInLayout(node);
            if (bounds != null) {
                result.put(entry.getKey(), bounds);
            }
        }
        return result;
    }

    /**
     * Returns the closest visible package parent per currently registered
     * package/class box. Used by projections such as the 3D view that need to
     * roll hidden class-level edges up to the same visible endpoint as the 2D
     * scene, including after What-If drag-and-drop moves.
     */
    Map<String, String> visibleElementParentFqns() {
        forceLayout();
        var result = new LinkedHashMap<String, String>();
        for (var entry : elementRegistry.entrySet()) {
            Node node = entry.getValue();
            if (!(node instanceof LevelPackageBox || node instanceof LevelClassBox)) continue;
            if (node.getScene() == null) continue;
            String parent = nearestVisiblePackageParent(node.getParent());
            if (parent != null) {
                result.put(entry.getKey(), parent);
            }
        }
        return result;
    }

    private static Bounds footprintBoundsInScene(Node node) {
        if (node instanceof LevelPackageBox || node instanceof LevelClassBox) {
            return node.localToScene(node.getBoundsInLocal());
        }
        return null;
    }

    private Bounds footprintBoundsInLayout(Node node) {
        if (!(node instanceof LevelPackageBox || node instanceof LevelClassBox)) {
            return null;
        }
        Pane content = zoomableContent.get();
        if (content == null || content.getScene() == null) {
            return footprintBoundsInScene(node);
        }
        return content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
    }

    private static String nearestVisiblePackageParent(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof LevelPackageBox pkg && isActuallyVisible(current)) {
                return pkg.getFullName();
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isActuallyVisible(Node node) {
        if (node == null || !node.isVisible()) {
            return false;
        }
        Parent parent = node.getParent();
        while (parent != null) {
            if (!parent.isVisible()) {
                return false;
            }
            parent = parent.getParent();
        }
        return true;
    }
}
