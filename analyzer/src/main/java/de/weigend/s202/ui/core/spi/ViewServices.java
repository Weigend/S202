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

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.canvas.WhatIfUndoManager;
import javafx.scene.Node;
import javafx.stage.Window;

import java.util.List;
import java.util.Set;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Die Dienste, die der {@code ArchitectureCanvas}-Canvas jeder Stil-Ansicht
 * beim Erzeugen mitgibt — ein Parameterobjekt statt des früheren
 * Supplier-Geflechts durch die Builder-Konstruktoren.
 *
 * @param elementRegistry     FQN → Szene-Knoten der aktuellen Ansicht
 * @param selectionSink       Klick-Selektionen aus dem Graphen
 * @param annotations         aktuelle Architektur-Annotations (lesend)
 * @param annotationsChanged  Ansicht meldet geänderte Annotations (+ Statuszeile)
 * @param rawDependencyModel  rohes Bytecode-Modell der Analyse
 * @param architecture        aktuelle Architektur-Projektion des Stils
 * @param markArrowsDirty     Kanten-Overlay zum Neuzeichnen anstoßen
 * @param skipTransparentTopLevelPackages Namespace-Wrapper oben überspringen?
 * @param dialogOwner         Fenster für Dialoge der Ansicht (kann null sein)
 * @param status              Statuszeilen-Senke
 * @param refreshView         Stil-Projektion neu aufbauen (Statusmeldung optional)
 * @param whatIfMoves         effektive What-If-Moves der Session (für Reports)
 * @param appliedCutEdges     aktuell geschnittene Preview-Kanten (für Reports)
 */
public record ViewServices(
        Map<String, Node> elementRegistry,
        Consumer<String> selectionSink,
        Supplier<ArchitectureAnnotations> annotations,
        BiConsumer<ArchitectureAnnotations, String> annotationsChanged,
        Supplier<DependencyModel> rawDependencyModel,
        Supplier<Architecture> architecture,
        Runnable markArrowsDirty,
        Supplier<Boolean> skipTransparentTopLevelPackages,
        Supplier<Window> dialogOwner,
        Consumer<String> status,
        Consumer<String> refreshView,
        Supplier<List<WhatIfUndoManager.Move>> whatIfMoves,
        Supplier<Set<DependencyEdge>> appliedCutEdges) {
}
