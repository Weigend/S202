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
package de.weigend.s202.ui.shell;

import de.weigend.s202.ui.core.model.EdgeKindGroup;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.graph.ArchitectureViewSettings;
import de.weigend.s202.ui.core.matrix.MatrixView;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.MatrixWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.windowmanager.api.ApplicationWindow;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Baut die geteilte Toolbar und spiegelt sie auf den aktiven Architektur-Tab
 * (Settings-Checkboxen, Undo/Redo, Zoom) — den, in dem der Benutzer arbeitet,
 * auch während der Fokus in einem Panel liegt. Aus S202Module extrahiert.
 */
public final class ToolbarController {

    private final ApplicationWindow applicationWindow;
    private final ArchitectureViewManager viewManager;
    private final Runnable openAction;
    private final Runnable exportImageAction;
    private final Runnable resetRefactoringsAction;

    private static final String EDGE_KINDS = "Edge Kinds";
    private static final String PLAN = "Plan";

    // Toolbar widgets — shared across all ArchitectureWfxView tabs.
    private Button openJarButton;
    private Spinner<Integer> depthSpinner;
    private Button refreshButton;
    private CheckBox showDependenciesCheckbox;
    private CheckBox showSccCheckbox;
    private CheckBox showPackageSccCheckbox;
    private CheckBox limitBoxWidthCheckbox;
    private MenuButton edgeKindsButton;
    private final Map<EdgeKindGroup, CheckMenuItem> edgeKindItems = new EnumMap<>(EdgeKindGroup.class);
    private CheckBox showWhatIfViolationsCheckbox;
    private CheckBox showIconsCheckbox;
    private CheckBox showArchLevelCheckbox;
    private Button undoButton;
    private Button redoButton;
    private Button resetRefactoringsButton;
    private CheckBox showPlanCheckbox;
    private Button matrixButton;
    private Button exportImageButton;
    private Button zoomOutButton;
    private Label zoomLabel;
    private Button zoomInButton;
    private Button zoomResetButton;
    private Button zoomFitButton;
    private final List<Node> viewDependentToolbarNodes = new ArrayList<>();
    private final List<Node> pictureOnlyNodes = new ArrayList<>();

    // Tracks the focused view's undo/redo capability — bound to the Edit menu items.
    private final SimpleBooleanProperty canUndo = new SimpleBooleanProperty(false);
    private final SimpleBooleanProperty canRedo = new SimpleBooleanProperty(false);

    // Tracks which view we currently mirror so we can unbind on focus change.
    private ArchitectureCanvas boundView;
    /** The matrix of {@link #boundView}, if the user works in its tab. */
    private MatrixView boundMatrix;
    private ReadOnlyDoubleProperty boundZoom;
    // Reusable listener so we can detach it cleanly (no-op when boundView is null).
    private ChangeListener<Number> zoomLabelListener;
    /** Keeps the widgets at what the bound view shows, whoever changes it. */
    private final ChangeListener<Object> settingsListener = (obs, was, isNow) -> mirrorSettings();

    public ToolbarController(ApplicationWindow applicationWindow,
                             ArchitectureViewManager viewManager,
                             Runnable openAction,
                             Runnable exportImageAction,
                             Runnable resetRefactoringsAction) {
        this.applicationWindow = applicationWindow;
        this.viewManager = viewManager;
        this.openAction = openAction;
        this.exportImageAction = exportImageAction;
        this.resetRefactoringsAction = resetRefactoringsAction;
    }

    /** Undo/Redo-Fähigkeit der fokussierten View — fürs Edit-Menü. */
    public BooleanProperty canUndoProperty() {
        return canUndo;
    }

    public BooleanProperty canRedoProperty() {
        return canRedo;
    }

    /** Die aktuell gespiegelte View (oder {@code null}) — für Menü-Undo/Redo. */
    public ArchitectureCanvas boundView() {
        return boundView;
    }

    public void install() {
        openJarButton = new Button("Open");
        openJarButton.setId("toolbar.open");
        openJarButton.getStyleClass().add("toolbar-button");
        openJarButton.setGraphic(toolbarIcon(MaterialDesignF.FOLDER_OPEN));
        openJarButton.setTooltip(new Tooltip("Open JAR(s), Maven pom.xml, or Gradle build script"));
        openJarButton.setOnAction(e -> openAction.run());

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
        refreshButton.setTooltip(new Tooltip(
                "Build the picture anew, with as many levels of packages open as Depth says"));
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

        resetRefactoringsButton = new Button();
        resetRefactoringsButton.setId("toolbar.resetRefactorings");
        resetRefactoringsButton.getStyleClass().add("toolbar-button");
        resetRefactoringsButton.setGraphic(toolbarIcon(MaterialDesignR.RESTORE));
        resetRefactoringsButton.setTooltip(new Tooltip(
                "Reset all refactorings of this analysis: moves, API mapping, cuts"));
        resetRefactoringsButton.setOnAction(e -> resetRefactoringsAction.run());

        matrixButton = new Button("Matrix");
        matrixButton.setId("toolbar.matrix");
        matrixButton.getStyleClass().add("toolbar-button");
        matrixButton.setGraphic(toolbarIcon(MaterialDesignG.GRID));
        matrixButton.setTooltip(new Tooltip(
                "Open the dependencies of this view as a matrix, in a tab of its own.\n"
                        + "Layered, scope and tangle views have one."));
        matrixButton.setOnAction(e -> {
            if (boundView != null) viewManager.openMatrix(boundView);
        });

        showPlanCheckbox = new CheckBox(PLAN);
        showPlanCheckbox.setId("toolbar.showPlan");
        showPlanCheckbox.setTooltip(new Tooltip(
                """
                On: what is planned - moves, planned packages, cuts - is applied, here and in all panels.
                Off: the code as it was analyzed. The plan is kept.
                The number says how many changes are planned."""));
        showPlanCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) boundView.setShowPlan(isNow);
        });

        exportImageButton = new Button();
        exportImageButton.setId("toolbar.exportImage");
        exportImageButton.getStyleClass().add("toolbar-button");
        exportImageButton.setGraphic(toolbarIcon(MaterialDesignC.CAMERA_OUTLINE));
        exportImageButton.setTooltip(new Tooltip("Save this view as an image (Ctrl+E)"));
        exportImageButton.setOnAction(e -> exportImageAction.run());

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

        limitBoxWidthCheckbox = new CheckBox("Limit Box Width");
        limitBoxWidthCheckbox.setTooltip(new Tooltip(
                "Keep package boxes compact instead of stretching them across their row.\n"
                        + "Off: boxes end flush with their container (small architectures).\n"
                        + "On: boxes stop at a maximum width (large systems)."));
        limitBoxWidthCheckbox.selectedProperty().addListener((obs, was, isNow) -> {
            if (boundView != null) boundView.setLimitBoxWidth(isNow);
        });

        edgeKindsButton = new MenuButton(EDGE_KINDS);
        edgeKindsButton.setId("toolbar.edgeKinds");
        edgeKindsButton.getStyleClass().add("toolbar-button");
        edgeKindsButton.setTooltip(new Tooltip(
                "Choose the kinds of dependency to draw.\n"
                        + "A dependency disappears when everything it consists of is hidden;\n"
                        + "it still counts for levels, cycles and violations."));
        for (EdgeKindGroup group : EdgeKindGroup.values()) {
            CheckMenuItem item = new CheckMenuItem(group.label());
            item.setSelected(true);
            item.setOnAction(e -> applyEdgeKinds());
            edgeKindItems.put(group, item);
            edgeKindsButton.getItems().add(item);
        }

        zoomOutButton = new Button("−");
        zoomOutButton.getStyleClass().add("toolbar-zoom-button");
        zoomOutButton.setTooltip(new Tooltip("Zoom Out (Ctrl+Scroll Down)"));
        zoomOutButton.setOnAction(e -> zoom(ArchitectureCanvas::zoomOut, MatrixView::zoomOut));

        zoomLabel = new Label("100%");
        zoomLabel.getStyleClass().add("toolbar-zoom-label");

        zoomInButton = new Button("+");
        zoomInButton.getStyleClass().add("toolbar-zoom-button");
        zoomInButton.setTooltip(new Tooltip("Zoom In (Ctrl+Scroll Up)"));
        zoomInButton.setOnAction(e -> zoom(ArchitectureCanvas::zoomIn, MatrixView::zoomIn));

        zoomResetButton = new Button("1:1");
        zoomResetButton.getStyleClass().add("toolbar-zoom-button");
        zoomResetButton.setTooltip(new Tooltip("Reset Zoom to 100%"));
        zoomResetButton.setOnAction(e -> zoom(ArchitectureCanvas::zoomReset, matrix -> matrix.setZoom(1.0)));

        zoomFitButton = new Button("Fit");
        zoomFitButton.getStyleClass().add("toolbar-zoom-button");
        zoomFitButton.setTooltip(new Tooltip("Zoom so that the whole picture fits into the window"));
        zoomFitButton.setOnAction(e -> zoom(ArchitectureCanvas::zoomToFit, MatrixView::zoomToFit));

        HBox zoomGroup = new HBox(2, zoomOutButton, zoomLabel, zoomInButton);
        zoomGroup.setAlignment(Pos.CENTER_LEFT);

        pictureOnlyNodes.addAll(List.of(depthSpinner, refreshButton, showDependenciesCheckbox,
                showIconsCheckbox, showArchLevelCheckbox));

        // Everything except the Open JAR button is view-dependent.
        viewDependentToolbarNodes.addAll(List.of(
                depthLabel, depthSpinner, refreshButton, matrixButton,
                showDependenciesCheckbox, showSccCheckbox, showPackageSccCheckbox,
                showWhatIfViolationsCheckbox, showIconsCheckbox, showArchLevelCheckbox,
                limitBoxWidthCheckbox, edgeKindsButton,
                zoomOutButton, zoomLabel, zoomInButton, zoomResetButton, zoomFitButton,
                resetRefactoringsButton, showPlanCheckbox, exportImageButton));

        applicationWindow.getToolbarItems().setAll(
                openJarButton, new Separator(),
                depthLabel, depthSpinner, refreshButton, matrixButton,
                new Separator(),
                undoButton, redoButton, resetRefactoringsButton, showPlanCheckbox,
                new Separator(),
                showDependenciesCheckbox, showSccCheckbox, showPackageSccCheckbox,
                showWhatIfViolationsCheckbox, showIconsCheckbox, showArchLevelCheckbox,
                limitBoxWidthCheckbox, edgeKindsButton,
                new Separator(),
                zoomGroup, zoomResetButton, zoomFitButton,
                new Separator(),
                exportImageButton);

        // Retarget the toolbar whenever another architecture tab becomes the active one.
        LookupContext active = Lookup.lookup(WindowManager.class).getActiveLookup();
        active.observe(ArchitectureCanvas.class, canvases -> bindToolbarToFocusedView());
        // From a picture to its matrix and back the canvas is the same one.
        active.observe(MatrixWfxView.class, matrices -> bindToolbarToFocusedView());
        bindToolbarToFocusedView();
    }

    /**
     * Detach toolbar widgets from the previously focused view, then mirror the
     * settings of the currently focused view. If no architecture view is
     * focused, disable everything except the Open JAR button.
     */
    private void bindToolbarToFocusedView() {
        if (boundZoom != null && zoomLabelListener != null) {
            boundZoom.removeListener(zoomLabelListener);
        }
        if (boundView != null) {
            settingsOf(boundView).forEach(setting -> setting.removeListener(settingsListener));
        }
        boundView = null;
        boundMatrix = null;
        boundZoom = null;
        zoomLabelListener = null;

        ArchitectureCanvas view = viewManager.activeCanvas();
        boolean enabled = view != null;
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

        boundView = view;
        MatrixWfxView matrixTab = Lookup.lookup(WindowManager.class).getActiveLookup().lookup(MatrixWfxView.class);
        boundMatrix = matrixTab == null ? null : matrixTab.getMatrix();
        undoButton.disableProperty().bind(view.canUndoWhatIfProperty().not());
        redoButton.disableProperty().bind(view.canRedoWhatIfProperty().not());
        canUndo.bind(view.canUndoWhatIfProperty());
        canRedo.bind(view.canRedoWhatIfProperty());

        settingsOf(view).forEach(setting -> setting.addListener(settingsListener));
        mirrorSettings();

        boundZoom = boundMatrix == null ? view.zoomFactorProperty() : boundMatrix.zoomProperty();
        updateZoomLabel(boundZoom.get());
        zoomLabelListener = (obs, was, isNow) -> updateZoomLabel(isNow.doubleValue());
        boundZoom.addListener(zoomLabelListener);
    }

    /**
     * What a view can be set to. The root is among them because a tab is told
     * that it is a tangle view while its first content is being built.
     */
    private static List<ObservableValue<?>> settingsOf(ArchitectureCanvas view) {
        return List.of(view.packageDepthProperty(),
                view.showDependenciesProperty(), view.showSccProperty(), view.showPackageSccProperty(),
                view.showWhatIfViolationsProperty(), view.limitBoxWidthProperty(),
                view.hiddenEdgeKindsProperty(), view.architectureRootProperty(),
                view.planProperty());
    }

    private void mirrorSettings() {
        ArchitectureCanvas view = boundView;
        if (view == null) {
            return;
        }
        depthSpinner.getValueFactory().setValue(view.getPackageDepth());
        showDependenciesCheckbox.setSelected(view.isShowDependencies());
        showSccCheckbox.setSelected(view.isShowScc());
        showPackageSccCheckbox.setSelected(view.isShowPackageScc());
        showWhatIfViolationsCheckbox.setSelected(view.isShowWhatIfViolations());
        showPlanCheckbox.setSelected(view.isShowPlan());
        int planned = view.getPlan().planned();
        showPlanCheckbox.setText(planned == 0 ? PLAN : PLAN + " (" + planned + ")");
        // A tangle view draws back-edges and both kinds of cycle in its one
        // arrow layer, explained by its own legend; the overlays would only
        // paint the same dependencies a second time.
        boolean matrix = boundMatrix != null;
        matrixButton.setDisable(!view.hasMatrix());
        // What belongs to the picture alone has no effect on the matrix.
        for (Node pictureOnly : pictureOnlyNodes) {
            pictureOnly.setDisable(matrix);
        }
        boolean tangleView = view.getTangleScope() != null || matrix;
        showSccCheckbox.setDisable(tangleView);
        showPackageSccCheckbox.setDisable(tangleView);
        showWhatIfViolationsCheckbox.setDisable(tangleView);
        // ... and its nodes are plain: icon and name, no box that could grow.
        limitBoxWidthCheckbox.setSelected(view.isLimitBoxWidth());
        limitBoxWidthCheckbox.setDisable(tangleView);
        edgeKindItems.forEach((group, item) -> item.setSelected(!view.getHiddenEdgeKinds().contains(group)));
        showHiddenEdgeKinds();
        showIconsCheckbox.setSelected(ArchitectureViewSettings.isShowIcons());
        showArchLevelCheckbox.setSelected(ArchitectureViewSettings.isShowArchitectureLevel());
    }

    /** Zooms what the user looks at: the matrix or the picture. */
    private void zoom(Consumer<ArchitectureCanvas> picture, Consumer<MatrixView> matrix) {
        if (boundMatrix != null) {
            matrix.accept(boundMatrix);
        } else if (boundView != null) {
            picture.accept(boundView);
        }
    }

    private void applyEdgeKinds() {
        if (boundView != null) {
            boundView.setHiddenEdgeKinds(edgeKindItems.entrySet().stream()
                    .filter(entry -> !entry.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet()));
        }
        showHiddenEdgeKinds();
    }

    /** A picture with something left out says so. */
    private void showHiddenEdgeKinds() {
        long shown = edgeKindItems.values().stream().filter(CheckMenuItem::isSelected).count();
        edgeKindsButton.setText(shown == edgeKindItems.size() ? EDGE_KINDS
                : EDGE_KINDS + " (" + shown + " of " + edgeKindItems.size() + ")");
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
}
