package de.weigend.s202.ui.wfx;

import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.model.DistrictRowLevelCalculator;
import de.weigend.s202.ui.wfx.events.NodeSelectionEvent;
import de.weigend.s202.ui.wfx.events.MenuRequestEvent;
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
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
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
    private final LevelCalculator levelCalculator = new LevelCalculator();
    private final ArchitectureNodeBuilder architectureNodeBuilder = new ArchitectureNodeBuilder();

    private int viewCounter;
    private File lastDirectory;

    private S202StatusBar statusBar;

    // Toolbar widgets — shared across all ArchitectureWfxView tabs.
    private Button openJarButton;
    private Spinner<Integer> depthSpinner;
    private Button refreshButton;
    private CheckBox showDependenciesCheckbox;
    private CheckBox circuitToggle;
    private CheckBox showSccCheckbox;
    private Button zoomOutButton;
    private Label zoomLabel;
    private Button zoomInButton;
    private Button zoomResetButton;
    private final List<Node> viewDependentToolbarNodes = new ArrayList<>();

    // Tracks which view we currently mirror so we can unbind on focus change.
    private ArchitectureView boundView;
    // Reusable listener so we can detach it cleanly (no-op when boundView is null).
    private ChangeListener<Number> zoomLabelListener;

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
        // No view registered up-front: the central docking area stays empty
        // until the user loads a JAR (or invokes Windows → New). loadJarFiles()
        // creates and registers the first tab on demand.
    }

    @SuppressWarnings("unchecked")
    private ArchitectureWfxView createArchitectureView() {
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
                "Architecture " + viewCounter,
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

        installToolbar();

        statusBar.setMessage("Ready to analyze bytecode. Click 'Open JAR' to begin.");
    }

    private void subscribeToMenuRequests(EventBus<EventObject> bus) {
        bus.subscribe(MenuRequestEvent.OpenJar.class, ev -> { openJarChooser(); return true; });
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
        var url = getClass().getResource("/de/weigend/s202/ui/styles.css");
        if (url != null) {
            stage.getScene().getStylesheets().add(url.toExternalForm());
            LOGGER.info("Attached stylesheet to Scene: {}", url);
        }
    }

    private void newArchitectureWindow() {
        Lookup.lookup(WindowManager.class).register(createArchitectureView());
    }

    private void closeFocusedView() {
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

    private void closeAllViews() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        // "Close All" means "close all open documents" — the user's analyses,
        // not the side panels. Filter to DOCUMENTs so TOOLs (Outline, Quality)
        // stay alive.
        for (View v : new ArrayList<>(wm.getVisibleViews())) {
            if (v.getKind() == ViewKind.DOCUMENT) {
                wm.closeView(v);
            }
        }
    }

    private void installToolbar() {
        openJarButton = new Button("Open JAR");
        openJarButton.setId("toolbar.open");
        openJarButton.getStyleClass().add("toolbar-button");
        openJarButton.setGraphic(toolbarIcon(MaterialDesignF.FOLDER_OPEN));
        openJarButton.setTooltip(new Tooltip("Analyse a JAR file"));
        openJarButton.setOnAction(e -> openJarChooser());

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
                showDependenciesCheckbox, circuitToggle, showSccCheckbox,
                zoomOutButton, zoomLabel, zoomInButton, zoomResetButton));

        applicationWindow.getToolbarItems().setAll(
                openJarButton, new Separator(),
                depthLabel, depthSpinner, refreshButton,
                new Separator(),
                showDependenciesCheckbox, circuitToggle, showSccCheckbox,
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
        fileChooser.setTitle("Select JAR File(s) to Analyze");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            fileChooser.setInitialDirectory(lastDirectory);
        }
        List<File> selected = fileChooser.showOpenMultipleDialog(applicationWindow.getStage());
        if (selected != null && !selected.isEmpty()) {
            lastDirectory = selected.get(0).getParentFile();
            loadJarFiles(selected);
        }
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
        ArchitectureWfxView target = createArchitectureView();
        Lookup.lookup(WindowManager.class).register(target);
        final ArchitectureView view = target.getArchitectureView();
        final String fileNames = jarFiles.stream().map(File::getName).collect(Collectors.joining(", "));
        final List<String> jarPaths = jarFiles.stream().map(File::getAbsolutePath).toList();

        publishProgress("Analyzing: " + fileNames + " (this may take a moment)...", -1);

        Task<AnalysisResult> task = new Task<>() {
            @Override
            protected AnalysisResult call() throws Exception {
                DependencyModel rawModel = rawAnalyzer.analyzeMultiple(jarPaths);
                if (rawModel.getAllClasses().isEmpty()) {
                    return new AnalysisResult(rawModel, null, null, null);
                }
                DomainModel calculated = levelCalculator.calculate(rawModel);
                ArchitectureNode root = architectureNodeBuilder.build(calculated);
                new DistrictRowLevelCalculator().assignDistrictRowLevels(root);
                QualityMetrics metrics = QualityMetrics.compute(calculated);
                return new AnalysisResult(rawModel, root, metrics, calculated);
            }
        };

        task.setOnSucceeded(e -> {
            AnalysisResult result = task.getValue();
            if (result.rootNode() == null) {
                publishProgress("Error: No classes found in JAR file(s)", 1);
                showError("No Classes Found", "The JAR file(s) do not contain any .class files");
                return;
            }
            // Domain model first so listeners on architectureRoot/metrics can
            // already query scoped data (e.g. quality module on package select).
            view.setDomainModel(result.domainModel());
            view.setArchitectureRoot(result.rootNode());
            view.setQualityMetrics(result.metrics());
            publishProgress(String.format(
                    "Loaded %d JAR(s) | %d classes | %d levels | Max level %d",
                    jarFiles.size(),
                    result.rawModel().getAllClasses().size(),
                    result.rootNode().getLevelCount(),
                    result.rootNode().getMaxLevel()), 1);
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

    private record AnalysisResult(DependencyModel rawModel, ArchitectureNode rootNode,
                                  QualityMetrics metrics, DomainModel domainModel) {}

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
