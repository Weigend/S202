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

import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.graph.ArchitectureViewSettings;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.windowmanager.api.ApplicationWindow;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

import java.util.ArrayList;
import java.util.List;

/**
 * Baut die geteilte Toolbar und spiegelt sie auf die jeweils fokussierte
 * Architektur-View (Settings-Checkboxen, Undo/Redo, Zoom). Aus S202Module
 * extrahiert.
 */
public final class ToolbarController {

    private final ApplicationWindow applicationWindow;
    private final ArchitectureViewManager viewManager;
    private final Runnable openAction;

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
    private final SimpleBooleanProperty canUndo = new SimpleBooleanProperty(false);
    private final SimpleBooleanProperty canRedo = new SimpleBooleanProperty(false);

    // Tracks which view we currently mirror so we can unbind on focus change.
    private ArchitectureCanvas boundView;
    // Reusable listener so we can detach it cleanly (no-op when boundView is null).
    private ChangeListener<Number> zoomLabelListener;

    public ToolbarController(ApplicationWindow applicationWindow,
                             ArchitectureViewManager viewManager,
                             Runnable openAction) {
        this.applicationWindow = applicationWindow;
        this.viewManager = viewManager;
        this.openAction = openAction;
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

    /** Löst die aktuelle Bindung und spiegelt die (neu) fokussierte View. */
    public void rebind() {
        bindToolbarToFocusedView();
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

        ArchitectureCanvas view = focused.getArchitectureView();
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
}
