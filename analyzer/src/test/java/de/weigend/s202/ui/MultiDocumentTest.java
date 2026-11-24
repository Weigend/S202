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

import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.events.CutTangleEdgeEvent;
import de.weigend.s202.ui.core.events.OpenTangleEvent;
import de.weigend.s202.ui.core.events.RestoreTangleEdgeEvent;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.core.platform.AnalysisDocument;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import de.weigend.s202.ui.core.platform.Refactorings;
import de.weigend.s202.ui.core.platform.ViewDescriptor;
import de.weigend.s202.ui.features.outline.OutlineExplorerModule;
import de.weigend.s202.ui.features.outline.OutlineExplorerView;
import de.weigend.s202.ui.features.quality.QualityModule;
import de.weigend.s202.ui.features.quality.QualityView;
import de.weigend.s202.ui.features.whatif.WhatIfDependenciesModule;
import de.weigend.s202.ui.features.whatif.WhatIfDependenciesView;
import de.weigend.s202.ui.shell.ProjectPersistenceController;
import de.weigend.s202.ui.shell.S202Module;
import de.weigend.s202.ui.views.tangle.TopTanglesModule;
import de.weigend.s202.ui.views.tangle.TopTanglesView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EventObject;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Zwei geladene Analysen nebeneinander: Jeder Tab gehört zu genau einem
 * {@link AnalysisDocument}, die Panels zeigen das Dokument, an dem der
 * Benutzer arbeitet, und was am Dokument hängt — abgeleitete Tabs, Tangle-Tabs,
 * Preview-Cuts, Annotationen —, bleibt bei diesem Dokument.
 * <p>
 * Läuft gegen den echten wfx-WindowManager und die echten Panel-Module
 * (Outline, Quality, Top Tangles, What-If), nur ohne Hauptfenster.
 */
class MultiDocumentTest {

    private static final Duration FX_TIMEOUT = Duration.ofSeconds(20);
    private static final String EXAMPLE_JAR = "../test-example/target/test-example-1.4.0.jar";
    private static final String HEXAGON_JAR = "../test-hexagon/target/test-hexagon-1.4.0.jar";

    private static boolean javaFxStarted;
    private static List<Module> panels;
    /** The views registered once the modules have started, before any analysis is open. */
    private static List<String> panelsAtStartup;

    private ArchitectureViewManager viewManager;
    private WindowManager windowManager;
    private EventBus<EventObject> bus;

    private AnalysisDocument example;
    private AnalysisDocument hexagon;
    private ArchitectureWfxView exampleView;
    private ArchitectureWfxView hexagonView;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void startWorkbench() throws Exception {
        Lookup.init();
        javaFxStarted = startJavaFx();
        Assumptions.assumeTrue(javaFxStarted, "JavaFX toolkit is unavailable");

        // The panel modules in their start order. preload() sleeps, so it
        // stays off the JavaFX thread — as in the application.
        List<Module> all = Lookup.lookupAll(Module.class);
        panels = List.of(
                module(all, OutlineExplorerModule.class),
                module(all, QualityModule.class),
                module(all, TopTanglesModule.class),
                module(all, WhatIfDependenciesModule.class));
        for (Module panel : panels) {
            panel.preload();
        }
        onFx(() -> {
            panels.forEach(Module::start);
            panelsAtStartup = Lookup.lookup(WindowManager.class).getRegisteredViews().stream()
                    .map(View::getViewId)
                    .toList();
        });
    }

    @AfterAll
    static void stopWorkbench() {
        if (javaFxStarted && panels != null) {
            onFx(() -> panels.forEach(Module::stop));
        }
        Lookup.shutdown();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void openTwoDocuments() throws Exception {
        Assumptions.assumeTrue(javaFxStarted, "JavaFX toolkit is unavailable");
        viewManager = Lookup.lookup(ArchitectureViewManager.class);
        windowManager = Lookup.lookup(WindowManager.class);
        bus = Lookup.lookup(EventBus.class);
        example = analyze("example.jar", EXAMPLE_JAR);
        hexagon = analyze("hexagon.jar", HEXAGON_JAR);
        onFx(() -> {
            exampleView = open(example);
            hexagonView = open(hexagon);
        });
    }

    @AfterEach
    void closeDocuments() {
        if (javaFxStarted && viewManager != null) {
            onFx(() -> viewManager.closeAllViews());
        }
    }

    /* ----- Die Panels ---------------------------------------------------------- */

    /**
     * Every panel is registered before the first analysis is opened. Only
     * then can the layout of the last session, which is put back at startup,
     * place all of them — the Dependencies panel included, which sits under
     * the editor area and used to be docked to the first architecture tab.
     */
    @Test
    void everyPanelIsRegisteredBeforeAnAnalysisIsOpen() {
        assertEquals(Set.of(OutlineExplorerView.VIEW_ID, QualityView.VIEW_ID, TopTanglesView.VIEW_ID,
                WhatIfDependenciesView.VIEW_ID), Set.copyOf(panelsAtStartup));
    }

    /* ----- Welches Dokument ist gemeint? ------------------------------------ */

    @Test
    void aNewlyOpenedDocumentIsInFrontAndActive() {
        onFx(() -> {
            assertSame(hexagon, viewManager.activeDocument());
            assertSame(hexagonView.getArchitectureView(), viewManager.activeCanvas());
            assertTrue(windowManager.getVisibleViews().contains(hexagonView),
                    "the document the panels show must be the one in front");
            assertFalse(windowManager.getVisibleViews().contains(exampleView));
            assertPanelsShow(hexagonView.getArchitectureView());
        });
    }

    @Test
    void panelsFollowTheDocumentTheUserWorksIn() {
        onFx(() -> {
            windowManager.setFocusedView(exampleView);

            assertSame(example, viewManager.activeDocument());
            assertPanelsShow(exampleView.getArchitectureView());
        });
    }

    @Test
    void workingInAPanelKeepsItsDocument() {
        onFx(() -> {
            windowManager.setFocusedView(exampleView);
            View outline = windowManager.findView(OutlineExplorerView.VIEW_ID);

            windowManager.setFocusedView(outline);

            assertSame(example, viewManager.activeDocument(),
                    "the outline shows the example; clicking into it must not change that");
            assertPanelsShow(exampleView.getArchitectureView());
        });
    }

    @Test
    void closingTheActiveDocumentLeavesTheOtherActive() {
        onFx(() -> {
            windowManager.closeView(hexagonView);

            assertSame(example, viewManager.activeDocument());
            assertPanelsShow(exampleView.getArchitectureView());

            windowManager.closeView(exampleView);

            assertNull(viewManager.activeDocument());
            assertPanelsShow(null);
        });
    }

    /* ----- Abgeleitete Tabs gehören zum aktiven Dokument --------------------- */

    @Test
    void aProjectionOpenedFromAPanelShowsThePanelsDocument() {
        AtomicReference<ArchitectureWfxView> projection = new AtomicReference<>();
        onFx(() -> {
            windowManager.setFocusedView(exampleView);
            // The user selects something in the outline, then opens the view from the menu.
            windowManager.setFocusedView(windowManager.findView(OutlineExplorerView.VIEW_ID));

            viewManager.openStyleView(ArchitectureKind.COMPONENT);

            projection.set(newestViewOf(example));
        });
        awaitRoot(projection.get());
        onFx(() -> {
            assertSame(example, projection.get().getDocument());
            assertEquals("example.jar · Component View", projection.get().getTitle());
            assertEquals(1, viewManager.viewsOf(hexagon).size(), "the other document got no tab");
            assertSame(example.domainModel(), projection.get().getArchitectureView().getDomainModel());
        });
    }

    @Test
    void aProjectionOpenedFromATangleTabShowsThatTabsDocument() {
        AtomicReference<ArchitectureWfxView> projection = new AtomicReference<>();
        onFx(() -> {
            // hexagon.jar is the second document: "the first architecture
            // view" would be the wrong one.
            bus.publish(new OpenTangleEvent(someClassesOf(hexagon), "tangle", "Tangle", this));
            ArchitectureWfxView tangleTab = newestViewOf(hexagon);
            assertSame(tangleTab.getArchitectureView(), viewManager.activeCanvas());

            viewManager.openStyleView(ArchitectureKind.COMPONENT);

            projection.set(newestViewOf(hexagon));
            assertNotSame(tangleTab, projection.get());
        });
        awaitRoot(projection.get());
        onFx(() -> {
            assertSame(hexagon, projection.get().getDocument());
            assertEquals(1, viewManager.viewsOf(example).size(), "the other document got no tab");
            assertSame(hexagon.rootNode(), projection.get().getArchitectureView().getArchitectureRoot(),
                    "from a tangle tab the projection shows the whole document, not the excerpt");
        });
    }

    @Test
    void resettingTheRefactoringsOfADocumentLeavesTheOthersAlone() {
        AtomicReference<ArchitectureWfxView> projection = new AtomicReference<>();
        onFx(() -> {
            windowManager.setFocusedView(hexagonView);
            viewManager.openStyleView(ArchitectureKind.COMPONENT);
            projection.set(newestViewOf(hexagon));
        });
        awaitRoot(projection.get());
        onFx(() -> {
            Refactorings refactorings = Lookup.lookup(Refactorings.class);
            ArchitectureCanvas component = projection.get().getArchitectureView();
            component.setArchitectureAnnotations(
                    component.getArchitectureAnnotations().withComponentApiIncluded("com.paperwhale.domain"));
            DependencyEdge cut = new DependencyEdge("com.paperwhale.a.A", "com.paperwhale.b.B");
            hexagon.addPreviewCut(cut);
            example.addPreviewCut(cut);
            assertEquals(new Refactorings.Counts(0, 0, 1, 1), refactorings.count(hexagon));
            assertEquals(Set.of("com.paperwhale.domain"),
                    refactorings.reportInput(hexagon).annotations().componentApiIncludes());
            DomainModel analyzed = hexagon.domainModel();

            refactorings.reset(hexagon);

            assertTrue(refactorings.count(hexagon).isEmpty());
            assertTrue(component.getArchitectureAnnotations().componentApiIncludes().isEmpty());
            assertTrue(hexagonView.getArchitectureView().getArchitectureAnnotations()
                    .componentApiIncludes().isEmpty(), "in all tabs of the document");
            assertSame(analyzed, component.getDomainModel(), "nothing was analyzed again");
            assertEquals(1, refactorings.count(example).cuts(), "the other document keeps its own");
        });
    }

    @Test
    void tangleTabsAreKeptPerDocument() {
        onFx(() -> {
            windowManager.setFocusedView(exampleView);
            bus.publish(new OpenTangleEvent(someClassesOf(example), "same key", "Tangle", this));
            ArchitectureWfxView exampleTangle = newestViewOf(example);
            assertEquals("same key", exampleTangle.getTangleKey());
            assertEquals("example.jar · Tangle", exampleTangle.getTitle());

            windowManager.setFocusedView(hexagonView);
            bus.publish(new OpenTangleEvent(someClassesOf(hexagon), "same key", "Tangle", this));
            ArchitectureWfxView hexagonTangle = newestViewOf(hexagon);

            assertNotSame(exampleTangle, hexagonTangle, "the same key in another document is another tab");
            assertSame(hexagon, hexagonTangle.getDocument());
            assertSame(hexagon.domainModel(), hexagonTangle.getArchitectureView().getDomainModel());

            // Asking for the example's tangle again brings its tab back instead of a third one.
            int tabs = viewManager.registeredArchitectureViews().size();
            windowManager.setFocusedView(exampleView);
            bus.publish(new OpenTangleEvent(someClassesOf(example), "same key", "Tangle", this));

            assertEquals(tabs, viewManager.registeredArchitectureViews().size());
            assertSame(exampleTangle.getArchitectureView(), viewManager.activeCanvas());
        });
    }

    /* ----- Zustand des Dokuments bleibt beim Dokument ------------------------ */

    @Test
    void aPreviewCutBelongsToTheDocumentOfTheViewItWasMadeIn() {
        onFx(() -> {
            // hexagon.jar is active, the cut is made in an arrow of the example's tab.
            bus.publish(new CutTangleEdgeEvent("a.From", "a.To", exampleView.getArchitectureView()));

            assertEquals(Set.of(new DependencyEdge("a.From", "a.To")), example.previewCuts());
            assertTrue(hexagon.previewCuts().isEmpty());

            bus.publish(new RestoreTangleEdgeEvent("a.From", "a.To", exampleView.getArchitectureView()));

            assertTrue(example.previewCuts().isEmpty());
        });
    }

    @Test
    void aPreviewCutMadeInAPanelBelongsToTheActiveDocument() {
        onFx(() -> {
            windowManager.setFocusedView(exampleView);

            bus.publish(new CutTangleEdgeEvent("a.From", "a.To", this));

            assertEquals(1, example.previewCuts().size());
            assertTrue(hexagon.previewCuts().isEmpty());
        });
    }

    @Test
    void openingAnotherDocumentKeepsTheCutsOfTheFirst() throws Exception {
        onFx(() -> bus.publish(new CutTangleEdgeEvent("a.From", "a.To", exampleView.getArchitectureView())));

        AnalysisDocument third = analyze("third.jar", HEXAGON_JAR);
        onFx(() -> {
            open(third);

            assertEquals(1, example.previewCuts().size());
            assertTrue(third.previewCuts().isEmpty());
        });
    }

    @Test
    void annotationsAreSharedByTheTabsOfOneDocumentOnly() {
        AtomicReference<ArchitectureWfxView> projection = new AtomicReference<>();
        onFx(() -> {
            windowManager.setFocusedView(exampleView);
            viewManager.openStyleView(ArchitectureKind.COMPONENT);
            projection.set(newestViewOf(example));
        });
        awaitRoot(projection.get());
        onFx(() -> {
            String someClass = someClassesOf(example).iterator().next();
            ArchitectureAnnotations marked = ArchitectureAnnotations.empty()
                    .withPort(someClass, ArchitectureAnnotations.PortDirection.INBOUND, null);

            projection.get().getArchitectureView().setArchitectureAnnotations(marked);

            assertEquals(marked, example.annotations(), "the document remembers them");
            assertEquals(marked, exampleView.getArchitectureView().getArchitectureAnnotations(),
                    "the other tab of the document follows");
            assertEquals(ArchitectureAnnotations.empty(),
                    hexagonView.getArchitectureView().getArchitectureAnnotations());
            assertEquals(ArchitectureAnnotations.empty(), hexagon.annotations());
        });
    }

    @Test
    void theSameSourceLoadedTwiceGetsADistinctName() {
        onFx(() -> {
            assertEquals("example.jar (2)", viewManager.uniqueDocumentName("example.jar"));
            assertEquals("other.jar", viewManager.uniqueDocumentName("other.jar"));
        });
    }

    /* ----- Projektdatei: ein gespeichertes Dokument --------------------------- */

    @Test
    void loadingAProjectAddsADocumentNextToTheOpenOnes(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("saved.s202.json");
        ProjectPersistenceController persistence = persistence();
        onFx(() -> persistence.saveProject(example, file));
        onFx(() -> bus.publish(new CutTangleEdgeEvent("a.From", "a.To", exampleView.getArchitectureView())));

        onFx(() -> persistence.loadProject(file));
        await(() -> viewManager.activeDocument() != null
                && viewManager.activeDocument().name().equals("saved")
                && viewManager.activeCanvas().hasRoot());

        onFx(() -> {
            assertEquals(3, viewManager.registeredArchitectureViews().size(), "the open documents stay");
            assertEquals(1, viewManager.viewsOf(example).size());
            assertEquals(1, viewManager.viewsOf(hexagon).size());
            assertEquals(1, example.previewCuts().size(), "and keep what was done to them");
            AnalysisDocument loaded = viewManager.activeDocument();
            assertEquals(example.domainModel().getAllClasses().keySet(),
                    loaded.domainModel().getAllClasses().keySet());
            assertTrue(loaded.previewCuts().isEmpty());
        });
    }

    @Test
    void aSavedProjectComesBackWithItsTabsAsTheUserLeftThem(@TempDir Path directory) {
        Path file = directory.resolve("saved.s202.json");
        ProjectPersistenceController persistence = persistence();
        String scopePackage = "com.example2";
        String addedToScope = "com.example1";
        AtomicReference<ArchitectureWfxView> component = new AtomicReference<>();
        AtomicReference<ArchitectureWfxView> scope = new AtomicReference<>();
        onFx(() -> {
            windowManager.setFocusedView(exampleView);
            viewManager.openStyleView(ArchitectureKind.COMPONENT);
            component.set(newestViewOf(example));
        });
        awaitRoot(component.get());
        onFx(() -> scope.set(viewManager.open(example, new ViewDescriptor(ViewDescriptor.Kind.SCOPE,
                "Scope example2", scopePackage, List.of(addedToScope), Set.of(), null, false))));
        awaitRoot(scope.get());

        Set<String> tangleMembers = someClassesOf(example);
        String selectedClass = tangleMembers.iterator().next();
        AtomicReference<Map<String, Boolean>> expansionBefore = new AtomicReference<>();
        onFx(() -> {
            windowManager.setFocusedView(exampleView);
            bus.publish(new OpenTangleEvent(tangleMembers, "the tangle", "Tangle T", this));
            bus.publish(new CutTangleEdgeEvent("a.From", "a.To", exampleView.getArchitectureView()));

            // The user works on the first tab: one level less, arrows on, a package closed, zoomed out.
            ArchitectureCanvas main = exampleView.getArchitectureView();
            main.setPackageDepth(2);
            main.refreshLayout();
            main.setShowDependencies(true);
            Map<String, Boolean> expansion = new HashMap<>(main.captureViewSnapshot().packageExpansion());
            String open = expansion.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey)
                    .sorted().findFirst().orElseThrow();
            expansion.put(open, false);
            main.restoreViewSnapshot(new ArchitectureCanvas.ViewSnapshot(0.5, 0, 0, expansion));
            expansionBefore.set(main.captureViewSnapshot().packageExpansion());
            assertEquals(false, expansionBefore.get().get(open), "the package is closed now");

            // ... and leaves from the Component tab, with a class selected there.
            windowManager.setFocusedView(component.get());
            component.get().getArchitectureView().selectByFullName(selectedClass);
            persistence.saveProject(example, file);
            viewManager.closeDocument(example);
            assertTrue(viewManager.viewsOf(example).isEmpty());
        });

        onFx(() -> persistence.loadProject(file));
        await(() -> {
            AnalysisDocument loaded = viewManager.activeDocument();
            return loaded != null && loaded.name().equals("saved")
                    && viewManager.viewsOf(loaded).size() == 4
                    && viewManager.viewsOf(loaded).stream().allMatch(v -> v.getArchitectureView().hasRoot())
                    && viewManager.wrapperOf(viewManager.activeCanvas()).getDescriptor().kind()
                            == ViewDescriptor.Kind.COMPONENT
                    && Math.abs(viewManager.viewsOf(loaded).get(0).getArchitectureView()
                            .captureViewSnapshot().zoom() - 0.5) < 0.001;
        });

        onFx(() -> {
            AnalysisDocument loaded = viewManager.activeDocument();
            List<ArchitectureWfxView> tabs = viewManager.viewsOf(loaded);
            assertEquals(List.of("saved", "saved \u00b7 Component View", "saved \u00b7 Scope example2",
                            "saved \u00b7 Tangle T"),
                    tabs.stream().map(ArchitectureWfxView::getTitle).toList());
            assertEquals(List.of(ViewDescriptor.Kind.LAYERED, ViewDescriptor.Kind.COMPONENT,
                            ViewDescriptor.Kind.SCOPE, ViewDescriptor.Kind.TANGLE),
                    tabs.stream().map(tab -> tab.getDescriptor().kind()).toList());

            ArchitectureCanvas main = tabs.get(0).getArchitectureView();
            assertEquals(2, main.getPackageDepth());
            assertTrue(main.isShowDependencies());
            assertEquals(expansionBefore.get(), main.captureViewSnapshot().packageExpansion(),
                    "the same packages are open and closed");

            ArchitectureCanvas componentTab = tabs.get(1).getArchitectureView();
            assertEquals(ArchitectureKind.COMPONENT, componentTab.getViewStyle());
            assertSame(componentTab, viewManager.activeCanvas(), "the tab the user left from is in front");
            assertEquals(selectedClass, componentTab.getSelectedFullName(), "with its selection");
            assertFalse(componentTab.isShowDependencies());

            ArchitectureWfxView scopeTab = tabs.get(2);
            assertEquals(scopePackage, scopeTab.getDescriptor().scope());
            assertEquals(List.of(addedToScope), scopeTab.getDescriptor().scopeExtensions());
            assertNotNull(find(scopeTab.getArchitectureView().getArchitectureRoot(), scopePackage));
            assertNotNull(find(scopeTab.getArchitectureView().getArchitectureRoot(), addedToScope),
                    "what was added to the scope is back in it");
            assertNull(find(scopeTab.getArchitectureView().getArchitectureRoot(), "sccs.api"),
                    "and nothing else");

            ArchitectureWfxView tangleTab = tabs.get(3);
            assertEquals("the tangle", tangleTab.getTangleKey());
            assertEquals(tangleMembers, tangleTab.getDescriptor().tangleMembers());
            assertNotNull(tangleTab.getArchitectureView().getTangleScope());
            assertTrue(tangleTab.getArchitectureView().isShowDependencies(), "a tangle tab shows its arrows");

            assertEquals(Set.of(new DependencyEdge("a.From", "a.To")), loaded.previewCuts());
            assertEquals(1, viewManager.viewsOf(hexagon).size(), "the other document is as it was");
        });
    }

    @Test
    void aProjectFileWithoutTabsOpensLikeAFreshAnalysis(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("plain.s202.json");
        new de.weigend.s202.project.impl.S202ProjectStore().save(file,
                new de.weigend.s202.project.impl.S202ProjectMapper().toProject("test", example.source(),
                        example.rawModel(), example.domainModel(), example.annotations(), null,
                        example.cycleBreakEdges()));
        ProjectPersistenceController persistence = persistence();

        onFx(() -> persistence.loadProject(file));
        await(() -> viewManager.activeDocument() != null
                && viewManager.activeDocument().name().equals("plain")
                && viewManager.activeCanvas().hasRoot());

        onFx(() -> {
            List<ArchitectureWfxView> tabs = viewManager.viewsOf(viewManager.activeDocument());
            assertEquals(1, tabs.size());
            assertEquals("plain", tabs.get(0).getTitle());
            assertEquals(ViewDescriptor.Kind.LAYERED, tabs.get(0).getDescriptor().kind());
        });
    }

    @Test
    void closingAProjectClosesTheActiveDocumentWithAllItsTabs() {
        AtomicReference<ArchitectureWfxView> projection = new AtomicReference<>();
        ProjectPersistenceController persistence = persistence();
        onFx(() -> {
            windowManager.setFocusedView(exampleView);
            viewManager.openStyleView(ArchitectureKind.COMPONENT);
            projection.set(newestViewOf(example));
        });
        awaitRoot(projection.get());
        onFx(() -> {
            assertEquals(2, viewManager.viewsOf(example).size());

            persistence.closeProject();

            assertTrue(viewManager.viewsOf(example).isEmpty(), "both tabs of the document are gone");
            assertEquals(1, viewManager.viewsOf(hexagon).size(), "the other document is not");
            assertSame(hexagon, viewManager.activeDocument());
            assertPanelsShow(hexagonView.getArchitectureView());
        });
    }

    @Test
    void closeAllClosesTheTabsBehindTheSelectedOneToo() {
        onFx(() -> {
            // Both documents share one pane; only one of their tabs is in front.
            assertFalse(windowManager.getVisibleViews().contains(exampleView));

            viewManager.closeAllViews();

            assertTrue(viewManager.registeredArchitectureViews().isEmpty());
            assertNull(viewManager.activeDocument());
        });
    }

    /* ----- Helfer ------------------------------------------------------------ */

    /**
     * The controller the application uses: the one S202Module created in its
     * constructor, while the bean scope was still being built. One made here,
     * after the fact, would not notice a service looked up too early.
     */
    private static ProjectPersistenceController persistence() {
        Module shell = module(Lookup.lookupAll(Module.class), S202Module.class);
        try {
            Field field = S202Module.class.getDeclaredField("persistence");
            field.setAccessible(true);
            return (ProjectPersistenceController) field.get(shell);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static AnalysisDocument analyze(String name, String jar) throws Exception {
        DependencyModel raw = new InputAnalyzer().analyze(jar);
        DomainModel domain = Lookup.lookup(DomainComputer.class).compute(raw);
        ArchitectureNode root = new ArchitectureNodeBuilder().build(domain);
        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
        return new AnalysisDocument(name, new S202Project.Source("JAR", List.of(jar), null),
                raw, domain, root, QualityMetrics.compute(domain), null, domain.getClassBackEdges());
    }

    /** What the analysis pipeline does once a result is there. */
    private ArchitectureWfxView open(AnalysisDocument document) {
        ArchitectureWfxView view = viewManager.createArchitectureView(document.name());
        viewManager.registerArchitectureView(view);
        viewManager.attach(view, document);
        view.getArchitectureView().setArchitectureRoot(document.rootNode());
        view.getArchitectureView().setQualityMetrics(document.metrics());
        return view;
    }

    private static ArchitectureNode find(ArchitectureNode node, String fullName) {
        if (node == null) {
            return null;
        }
        if (fullName.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = find(child, fullName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private ArchitectureWfxView newestViewOf(AnalysisDocument document) {
        List<ArchitectureWfxView> views = viewManager.viewsOf(document);
        return views.get(views.size() - 1);
    }

    /** Two classes of one package of the document — enough to open a tangle tab on. */
    private static Set<String> someClassesOf(AnalysisDocument document) {
        Set<String> classes = new LinkedHashSet<>();
        String firstPackage = null;
        for (String fqn : document.domainModel().getAllClasses().keySet().stream().sorted().toList()) {
            String packageName = fqn.substring(0, Math.max(0, fqn.lastIndexOf('.')));
            if (firstPackage == null) {
                firstPackage = packageName;
            }
            if (packageName.equals(firstPackage)) {
                classes.add(fqn);
            }
            if (classes.size() == 2) {
                break;
            }
        }
        return classes;
    }

    private static void assertPanelsShow(ArchitectureCanvas expected) {
        for (Module panel : panels) {
            assertSame(expected, boundViewOf(panel), panel.getName() + " shows another tab");
        }
    }

    private static Object boundViewOf(Module panel) {
        try {
            Field field = panel.getClass().getDeclaredField("boundView");
            field.setAccessible(true);
            return field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Module module(List<Module> all, Class<? extends Module> type) {
        return all.stream().filter(type::isInstance).findFirst()
                .orElseThrow(() -> new AssertionError("Module not found: " + type.getSimpleName()));
    }

    /** Wait until the asynchronous build of the tab's tree has finished. */
    private static void awaitRoot(ArchitectureWfxView view) {
        assertNotNull(view);
        await(() -> view.getArchitectureView().hasRoot());
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + FX_TIMEOUT.toNanos();
        AtomicReference<Boolean> met = new AtomicReference<>(false);
        while (System.nanoTime() < deadline) {
            onFx(() -> met.set(condition.getAsBoolean()));
            if (met.get()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting", e);
            }
        }
        fail("Condition was not met within " + FX_TIMEOUT);
    }

    private static boolean startJavaFx() {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                Platform.startup(() -> {
                    Platform.setImplicitExit(false);
                    ready.countDown();
                });
            } catch (IllegalStateException alreadyStarted) {
                try {
                    Platform.runLater(ready::countDown);
                } catch (IllegalStateException e) {
                    failure.set(e);
                    ready.countDown();
                }
            } catch (Throwable t) {
                failure.set(t);
                ready.countDown();
            }
        }, "javafx-test-starter");
        starter.setDaemon(true);
        starter.start();
        try {
            return ready.await(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) && failure.get() == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void onFx(Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        try {
            task.get(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for JavaFX", e);
        } catch (TimeoutException e) {
            fail("Timed out while waiting for JavaFX", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError assertionError) {
                throw assertionError;
            }
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        }
    }
}
