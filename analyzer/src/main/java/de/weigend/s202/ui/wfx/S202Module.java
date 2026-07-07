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
package de.weigend.s202.ui.wfx;

import de.weigend.s202.analysis.invariants.LayoutInvariantChecker;
import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.project.ProjectMapper;
import de.weigend.s202.project.ProjectStore;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.report.quality.QualityReportExporter;
import de.weigend.s202.report.quality.QualityReportInput;
import de.weigend.s202.report.quality.QualityReportModel;
import de.weigend.s202.report.quality.QualityReportOptions;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.LanguageAnalyzer;
import de.weigend.s202.reader.ProjectScanner;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.graph.ArchitectureViewSettings;
import de.weigend.s202.ui.city3d.CityModelSerializer;
import de.weigend.s202.ui.city3d.CityView3DServer;
import de.weigend.s202.ui.ArchitectureViewStyle;
import de.weigend.s202.ui.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.rendering.TangleEdgeRenderer;
import de.weigend.s202.ui.wfx.shell.ArchitectureViewManager;
import de.weigend.s202.ui.wfx.shell.Dialogs;
import de.weigend.s202.ui.wfx.shell.ProgressPublisher;
import de.weigend.s202.ui.wfx.shell.RecentDirectories;
import de.weigend.s202.ui.wfx.shell.RefactoringPreviewState;
import de.weigend.s202.ui.wfx.view.ArchitectureWfxView;
import de.weigend.s202.ui.wfx.events.CutTangleEdgeEvent;
import de.weigend.s202.ui.wfx.events.CutTangleEdgesEvent;
import de.weigend.s202.ui.wfx.events.MethodSelectionEvent;
import de.weigend.s202.ui.wfx.events.NodeSelectionEvent;
import de.weigend.s202.ui.wfx.events.MenuRequestEvent;
import de.weigend.s202.ui.wfx.events.OpenScopeEvent;
import de.weigend.s202.ui.wfx.events.OpenTangleEvent;
import de.weigend.s202.ui.wfx.events.RestoreTangleEdgeEvent;
import de.weigend.s202.ui.wfx.report.QualityReportController;
import de.weigend.s202.ui.wfx.tangles.TangleTabController;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.events.ProgressEvent;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmanager.api.ApplicationWindow;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewKind;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import jakarta.inject.Singleton;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S202 platform module: wires the WFX shell (status bar, menus, toolbar,
 * preloader splash already provided by /splash/splash.fxml) and runs the code
 * analysis pipeline. The status bar and menu bar live in their own classes;
 * this module wires them via the {@link EventBus} and reacts to
 * {@link MenuRequestEvent}s from the menu. The central docking area starts
 * empty; each source open action registers a fresh {@link ArchitectureWfxView}
 * with the {@link WindowManager} and loads its content on a background thread.
 */
@Singleton
public class S202Module implements Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(S202Module.class);
    private static final String JAVA_BYTECODE_ANALYZER = "Java bytecode";
    private static final String PYTHON_ANALYZER = "Python";
    private static final String C_ANALYZER = "C";
    private static final String GO_ANALYZER = "Go";
    private static final String MAVEN_SCANNER = "Maven";
    private static final String GRADLE_SCANNER = "Gradle";

    private final ApplicationWindow applicationWindow;
    private final ArchitectureNodeBuilder architectureNodeBuilder = new ArchitectureNodeBuilder();
    private final LayoutInvariantChecker invariantChecker = new LayoutInvariantChecker();
    private final ProjectStore projectStore = Lookup.lookup(ProjectStore.class);
    private final ProjectMapper projectMapper = Lookup.lookup(ProjectMapper.class);


    private final RecentDirectories recentDirs = new RecentDirectories();
    private final ProgressPublisher progressPublisher = new ProgressPublisher(this);

    private S202StatusBar statusBar;

    // Toolbar widgets — shared across all ArchitectureWfxView tabs.
    private Button openJarButton;
    private Spinner<Integer> depthSpinner;
    private Button refreshButton;
    private CheckBox showDependenciesCheckbox;
    private CheckBox showSccCheckbox;
    private CheckBox showPackageSccCheckbox;
    private CheckBox showWhatIfViolationsCheckbox;
    private CheckBox debugLinesCheckbox;
    private CheckBox showIconsCheckbox;
    private CheckBox showArchLevelCheckbox;
    private Button undoButton;
    private Button redoButton;
    private Button zoomOutButton;
    private Label zoomLabel;
    private Button zoomInButton;
    private Button zoomResetButton;
    private final List<Node> viewDependentToolbarNodes = new ArrayList<>();

    // Tracks the focused view's undo/redo capability — bound to the Edit menu items.
    private final javafx.beans.property.SimpleBooleanProperty canUndo = new javafx.beans.property.SimpleBooleanProperty(false);
    private final javafx.beans.property.SimpleBooleanProperty canRedo = new javafx.beans.property.SimpleBooleanProperty(false);

    // Tracks which view we currently mirror so we can unbind on focus change.
    private ArchitectureView boundView;
    // Reusable listener so we can detach it cleanly (no-op when boundView is null).
    private ChangeListener<Number> zoomLabelListener;

    private final RefactoringPreviewState previewState = new RefactoringPreviewState();
    private final ArchitectureViewManager viewManager =
            new ArchitectureViewManager(progressPublisher, previewState);
    private final TangleTabController tangleTabs =
            new TangleTabController(viewManager, previewState, progressPublisher);
    private final City3DController city3d = new City3DController(viewManager);
    private final QualityReportController qualityReport;

    public S202Module(ApplicationWindow applicationWindow) {
        this.applicationWindow = applicationWindow;
        this.qualityReport = new QualityReportController(
                viewManager, previewState, progressPublisher, recentDirs,
                () -> applicationWindow.getStage(), this::appVersion);
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
        // until the user opens sources (or invokes Windows -> New). The
        // analysis loaders create and register the first tab on demand.
    }

    private void waitForDemoPreloader() throws PlatformException {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying S202 preload", e);
        }
    }

    private void publishStatus(String message) {
        progressPublisher.status(message);
    }

    private void publishProgress(String message, double progress) {
        progressPublisher.progress(message, progress);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        installSceneStylesheet();
        installWindowIcon();

        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);

        statusBar = new S202StatusBar((EventBus) bus);
        applicationWindow.getStatusBarItems().setAll(statusBar.getNode());

        new S202MenuBar(applicationWindow, bus, canUndo, canRedo).install();
        subscribeToMenuRequests(bus);
        subscribeToNodeSelection(bus);
        subscribeToOpenScope(bus);
        subscribeToOpenTangle(bus);
        subscribeToTanglePreviewEvents(bus);

        installToolbar();

        statusBar.setMessage("Ready to analyze code. Open JARs or Python source roots to begin.");
    }

    private void subscribeToOpenTangle(EventBus<EventObject> bus) {
        bus.subscribe(OpenTangleEvent.class, ev -> {
            tangleTabs.openTangleView(ev.getMembers(), ev.getTangleKey(), ev.getTitle());
            return true;
        });
    }

    private void subscribeToOpenScope(EventBus<EventObject> bus) {
        bus.subscribe(OpenScopeEvent.class, ev -> {
            viewManager.openScopeView(ev.getScope(), ev.getArchitectureView());
            return true;
        });
    }

    private void subscribeToTanglePreviewEvents(EventBus<EventObject> bus) {
        bus.subscribe(CutTangleEdgeEvent.class, ev -> {
            tangleTabs.applyPreviewCutToViews(ev.getFrom(), ev.getTo());
            return true;
        });
        bus.subscribe(CutTangleEdgesEvent.class, ev -> {
            tangleTabs.applyPreviewCutsToViews(ev.getEdges());
            return true;
        });
        bus.subscribe(RestoreTangleEdgeEvent.class, ev -> {
            tangleTabs.restorePreviewCutInViews(ev.getFrom(), ev.getTo());
            return true;
        });
    }

    private void subscribeToMenuRequests(EventBus<EventObject> bus) {
        bus.subscribe(MenuRequestEvent.OpenJar.class, ev -> { openJarChooser(); return true; });
        bus.subscribe(MenuRequestEvent.OpenPythonSource.class, ev -> { openPythonSourceRoot(); return true; });
        bus.subscribe(MenuRequestEvent.OpenCSource.class, ev -> { openCSourceRoot(); return true; });
        bus.subscribe(MenuRequestEvent.OpenGoSource.class, ev -> { openGoModuleRoot(); return true; });
        bus.subscribe(MenuRequestEvent.OpenMavenProject.class, ev -> { openMavenProject(); return true; });
        bus.subscribe(MenuRequestEvent.OpenGradleProject.class, ev -> { openGradleProject(); return true; });
        bus.subscribe(MenuRequestEvent.SaveProject.class, ev -> { saveProject(); return true; });
        bus.subscribe(MenuRequestEvent.ExportQualityReport.class, ev -> { qualityReport.exportQualityReport(); return true; });
        bus.subscribe(MenuRequestEvent.OpenCity3DView.class, ev -> { city3d.openCity3DView(); return true; });
        bus.subscribe(MenuRequestEvent.LoadProject.class, ev -> { loadProject(); return true; });
        bus.subscribe(MenuRequestEvent.CloseProject.class, ev -> { closeProject(); return true; });
        bus.subscribe(MenuRequestEvent.Exit.class, ev -> { Platform.exit(); return true; });
        bus.subscribe(MenuRequestEvent.NewView.class, ev -> { viewManager.newArchitectureWindow(); return true; });
        bus.subscribe(MenuRequestEvent.OpenComponentView.class, ev -> { viewManager.openComponentView(); return true; });
        bus.subscribe(MenuRequestEvent.OpenHexagonalView.class, ev -> { viewManager.openHexagonalView(); return true; });
        bus.subscribe(MenuRequestEvent.CloseFocusedView.class, ev -> { viewManager.closeFocusedView(); return true; });
        bus.subscribe(MenuRequestEvent.CloseAllViews.class, ev -> { viewManager.closeAllViews(); return true; });
        bus.subscribe(MenuRequestEvent.RestoreDefaultLayout.class, ev -> {
            Lookup.lookup(WindowManager.class).restoreDefaultLayout();
            return true;
        });
        bus.subscribe(MenuRequestEvent.Undo.class, ev -> { if (boundView != null) boundView.undoWhatIf(); return true; });
        bus.subscribe(MenuRequestEvent.Redo.class, ev -> { if (boundView != null) boundView.redoWhatIf(); return true; });
    }

    private void subscribeToNodeSelection(EventBus<EventObject> bus) {
        bus.subscribe(NodeSelectionEvent.class, ev -> {
            ArchitectureWfxView focused = viewManager.focusedArchitectureView();
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

    private void installWindowIcon() {
        var stage = applicationWindow.getStage();
        if (stage == null) return;
        int size = 64;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double cx = size / 2.0, cy = size / 2.0;
        double r = size / 2.0 - 4;
        // hexagon
        double[] xs = new double[6], ys = new double[6];
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i - 90);
            xs[i] = cx + r * Math.cos(a);
            ys[i] = cy + r * Math.sin(a);
        }
        gc.setStroke(Color.web("#ffd54f"));
        gc.setLineWidth(3.5);
        gc.strokePolygon(xs, ys, 6);
        // inner circle
        double cr = r * 0.38;
        gc.setLineWidth(3.0);
        gc.strokeOval(cx - cr, cy - cr, cr * 2, cr * 2);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage img = canvas.snapshot(params, null);
        stage.getIcons().setAll(img);
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

        undoButton = new Button();
        undoButton.getStyleClass().add("toolbar-button");
        undoButton.setGraphic(toolbarIcon(MaterialDesignU.UNDO));
        undoButton.setTooltip(new Tooltip("Undo last What-If move (Ctrl+Z)"));
        undoButton.setOnAction(e -> { if (boundView != null) boundView.undoWhatIf(); });

        redoButton = new Button();
        redoButton.getStyleClass().add("toolbar-button");
        redoButton.setGraphic(toolbarIcon(MaterialDesignR.REDO));
        redoButton.setTooltip(new Tooltip("Redo What-If move (Ctrl+Shift+Z)"));
        redoButton.setOnAction(e -> { if (boundView != null) boundView.redoWhatIf(); });

        showDependenciesCheckbox = new CheckBox("Show Dependencies");
        showDependenciesCheckbox.setTooltip(new Tooltip("Toggle dependency arrows"));
        showDependenciesCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) {
                boundView.setShowDependencies(isNow);
            }
        });

        showSccCheckbox = new CheckBox("Class SCCs");
        showSccCheckbox.setTooltip(new Tooltip("Show strict class-level cycles (red)"));
        showSccCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) boundView.setShowScc(isNow);
        });

        showPackageSccCheckbox = new CheckBox("Package Cycles");
        showPackageSccCheckbox.setTooltip(new Tooltip("Show classes contributing to package-level cycles (orange)"));
        showPackageSccCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) boundView.setShowPackageScc(isNow);
        });

        showWhatIfViolationsCheckbox = new CheckBox("Show Violations");
        showWhatIfViolationsCheckbox.setTooltip(new Tooltip(
                "Toggle dashed arrows for active architecture violations"));
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
                ArchitectureViewSettings.setShowIcons(isNow);
            }
        });

        showArchLevelCheckbox = new CheckBox("Show Arch Level");
        showArchLevelCheckbox.setTooltip(new Tooltip(
                "Toggle the global architecture level (G:n) suffix next to each box's local level (L:n)"));
        showArchLevelCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) {
                ArchitectureViewSettings.setShowArchitectureLevel(isNow);
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
                showDependenciesCheckbox, showSccCheckbox, showPackageSccCheckbox,
                showWhatIfViolationsCheckbox, debugLinesCheckbox, showIconsCheckbox, showArchLevelCheckbox,
                zoomOutButton, zoomLabel, zoomInButton, zoomResetButton));

        applicationWindow.getToolbarItems().setAll(
                openJarButton, new Separator(),
                depthLabel, depthSpinner, refreshButton,
                new Separator(),
                undoButton, redoButton,
                new Separator(),
                showDependenciesCheckbox, showSccCheckbox, showPackageSccCheckbox,
                showWhatIfViolationsCheckbox, debugLinesCheckbox, showIconsCheckbox, showArchLevelCheckbox,
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

        ArchitectureWfxView focused = viewManager.focusedArchitectureView();
        boolean enabled = focused != null;
        for (Node n : viewDependentToolbarNodes) {
            n.setDisable(!enabled);
        }
        // Undo/redo buttons bind to the view's canUndo/canRedo properties.
        undoButton.disableProperty().unbind();
        redoButton.disableProperty().unbind();
        canUndo.unbind();
        canRedo.unbind();
        if (!enabled) {
            undoButton.setDisable(true);
            redoButton.setDisable(true);
            canUndo.set(false);
            canRedo.set(false);
            zoomLabel.setText("--");
            return;
        }

        ArchitectureView view = focused.getArchitectureView();
        boundView = view;
        undoButton.disableProperty().bind(view.canUndoWhatIfProperty().not());
        redoButton.disableProperty().bind(view.canRedoWhatIfProperty().not());
        canUndo.bind(view.canUndoWhatIfProperty());
        canRedo.bind(view.canRedoWhatIfProperty());

        depthSpinner.getValueFactory().setValue(view.getPackageDepth());
        showDependenciesCheckbox.setSelected(view.isShowDependencies());
        showSccCheckbox.setSelected(view.isShowScc());
        showPackageSccCheckbox.setSelected(view.isShowPackageScc());
        showWhatIfViolationsCheckbox.setSelected(view.isShowWhatIfViolations());
        debugLinesCheckbox.setSelected(view.isShowTangleDebugLines());
        showIconsCheckbox.setSelected(ArchitectureViewSettings.isShowIcons());
        showArchLevelCheckbox.setSelected(ArchitectureViewSettings.isShowArchitectureLevel());

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
        List<Path> inputs = new JarFileLoader().chooseInput(applicationWindow.getStage());
        if (!inputs.isEmpty()) {
            loadJarFiles(toFiles(inputs));
        }
    }

    private void openPythonSourceRoot() {
        List<Path> inputs = new GenericDirectoryLoader("Open Python Source", "Select Python source root")
                .chooseInput(applicationWindow.getStage());
        if (!inputs.isEmpty()) {
            loadSourceRoot(requireLanguageAnalyzer(PYTHON_ANALYZER), inputs.getFirst(), "PYTHON", "Python",
                    "Reading Python ASTs...", "No Python Modules Found",
                    "The selected source root does not contain analyzable .py files.");
        }
    }

    private void openCSourceRoot() {
        List<Path> inputs = new GenericDirectoryLoader("Open C Source", "Select C source root")
                .chooseInput(applicationWindow.getStage());
        if (!inputs.isEmpty()) {
            loadSourceRoot(requireLanguageAnalyzer(C_ANALYZER), inputs.getFirst(), "C", "C",
                    "Scanning C translation units...", "No C Sources Found",
                    "The selected source root does not contain analyzable .c files.");
        }
    }

    private void openGoModuleRoot() {
        List<Path> inputs = new GenericDirectoryLoader("Open Go Module", "Select Go module root (directory with go.mod)")
                .chooseInput(applicationWindow.getStage());
        if (!inputs.isEmpty()) {
            loadSourceRoot(requireLanguageAnalyzer(GO_ANALYZER), inputs.getFirst(), "GO", "Go",
                    "Scanning Go packages...", "No Go Sources Found",
                    "The selected directory does not contain a go.mod file or analyzable .go files.");
        }
    }

    private void openMavenProject() {
        List<Path> inputs = new MavenProjectLoader().chooseInput(applicationWindow.getStage());
        if (!inputs.isEmpty()) {
            scanMavenProjectAt(inputs.getFirst().getParent().toFile());
        }
    }

    private void openGradleProject() {
        List<Path> inputs = new GradleProjectLoader().chooseInput(applicationWindow.getStage());
        if (!inputs.isEmpty()) {
            scanGradleProjectAt(inputs.getFirst().getParent().toFile());
        }
    }

    private interface FileLoader {
        String menuLabel();

        List<Path> chooseInput(Window owner);
    }

    private final class JarFileLoader implements FileLoader {
        @Override
        public String menuLabel() {
            return "Open JAR";
        }

        @Override
        public List<Path> chooseInput(Window owner) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select JAR file(s) to analyze");
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            if (recentDirs.initialJarDirectory() != null) {
                fileChooser.setInitialDirectory(recentDirs.initialJarDirectory());
            }
            List<File> picked = fileChooser.showOpenMultipleDialog(owner);
            if (picked == null || picked.isEmpty()) {
                return List.of();
            }
            recentDirs.setLastDirectory(picked.get(0).getParentFile());
            if (picked.size() == 1) {
                return picked.stream().map(File::toPath).toList();
            }
            return SourceSetDialog.chooseSourceSet(applicationWindow.getStage(), recentDirs.lastDirectory(), picked)
                    .map(selected -> {
                        if (selected.isEmpty()) {
                            return List.<Path>of();
                        }
                        recentDirs.setLastDirectory(selected.get(0).getParentFile());
                        return selected.stream().map(File::toPath).toList();
                    })
                    .orElseGet(List::of);
        }
    }

    private final class MavenProjectLoader implements FileLoader {
        @Override
        public String menuLabel() {
            return "Open Maven Project";
        }

        @Override
        public List<Path> chooseInput(Window owner) {
            File pom = chooseProjectFile("Select Maven project root pom.xml",
                    new FileChooser.ExtensionFilter("Maven POM", "pom.xml"));
            return pom == null ? List.of() : List.of(pom.toPath());
        }
    }

    private final class GradleProjectLoader implements FileLoader {
        @Override
        public String menuLabel() {
            return "Open Gradle Project";
        }

        @Override
        public List<Path> chooseInput(Window owner) {
            File buildScript = chooseProjectFile("Select Gradle root settings.gradle or build.gradle",
                    new FileChooser.ExtensionFilter("Gradle settings/build script",
                            "settings.gradle", "settings.gradle.kts",
                            "build.gradle", "build.gradle.kts"));
            return buildScript == null ? List.of() : List.of(buildScript.toPath());
        }
    }

    private final class GenericDirectoryLoader implements FileLoader {
        private final String menuLabel;
        private final String title;

        private GenericDirectoryLoader(String menuLabel, String title) {
            this.menuLabel = menuLabel;
            this.title = title;
        }

        @Override
        public String menuLabel() {
            return menuLabel;
        }

        @Override
        public List<Path> chooseInput(Window owner) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(title);
            File initial = recentDirs.initialSourceDirectory();
            if (initial != null) {
                chooser.setInitialDirectory(initial);
            }
            File root = chooser.showDialog(owner);
            if (root == null) {
                return List.of();
            }
            recentDirs.setLastProjectDirectory(root);
            return List.of(root.toPath());
        }
    }


    private static List<File> toFiles(List<Path> paths) {
        return paths.stream().map(Path::toFile).toList();
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
        File initial = recentDirs.initialAnyDirectory();
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
        recentDirs.setLastDirectory(picked.get(0).getParentFile());

        if (picked.size() == 1) {
            loadJarFiles(picked);
            return;
        }
        SourceSetDialog.chooseSourceSet(applicationWindow.getStage(), recentDirs.lastDirectory(), picked)
                .ifPresent(selected -> {
                    if (!selected.isEmpty()) {
                        recentDirs.setLastDirectory(selected.get(0).getParentFile());
                        loadJarFiles(selected);
                    }
                });
    }

    private void scanMavenProjectAt(File root) {
        recentDirs.setLastProjectDirectory(root);
        ProjectScanner scanner = requireProjectScanner(MAVEN_SCANNER);
        runProjectScan(scanner.displayName(), root,
                () -> toScanResult(scanner.scan(root.toPath())),
                scanner.buildHint());
    }

    private void scanGradleProjectAt(File root) {
        recentDirs.setLastProjectDirectory(root);
        ProjectScanner scanner = requireProjectScanner(GRADLE_SCANNER);
        runProjectScan(scanner.displayName(), root,
                () -> toScanResult(scanner.scan(root.toPath())),
                scanner.buildHint());
    }

    private File chooseProjectFile(String title, FileChooser.ExtensionFilter filter) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(filter,
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File initial = recentDirs.initialSourceDirectory();
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

    private static ScanResult toScanResult(ProjectScanner.ProjectScanResult result) {
        return new ScanResult(
                toFiles(result.jars()),
                result.missingArtifactModules(),
                result.scannedModuleCount());
    }

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
                        recentDirs.setLastDirectory(selected.get(0).getParentFile());
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
        ArchitectureWfxView target = viewManager.createArchitectureView();
        viewManager.registerArchitectureView(target);
        final ArchitectureView view = target.getArchitectureView();
        final String fileNames = jarFiles.stream().map(File::getName).collect(Collectors.joining(", "));
        final List<String> jarPaths = jarFiles.stream().map(File::getAbsolutePath).toList();
        final List<Path> jarInputPaths = jarFiles.stream().map(File::toPath).toList();
        final LanguageAnalyzer languageAnalyzer = requireLanguageAnalyzer(JAVA_BYTECODE_ANALYZER);

        publishProgress("Analyzing: " + fileNames + " (this may take a moment)...", -1);

        Task<AnalysisResult> task = new Task<>() {
            @Override
            protected AnalysisResult call() throws Exception {
                publishProgress("Reading bytecode: " + fileNames, 0.20);
                DependencyModel rawModel = languageAnalyzer.analyze(jarInputPaths);
                return buildAnalysisResult(rawModel, jarPaths);
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
            AnalysisInputSummary summary = new AnalysisInputSummary("JAR(s)", jarFiles.size());
            yieldToPulse.setOnFinished(event -> applyAnalysisResult(summary, view, source, result));
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

    private void loadSourceRoot(LanguageAnalyzer analyzer, Path root, String sourceKind, String titlePrefix,
                                String readerProgress, String emptyTitle, String emptyMessage) {
        if (root == null || !root.toFile().isDirectory()) {
            return;
        }
        File rootFile = root.toFile();
        ArchitectureWfxView target = viewManager.createArchitectureView(titlePrefix + ": " + rootFile.getName());
        viewManager.registerArchitectureView(target);
        final ArchitectureView view = target.getArchitectureView();
        final String rootPath = rootFile.getAbsolutePath();
        final S202Project.Source source = new S202Project.Source(sourceKind, List.of(rootPath), rootPath);

        publishProgress("Analyzing " + analyzer.displayName() + " source: "
                + rootFile.getName() + " (this may take a moment)...", -1);

        Task<AnalysisResult> task = new Task<>() {
            @Override
            protected AnalysisResult call() throws Exception {
                publishProgress(readerProgress, 0.20);
                DependencyModel rawModel = analyzer.analyze(List.of(root));
                return buildAnalysisResult(rawModel, List.of(rootPath));
            }
        };

        task.setOnSucceeded(e -> {
            AnalysisResult result = task.getValue();
            if (result.rootNode() == null) {
                publishProgress("Error: " + emptyTitle, 1);
                showError(emptyTitle, emptyMessage);
                return;
            }
            publishProgress("Building JavaFX architecture view...", 0.97);

            PauseTransition yieldToPulse = new PauseTransition(Duration.millis(50));
            AnalysisInputSummary summary = new AnalysisInputSummary(analyzer.displayName() + " root(s)", 1);
            yieldToPulse.setOnFinished(event -> applyAnalysisResult(summary, view, source, result));
            yieldToPulse.play();
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            LOGGER.error("{} analysis failed", analyzer.displayName(), t);
            String msg = t != null ? t.getMessage() : "unknown error";
            publishProgress("Error: " + msg, 1);
            showError(titlePrefix + " Analysis Error",
                    "Failed to analyze " + analyzer.displayName() + " source root:\n" + msg);
        });

        Thread analyzerThread = new Thread(task, "s202-" + sourceKind.toLowerCase(Locale.ROOT) + "-analyzer");
        analyzerThread.setDaemon(true);
        analyzerThread.start();
    }

    private static LanguageAnalyzer requireLanguageAnalyzer(String displayName) {
        return Lookup.lookupAll(LanguageAnalyzer.class).stream()
                .filter(analyzer -> displayName.equalsIgnoreCase(analyzer.displayName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No language analyzer registered for " + displayName));
    }

    private static ProjectScanner requireProjectScanner(String displayName) {
        return Lookup.lookupAll(ProjectScanner.class).stream()
                .filter(scanner -> displayName.equalsIgnoreCase(scanner.displayName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No project scanner registered for " + displayName));
    }

    private static DomainComputer requireDomainComputer() {
        DomainComputer computer = Lookup.lookup(DomainComputer.class);
        if (computer == null) {
            throw new IllegalStateException("No domain computer registered");
        }
        return computer;
    }

    private AnalysisResult buildAnalysisResult(DependencyModel rawModel, List<String> sourcePaths) {
        if (rawModel.getAllClasses().isEmpty()) {
            return new AnalysisResult(rawModel, null, null, null, null, Set.of());
        }

        publishProgress("Calculating architectural levels...", 0.75);
        DomainModel calculated = requireDomainComputer().compute(rawModel);
        Set<DependencyEdge> cycleBreakEdges = calculated.getClassBackEdges();

        publishProgress("Building architecture tree...", 0.85);
        ArchitectureNode root = architectureNodeBuilder.build(calculated);
        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
        de.weigend.s202.ui.consistency.ArchitectureConsistencyDevHook
                .runIfEnabled(calculated, root);

        publishProgress("Preparing quality metrics...", 0.90);
        QualityMetrics metrics = QualityMetrics.compute(calculated);

        // Layout invariant check runs on the same background thread so
        // findings reach the FX thread together with the rest of the result.
        publishProgress("Verifying layout invariants...", 0.95);
        LayoutInvariantReport invariants = invariantChecker.check(calculated, rawModel, sourcePaths);
        return new AnalysisResult(rawModel, root, metrics, calculated, invariants, cycleBreakEdges);
    }

    private void applyAnalysisResult(AnalysisInputSummary summary, ArchitectureView view,
                                     S202Project.Source source, AnalysisResult result) {
        previewState.clear();
        // Domain model first so listeners on architectureRoot/metrics can
        // already query scoped data (e.g. quality module on package select).
        view.setDomainModel(result.domainModel());
        view.setRawDependencyModel(result.rawModel());
        view.setCycleBreakEdges(result.cycleBreakEdges());
        view.setArchitectureRootAsync(
                result.rootNode(),
                progress -> publishJavaFxBuildProgress("Building JavaFX architecture view", progress),
                () -> finishAppliedAnalysisResult(summary, view, source, result));
    }

    private void finishAppliedAnalysisResult(AnalysisInputSummary summary, ArchitectureView view,
                                             S202Project.Source source, AnalysisResult result) {
        view.setQualityMetrics(result.metrics());
        viewManager.putSource(view, source);
        viewManager.putInvariants(view, result.invariants());

        LayoutInvariantReport invariants = result.invariants();
        String invariantSuffix = invariantSuffix(invariants);
        publishProgress(String.format(
                "Loaded %d %s | %d classes | %d levels | Max level %d%s",
                summary.count(),
                summary.label(),
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
        progressPublisher.javaFxBuildProgress(label, progress);
    }

    private void saveProject() {
        ArchitectureWfxView focused = viewManager.focusedSourceArchitectureView();
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
        recentDirs.setLastProjectFileDirectory(target.getParentFile());

        ArchitectureView view = focused.getArchitectureView();
        S202Project.Source source = viewManager.sourceOf(view,
                new S202Project.Source("UNKNOWN", List.of(), null));
        S202Project project = projectMapper.toProject(
                appVersion(),
                source,
                view.getRawDependencyModel(),
                view.getDomainModel(),
                view.getArchitectureAnnotations(),
                viewManager.invariantsOf(view),
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
        recentDirs.setLastProjectFileDirectory(file.getParentFile());
        publishProgress("Loading project: " + file.getName() + "...", -1);

        Task<LoadedProject> task = new Task<>() {
            @Override
            protected LoadedProject call() throws Exception {
                S202Project project = projectStore.load(file.toPath());
                DependencyModel rawModel = projectMapper.toDependencyModel(project.dependencyModel());
                DomainModel domainModel = projectMapper.toDomainModel(project.domainModel());
                ArchitectureAnnotations annotations =
                        projectMapper.toArchitectureAnnotations(project.architectureAnnotations());
                ArchitectureNode root = architectureNodeBuilder.build(domainModel);
                new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
                de.weigend.s202.ui.consistency.ArchitectureConsistencyDevHook
                        .runIfEnabled(domainModel, root);
                QualityMetrics metrics = QualityMetrics.compute(domainModel);
                LayoutInvariantReport invariants = projectMapper.toLayoutInvariantReport(project.layoutInvariantReport());
                Set<DependencyEdge> cycleBreakEdges =
                        projectMapper.toCycleBreakEdges(project.cycleBreakEdges());
                return new LoadedProject(project, rawModel, domainModel, annotations, root, metrics, invariants, cycleBreakEdges);
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

        ArchitectureWfxView target = viewManager.createArchitectureView();
        viewManager.registerArchitectureView(target);
        ArchitectureView view = target.getArchitectureView();
        view.setArchitectureAnnotations(loaded.annotations());
        view.setDomainModel(loaded.domainModel());
        view.setRawDependencyModel(loaded.rawModel());
        view.setCycleBreakEdges(loaded.cycleBreakEdges());
        view.setArchitectureRootAsync(
                loaded.rootNode(),
                progress -> publishJavaFxBuildProgress("Building JavaFX project view", progress),
                () -> {
                    view.setQualityMetrics(loaded.metrics());
                    viewManager.putSource(view, loaded.project().source());
                    viewManager.putInvariants(view, loaded.invariants());

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
        publishProgress("Ready to analyze code. Open JARs, Python source roots, or C source roots to begin.", 0);
    }

    private void resetProjectUi() {
        viewManager.resetDocuments();
        previewState.clear();
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
        File initial = recentDirs.initialProjectFileDirectory();
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
                                 DomainModel domainModel, ArchitectureAnnotations annotations,
                                 ArchitectureNode rootNode,
                                 QualityMetrics metrics, LayoutInvariantReport invariants,
                                 Set<DependencyEdge> cycleBreakEdges) {}

    private record AnalysisInputSummary(String label, int count) {}

    private record AnalysisResult(DependencyModel rawModel, ArchitectureNode rootNode,
                                  QualityMetrics metrics, DomainModel domainModel,
                                  LayoutInvariantReport invariants,
                                  Set<DependencyEdge> cycleBreakEdges) {}

    private void showError(String title, String message) {
        Dialogs.showError(title, message);
    }
}
