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
package de.weigend.s202.ui.core.platform;

import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.PhysicalHierarchy;
import de.weigend.s202.domain.SliceHierarchy;
import de.weigend.s202.domain.ShownNames;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Eine geladene Analyse: das, was aus einem Satz JARs, einem Source-Root oder
 * einer Projektdatei entstanden ist. Jeder Architektur-Tab — die Schichten-
 * Sicht, Component- und Hexagonal-Projektion, Scope- und Tangle-Tabs — zeigt
 * genau ein Dokument, und mehrere Tabs können dasselbe zeigen.
 * <p>
 * Das Dokument besitzt, was allen seinen Tabs gemeinsam ist: die Modelle der
 * Analyse (unveränderlich) sowie die Architektur-Annotationen und die
 * Preview-Cuts des Benutzers (veränderlich). Was nur einen Tab betrifft —
 * Selektion, Zoom, What-If-Verschiebungen —, bleibt im
 * {@link de.weigend.s202.ui.core.canvas.ArchitectureCanvas}.
 * <p>
 * Ein Tab meldet sein Dokument über seinen wfx-Kontext
 * ({@link ArchitectureWfxView#getLookup()}); „das aktive Dokument“ ist das im
 * aktiven Kontext des WindowManagers. Niemand hält Dokumente in einer Liste:
 * Schließt der letzte Tab, ist das Dokument frei.
 */
public final class AnalysisDocument {

    private final String name;
    private final S202Project.Source source;
    private final DependencyModel rawModel;
    private final DomainModel domainModel;
    private final ArchitectureNode rootNode;
    private final QualityMetrics metrics;
    private final LayoutInvariantReport invariants;
    private final Set<DependencyEdge> cycleBreakEdges;

    private ArchitectureAnnotations annotations = ArchitectureAnnotations.empty();
    private final Set<DependencyEdge> previewCuts = new LinkedHashSet<>();
    private DerivedAnalysis physical;
    private final java.util.Map<Integer, DerivedAnalysis> slices = new java.util.HashMap<>();
    private java.util.List<SliceHierarchy.Level> sliceLevels;
    private Integer files;

    public AnalysisDocument(String name,
                            S202Project.Source source,
                            DependencyModel rawModel,
                            DomainModel domainModel,
                            ArchitectureNode rootNode,
                            QualityMetrics metrics,
                            LayoutInvariantReport invariants,
                            Set<DependencyEdge> cycleBreakEdges) {
        this.name = Objects.requireNonNull(name, "name");
        this.source = Objects.requireNonNull(source, "source");
        this.rawModel = Objects.requireNonNull(rawModel, "rawModel");
        this.domainModel = Objects.requireNonNull(domainModel, "domainModel");
        this.rootNode = Objects.requireNonNull(rootNode, "rootNode");
        this.metrics = metrics;
        this.invariants = invariants;
        this.cycleBreakEdges = cycleBreakEdges == null ? Set.of() : cycleBreakEdges;
    }

    /** Anzeigename, eindeutig unter den offenen Dokumenten; Präfix aller Tab-Titel. */
    public String name() {
        return name;
    }

    /** Woraus die Analyse entstand (Art, Pfade) — für Projektdatei und Report. */
    public S202Project.Source source() {
        return source;
    }

    public DependencyModel rawModel() {
        return rawModel;
    }

    public DomainModel domainModel() {
        return domainModel;
    }

    /** Der vollständige Architekturbaum der Analyse, wie ihn die Schichten-Sicht zeigt. */
    public ArchitectureNode rootNode() {
        return rootNode;
    }

    public QualityMetrics metrics() {
        return metrics;
    }

    /** Ergebnis der Layout-Invarianten-Prüfung; {@code null}, wenn keine lief. */
    public LayoutInvariantReport invariants() {
        return invariants;
    }

    public Set<DependencyEdge> cycleBreakEdges() {
        return cycleBreakEdges;
    }

    /** Whether the classes come from at least two files: only then has the Physical View something to show. */
    public synchronized boolean hasPhysicalView() {
        if (files == null) {
            files = PhysicalHierarchy.files(rawModel);
        }
        return files >= 2;
    }

    /**
     * The analysis with the classes ordered by their files; computed when it
     * is asked for the first time, which takes as long as the analysis did.
     */
    public synchronized DerivedAnalysis physical() {
        if (physical == null) {
            physical = DerivedAnalysis.physical(rawModel);
        }
        return physical;
    }

    /**
     * The analysis with the packages of {@code depth} side by side, a
     * horizontal slice; computed when it is asked for the first time.
     */
    public synchronized DerivedAnalysis slice(int depth) {
        return slices.computeIfAbsent(depth, d -> DerivedAnalysis.slice(rawModel, d));
    }

    /** Every depth of the packages, with how many there are and how tangled they are. */
    public synchronized java.util.List<SliceHierarchy.Level> sliceLevels() {
        if (sliceLevels == null) {
            sliceLevels = SliceHierarchy.levels(rawModel,
                    io.softwareecg.wfx.lookup.api.Lookup.require(de.weigend.s202.domain.SCCFinder.class));
        }
        return sliceLevels;
    }

    /* ----- Veränderlicher, von allen Tabs geteilter Zustand ----------------- */

    public ArchitectureAnnotations annotations() {
        return annotations;
    }

    public void setAnnotations(ArchitectureAnnotations annotations) {
        this.annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
    }

    /**
     * Die im Refactoring-Preview „geschnittenen“ Kanten dieses Dokuments,
     * unter den Namen der Analyse - auch wenn sie in einer Physical View
     * geschnitten wurden.
     */
    public Set<DependencyEdge> previewCuts() {
        return Collections.unmodifiableSet(previewCuts);
    }

    /** @return {@code false}, wenn die Kante schon geschnitten war */
    public boolean addPreviewCut(DependencyEdge edge) {
        return previewCuts.add(logical(edge));
    }

    /** @return {@code false}, wenn keine der Kanten neu war */
    public boolean addPreviewCuts(Collection<DependencyEdge> edges) {
        return previewCuts.addAll(edges.stream().map(AnalysisDocument::logical).toList());
    }

    /** @return {@code false}, wenn die Kante nicht geschnitten war */
    public boolean removePreviewCut(DependencyEdge edge) {
        return previewCuts.remove(logical(edge));
    }

    private static DependencyEdge logical(DependencyEdge edge) {
        return new DependencyEdge(ShownNames.toLogical(edge.from()), ShownNames.toLogical(edge.to()));
    }

    public void clearPreviewCuts() {
        previewCuts.clear();
    }

    @Override
    public String toString() {
        return "AnalysisDocument[" + name + "]";
    }
}
