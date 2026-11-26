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

import de.weigend.s202.domain.ShownNames;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.events.NodeSelectionEvent;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.core.model.ScopeExtensionModel;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewKind;
import io.softwareecg.wfx.windowmanager.api.ViewLifecycle;
import io.softwareecg.wfx.windowmanager.api.WindowManager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EventObject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Besitzt und verwaltet alle Architektur-Tabs: Erzeugung/Registrierung der
 * {@link ArchitectureWfxView}-Wrapper, die Zuordnung von Tabs zu ihrem
 * {@link AnalysisDocument} sowie das Öffnen eines Tabs aus seiner
 * {@link ViewDescriptor Beschreibung} — für die Menü-Aktionen ebenso wie für
 * das Laden eines Projekts. Aus S202Module extrahiert; S202Module bleibt
 * Composition-Root und verdrahtet nur noch.
 * <p>
 * Welcher Tab und welches Dokument „gemeint“ sind, entscheidet nicht dieser
 * Manager, sondern der aktive Kontext des wfx-WindowManagers: Er folgt dem
 * Tab, in dem der Benutzer arbeitet, bleibt stehen, solange der Fokus in
 * einem Panel liegt, und geht beim Schließen auf den vorher aktiven Tab
 * zurück. {@link #activeCanvas()} und {@link #activeDocument()} lesen ihn nur.
 */
@jakarta.inject.Singleton
public final class ArchitectureViewManager {

    /** Trennt im Tab-Titel den Namen des Dokuments von der Sicht darauf. */
    private static final String TITLE_SEPARATOR = " · ";
    /** The label of the tab that orders the classes by their files. */
    public static final String PHYSICAL_VIEW = "Physical View";
    /** The label of a slice tab, followed by its depth. */
    public static final String SLICE_VIEW = "Slice ";

    /**
     * Baut einen Tab einer Art auf. Die Schichten-, Projektions- und
     * Scope-Tabs baut dieser Manager selbst; andere Fachkomponenten melden
     * ihre Art mit {@link #registerOpener} an (die Tangle-Tabs).
     */
    @FunctionalInterface
    public interface ViewOpener {

        /**
         * @param prepare   läuft auf dem Canvas, bevor sein Inhalt gebaut wird
         *                  (z. B. die Pakettiefe setzen)
         * @param whenBuilt läuft, sobald der Tab seinen Inhalt zeigt
         * @return der registrierte Tab; {@code null}, wenn es in dem Dokument
         *         nichts gibt, was die Beschreibung meint
         */
        ArchitectureWfxView open(AnalysisDocument document, ViewDescriptor descriptor,
                                 Consumer<ArchitectureCanvas> prepare,
                                 Consumer<ArchitectureWfxView> whenBuilt);
    }

    private final ProgressPublisher progress;
    private final Map<ViewDescriptor.Kind, ViewOpener> openers = new EnumMap<>(ViewDescriptor.Kind.class);

    private int viewCounter;
    private boolean propagatingArchitectureAnnotations;

    @jakarta.inject.Inject
    public ArchitectureViewManager(ProgressPublisher progress) {
        this.progress = progress;
        openers.put(ViewDescriptor.Kind.LAYERED, this::openTreeView);
        openers.put(ViewDescriptor.Kind.COMPONENT, this::openTreeView);
        openers.put(ViewDescriptor.Kind.HEXAGONAL, this::openTreeView);
        openers.put(ViewDescriptor.Kind.SCOPE, this::openTreeView);
    }

    /* ----- Erzeugung & Registrierung --------------------------------------- */

    public ArchitectureWfxView createArchitectureView() {
        return createArchitectureView(null);
    }

    public ArchitectureWfxView createArchitectureView(String title) {
        return createArchitectureView(title, ViewDescriptor.layered());
    }

    @SuppressWarnings("unchecked")
    private ArchitectureWfxView createArchitectureView(String title, ViewDescriptor descriptor) {
        viewCounter++;
        ArchitectureCanvas view = new ArchitectureCanvas();
        view.setStatusSink(progress::status);

        // Bridge graph node selections (class or package) onto the bus so
        // the outline (and any future listener) can react without a direct
        // dependency.
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        view.setOnNodeSelected(fqn -> bus.publish(new NodeSelectionEvent(fqn, view)));

        applyStylesheet(view);

        return new ArchitectureWfxView(
                ArchitectureWfxView.VIEW_ID_PREFIX + viewCounter,
                title == null || title.isBlank() ? "Architecture " + viewCounter : title,
                view, descriptor);
    }

    /** Nächste View-Id für Sonder-Wrapper (Tangle-Tabs), gleiche Zählung wie hier. */
    public String nextViewId() {
        viewCounter++;
        return ArchitectureWfxView.VIEW_ID_PREFIX + viewCounter;
    }

    public void applyStylesheet(ArchitectureCanvas view) {
        var css = getClass().getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            view.getStylesheets().add(css.toExternalForm());
        }
    }

    /**
     * Register an architecture wrapper with the {@link WindowManager}. Every
     * code path that creates an architecture view (Open JAR, New Window,
     * Open Scope, Open Tangle, Load Project) routes through here, so that
     * each of them gets the same wiring.
     */
    public void registerArchitectureView(ArchitectureWfxView wrapper) {
        Lookup.lookup(WindowManager.class).register(wrapper);
        ArchitectureCanvas view = wrapper.getArchitectureView();
        closeMatrixWith(view);
        view.architectureAnnotationsProperty().addListener((obs, oldValue, newValue) ->
                propagateArchitectureAnnotations(wrapper, newValue));
        // "Cut" / "Restore" on a dependency arrow of any architecture view goes
        // onto the bus; the refactoring preview distributes it to the views
        // of that view's document.
        @SuppressWarnings("unchecked")
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        view.setOnDependencyEdgeCut((from, to) ->
                bus.publish(new de.weigend.s202.ui.core.events.CutTangleEdgeEvent(from, to, view)));
        view.setOnDependencyEdgeRestore((from, to) ->
                bus.publish(new de.weigend.s202.ui.core.events.RestoreTangleEdgeEvent(from, to, view)));
        view.setOnOpenScope(scope ->
                bus.publish(new de.weigend.s202.ui.core.events.OpenScopeEvent(scope, view, view)));
        view.setOnFromTo((from, to) ->
                bus.publish(new de.weigend.s202.ui.core.events.FromToEvent(from, to, view)));
        // What the user selects among the arrows, for the panels that explain it.
        view.setOnDependencySelected(selection ->
                bus.publish(new de.weigend.s202.ui.core.events.DependencySelectionEvent(selection, view)));
    }

    /* ----- Dokument und seine Tabs ------------------------------------------ */

    /**
     * Macht {@code wrapper} zu einem Tab von {@code document}: bringt die
     * Daten des Dokuments in den Canvas und meldet das Dokument im Kontext
     * des Tabs. Die einzige Stelle, an der Dokument-Daten in einen Canvas
     * gelangen — Schichten-Sicht, Projektionen, Scope- und Tangle-Tabs gehen
     * alle hier durch.
     * <p>
     * Den Architekturbaum setzt der Aufrufer selbst, weil jede Sicht einen
     * anderen Ausschnitt zeigt.
     */
    public void attach(ArchitectureWfxView wrapper, AnalysisDocument document) {
        ArchitectureCanvas canvas = wrapper.getArchitectureView();
        // Jedes Setzen der Annotationen baut die Projektion neu; ein frischer
        // Canvas und ein frisches Dokument sind sich schon einig.
        if (!document.annotations().equals(canvas.getArchitectureAnnotations())) {
            canvas.setArchitectureAnnotations(document.annotations());
        }
        DerivedAnalysis derived = derived(document, wrapper.getDescriptor());
        if (derived != null) {
            // The same document with the classes ordered otherwise: its own
            // models, and the cuts of the document under the names they have there.
            canvas.setOwnName(derived.ownName());
            canvas.setRawDependencyModel(derived.rawModel());
            canvas.setDomainModel(derived.domainModel());
            canvas.setCycleBreakEdges(derived.cycleBreakEdges());
        } else {
            // Raw model before domain model: setting the domain model builds the
            // architecture projection, which reads the raw model.
            canvas.setRawDependencyModel(document.rawModel());
            canvas.setDomainModel(document.domainModel());
            canvas.setCycleBreakEdges(document.cycleBreakEdges());
        }
        canvas.setAppliedCutEdges(document.previewCuts());
        wrapper.setDocument(document);
    }

    /** Der Canvas des Tabs, in dem der Benutzer arbeitet; {@code null}, wenn keiner offen ist. */
    public ArchitectureCanvas activeCanvas() {
        return activeContext().lookup(ArchitectureCanvas.class);
    }

    /** Das Dokument, an dem der Benutzer arbeitet; {@code null}, wenn keines geladen ist. */
    public AnalysisDocument activeDocument() {
        return activeContext().lookup(AnalysisDocument.class);
    }

    private static LookupContext activeContext() {
        return Lookup.lookup(WindowManager.class).getActiveLookup();
    }

    /** Das Dokument, zu dessen Tabs {@code canvas} gehört; {@code null}, wenn zu keinem. */
    public AnalysisDocument documentOf(ArchitectureCanvas canvas) {
        ArchitectureWfxView wrapper = wrapperOf(canvas);
        return wrapper == null ? null : wrapper.getDocument();
    }

    /**
     * Das Dokument, das ein Bus-Event meint: das des Canvas, von dem es
     * stammt — sonst (Event aus einem Panel) das aktive.
     */
    public AnalysisDocument documentFor(Object eventSource) {
        if (eventSource instanceof ArchitectureCanvas canvas) {
            AnalysisDocument document = documentOf(canvas);
            if (document != null) {
                return document;
            }
        }
        return activeDocument();
    }

    /** Der Tab, dem {@code canvas} gehört; {@code null}, wenn keinem offenen. */
    public ArchitectureWfxView wrapperOf(ArchitectureCanvas canvas) {
        if (canvas == null) {
            return null;
        }
        for (ArchitectureWfxView wrapper : registeredArchitectureViews()) {
            if (wrapper.getArchitectureView() == canvas) {
                return wrapper;
            }
        }
        return null;
    }

    /** Alle offenen Tabs, die {@code document} zeigen. */
    public List<ArchitectureWfxView> viewsOf(AnalysisDocument document) {
        if (document == null) {
            return List.of();
        }
        return registeredArchitectureViews().stream()
                .filter(wrapper -> wrapper.getDocument() == document)
                .toList();
    }

    public List<ArchitectureWfxView> registeredArchitectureViews() {
        return Lookup.lookup(WindowManager.class).getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .toList();
    }

    /**
     * Der Baum, aus dem neue Tabs und Exporte des aktiven Dokuments entstehen:
     * das, was der aktive Tab zeigt. Ein Tangle-Tab zeigt nur einen als Graph
     * gezeichneten Ausschnitt — von ihm aus gilt der ganze Baum des Dokuments.
     *
     * @return {@code null}, wenn kein Dokument aktiv ist
     */
    public ArchitectureNode activeSourceRoot() {
        AnalysisDocument document = activeDocument();
        if (document == null) {
            return null;
        }
        ArchitectureCanvas canvas = activeCanvas();
        if (canvas == null || canvas.getTangleScope() != null || canvas.getArchitectureRoot() == null) {
            return document.rootNode();
        }
        return canvas.getArchitectureRoot();
    }

    /**
     * Ein Name für ein neues Dokument, der keinem offenen Tab gehört: Wer
     * dasselbe JAR zweimal lädt, bekommt „name (2)“.
     */
    public String uniqueDocumentName(String base) {
        String name = base == null || base.isBlank() ? "Architecture" : base;
        String candidate = name;
        for (int n = 2; isDocumentNameInUse(candidate); n++) {
            candidate = name + " (" + n + ")";
        }
        return candidate;
    }

    private boolean isDocumentNameInUse(String name) {
        for (ArchitectureWfxView wrapper : registeredArchitectureViews()) {
            String title = wrapper.getTitle();
            if (title.equals(name) || title.startsWith(name + TITLE_SEPARATOR)) {
                return true;
            }
        }
        return false;
    }

    /** Titel eines Tabs: „dokument“ für die Schichten-Sicht des Ganzen, sonst „dokument · sicht“. */
    public String titleOf(AnalysisDocument document, String view) {
        return view == null || view.isBlank() ? document.name() : document.name() + TITLE_SEPARATOR + view;
    }

    private void propagateArchitectureAnnotations(ArchitectureWfxView source,
                                                  ArchitectureAnnotations annotations) {
        AnalysisDocument document = source.getDocument();
        if (propagatingArchitectureAnnotations || document == null) {
            return;
        }
        propagatingArchitectureAnnotations = true;
        try {
            ArchitectureAnnotations effective =
                    annotations == null ? ArchitectureAnnotations.empty() : annotations;
            document.setAnnotations(effective);
            for (ArchitectureWfxView wrapper : viewsOf(document)) {
                if (wrapper == source) {
                    continue;
                }
                ArchitectureCanvas target = wrapper.getArchitectureView();
                target.setArchitectureAnnotations(effective);
                if ((target.getViewStyle() == ArchitectureKind.COMPONENT
                        || target.getViewStyle() == ArchitectureKind.HEXAGONAL)
                        && target.hasRoot()) {
                    target.refreshStyleProjection();
                }
            }
        } finally {
            propagatingArchitectureAnnotations = false;
        }
    }

    /* ----- Schließen & Zurücksetzen ------------------------------------------ */

    public void closeFocusedView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View focused = wm.getFocusedView();
        if (focused != null) {
            // closeView is kind-aware: TOOL is hidden (still in View menu);
            // DOCUMENT is fully unregistered. Old code called unregister
            // unconditionally, which forcibly removed Outline/Quality from
            // the registry when the user happened to focus them.
            wm.closeView(focused);
        }
    }

    /** Schließt {@code document}: alle Tabs, die es zeigen. Die anderen Dokumente bleiben offen. */
    public void closeDocument(AnalysisDocument document) {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        for (ArchitectureWfxView wrapper : viewsOf(document)) {
            wm.closeView(wrapper);
        }
    }

    public void closeAllViews() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        // "Close All" means "close all open documents" — the user's analyses,
        // not the side panels. Filter to DOCUMENTs so TOOLs (Outline, Quality)
        // stay alive. All registered ones: the tabs behind the selected tab
        // of a pane are open documents too.
        for (View v : new ArrayList<>(wm.getRegisteredViews())) {
            if (v.getKind() == ViewKind.DOCUMENT) {
                wm.closeView(v);
            }
        }
    }

    /* ----- Tabs aus ihrer Beschreibung öffnen ------------------------------- */

    public void newArchitectureWindow() {
        registerArchitectureView(createArchitectureView());
    }

    /** Meldet an, wer Tabs der Art {@code kind} aufbaut. */
    public void registerOpener(ViewDescriptor.Kind kind, ViewOpener opener) {
        openers.put(kind, opener);
    }

    /** Öffnet in {@code document} den Tab, den {@code descriptor} beschreibt. */
    public ArchitectureWfxView open(AnalysisDocument document, ViewDescriptor descriptor) {
        return open(document, descriptor, canvas -> { }, wrapper -> { });
    }

    /**
     * Öffnet in {@code document} den Tab, den {@code descriptor} beschreibt.
     * Der einzige Weg, auf dem ein Tab eines Dokuments entsteht — ob der
     * Benutzer ihn über das Menü verlangt oder ein geladenes Projekt ihn
     * mitbringt.
     *
     * @param prepare   läuft auf dem Canvas, bevor sein Inhalt gebaut wird
     * @param whenBuilt läuft, sobald der Tab seinen Inhalt zeigt
     * @return der Tab; {@code null}, wenn es in dem Dokument nichts gibt, was
     *         die Beschreibung meint (ein Paket, das es nicht mehr gibt)
     */
    public ArchitectureWfxView open(AnalysisDocument document, ViewDescriptor descriptor,
                                    Consumer<ArchitectureCanvas> prepare,
                                    Consumer<ArchitectureWfxView> whenBuilt) {
        ViewOpener opener = openers.get(descriptor.kind());
        if (opener == null) {
            throw new IllegalStateException("Nobody opens views of kind " + descriptor.kind());
        }
        return opener.open(document, descriptor, prepare, whenBuilt);
    }

    /**
     * Der Baum, den ein Tab mit dieser Beschreibung zeigt oder aus dem er
     * seinen Ausschnitt nimmt: das ganze Dokument oder, bei einem Scope, das
     * Paket samt dem, was per „Add to Scope“ dazukam.
     *
     * @return {@code null}, wenn das Dokument das Paket nicht enthält
     */
    public ArchitectureNode rootFor(AnalysisDocument document, ViewDescriptor descriptor) {
        DerivedAnalysis derived = derived(document, descriptor);
        ArchitectureNode whole = derived != null ? derived.rootNode() : document.rootNode();
        if (descriptor.scope() == null) {
            return whole;
        }
        ArchitectureNode root = filterPackageScope(whole, descriptor.scope());
        if (root == null) {
            return null;
        }
        for (String extension : descriptor.scopeExtensions()) {
            ScopeExtensionModel.addToScope(root, whole, extension);
        }
        return root;
    }

    /** Die Analyse in der Ordnung des Tabs; {@code null} für die nach Paketen. */
    private static DerivedAnalysis derived(AnalysisDocument document, ViewDescriptor descriptor) {
        if (descriptor.physical()) {
            return document.physical();
        }
        return descriptor.slice() > 0 ? document.slice(descriptor.slice()) : null;
    }

    /**
     * Öffnet einen horizontalen Schnitt des aktiven Dokuments: alle Pakete der
     * Tiefe {@code depth} nebeneinander. Die Analyse dafür läuft beim ersten
     * Mal im Hintergrund.
     */
    public void openSliceView(AnalysisDocument document, int depth) {
        progress.progress("Slicing the packages at depth " + depth + "...", 0.5);
        Thread worker = new Thread(() -> {
            try {
                document.slice(depth);
                javafx.application.Platform.runLater(() -> open(document, ViewDescriptor.slice(SLICE_VIEW + depth, depth)));
            } catch (RuntimeException e) {
                javafx.application.Platform.runLater(() -> Dialogs.showError("Horizontal Slice", String.valueOf(e)));
            }
        }, "s202-slice-view");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Öffnet die Physical View des aktiven Dokuments: die Klassen nach den
     * Dateien geordnet, aus denen sie gelesen wurden. Die Analyse dafür läuft
     * beim ersten Mal im Hintergrund.
     */
    public void openPhysicalView() {
        AnalysisDocument document = activeDocument();
        if (document == null) {
            progress.progress("No architecture loaded for a physical view", 1);
            return;
        }
        if (!document.hasPhysicalView()) {
            Dialogs.showError("Physical View",
                    "All classes of " + document.name() + " come from one file: there is no physical structure to show.");
            return;
        }
        progress.progress("Ordering the classes by their files...", 0.5);
        Thread worker = new Thread(() -> {
            try {
                document.physical();
                javafx.application.Platform.runLater(() -> open(document, ViewDescriptor.physical(PHYSICAL_VIEW)));
            } catch (RuntimeException e) {
                javafx.application.Platform.runLater(() -> Dialogs.showError("Physical View", String.valueOf(e)));
            }
        }, "s202-physical-view");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Die Beschreibung des aktiven Tabs — der Ausschnitt, von dem ein Tab
     * ausgeht, den der Benutzer jetzt öffnet. Ohne aktiven Tab das ganze
     * Dokument.
     */
    public ViewDescriptor activeDescriptor() {
        ArchitectureWfxView active = wrapperOf(activeCanvas());
        return active == null ? ViewDescriptor.layered() : active.getDescriptor();
    }

    /**
     * Öffnet eine Stil-Projektion des aktiven Dokuments, auf dem Ausschnitt,
     * den der aktive Tab zeigt. Der Manager kennt keine konkreten Stile mehr —
     * der Stil ist reiner Parameter, die Ansicht kommt über das StyleView-SPI
     * des Canvas.
     */
    public void openStyleView(ArchitectureKind style) {
        String name = style.name().toLowerCase(java.util.Locale.ROOT);
        String label = Character.toUpperCase(name.charAt(0)) + name.substring(1) + " View";
        AnalysisDocument document = activeDocument();
        if (document == null) {
            progress.progress("No architecture loaded for " + name + " projection", 1);
            return;
        }
        open(document, ViewDescriptor.projection(style, label, activeDescriptor()));
    }

    /**
     * Öffnet die Dependency-Matrix zu {@code source} in einem eigenen Tab oder
     * holt ihren Tab nach vorn. Sie schließt mit dem Tab, zu dem sie gehört.
     *
     * @return {@code null}, wenn es zu dem, was der Tab zeigt, keine Matrix gibt
     */
    public MatrixWfxView openMatrix(ArchitectureCanvas source) {
        ArchitectureWfxView wrapper = wrapperOf(source);
        if (wrapper == null || wrapper.getDocument() == null || !source.hasMatrix()) {
            return null;
        }
        WindowManager wm = Lookup.lookup(WindowManager.class);
        MatrixWfxView open = matrixOf(source);
        if (open != null) {
            wm.showView(open);
            wm.setFocusedView(open);
            return open;
        }
        MatrixWfxView matrix = new MatrixWfxView(
                nextViewId(), wrapper.getTitle() + TITLE_SEPARATOR + "Matrix", source, wrapper.getDocument());
        wm.register(matrix, wrapper);
        return matrix;
    }

    /** Der offene Matrix-Tab zu {@code source}; {@code null}, wenn keiner offen ist. */
    public MatrixWfxView matrixOf(ArchitectureCanvas source) {
        return Lookup.lookup(WindowManager.class).getRegisteredViews().stream()
                .filter(MatrixWfxView.class::isInstance)
                .map(MatrixWfxView.class::cast)
                .filter(matrix -> matrix.getSource() == source)
                .findFirst().orElse(null);
    }

    private void closeMatrixWith(ArchitectureCanvas source) {
        ViewLifecycle.bind(source, new ViewLifecycle.Hook() {
            @Override
            public void attached() {
            }

            @Override
            public void detached() {
                MatrixWfxView matrix = matrixOf(source);
                if (matrix != null) {
                    Lookup.lookup(WindowManager.class).closeView(matrix);
                }
            }
        });
    }

    /**
     * Öffnet einen Scope-Tab für das Paket {@code scope}.
     *
     * @param requestedSourceView der Canvas, in dessen Dokument das Paket
     *                            liegt; {@code null} für den aktiven
     */
    public void openScopeView(String scope, ArchitectureCanvas requestedSourceView) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        ArchitectureCanvas source = requestedSourceView != null ? requestedSourceView : activeCanvas();
        AnalysisDocument document = documentOf(source);
        if (document == null) {
            return;
        }
        // A package of a Physical View lies in a file, one of a slice in the
        // slice: its scope stays in that order.
        ArchitectureWfxView sourceWrapper = wrapperOf(source);
        ArchitectureWfxView opened = open(document, ViewDescriptor.scope(
                "Scope " + ShownNames.shown(simple(scope)), scope,
                sourceWrapper == null ? ViewDescriptor.layered() : sourceWrapper.getDescriptor()));
        if (opened == null) {
            Dialogs.showError("Open Scope", "Package scope was not found in the focused architecture: " + scope);
        }
    }

    /**
     * Baut einen Tab, der einen Baum zeigt: die Schichten-Sicht des ganzen
     * Dokuments, eine Stil-Projektion oder einen Scope. Sie unterscheiden sich
     * nur im Stil und im Ausschnitt.
     */
    private ArchitectureWfxView openTreeView(AnalysisDocument document, ViewDescriptor descriptor,
                                             Consumer<ArchitectureCanvas> prepare,
                                             Consumer<ArchitectureWfxView> whenBuilt) {
        ArchitectureNode root = rootFor(document, descriptor);
        if (root == null) {
            return null;
        }
        boolean scopeView = descriptor.kind() == ViewDescriptor.Kind.SCOPE;
        String what = descriptor.label() == null ? "architecture view" : descriptor.label();

        ArchitectureWfxView wrapper = createArchitectureView(titleOf(document, descriptor.label()), descriptor);
        ArchitectureCanvas canvas = wrapper.getArchitectureView();
        canvas.setViewStyle(descriptor.style());
        if (scopeView) {
            canvas.setPreferredScope(descriptor.scope());
            canvas.enableScopeExtensionFrom(document.rootNode());
            canvas.setScopeExtensions(descriptor.scopeExtensions());
        }
        attach(wrapper, document);
        prepare.accept(canvas);

        registerArchitectureView(wrapper);
        Lookup.lookup(WindowManager.class).showView(wrapper);

        canvas.setArchitectureRootAsync(
                root,
                buildProgress -> progress.javaFxBuildProgress("Building JavaFX " + what, buildProgress),
                () -> {
                    if (scopeView) {
                        canvas.selectByFullName(descriptor.scope());
                    }
                    progress.progress("Opened " + what, 1);
                    whenBuilt.accept(wrapper);
                });
        return wrapper;
    }

    private static ArchitectureNode filterPackageScope(ArchitectureNode sourceRoot, String scope) {
        ArchitectureNode scopeNode = findPackageNode(sourceRoot, scope);
        if (scopeNode == null) {
            return null;
        }
        ArchitectureNode root = ArchitectureNodeCloner.cloneShallow(sourceRoot);
        root.addChild(ArchitectureNodeCloner.cloneTree(scopeNode));
        return root;
    }

    private static ArchitectureNode findPackageNode(ArchitectureNode node, String scope) {
        if (node.getType() == ArchitectureNode.NodeType.PACKAGE
                && scope.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findPackageNode(child, scope);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String simple(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
