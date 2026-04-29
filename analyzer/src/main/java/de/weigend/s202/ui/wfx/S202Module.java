package de.weigend.s202.ui.wfx;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.model.DistrictRowLevelCalculator;
import io.softwareecg.wfx.extension.uiutils.MenuUtil;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.events.ProgressEvent;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmtg.api.ApplicationWindow;
import io.softwareecg.wfx.windowmtg.api.View;
import io.softwareecg.wfx.windowmtg.api.WindowManager;
import jakarta.inject.Singleton;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * S202 platform module: builds the WFX shell (status bar, menus, toolbar, About,
 * preloader splash already provided by /splash/splash.fxml) and runs the JAR
 * analysis pipeline. The central docking area starts empty; each Open JAR
 * action registers a fresh {@link ArchitectureWfxView} with the
 * {@link WindowManager} and loads its content on a background thread.
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

    private Label statusLabel;
    private ProgressBar statusProgressBar;

    // Toolbar widgets — shared across all ArchitectureWfxView tabs.
    private Button openJarButton;
    private Spinner<Integer> depthSpinner;
    private Button refreshButton;
    private CheckBox showDependenciesCheckbox;
    private ToggleButton circuitToggle;
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

    private ArchitectureWfxView createArchitectureView() {
        viewCounter++;
        ArchitectureView view = new ArchitectureView();
        view.setStatusSink(this::publishStatus);

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
    public void start() {
        installSceneStylesheet();
        installStatusBar();
        installFileMenu();
        installWindowsMenu();
        installHelpMenu();
        installToolbar();

        statusLabel.setText("Ready to analyze bytecode. Click 'Open JAR' to begin.");
    }

    @SuppressWarnings("unchecked")
    private void installStatusBar() {
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-bar");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setAlignment(Pos.CENTER_LEFT);

        statusProgressBar = new ProgressBar(0);
        statusProgressBar.setMaxWidth(Double.MAX_VALUE);
        // hide while idle (progress == 0 or finished == 1)
        statusProgressBar.visibleProperty().bind(
                statusProgressBar.progressProperty().isNotEqualTo(0)
                        .and(statusProgressBar.progressProperty().isEqualTo(1).not()));

        // 70 / 30 split: status text left, progress bar right.
        ColumnConstraints leftCol = new ColumnConstraints();
        leftCol.setPercentWidth(70);
        ColumnConstraints rightCol = new ColumnConstraints();
        rightCol.setPercentWidth(30);

        GridPane bar = new GridPane();
        bar.getColumnConstraints().addAll(leftCol, rightCol);
        bar.setHgap(8);
        bar.setPadding(new Insets(2, 8, 2, 8));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.add(statusLabel, 0, 0);
        bar.add(statusProgressBar, 1, 0);
        GridPane.setHalignment(statusLabel, HPos.LEFT);
        GridPane.setHalignment(statusProgressBar, HPos.RIGHT);
        GridPane.setFillWidth(statusLabel, true);
        GridPane.setFillWidth(statusProgressBar, true);
        HBox.setHgrow(bar, Priority.ALWAYS);

        // Replace wfx's built-in status item (StatusBarProgress.fxml: fixed 250+200 px)
        // with our 70/30 layout. The wfx ProgressController is still subscribed to the
        // EventBus but updates an orphaned widget — harmless.
        applicationWindow.getStatusBarItems().setAll(bar);

        EventBus<ProgressEvent> bus = Lookup.lookup(EventBus.class);
        bus.subscribe(ProgressEvent.class, ev -> {
            if (ev.getMessage() != null && !ev.getMessage().isEmpty()) {
                statusLabel.setText(ev.getMessage());
            }
            statusProgressBar.setProgress(ev.getProgress());
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

    private void installFileMenu() {
        MenuItem openItem = MenuUtil.createMenuItem("file.open", "Open JAR...", e -> openJarChooser());
        openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));

        MenuItem exitItem = MenuUtil.createMenuItem("file.exit", "Exit", e -> Platform.exit());
        exitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));

        Menu fileMenu = MenuUtil.createMenu("file", "File");
        fileMenu.getItems().addAll(openItem, new SeparatorMenuItem(), exitItem);

        applicationWindow.getMenu().add(0, fileMenu);
    }

    private void installWindowsMenu() {
        MenuItem newItem = MenuUtil.createMenuItem("windows.new", "New", e -> newArchitectureWindow());
        newItem.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN));

        MenuItem closeItem = MenuUtil.createMenuItem("windows.close", "Close", e -> closeFocusedView());
        closeItem.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));

        MenuItem closeAllItem = MenuUtil.createMenuItem("windows.closeAll", "Close All", e -> closeAllViews());
        closeAllItem.setAccelerator(new KeyCodeCombination(
                KeyCode.W, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));

        MenuItem defaultLayoutItem = MenuUtil.createMenuItem(
                "windows.defaultLayout", "Default Layout",
                e -> Lookup.lookup(WindowManager.class).restoreDefaultLayout());

        Menu windowsMenu = MenuUtil.createMenu("windows", "Windows");
        windowsMenu.getItems().addAll(newItem, closeItem, closeAllItem,
                new SeparatorMenuItem(), defaultLayoutItem);

        applicationWindow.getMenu().add(windowsMenu);
    }

    private void installHelpMenu() {
        MenuItem aboutItem = MenuUtil.createMenuItem("help.about", "About...", e -> showAboutDialog());

        Menu helpMenu = MenuUtil.createMenu("help", "Help");
        helpMenu.getItems().add(aboutItem);

        applicationWindow.getMenu().add(helpMenu);
    }

    private void showAboutDialog() {
        Alert about = new Alert(Alert.AlertType.NONE);
        about.setTitle("About S202 Code Analyzer");
        about.setHeaderText("S202 Code Analyzer");
        about.setContentText("""
                Java bytecode architecture viewer.

                Analyzes JAR files, extracts package and class dependencies,
                detects cyclic dependencies (SCCs) and visualizes the layered
                architecture.

                Built on the WFX Rich Client Platform.
                """);
        about.getButtonTypes().setAll(ButtonType.CLOSE);
        about.initOwner(applicationWindow.getStage());
        about.showAndWait();
    }

    private void newArchitectureWindow() {
        Lookup.lookup(WindowManager.class).register(createArchitectureView());
    }

    private void closeFocusedView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View focused = wm.getFocusedView();
        if (focused != null) {
            wm.unregister(focused);
        }
    }

    private void closeAllViews() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        // Copy because unregister mutates the visible-views list.
        for (View v : new ArrayList<>(wm.getVisibleViews())) {
            wm.unregister(v);
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

        circuitToggle = new ToggleButton("Leiterbahn");
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
                    return new AnalysisResult(rawModel, null);
                }
                DomainModel calculated = levelCalculator.calculate(rawModel);
                ArchitectureNode root = architectureNodeBuilder.build(calculated);
                new DistrictRowLevelCalculator().assignDistrictRowLevels(root);
                return new AnalysisResult(rawModel, root);
            }
        };

        task.setOnSucceeded(e -> {
            AnalysisResult result = task.getValue();
            if (result.rootNode() == null) {
                publishProgress("Error: No classes found in JAR file(s)", 1);
                showError("No Classes Found", "The JAR file(s) do not contain any .class files");
                return;
            }
            view.setArchitectureRoot(result.rootNode());
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

    private record AnalysisResult(DependencyModel rawModel, ArchitectureNode rootNode) {}

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
