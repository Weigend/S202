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

import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.ui.core.canvas.ArchitectureTreeBuilder;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.VBox;

import java.util.List;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Eine Stil-Ansicht der Architektur (Layered, Component, Hexagonal, …) als
 * Plugin: baut ihren Szenen-Inhalt, benennt ihre Verstoß-Arten und erhält
 * ihre Expand-Zustände über Rebuilds. Ersetzt den früheren
 * {@code ArchitectureKind}-Modus-Switch im Canvas — das UI-Gegenstück
 * zum Domain-SPI {@code ArchitectureStyle}.
 *
 * <p>Instanzen sind pro Canvas (via {@link StyleViewFactory}) und dürfen
 * ansichtsspezifischen Zustand halten (z. B. Expand-Zustände der
 * Hexagonal-Overlays).</p>
 */
public interface StyleView {

    ArchitectureKind kind();

    VBox buildTree(ArchitectureNode root, int maxDepth);

    void buildTreeAsync(ArchitectureNode root,
                        int maxDepth,
                        ArchitectureTreeBuilder.ProgressSink progressSink,
                        Consumer<VBox> onComplete);

    /** Verstoß-Arten, die das What-If-/Verstoß-Overlay für diesen Stil zeichnet. */
    Set<ViolationKind> violationOverlayKinds();

    /**
     * Ob das Verstoß-Overlay bevorzugt aus der What-If-Architektur gespeist
     * wird (Layered) statt aus der Original-Projektion (Component/Hexagonal).
     */
    default boolean usesWhatIfViolationSource() {
        return false;
    }

    /**
     * Stil-spezifische Expand-Zustände VOR einem Rebuild einsammeln. Der
     * generische Paket-Box-Anteil läuft im Canvas; hier ergänzen Ansichten
     * ihre eigenen Knoten (z. B. Komponenten-Boxen mit API-Bereich).
     *
     * @param node       aktueller Szene-Knoten des Walks
     * @param byFqn      geteilter FQN→expanded-Fallback (Rollenwechsel überlebt)
     * @param styleState frei nutzbarer Zustands-Speicher dieser Ansicht
     */
    default void collectStyleExpansion(Node node,
                                       Map<String, Boolean> byFqn,
                                       Map<String, Object> styleState) {
    }

    /** Gegenstück zu {@link #collectStyleExpansion} NACH dem Rebuild. */
    default void restoreStyleExpansion(Node node,
                                       Map<String, Boolean> byFqn,
                                       Map<String, Object> styleState) {
    }

    /**
     * Läuft nach jedem Content-Build (inkl. Expansion-Restore): Ansichten
     * dekorieren hier ihren Top-Level-Container (z. B. geplante Pakete der
     * Component-Ansicht).
     */
    default void afterContentBuilt(VBox topLevelContainer) {
    }

    /**
     * Stil-spezifische Einträge fürs Kontextmenü des Canvas — pro Öffnen
     * frisch erzeugt. Leer = kein Beitrag.
     */
    default List<MenuItem> contextMenuItems() {
        return List.of();
    }
}
