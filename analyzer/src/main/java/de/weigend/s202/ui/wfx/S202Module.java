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
    private final AnalysisPipeline pipeline;
    private final SourceOpenController sourceOpen;
    private final ProjectPersistenceController persistence;

    public S202Module(ApplicationWindow applicationWindow) {
        this.applicationWindow = applicationWindow;
        this.qualityReport = new QualityReportController(
                viewManager, previewState, progressPublisher, recentDirs,
                () -> applicationWindow.getStage(), this::appVersion);
        this.pipeline = new AnalysisPipeline(
                viewManager, previewState, progressPublisher, () -> applicationWindow.getStage());
        this.sourceOpen = new SourceOpenController(
                pipeline, progressPublisher, recentDirs, () -> applicationWindow.getStage());
        this.persistence = new ProjectPersistenceController(
                viewManager, previewState, progressPublisher, recentDirs,
                () -> applicationWindow.getStage(), this::appVersion,
                () -> {
                    boundView = null;
                    zoomLabelListener = null;
                    bindToolbarToFocusedView();
                });
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
        bus.subscribe(MenuRequestEvent.OpenJar.class, ev -> { sourceOpen.openJarChooser(); return true; });
        bus.subscribe(MenuRequestEvent.OpenPythonSource.class, ev -> { sourceOpen.openPythonSourceRoot(); return true; });
        bus.subscribe(MenuRequestEvent.OpenCSource.class, ev -> { sourceOpen.openCSourceRoot(); return true; });
        bus.subscribe(MenuRequestEvent.OpenGoSource.class, ev -> { sourceOpen.openGoModuleRoot(); return true; });
        bus.subscribe(MenuRequestEvent.OpenMavenProject.class, ev -> { sourceOpen.openMavenProject(); return true; });
        bus.subscribe(MenuRequestEvent.OpenGradleProject.class, ev -> { sourceOpen.openGradleProject(); return true; });
        bus.subscribe(MenuRequestEvent.SaveProject.class, ev -> { persistence.saveProject(); return true; });
        bus.subscribe(MenuRequestEvent.ExportQualityReport.class, ev -> { qualityReport.exportQualityReport(); return true; });
        bus.subscribe(MenuRequestEvent.OpenCity3DView.class, ev -> { city3d.openCity3DView(); return true; });
        bus.subscribe(MenuRequestEvent.LoadProject.class, ev -> { persistence.loadProject(); return true; });
        bus.subscribe(MenuRequestEvent.CloseProject.class, ev -> { persistence.closeProject(); return true; });
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
        openJarButton.setOnAction(e -> sourceOpen.openAnyChooser());

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


    /**
     * Adapter result so the Maven and Gradle scanner result records can flow
     * through a shared post-scan handler without bleeding their concrete
     * record types into S202Module.
     */

    @Override
    public void stop() {
        // nothing to release
    }


    private String appVersion() {
        String version = getVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }


    private void showError(String title, String message) {
        Dialogs.showError(title, message);
    }
}
