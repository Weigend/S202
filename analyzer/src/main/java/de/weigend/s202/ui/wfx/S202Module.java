package de.weigend.s202.ui.wfx;

import de.weigend.s202.analysis.invariants.LayoutInvariantChecker;
import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.graph.SCCBreaker;
import de.weigend.s202.analysis.strategy.impl.HeuristicSCCBreakingStrategy;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.analysis.strategy.LevelCalculator;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.project.S202ProjectMapper;
import de.weigend.s202.project.S202ProjectStore;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.GradleProjectScanner;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.reader.MavenProjectScanner;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.rendering.TangleEdgeRenderer;
import de.weigend.s202.ui.wfx.events.CutTangleEdgeEvent;
import de.weigend.s202.ui.wfx.events.CutTangleEdgesEvent;
import de.weigend.s202.ui.wfx.events.MethodSelectionEvent;
import de.weigend.s202.ui.wfx.events.NodeSelectionEvent;
import de.weigend.s202.ui.wfx.events.MenuRequestEvent;
import de.weigend.s202.ui.wfx.events.OpenScopeEvent;
import de.weigend.s202.ui.wfx.events.OpenTangleEvent;
import de.weigend.s202.ui.wfx.events.RestoreTangleEdgeEvent;
import de.weigend.s202.ui.wfx.tangles.TangleFilter;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.events.ProgressEvent;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmtg.api.ApplicationWindow;
import io.softwareecg.wfx.windowmtg.api.View;
import io.softwareecg.wfx.windowmtg.api.ViewKind;
import io.softwareecg.wfx.windowmtg.api.WindowManager;
import jakarta.inject.Singleton;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S202 platform module: wires the WFX shell (status bar, menus, toolbar,
 * preloader splash already provided by /splash/splash.fxml) and runs the JAR
 * analysis pipeline. The status bar and menu bar live in their own classes;
 * this module wires them via the {@link EventBus} and reacts to
 * {@link MenuRequestEvent}s from the menu. The central docking area starts
 * empty; each Open JAR action registers a fresh {@link ArchitectureWfxView}
 * with the {@link WindowManager} and loads its content on a background thread.
 */
@Singleton
public class S202Module implements Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(S202Module.class);

    private final ApplicationWindow applicationWindow;
    private final InputAnalyzer rawAnalyzer = new InputAnalyzer();
    private final ArchitectureNodeBuilder architectureNodeBuilder = new ArchitectureNodeBuilder();
    private final LayoutInvariantChecker invariantChecker = new LayoutInvariantChecker();
    private final S202ProjectStore projectStore = new S202ProjectStore();
    private final S202ProjectMapper projectMapper = new S202ProjectMapper();

    private int viewCounter;
    private File lastDirectory;
    private File lastProjectDirectory;
    private File lastProjectFileDirectory;

    private S202StatusBar statusBar;

    // Toolbar widgets — shared across all ArchitectureWfxView tabs.
    private Button openJarButton;
    private Spinner<Integer> depthSpinner;
    private Button refreshButton;
    private CheckBox showDependenciesCheckbox;
    private CheckBox circuitToggle;
    private CheckBox showSccCheckbox;
    private CheckBox showWhatIfViolationsCheckbox;
    private CheckBox debugLinesCheckbox;
    private CheckBox showIconsCheckbox;
    private Button zoomOutButton;
    private Label zoomLabel;
    private Button zoomInButton;
    private Button zoomResetButton;
    private final List<Node> viewDependentToolbarNodes = new ArrayList<>();

    // Tracks which view we currently mirror so we can unbind on focus change.
    private ArchitectureView boundView;
    // Reusable listener so we can detach it cleanly (no-op when boundView is null).
    private ChangeListener<Number> zoomLabelListener;

    // Dedicated tangle tabs keyed by the tangle's member set. Each tangle row
    // gets one view instance and reopens/focuses that view on later requests.
    private final Map<String, ArchitectureWfxView> tangleViews = new HashMap<>();
    private final Map<ArchitectureView, S202Project.Source> viewSources = new HashMap<>();
    private final Map<ArchitectureView, LayoutInvariantReport> viewInvariantReports = new HashMap<>();
    private final Set<TangleEdgeRenderer.Edge> refactoringPreviewCuts = new HashSet<>();

    public S202Module(ApplicationWindow applicationWindow) {
        this.applicationWindow = applicationWindow;
    }

    @Override
    public String getName() {
        return "S202 Code Analyzer";
    }

    @Override
    public String getVersion() {
        return getClass().getPackage().getImplementationVersion();
    }

    @Override
    public void preload() throws PlatformException {
        waitForDemoPreloader();
        // No view registered up-front: the central docking area stays empty
        // until the user loads a JAR (or invokes Windows → New). loadJarFiles()
        // creates and registers the first tab on demand.
    }

    private void waitForDemoPreloader() throws PlatformException {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying S202 preload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private ArchitectureWfxView createArchitectureView() {
        return createArchitectureView(null);
    }

    @SuppressWarnings("unchecked")
    private ArchitectureWfxView createArchitectureView(String title) {
        viewCounter++;
        ArchitectureView view = new ArchitectureView();
        view.setStatusSink(this::publishStatus);

        // Bridge graph node double-clicks (class or package) onto the bus so
        // the outline (and any future listener) can react without a direct
        // dependency.
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        view.setOnNodeDoubleClicked(fqn -> bus.publish(new NodeSelectionEvent(fqn, view)));

        var css = getClass().getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            view.getStylesheets().add(css.toExternalForm());
        }

        return new ArchitectureWfxView(
                ArchitectureWfxView.VIEW_ID_PREFIX + viewCounter,
                title == null || title.isBlank() ? "Architecture " + viewCounter : title,
                view);
    }

    private void publishStatus(String message) {
        publishProgress(message, 0.0);
    }

    @SuppressWarnings("unchecked")
    private void publishProgress(String message, double progress) {
        Runnable publish = () -> Lookup.lookup(EventBus.class)
                .publish(new ProgressEvent(message, progress, this));
        if (Platform.isFxApplicationThread()) {
            publish.run();
        } else {
            Platform.runLater(publish);
        }
    }

    private ArchitectureWfxView focusedArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View focused = wm.getFocusedView();
        if (focused instanceof ArchitectureWfxView a) {
            return a;
        }
        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .findFirst()
                .orElse(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        installSceneStylesheet();

        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);

        statusBar = new S202StatusBar((EventBus) bus);
        applicationWindow.getStatusBarItems().setAll(statusBar.getNode());

        new S202MenuBar(applicationWindow, bus).install();
        subscribeToMenuRequests(bus);
        subscribeToNodeSelection(bus);
        subscribeToOpenScope(bus);
        subscribeToOpenTangle(bus);
        subscribeToTanglePreviewEvents(bus);

        installToolbar();

        statusBar.setMessage("Ready to analyze bytecode. Click 'Open JAR' to begin.");
    }

    private void subscribeToOpenTangle(EventBus<EventObject> bus) {
        bus.subscribe(OpenTangleEvent.class, ev -> {
            openTangleView(ev.getMembers(), ev.getTangleKey(), ev.getTitle());
            return true;
        });
    }

    private void subscribeToOpenScope(EventBus<EventObject> bus) {
        bus.subscribe(OpenScopeEvent.class, ev -> {
            openScopeView(ev.getScope(), ev.getArchitectureView());
            return true;
        });
    }

    private void subscribeToTanglePreviewEvents(EventBus<EventObject> bus) {
        bus.subscribe(CutTangleEdgeEvent.class, ev -> {
            applyTanglePreviewCutToViews(ev.getFrom(), ev.getTo());
            return true;
        });
        bus.subscribe(CutTangleEdgesEvent.class, ev -> {
            applyTanglePreviewCutsToViews(ev.getEdges());
            return true;
        });
        bus.subscribe(RestoreTangleEdgeEvent.class, ev -> {
            restoreTanglePreviewCutInViews(ev.getFrom(), ev.getTo());
            return true;
        });
    }

    private void subscribeToMenuRequests(EventBus<EventObject> bus) {
        bus.subscribe(MenuRequestEvent.OpenJar.class, ev -> { openJarChooser(); return true; });
        bus.subscribe(MenuRequestEvent.OpenMavenProject.class, ev -> { openMavenProject(); return true; });
        bus.subscribe(MenuRequestEvent.OpenGradleProject.class, ev -> { openGradleProject(); return true; });
        bus.subscribe(MenuRequestEvent.SaveProject.class, ev -> { saveProject(); return true; });
        bus.subscribe(MenuRequestEvent.LoadProject.class, ev -> { loadProject(); return true; });
        bus.subscribe(MenuRequestEvent.CloseProject.class, ev -> { closeProject(); return true; });
        bus.subscribe(MenuRequestEvent.Exit.class, ev -> { Platform.exit(); return true; });
        bus.subscribe(MenuRequestEvent.NewView.class, ev -> { newArchitectureWindow(); return true; });
        bus.subscribe(MenuRequestEvent.CloseFocusedView.class, ev -> { closeFocusedView(); return true; });
        bus.subscribe(MenuRequestEvent.CloseAllViews.class, ev -> { closeAllViews(); return true; });
        bus.subscribe(MenuRequestEvent.RestoreDefaultLayout.class, ev -> {
            Lookup.lookup(WindowManager.class).restoreDefaultLayout();
            return true;
        });
    }

    private void subscribeToNodeSelection(EventBus<EventObject> bus) {
        bus.subscribe(NodeSelectionEvent.class, ev -> {
            ArchitectureWfxView focused = focusedArchitectureView();
            if (focused == null) {
                return true;
            }
            ArchitectureView view = focused.getArchitectureView();
            // The graph already selected the node inline; skip our own echo.
            if (ev.getSource() == view) {
                return true;
            }
            view.selectByFullName(ev.getFullName());
            return true;
        });
    }

    private void installSceneStylesheet() {
        var stage = applicationWindow.getStage();
        if (stage == null || stage.getScene() == null) {
            LOGGER.warn("No scene available; skipping scene-level stylesheet install");
            return;
        }
        stage.setMinWidth(1024);
        stage.setMinHeight(768);
        var url = getClass().getResource("/de/weigend/s202/ui/styles.css");
        if (url != null) {
            stage.getScene().getStylesheets().add(url.toExternalForm());
            LOGGER.info("Attached stylesheet to Scene: {}", url);
        }
    }

    private void newArchitectureWindow() {
        ArchitectureWfxView wrapper = createArchitectureView();
        registerArchitectureView(wrapper);
    }

    /**
     * Register an architecture wrapper with the {@link WindowManager} and,
     * on first call, dock the What-If Dependencies panel under it. Every
     * code path that creates an architecture view (Open JAR, New Window,
     * Open Scope, Open Tangle, Load Project) routes through here so the
     * Dependencies panel reliably attaches to the first chart that
     * appears.
     */
    private void registerArchitectureView(ArchitectureWfxView wrapper) {
        Lookup.lookup(WindowManager.class).register(wrapper);
        Lookup.lookup(de.weigend.s202.ui.wfx.whatif.WhatIfDependenciesModule.class)
                .dockUnder(wrapper);
    }

    private void openScopeView(String scope, ArchitectureView requestedSourceView) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        ArchitectureView sourceView = requestedSourceView;
        if (sourceView == null) {
            ArchitectureWfxView source = focusedSourceArchitectureView();
            sourceView = source == null ? null : source.getArchitectureView();
        }
        if (sourceView == null) {
            return;
        }
        ArchitectureView finalSourceView = sourceView;
        ArchitectureNode sourceRoot = sourceView.getArchitectureRoot();
        if (sourceRoot == null) {
            return;
        }
        ArchitectureNode scopedRoot = filterPackageScope(sourceRoot, scope);
        if (scopedRoot == null) {
            showError("Open Scope", "Package scope was not found in the focused architecture: " + scope);
            return;
        }

        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView wrapper = createArchitectureView("Scope " + simple(scope));
        ArchitectureView scopeView = wrapper.getArchitectureView();
        scopeView.setPreferredTopTanglesScope(scope);
        scopeView.setDomainModel(sourceView.getDomainModel());
        scopeView.setRawDependencyModel(sourceView.getRawDependencyModel());
        scopeView.setCycleBreakEdges(sourceView.getCycleBreakEdges());
        scopeView.setAppliedTangleCutEdges(refactoringPreviewCuts);
        viewSources.put(scopeView, viewSources.get(sourceView));
        viewInvariantReports.put(scopeView, viewInvariantReports.get(sourceView));

        registerArchitectureView(wrapper);
        wm.showView(wrapper);

        scopeView.setArchitectureRootAsync(
                scopedRoot,
                progress -> publishJavaFxBuildProgress("Building JavaFX scope view", progress),
                () -> {
                    scopeView.setQualityMetrics(finalSourceView.getQualityMetrics());
                    scopeView.selectByFullName(scope);
                    publishProgress("Opened scope " + scope, 1);
                });
    }

    private static ArchitectureNode filterPackageScope(ArchitectureNode sourceRoot, String scope) {
        ArchitectureNode scopeNode = findPackageNode(sourceRoot, scope);
        if (scopeNode == null) {
            return null;
        }
        ArchitectureNode root = cloneShallow(sourceRoot);
        root.addChild(cloneTree(scopeNode));
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

    private static ArchitectureNode cloneTree(ArchitectureNode source) {
        ArchitectureNode clone = cloneShallow(source);
        for (ArchitectureNode child : source.getChildren()) {
            clone.addChild(cloneTree(child));
        }
        return clone;
    }

    private static ArchitectureNode cloneShallow(ArchitectureNode source) {
        ArchitectureNode clone = new ArchitectureNode(
                source.getFullName(),
                source.getSimpleName(),
                source.getType(),
                source.isAutoExpanded(),
                source.getLevel(),
                source.isInterfaceType());
        clone.setDependencies(source.getDependencies());
        clone.setDependents(source.getDependents());
        return clone;
    }

    private static String simple(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private void closeFocusedView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View focused = wm.getFocusedView();
        if (focused != null) {
            if (focused instanceof ArchitectureWfxView a) {
                forgetView(a);
            }
            // closeView is kind-aware: TOOL is hidden (still in View menu);
            // DOCUMENT is fully unregistered. Old code called unregister
            // unconditionally, which forcibly removed Outline/Quality from
            // the registry when the user happened to focus them.
            wm.closeView(focused);
        }
    }

    private void closeAllViews() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        // "Close All" means "close all open documents" — the user's analyses,
        // not the side panels. Filter to DOCUMENTs so TOOLs (Outline, Quality)
        // stay alive.
        for (View v : new ArrayList<>(wm.getVisibleViews())) {
            if (v.getKind() == ViewKind.DOCUMENT) {
                if (v instanceof ArchitectureWfxView a) {
                    forgetView(a);
                }
                wm.closeView(v);
            }
        }
    }

    private void forgetView(ArchitectureWfxView wrapper) {
        if (wrapper == null) {
            return;
        }
        ArchitectureView view = wrapper.getArchitectureView();
        viewSources.remove(view);
        viewInvariantReports.remove(view);
        tangleViews.values().removeIf(wrapper::equals);
    }

    private void installToolbar() {
        openJarButton = new Button("Open");
        openJarButton.setId("toolbar.open");
        openJarButton.getStyleClass().add("toolbar-button");
        openJarButton.setGraphic(toolbarIcon(MaterialDesignF.FOLDER_OPEN));
        openJarButton.setTooltip(new Tooltip("Open JAR(s), Maven pom.xml, or Gradle build script"));
        openJarButton.setOnAction(e -> openAnyChooser());

        Label depthLabel = new Label("Depth:");
        depthLabel.getStyleClass().add("toolbar-label");

        depthSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 10, 3));
        depthSpinner.setTooltip(new Tooltip("Package nesting depth to display"));
        depthSpinner.valueProperty().addListener((obs, was, isNow) -> {
            if (boundView != null && isNow != null) {
                boundView.setPackageDepth(isNow);
            }
        });

        refreshButton = new Button("Refresh");
        refreshButton.getStyleClass().add("toolbar-button");
        refreshButton.setGraphic(toolbarIcon(MaterialDesignR.REFRESH));
        refreshButton.setTooltip(new Tooltip("Rebuild architecture view (re-layout all packages)"));
        refreshButton.setOnAction(e -> {
            if (boundView != null) {
                boundView.refreshLayout();
            }
        });

        showDependenciesCheckbox = new CheckBox("Show Dependencies");
        showDependenciesCheckbox.setTooltip(new Tooltip("Toggle dependency arrows"));
        showDependenciesCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) {
                boundView.setShowDependencies(isNow);
            }
        });

        circuitToggle = new CheckBox("Leiterbahn");
        circuitToggle.setTooltip(new Tooltip("Dependency style: classic vs. circuit-board routing"));
        circuitToggle.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) {
                boundView.setCircuitMode(isNow);
            }
        });

        showSccCheckbox = new CheckBox("Show SCCs");
        showSccCheckbox.setTooltip(new Tooltip("Toggle cycle highlighting (Strongly Connected Components)"));
        showSccCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) {
                boundView.setShowScc(isNow);
            }
        });

        showWhatIfViolationsCheckbox = new CheckBox("Show Violations");
        showWhatIfViolationsCheckbox.setTooltip(new Tooltip(
                "Toggle dashed arrows for wrong-direction dependencies introduced by moves"));
        showWhatIfViolationsCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) {
                boundView.setShowWhatIfViolations(isNow);
            }
        });

        debugLinesCheckbox = new CheckBox("Debug Lines");
        debugLinesCheckbox.setTooltip(new Tooltip("Toggle visible tangle routing debug lines"));
        debugLinesCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) {
                boundView.setShowTangleDebugLines(isNow);
            }
        });

        showIconsCheckbox = new CheckBox("Show Icons");
        showIconsCheckbox.setTooltip(new Tooltip("Toggle package/class/interface icons in the architecture view"));
        showIconsCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) {
                boundView.setShowIcons(isNow);
            }
        });

        zoomOutButton = new Button("−");
        zoomOutButton.getStyleClass().add("toolbar-zoom-button");
        zoomOutButton.setTooltip(new Tooltip("Zoom Out (Ctrl+Scroll Down)"));
        zoomOutButton.setOnAction(e -> { if (boundView != null) boundView.zoomOut(); });

        zoomLabel = new Label("100%");
        zoomLabel.getStyleClass().add("toolbar-zoom-label");

        zoomInButton = new Button("+");
        zoomInButton.getStyleClass().add("toolbar-zoom-button");
        zoomInButton.setTooltip(new Tooltip("Zoom In (Ctrl+Scroll Up)"));
        zoomInButton.setOnAction(e -> { if (boundView != null) boundView.zoomIn(); });

        zoomResetButton = new Button("1:1");
        zoomResetButton.getStyleClass().add("toolbar-zoom-button");
        zoomResetButton.setTooltip(new Tooltip("Reset Zoom to 100%"));
        zoomResetButton.setOnAction(e -> { if (boundView != null) boundView.zoomReset(); });

        HBox zoomGroup = new HBox(2, zoomOutButton, zoomLabel, zoomInButton);
        zoomGroup.setAlignment(Pos.CENTER_LEFT);

        // Everything except the Open JAR button is view-dependent.
        viewDependentToolbarNodes.addAll(List.of(
                depthLabel, depthSpinner, refreshButton,
                showDependenciesCheckbox, circuitToggle, showSccCheckbox, showWhatIfViolationsCheckbox,
                debugLinesCheckbox, showIconsCheckbox,
                zoomOutButton, zoomLabel, zoomInButton, zoomResetButton));

        applicationWindow.getToolbarItems().setAll(
                openJarButton, new Separator(),
                depthLabel, depthSpinner, refreshButton,
                new Separator(),
                showDependenciesCheckbox, circuitToggle, showSccCheckbox,
                showWhatIfViolationsCheckbox, debugLinesCheckbox, showIconsCheckbox,
                new Separator(),
                zoomGroup, zoomResetButton);

        // Track focus changes to retarget the toolbar.
        Lookup.lookup(WindowManager.class).focusedViewProperty()
                .addListener((obs, was, isNow) -> bindToolbarToFocusedView());
        bindToolbarToFocusedView();
    }

    /**
     * Detach toolbar widgets from the previously focused view, then mirror the
     * settings of the currently focused view. If no architecture view is
     * focused, disable everything except the Open JAR button.
     */
    private void bindToolbarToFocusedView() {
        if (boundView != null && zoomLabelListener != null && boundView.zoomFactorProperty() != null) {
            boundView.zoomFactorProperty().removeListener(zoomLabelListener);
        }
        boundView = null;
        zoomLabelListener = null;

        ArchitectureWfxView focused = focusedArchitectureView();
        boolean enabled = focused != null;
        for (Node n : viewDependentToolbarNodes) {
            n.setDisable(!enabled);
        }
        if (!enabled) {
            zoomLabel.setText("--");
            return;
        }

        ArchitectureView view = focused.getArchitectureView();
        boundView = view;

        depthSpinner.getValueFactory().setValue(view.getPackageDepth());
        showDependenciesCheckbox.setSelected(view.isShowDependencies());
        circuitToggle.setSelected(view.isCircuitMode());
        showSccCheckbox.setSelected(view.isShowScc());
        showWhatIfViolationsCheckbox.setSelected(view.isShowWhatIfViolations());
        debugLinesCheckbox.setSelected(view.isShowTangleDebugLines());
        showIconsCheckbox.setSelected(view.isShowIcons());

        ReadOnlyDoubleProperty zoomProp = view.zoomFactorProperty();
        if (zoomProp != null) {
            updateZoomLabel(zoomProp.get());
            zoomLabelListener = (obs, was, isNow) -> updateZoomLabel(isNow.doubleValue());
            zoomProp.addListener(zoomLabelListener);
        } else {
            zoomLabel.setText("100%");
        }
    }

    private void updateZoomLabel(double factor) {
        zoomLabel.setText(Math.round(factor * 100) + "%");
    }

    private static FontIcon toolbarIcon(Ikon code) {
        FontIcon icon = new FontIcon(code);
        icon.setIconSize(16);
        icon.setIconColor(Color.web("#ffd54f"));
        return icon;
    }

    private void openJarChooser() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select JAR file(s) to analyze");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            fileChooser.setInitialDirectory(lastDirectory);
        }
        loadJarSelection(fileChooser.showOpenMultipleDialog(applicationWindow.getStage()));
    }

    private void openMavenProject() {
        File pom = chooseProjectFile("Select Maven project root pom.xml",
                new FileChooser.ExtensionFilter("Maven POM", "pom.xml"));
        if (pom != null) {
            scanMavenProjectAt(pom.getParentFile());
        }
    }

    private void openGradleProject() {
        File buildScript = chooseProjectFile("Select Gradle root settings.gradle or build.gradle",
                new FileChooser.ExtensionFilter("Gradle settings/build script",
                        "settings.gradle", "settings.gradle.kts",
                        "build.gradle", "build.gradle.kts"));
        if (buildScript != null) {
            scanGradleProjectAt(buildScript.getParentFile());
        }
    }

    /**
     * Unified toolbar entry point. Accepts JARs, a Maven {@code pom.xml}, or
     * a Gradle settings/build script in a single FileChooser and dispatches
     * to the right loader by filename.
     */
    private void openAnyChooser() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open JAR(s), Maven pom.xml, or Gradle build script");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All supported",
                        "*.jar", "pom.xml",
                        "settings.gradle", "settings.gradle.kts",
                        "build.gradle", "build.gradle.kts"),
                new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
                new FileChooser.ExtensionFilter("Maven POM", "pom.xml"),
                new FileChooser.ExtensionFilter("Gradle settings/build script",
                        "settings.gradle", "settings.gradle.kts",
                        "build.gradle", "build.gradle.kts"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File initial = lastDirectory != null && lastDirectory.isDirectory()
                ? lastDirectory
                : (lastProjectDirectory != null && lastProjectDirectory.isDirectory() ? lastProjectDirectory : null);
        if (initial != null) {
            fileChooser.setInitialDirectory(initial);
        }
        List<File> picked = fileChooser.showOpenMultipleDialog(applicationWindow.getStage());
        if (picked == null || picked.isEmpty()) {
            return;
        }

        // Single-file picks may be a project descriptor; check the filename first.
        if (picked.size() == 1) {
            File f = picked.get(0);
            String name = f.getName();
            if (name.equalsIgnoreCase("pom.xml")) {
                scanMavenProjectAt(f.getParentFile());
                return;
            }
            if (name.equalsIgnoreCase("settings.gradle") || name.equalsIgnoreCase("settings.gradle.kts")
                    || name.equalsIgnoreCase("build.gradle") || name.equalsIgnoreCase("build.gradle.kts")) {
                scanGradleProjectAt(f.getParentFile());
                return;
            }
        }

        // Otherwise treat as JAR selection — but reject mixed picks, since
        // mixing a pom.xml with JARs has no defined semantics.
        for (File f : picked) {
            if (!f.getName().toLowerCase().endsWith(".jar")) {
                showError("Mixed selection",
                        "Pick either ONE pom.xml / settings.gradle / build.gradle for a project import,\n"
                                + "or one or more *.jar files for direct analysis. Mixed selections aren't supported.");
                return;
            }
        }
        loadJarSelection(picked);
    }

    /** Routes a list of JAR files through the staging dialog when there's
     *  more than one, and into the analysis pipeline otherwise. Shared by the
     *  JAR-only menu entry and the unified toolbar chooser. */
    private void loadJarSelection(List<File> picked) {
        if (picked == null || picked.isEmpty()) {
            return;
        }
        lastDirectory = picked.get(0).getParentFile();

        if (picked.size() == 1) {
            loadJarFiles(picked);
            return;
        }
        SourceSetDialog.chooseSourceSet(applicationWindow.getStage(), lastDirectory, picked)
                .ifPresent(selected -> {
                    if (!selected.isEmpty()) {
                        lastDirectory = selected.get(0).getParentFile();
                        loadJarFiles(selected);
                    }
                });
    }

    private void scanMavenProjectAt(File root) {
        lastProjectDirectory = root;
        runProjectScan("Maven", root,
                () -> {
                    var r = new MavenProjectScanner().scan(root);
                    return new ScanResult(r.jars(), r.missingArtifactModules(), r.scannedModuleCount());
                },
                "mvn package");
    }

    private void scanGradleProjectAt(File root) {
        lastProjectDirectory = root;
        runProjectScan("Gradle", root,
                () -> {
                    var r = new GradleProjectScanner().scan(root);
                    return new ScanResult(r.jars(), r.missingArtifactModules(), r.scannedModuleCount());
                },
                "gradle build");
    }

    private File chooseProjectFile(String title, FileChooser.ExtensionFilter filter) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(filter,
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File initial = lastProjectDirectory != null && lastProjectDirectory.isDirectory()
                ? lastProjectDirectory
                : (lastDirectory != null && lastDirectory.isDirectory() ? lastDirectory : null);
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        return chooser.showOpenDialog(applicationWindow.getStage());
    }

    /**
     * Adapter result so the Maven and Gradle scanner result records can flow
     * through a shared post-scan handler without bleeding their concrete
     * record types into S202Module.
     */
    private record ScanResult(List<File> jars, List<String> missingArtifactModules, int scannedModuleCount) {}

    @FunctionalInterface
    private interface ScanCallable {
        ScanResult call() throws Exception;
    }

    private void runProjectScan(String kind, File root, ScanCallable scanCallable, String buildHint) {
        publishProgress("Scanning " + kind + " project " + root.getName() + "…", -1);

        Task<ScanResult> task = new Task<>() {
            @Override
            protected ScanResult call() throws Exception {
                return scanCallable.call();
            }
        };
        task.setOnSucceeded(e -> handleScanResult(kind, root, task.getValue(), buildHint));
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            String msg = t != null ? t.getMessage() : "unknown error";
            LOGGER.error("{} project scan failed", kind, t);
            publishProgress("Error scanning " + kind + " project: " + msg, 1);
            showError(kind + " scan failed", "Could not scan " + kind + " project:\n" + msg);
        });
        Thread th = new Thread(task, "s202-" + kind.toLowerCase() + "-scan");
        th.setDaemon(true);
        th.start();
    }

    private void handleScanResult(String kind, File root, ScanResult result, String buildHint) {
        List<File> jars = result.jars();
        List<String> missing = result.missingArtifactModules();
        int total = result.scannedModuleCount();

        if (jars.isEmpty()) {
            publishProgress("No built JARs found in " + kind + " project " + root.getName(), 0);
            showError("No JARs found",
                    "Found no analyzable JARs under\n" + root.getAbsolutePath()
                            + "\n\nTry running \"" + buildHint + "\" first to produce module artifacts.");
            return;
        }

        int built = total - missing.size();
        String summary = jars.size() + " JAR(s) from " + built + "/" + total
                + " " + kind + " module(s)"
                + (missing.isEmpty() ? "" : " (" + missing.size() + " not built)");
        publishProgress(summary, 0);
        if (!missing.isEmpty()) {
            LOGGER.warn("{} project '{}': {} module(s) without artifact: {}",
                    kind, root.getName(), missing.size(), missing);
        }

        File initialDir = jars.get(0).getParentFile();
        SourceSetDialog.chooseSourceSet(applicationWindow.getStage(), initialDir, jars)
                .ifPresent(selected -> {
                    if (!selected.isEmpty()) {
                        lastDirectory = selected.get(0).getParentFile();
                        loadJarFiles(selected, projectSource(kind, root, selected));
                    }
                });
    }

    private S202Project.Source projectSource(String kind, File root, List<File> jars) {
        return new S202Project.Source(
                kind.toUpperCase(),
                jars.stream().map(File::getAbsolutePath).toList(),
                root == null ? null : root.getAbsolutePath());
    }

    @Override
    public void stop() {
        // nothing to release
    }

    /**
     * Run the analysis pipeline (raw analysis -> levels -> node tree -> render)
     * on a background thread; rendering happens in {@code onSucceeded} on the FX
     * thread. Each invocation opens a new architecture tab, which then becomes
     * the focused view.
     */
    private void loadJarFiles(List<File> jarFiles) {
        if (jarFiles == null || jarFiles.isEmpty()) {
            return;
        }
        loadJarFiles(jarFiles, projectSource("JAR", null, jarFiles));
    }

    private void loadJarFiles(List<File> jarFiles, S202Project.Source source) {
        if (jarFiles == null || jarFiles.isEmpty()) {
            return;
        }
        ArchitectureWfxView target = createArchitectureView();
        registerArchitectureView(target);
        final ArchitectureView view = target.getArchitectureView();
        final String fileNames = jarFiles.stream().map(File::getName).collect(Collectors.joining(", "));
        final List<String> jarPaths = jarFiles.stream().map(File::getAbsolutePath).toList();

        publishProgress("Analyzing: " + fileNames + " (this may take a moment)...", -1);

        Task<AnalysisResult> task = new Task<>() {
            @Override
            protected AnalysisResult call() throws Exception {
                final int[] lastReportedClass = {-1};
                DependencyModel rawModel = rawAnalyzer.analyzeMultiple(jarPaths, progress -> {
                    int total = progress.totalClasses();
                    int processed = progress.processedClasses();
                    if (total <= 0 || (processed != total && processed - lastReportedClass[0] < 25)) {
                        return;
                    }
                    lastReportedClass[0] = processed;
                    double bytecodeProgress = Math.min(0.70, 0.70 * processed / total);
                    String jarName = progress.jarPath() == null ? fileNames : new File(progress.jarPath()).getName();
                    publishProgress(String.format("Reading bytecode: %s (%d/%d classes)",
                            jarName, processed, total), bytecodeProgress);
                });
                if (rawModel.getAllClasses().isEmpty()) {
                    return new AnalysisResult(rawModel, null, null, null, null, Set.of());
                }

                publishProgress("Calculating architectural levels...", 0.75);
                LevelCalculator calculator = new LevelCalculator();
                DomainModel calculated = calculator.calculate(rawModel);
                Set<TangleEdgeRenderer.Edge> cycleBreakEdges = cycleBreakEdgesFromLastLevelCalculation(calculator);

                publishProgress("Building architecture tree...", 0.85);
                ArchitectureNode root = architectureNodeBuilder.build(calculated);
                new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
                de.weigend.s202.ui.consistency.ArchitectureConsistencyDevHook
                        .runIfEnabled(calculated, root);

                publishProgress("Preparing quality metrics...", 0.90);
                QualityMetrics metrics = QualityMetrics.compute(calculated);

                // Layout invariant check runs on the same background thread so
                // findings reach the FX thread together with the rest of the
                // result — the user sees one settled state, not a mid-render
                // popup. Findings flagged here are real algorithm bugs in the
                // level pipeline, not architectural violations (those already
                // surface as red dependency edges).
                publishProgress("Verifying layout invariants...", 0.95);
                LayoutInvariantReport invariants = invariantChecker.check(
                        calculated, rawModel,
                        jarFiles.stream().map(File::getAbsolutePath).toList());
                return new AnalysisResult(rawModel, root, metrics, calculated, invariants, cycleBreakEdges);
            }
        };

        task.setOnSucceeded(e -> {
            AnalysisResult result = task.getValue();
            if (result.rootNode() == null) {
                publishProgress("Error: No classes found in JAR file(s)", 1);
                showError("No Classes Found", "The JAR file(s) do not contain any .class files");
                return;
            }
            publishProgress("Building JavaFX architecture view...", 0.97);

            PauseTransition yieldToPulse = new PauseTransition(Duration.millis(50));
            yieldToPulse.setOnFinished(event -> applyAnalysisResult(jarFiles, view, source, result));
            yieldToPulse.play();
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            LOGGER.error("Analysis failed", t);
            String msg = t != null ? t.getMessage() : "unknown error";
            publishProgress("Error: " + msg, 1);
            showError("Analysis Error", "Failed to analyze JAR file(s):\n" + msg);
        });

        Thread analyzer = new Thread(task, "s202-analyzer");
        analyzer.setDaemon(true);
        analyzer.start();
    }

    private void applyAnalysisResult(List<File> jarFiles, ArchitectureView view,
                                     S202Project.Source source, AnalysisResult result) {
        refactoringPreviewCuts.clear();
        // Domain model first so listeners on architectureRoot/metrics can
        // already query scoped data (e.g. quality module on package select).
        view.setDomainModel(result.domainModel());
        view.setRawDependencyModel(result.rawModel());
        view.setCycleBreakEdges(result.cycleBreakEdges());
        view.setArchitectureRootAsync(
                result.rootNode(),
                progress -> publishJavaFxBuildProgress("Building JavaFX architecture view", progress),
                () -> finishAppliedAnalysisResult(jarFiles, view, source, result));
    }

    private void finishAppliedAnalysisResult(List<File> jarFiles, ArchitectureView view,
                                             S202Project.Source source, AnalysisResult result) {
        view.setQualityMetrics(result.metrics());
        viewSources.put(view, source);
        viewInvariantReports.put(view, result.invariants());

        LayoutInvariantReport invariants = result.invariants();
        String invariantSuffix = invariantSuffix(invariants);
        publishProgress(String.format(
                "Loaded %d JAR(s) | %d classes | %d levels | Max level %d%s",
                jarFiles.size(),
                result.rawModel().getAllClasses().size(),
                result.rootNode().getLevelCount(),
                result.rootNode().getMaxLevel(),
                invariantSuffix), 1);

        showInvariantReportIfNeeded(invariants);
    }

    private String invariantSuffix(LayoutInvariantReport invariants) {
        if (invariants == null) {
            return "";
        }
        int n = invariants.findings().size();
        if (n == 0) {
            return " | invariants OK";
        }
        LOGGER.warn("Layout invariant report ({} finding(s)):\n{}",
                n, invariants.toReproducerText());
        return " | " + n + " invariant finding" + (n == 1 ? "" : "s");
    }

    private void showInvariantReportIfNeeded(LayoutInvariantReport invariants) {
        if (invariants == null || !invariants.hasFindings()) {
            return;
        }
        Platform.runLater(() ->
                InvariantReportDialog.show(applicationWindow.getStage(), invariants));
    }

    private void publishJavaFxBuildProgress(String label, ArchitectureView.BuildProgress progress) {
        int total = Math.max(1, progress.totalNodes());
        int processed = Math.min(progress.processedNodes(), total);
        double fraction = Math.max(0.0, Math.min(1.0, (double) processed / total));
        double mapped = 0.97 + fraction * 0.025;
        publishProgress(String.format("%s: %,d/%,d nodes", label, processed, total), mapped);
    }

    private void saveProject() {
        ArchitectureWfxView focused = focusedSourceArchitectureView();
        if (focused == null || focused.getArchitectureView().getDomainModel() == null
                || focused.getArchitectureView().getRawDependencyModel() == null) {
            showError("Save Project", "There is no loaded analysis to save.");
            return;
        }

        FileChooser chooser = projectFileChooser("Save Structure202 Project");
        File target = chooser.showSaveDialog(applicationWindow.getStage());
        if (target == null) {
            return;
        }
        target = withDefaultProjectExtension(target);
        lastProjectFileDirectory = target.getParentFile();

        ArchitectureView view = focused.getArchitectureView();
        S202Project.Source source = viewSources.getOrDefault(view,
                new S202Project.Source("UNKNOWN", List.of(), null));
        S202Project project = projectMapper.toProject(
                appVersion(),
                source,
                view.getRawDependencyModel(),
                view.getDomainModel(),
                viewInvariantReports.get(view),
                view.getCycleBreakEdges());

        try {
            projectStore.save(target.toPath(), project);
            publishProgress("Saved project: " + target.getName(), 1);
        } catch (IOException ex) {
            LOGGER.error("Could not save project {}", target, ex);
            showError("Save Project", "Could not save project:\n" + ex.getMessage());
        }
    }

    private void loadProject() {
        FileChooser chooser = projectFileChooser("Load Structure202 Project");
        File file = chooser.showOpenDialog(applicationWindow.getStage());
        if (file == null) {
            return;
        }
        lastProjectFileDirectory = file.getParentFile();
        publishProgress("Loading project: " + file.getName() + "...", -1);

        Task<LoadedProject> task = new Task<>() {
            @Override
            protected LoadedProject call() throws Exception {
                S202Project project = projectStore.load(file.toPath());
                DependencyModel rawModel = projectMapper.toDependencyModel(project.dependencyModel());
                DomainModel domainModel = projectMapper.toDomainModel(project.domainModel());
                ArchitectureNode root = architectureNodeBuilder.build(domainModel);
                new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
                de.weigend.s202.ui.consistency.ArchitectureConsistencyDevHook
                        .runIfEnabled(domainModel, root);
                QualityMetrics metrics = QualityMetrics.compute(domainModel);
                LayoutInvariantReport invariants = projectMapper.toLayoutInvariantReport(project.layoutInvariantReport());
                Set<TangleEdgeRenderer.Edge> cycleBreakEdges =
                        projectMapper.toCycleBreakEdges(project.cycleBreakEdges());
                return new LoadedProject(project, rawModel, domainModel, root, metrics, invariants, cycleBreakEdges);
            }
        };
        task.setOnSucceeded(e -> applyLoadedProject(file.toPath(), task.getValue()));
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            LOGGER.error("Could not load project {}", file, t);
            String msg = t != null ? t.getMessage() : "unknown error";
            publishProgress("Error loading project: " + msg, 1);
            showError("Load Project", "Could not load project:\n" + msg);
        });

        Thread loader = new Thread(task, "s202-project-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void applyLoadedProject(Path path, LoadedProject loaded) {
        resetProjectUi();

        ArchitectureWfxView target = createArchitectureView();
        registerArchitectureView(target);
        ArchitectureView view = target.getArchitectureView();
        view.setDomainModel(loaded.domainModel());
        view.setRawDependencyModel(loaded.rawModel());
        view.setCycleBreakEdges(loaded.cycleBreakEdges());
        view.setArchitectureRootAsync(
                loaded.rootNode(),
                progress -> publishJavaFxBuildProgress("Building JavaFX project view", progress),
                () -> {
                    view.setQualityMetrics(loaded.metrics());
                    viewSources.put(view, loaded.project().source());
                    viewInvariantReports.put(view, loaded.invariants());

                    publishProgress(String.format(
                            "Loaded project %s | %d classes | %d levels | Max level %d",
                            path.getFileName(),
                            loaded.rawModel().getAllClasses().size(),
                            loaded.rootNode().getLevelCount(),
                            loaded.rootNode().getMaxLevel()), 1);
                });
    }

    private void closeProject() {
        resetProjectUi();
        publishProgress("Ready to analyze bytecode. Click 'Open JAR' to begin.", 0);
    }

    private void resetProjectUi() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        for (View v : new ArrayList<>(wm.getRegisteredViews())) {
            if (v.getKind() == ViewKind.DOCUMENT) {
                if (v instanceof ArchitectureWfxView a) {
                    forgetView(a);
                }
                wm.closeView(v);
            }
        }
        tangleViews.clear();
        viewSources.clear();
        viewInvariantReports.clear();
        refactoringPreviewCuts.clear();
        boundView = null;
        zoomLabelListener = null;
        Lookup.lookup(WindowManager.class).restoreDefaultLayout();
        bindToolbarToFocusedView();
    }

    private FileChooser projectFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Structure202 Project", "*.s202.json"),
                new FileChooser.ExtensionFilter("JSON Files", "*.json"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File initial = lastProjectFileDirectory != null && lastProjectFileDirectory.isDirectory()
                ? lastProjectFileDirectory
                : (lastDirectory != null && lastDirectory.isDirectory() ? lastDirectory : null);
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        return chooser;
    }

    private static File withDefaultProjectExtension(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".s202.json") || name.endsWith(".json")) {
            return file;
        }
        File parent = file.getParentFile();
        return parent == null
                ? new File(file.getName() + ".s202.json")
                : new File(parent, file.getName() + ".s202.json");
    }

    private String appVersion() {
        String version = getVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private record LoadedProject(S202Project project, DependencyModel rawModel,
                                 DomainModel domainModel, ArchitectureNode rootNode,
                                 QualityMetrics metrics, LayoutInvariantReport invariants,
                                 Set<TangleEdgeRenderer.Edge> cycleBreakEdges) {}

    private record AnalysisResult(DependencyModel rawModel, ArchitectureNode rootNode,
                                  QualityMetrics metrics, DomainModel domainModel,
                                  LayoutInvariantReport invariants,
                                  Set<TangleEdgeRenderer.Edge> cycleBreakEdges) {}

    private static Set<TangleEdgeRenderer.Edge> cycleBreakEdgesFromLastLevelCalculation(LevelCalculator calculator) {
        var strategy = calculator.getStrategyContext().getClassLevelStrategy();
        if (!(strategy instanceof HeuristicSCCBreakingStrategy heuristic)) {
            return Set.of();
        }
        return heuristic.getLastIdentifiedBackEdges().stream()
                .map(S202Module::toTangleEdge)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static TangleEdgeRenderer.Edge toTangleEdge(SCCBreaker.Edge edge) {
        return new TangleEdgeRenderer.Edge(edge.from, edge.to);
    }

    /**
     * Open the Tangle tab focused on a specific tangle. Each tangle entry gets
     * one tab, reused on later double-clicks of that same entry.
     * <p>
     * The tab uses the dedicated {@code TangleEdgeRenderer} for the cycle
     * visualisation — independent of the toolbar's Show Dependencies / Show
     * SCC checkboxes, with arrows that dock to the box perimeters and a
     * orthogonal intra-SCC edge visualisation.
     */
    private void openTangleView(java.util.Set<String> members, String tangleKey, String title) {
        if (members == null || members.isEmpty()) {
            return;
        }
        ArchitectureWfxView source = focusedSourceArchitectureView();
        if (source == null) {
            return;
        }
        ArchitectureView sourceView = source.getArchitectureView();
        ArchitectureNode sourceRoot = sourceView.getArchitectureRoot();
        if (sourceRoot == null) {
            return;
        }
        ArchitectureNode filteredRoot = TangleFilter.filter(sourceRoot, members);
        if (filteredRoot == null) {
            showError("Open Tangle", "None of the tangle's classes were found in the focused architecture.");
            return;
        }

        java.util.List<de.weigend.s202.ui.rendering.TangleEdgeRenderer.Edge> edges =
                collectInternalEdges(filteredRoot, members);

        WindowManager wm = Lookup.lookup(WindowManager.class);
        String key = tangleKey == null || tangleKey.isBlank()
                ? members.stream().sorted().collect(Collectors.joining("|"))
                : tangleKey;
        ArchitectureWfxView wrapper = reusableTangleWrapper(wm, key, title);
        ArchitectureView tangleView = wrapper.getArchitectureView();

        // Snapshot the current zoom before setArchitectureRoot wipes it via
        // resetZoom — the user typically tunes the zoom once for a tangle
        // and we don't want each subsequent open-from-tree to snap back to
        // 100%. -1 sentinel = no previous zoom (singleton just created).
        double previousZoom = -1;
        javafx.beans.property.ReadOnlyDoubleProperty zoomProp = tangleView.zoomFactorProperty();
        if (zoomProp != null) {
            previousZoom = zoomProp.get();
        }

        // Carry the focused view's models through so the side panels keep
        // working when this new tab gains focus.
        tangleView.setDomainModel(sourceView.getDomainModel());
        tangleView.setRawDependencyModel(sourceView.getRawDependencyModel());
        tangleView.setCycleBreakEdges(sourceView.getCycleBreakEdges());
        tangleView.setAppliedTangleCutEdges(refactoringPreviewCuts);
        tangleView.setArchitectureRoot(filteredRoot);
        tangleView.setQualityMetrics(sourceView.getQualityMetrics());

        // Install the dedicated tangle edge overlay. The renderer listens to
        // layoutBounds itself so the first paint lands once the box layout
        // settles — no Platform.runLater fight with the FX pulse.
        tangleView.setTangleVisualization(edges, null, null);

        // Restore the captured zoom (if any) — defer one pulse so the new
        // ZoomController has a laid-out content node to scale against.
        if (previousZoom > 0) {
            final double zoom = previousZoom;
            Platform.runLater(() -> tangleView.setZoom(zoom));
        }

        wm.showView(wrapper);
    }

    /**
     * @return the tangle wrapper for {@code key}, reused if still registered;
     *         otherwise a freshly created one.
     */
    @SuppressWarnings("unchecked")
    private ArchitectureWfxView reusableTangleWrapper(WindowManager wm, String key, String title) {
        ArchitectureWfxView existing = tangleViews.get(key);
        if (existing != null && wm.hasRegisteredView(existing)) {
            return existing;
        }
        viewCounter++;
        ArchitectureView tangleView = new ArchitectureView();
        tangleView.setTopTanglesScopeOwner(false);
        tangleView.setStatusSink(this::publishStatus);
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        tangleView.setOnNodeDoubleClicked(fqn -> bus.publish(new NodeSelectionEvent(fqn, tangleView)));
        tangleView.setOnTangleEdgeClicked((from, to) ->
                publishTangleEdgeSelection(bus, tangleView, from, to));
        tangleView.setOnTangleEdgeCut((from, to) ->
                bus.publish(new CutTangleEdgeEvent(from, to, tangleView)));
        tangleView.setOnTangleEdgeRestore((from, to) ->
                bus.publish(new RestoreTangleEdgeEvent(from, to, tangleView)));
        var css = getClass().getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            tangleView.getStylesheets().add(css.toExternalForm());
        }
        String viewTitle = title == null || title.isBlank() ? "Tangle" : title;
        ArchitectureWfxView wrapper = new ArchitectureWfxView(
                ArchitectureWfxView.VIEW_ID_PREFIX + viewCounter,
                viewTitle,
                tangleView);
        registerArchitectureView(wrapper);
        tangleViews.put(key, wrapper);
        return wrapper;
    }

    private void applyTanglePreviewCutToViews(String from, String to) {
        refactoringPreviewCuts.add(new TangleEdgeRenderer.Edge(from, to));
        for (ArchitectureWfxView wrapper : registeredArchitectureViews()) {
            wrapper.getArchitectureView().applyTangleEdgeCut(from, to);
        }
    }

    private void applyTanglePreviewCutsToViews(Set<TangleEdgeRenderer.Edge> edges) {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        refactoringPreviewCuts.addAll(edges);
        for (ArchitectureWfxView wrapper : registeredArchitectureViews()) {
            wrapper.getArchitectureView().applyTangleEdgeCuts(edges);
        }
    }

    private void restoreTanglePreviewCutInViews(String from, String to) {
        refactoringPreviewCuts.remove(new TangleEdgeRenderer.Edge(from, to));
        for (ArchitectureWfxView wrapper : registeredArchitectureViews()) {
            wrapper.getArchitectureView().restoreTangleEdgeCut(from, to);
        }
    }

    private List<ArchitectureWfxView> registeredArchitectureViews() {
        return Lookup.lookup(WindowManager.class).getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .toList();
    }

    private void publishTangleEdgeSelection(EventBus<EventObject> bus,
                                            ArchitectureView tangleView,
                                            String from,
                                            String to) {
        if (from == null) {
            bus.publish(new MethodSelectionEvent(null, null, null, tangleView));
            return;
        }
        TargetMethod targetMethod =
                firstTargetMethodCalledByDependency(tangleView.getRawDependencyModel(), from, to);
        if (targetMethod != null) {
            bus.publish(new MethodSelectionEvent(
                    targetMethod.className(), targetMethod.methodName(), targetMethod.descriptor(), tangleView));
            return;
        }
        bus.publish(new NodeSelectionEvent(to != null ? to : from, tangleView));
    }

    record TargetMethod(String className, String methodName, String descriptor, int callCount) {}

    static TargetMethod firstTargetMethodCalledByDependency(DependencyModel rawModel,
                                                            String from,
                                                            String to) {
        if (rawModel == null || from == null || to == null) {
            return null;
        }
        DependencyModel.ClassInfo sourceClass = rawModel.getClass(from);
        if (sourceClass == null) {
            return null;
        }
        Map<String, TargetMethod> candidates = new HashMap<>();
        for (DependencyModel.MethodInfo sourceMethod : sourceClass.methods.values()) {
            for (Map.Entry<String, Integer> call : sourceMethod.methodCalls.entrySet()) {
                String methodCall = call.getKey();
                if (!callOwnerMatchesTarget(methodCall, to)) {
                    continue;
                }
                String targetMethodName = methodCallName(methodCall);
                if (targetMethodName == null) {
                    continue;
                }
                Set<String> descriptors = sourceMethod.methodCallDescriptors.get(methodCall);
                if (descriptors == null || descriptors.isEmpty()) {
                    addTargetMethodCandidate(candidates, rawModel, to, targetMethodName, null, call.getValue());
                } else {
                    for (String descriptor : descriptors) {
                        addTargetMethodCandidate(candidates, rawModel, to, targetMethodName, descriptor, call.getValue());
                    }
                }
            }
        }
        return candidates.values().stream()
                .sorted(Comparator
                        .comparingInt(TargetMethod::callCount)
                        .reversed()
                        .thenComparing(TargetMethod::methodName)
                        .thenComparing(method -> method.descriptor() == null ? "" : method.descriptor()))
                .findFirst()
                .orElse(null);
    }

    private static void addTargetMethodCandidate(Map<String, TargetMethod> candidates,
                                                 DependencyModel rawModel,
                                                 String targetClass,
                                                 String methodName,
                                                 String descriptor,
                                                 Integer count) {
        String knownDescriptor = knownTargetDescriptor(rawModel, targetClass, methodName, descriptor);
        String key = targetClass + "#" + methodName + "#" + (knownDescriptor == null ? "" : knownDescriptor);
        int callCount = count == null ? 0 : count;
        TargetMethod existing = candidates.get(key);
        if (existing == null) {
            candidates.put(key, new TargetMethod(targetClass, methodName, knownDescriptor, callCount));
        } else {
            candidates.put(key, new TargetMethod(
                    existing.className(), existing.methodName(), existing.descriptor(),
                    existing.callCount() + callCount));
        }
    }

    private static String knownTargetDescriptor(DependencyModel rawModel,
                                                String targetClass,
                                                String methodName,
                                                String descriptor) {
        if (descriptor == null) {
            return null;
        }
        DependencyModel.ClassInfo targetInfo = rawModel.getClass(targetClass);
        if (targetInfo == null || targetInfo.getMethod(methodName, descriptor) == null) {
            return null;
        }
        return descriptor;
    }

    private static boolean callOwnerMatchesTarget(String methodCall, String targetClass) {
        String owner = methodCallOwner(methodCall);
        return owner != null
                && (targetClass.equals(owner) || targetClass.equals(outerClassName(owner)));
    }

    private static String methodCallOwner(String methodCall) {
        if (methodCall == null) {
            return null;
        }
        int dot = methodCall.lastIndexOf('.');
        return dot <= 0 ? null : methodCall.substring(0, dot);
    }

    private static String methodCallName(String methodCall) {
        if (methodCall == null) {
            return null;
        }
        int dot = methodCall.lastIndexOf('.');
        return dot < 0 || dot == methodCall.length() - 1 ? null : methodCall.substring(dot + 1);
    }

    private static String outerClassName(String className) {
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }

    /**
     * Walk the (already filtered) tangle subtree and emit one edge per
     * intra-tangle dependency. Edges are deduplicated by (from, to) and
     * sorted alphabetically for stable rendering.
     */
    private static java.util.List<de.weigend.s202.ui.rendering.TangleEdgeRenderer.Edge>
            collectInternalEdges(ArchitectureNode root, java.util.Set<String> members) {
        java.util.Set<de.weigend.s202.ui.rendering.TangleEdgeRenderer.Edge> seen = new java.util.LinkedHashSet<>();
        collectInternalEdgesRec(root, members, seen);
        java.util.List<de.weigend.s202.ui.rendering.TangleEdgeRenderer.Edge> sorted = new ArrayList<>(seen);
        sorted.sort((a, b) -> {
            int c = a.from().compareTo(b.from());
            return c != 0 ? c : a.to().compareTo(b.to());
        });
        return sorted;
    }

    private static void collectInternalEdgesRec(ArchitectureNode node,
                                                java.util.Set<String> members,
                                                java.util.Set<de.weigend.s202.ui.rendering.TangleEdgeRenderer.Edge> out) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS && members.contains(node.getFullName())) {
            for (String dep : node.getDependencies()) {
                if (members.contains(dep) && !dep.equals(node.getFullName())) {
                    out.add(new de.weigend.s202.ui.rendering.TangleEdgeRenderer.Edge(node.getFullName(), dep));
                }
            }
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectInternalEdgesRec(child, members, out);
        }
    }

    /** Pick a non-tangle architecture tab to use as the source for tangle filtering. */
    private ArchitectureWfxView focusedSourceArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View focused = wm.getFocusedView();
        if (focused instanceof ArchitectureWfxView arch && !tangleViews.containsValue(arch)) {
            return arch;
        }
        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(v -> !tangleViews.containsValue(v))
                .findFirst()
                .orElse(null);
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
