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
package de.weigend.s202.ui.core.graph;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Reusable architecture level containing 4 rows with 1, 2, 3, 4 elements.
 * Row 2 can contain a nested ArchitectureLevel at position 2 (Element 2.2).
 * Can be nested inside other ArchitectureLevel instances.
 */
public class LevelPackageBox extends VBox implements GraphSelection.Selectable, ContainerBox {

    private static final String STYLE_NORMAL =
            "-fx-background-color: #fffacd; -fx-border-color: #999999; -fx-border-width: 1;";
    private static final String STYLE_SELECTED =
            "-fx-background-color: #fff3a0; -fx-border-color: #ff6600; -fx-border-width: 2;";
    private static final String STYLE_MOVED =
            "-fx-background-color: #fffacd; -fx-border-color: #1d4ed8; -fx-border-width: 2;";
    private static final String STYLE_MOVED_SELECTED =
            "-fx-background-color: #fff3a0; -fx-border-color: #1d4ed8; -fx-border-width: 3;";
    private static final String STYLE_TRANSPARENT =
            "-fx-background-color: transparent; -fx-border-width: 0;";
    // A plain package is icon and name only; opened, a thin frame holds its content together.
    private static final String STYLE_PLAIN_COLLAPSED =
            "-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 1;";
    private static final String STYLE_PLAIN_EXPANDED =
            "-fx-background-color: transparent; -fx-border-color: #b9bcc0; -fx-border-width: 1;";

    /** Gap between the level rows of plain boxes: room for the arrows and their weights. */
    public static final double PLAIN_LEVEL_GAP = 30.0;
    /** Gap between plain boxes on the same level. */
    public static final double PLAIN_SIBLING_GAP = 26.0;
    private static final double LEVEL_GAP = 6.0;
    private static final double SIBLING_GAP = 10.0;
    private static final Color PACKAGE_COLOR = Color.web("#e6c46a");

    private Label toggleIcon;
    private Label nameLabel;
    private VBox contentContainer;
    private boolean isExpanded = true;
    private final String simpleName;
    private final int staticLevel;
    private int currentLocalLevel;
    private final int architectureLevel;
    private String levelName;
    private final String fullName;
    private final Map<Integer, HBox> levelRows;
    private final boolean transparent;
    private boolean plain;
    private boolean limitRowWidth;
    private boolean selected;
    private boolean virtuallyMoved;
    private Consumer<String> selectionChangeSink;
    /** Builds the child boxes on first use; {@code null} once they exist. */
    private Runnable lazyContent;
    /** Full names of all elements {@link #lazyContent} will create, directly or in sub-packages. */
    private Set<String> pendingElements = Set.of();

    // Static callback for notifying when expand/collapse changes
    private static Runnable onExpandChangeCallback = null;

    /**
     * Sets a global callback that is called whenever any LevelPackageBox is expanded/collapsed.
     * Used to refresh dependency arrows in ArchitectureCanvas.
     */
    public static void setOnExpandChangeCallback(Runnable callback) {
        onExpandChangeCallback = callback;
    }

    public LevelPackageBox(String levelName) {
        this(levelName, -1);
    }

    public LevelPackageBox(String levelName, int level) {
        this(levelName, level, false);
    }

    public LevelPackageBox(String levelName, int level, boolean transparent) {
        this(levelName, level, transparent, null);
    }

    public LevelPackageBox(String levelName, int level, boolean transparent, String fullName) {
        this(levelName, level, transparent, fullName, -1);
    }

    /**
     * Create a new LevelPackageBox.
     *
     * @param levelName          Display name (simple name + optional level marker).
     * @param level              Local layer index, -1 if unspecified.
     * @param transparent        Pass-through visual (no border / no background).
     * @param fullName           Fully qualified package name; required for selection.
     * @param architectureLevel  Global architecture level (longest dependency-chain
     *                           depth), -1 if unknown. Shown next to the local level.
     */
    public LevelPackageBox(String levelName, int level, boolean transparent, String fullName, int architectureLevel) {
        super(transparent ? 0 : 3);
        this.simpleName = levelName;
        this.staticLevel = level;
        this.currentLocalLevel = level;
        this.architectureLevel = architectureLevel;
        this.levelName = BoxLabelFormatter.format(levelName, level, architectureLevel,
                ArchitectureViewSettings.showArchitectureLevelProperty().get());
        this.transparent = transparent;
        this.fullName = fullName;

        getStyleClass().add(transparent ? "package-box-transparent" : "package-box");

        // Apply styles directly - CSS doesn't override VBox defaults reliably
        applyBaseStyle();
        this.setPadding(new Insets(0));

        this.levelRows = new TreeMap<>();

        createHeader();

        contentContainer = new VBox(LEVEL_GAP);
        contentContainer.setPadding(transparent ? new Insets(0, 0, 0, 10) : new Insets(6, 6, 6, 20));
        contentContainer.setMaxWidth(Double.MAX_VALUE);
        ArchitectureDragController.markAsRowStack(contentContainer);

        this.getChildren().add(contentContainer);

        ArchitectureDragController.makeDraggable(this);

        // Live toggle: re-render the label whenever the global architecture-level
        // visibility flips, without rebuilding the tree.
        ArchitectureViewSettings.showArchitectureLevelProperty().addListener((obs, oldVal, newVal) -> applyLabelText());

        // Selectable click target on the package frame itself (free area outside
        // the header). The toggle icon and inner boxes consume their own clicks
        // so they do not bubble up here.
        if (!transparent && fullName != null) {
            this.setCursor(Cursor.HAND);
            this.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                handleSelectionClick(event.getClickCount());
                event.consume();
            });
        }
    }

    @Override
    public VBox rowStack(boolean api) {
        // Rows may be about to be inserted: the package's own content must
        // exist first, or a later lazy build would land on top of them.
        materialize();
        return contentContainer;
    }

    /** Returns the VBox row-stack that holds this package's child rows. */
    public VBox getContentContainer() {
        return contentContainer;
    }

    private void applyBaseStyle() {
        if (transparent) {
            this.setStyle(STYLE_TRANSPARENT);
            return;
        }
        if (virtuallyMoved) {
            this.setStyle(selected ? STYLE_MOVED_SELECTED : STYLE_MOVED);
        } else if (selected) {
            this.setStyle(STYLE_SELECTED);
        } else if (plain) {
            this.setStyle(isExpanded ? STYLE_PLAIN_EXPANDED : STYLE_PLAIN_COLLAPSED);
        } else {
            this.setStyle(STYLE_NORMAL);
        }
    }

    /**
     * Draws this package the way a graph draws a node: icon and name, no
     * filled box, no wider than its content, with room around it for arrows.
     * Call before any child is added.
     */
    public void setPlain(boolean plain) {
        if (this.plain == plain || transparent) {
            return;
        }
        this.plain = plain;
        contentContainer.setSpacing(plain ? PLAIN_LEVEL_GAP : LEVEL_GAP);
        contentContainer.setPadding(plain ? new Insets(8, 12, 10, 12) : new Insets(6, 6, 6, 20));
        setMaxWidth(plain ? Region.USE_PREF_SIZE : Region.USE_COMPUTED_SIZE);
        setMaxHeight(plain ? Region.USE_PREF_SIZE : Double.MAX_VALUE);
        applyBaseStyle();
    }

    /**
     * Lets this box share the height and the width of the level row it was
     * added to — the width only up to {@link #MAX_ROW_FILL_WIDTH} while it is
     * limited. A plain box keeps the size of its content.
     */
    @Override
    public void fillRow() {
        if (plain) {
            return;
        }
        setMaxWidth(Region.USE_COMPUTED_SIZE);
        setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(this, Priority.ALWAYS);
    }

    /**
     * Chooses how far this box grows with its level row. Unlimited (the
     * default), the boxes of a row share its whole width and end flush with
     * their container — the tidy picture for a small architecture. Limited,
     * a box grows only up to {@link #MAX_ROW_FILL_WIDTH}, so a wide sibling
     * subtree does not stretch it into a bar — what a large system needs.
     * Takes effect with the next layout pass, no rebuild required.
     */
    @Override
    public void setLimitRowWidth(boolean limit) {
        if (limitRowWidth == limit) {
            return;
        }
        limitRowWidth = limit;
        requestLayout();
    }

    @Override
    public boolean isLimitRowWidth() {
        return limitRowWidth;
    }

    /**
     * Unlimited, a box takes whatever width its row offers. Limited, it stops
     * at {@link #MAX_ROW_FILL_WIDTH}; content that needs more keeps its own
     * width. A transparent box is an invisible wrapper and always passes the
     * full width on to its children.
     */
    @Override
    protected double computeMaxWidth(double height) {
        if (transparent || !limitRowWidth) {
            return Double.MAX_VALUE;
        }
        return Math.max(prefWidth(height), MAX_ROW_FILL_WIDTH);
    }

    private void handleSelectionClick(int clickCount) {
        if (transparent || fullName == null) {
            return;
        }
        if (clickCount == 2) {
            GraphSelection.ensureSelected(this);
            GraphSelection.fireDoubleClick(fullName);
        } else {
            GraphSelection.select(this);
        }
    }

    /**
     * Create the header. The toggle icon owns its own click handler (with
     * {@code consume()}) so collapse/expand never falls through to selection.
     * The rest of the header bubbles up to the package's mouse handler and is
     * therefore part of the "free area" that selects the package.
     */
    private void createHeader() {
        HBox header = new HBox(6);
        header.getStyleClass().add("header");
        header.setPadding(transparent ? new Insets(0) : new Insets(4));
        header.setMaxWidth(Double.MAX_VALUE);
        header.setAlignment(Pos.CENTER_LEFT);

        // Toggle icon (- for expanded, + for collapsed)
        toggleIcon = new Label("−");
        toggleIcon.getStyleClass().add("toggle-icon");
        toggleIcon.setStyle("-fx-text-fill: #000000; -fx-font-size: 10px; -fx-font-weight: bold;");
        toggleIcon.setMinWidth(12);
        toggleIcon.setPrefWidth(12);
        toggleIcon.setAlignment(Pos.CENTER);
        toggleIcon.setCursor(Cursor.HAND);
        toggleIcon.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                toggleExpanded();
                event.consume();
            }
        });

        FontIcon packageIcon = new FontIcon(MaterialDesignP.PACKAGE_VARIANT_CLOSED);
        packageIcon.getStyleClass().add("architecture-icon");
        packageIcon.setIconColor(PACKAGE_COLOR);
        packageIcon.visibleProperty().bind(ArchitectureViewSettings.showIconsProperty());
        packageIcon.managedProperty().bind(ArchitectureViewSettings.showIconsProperty());

        nameLabel = new Label(levelName);
        nameLabel.getStyleClass().add("package-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        header.getChildren().addAll(toggleIcon, packageIcon, nameLabel);

        this.getChildren().add(0, header);
    }

    public boolean isExpanded() {
        return isExpanded;
    }

    /**
     * Defers the creation of this package's child boxes. A package that starts
     * collapsed needs no children in the scene graph; {@code builder} runs once,
     * on the first expand or on {@link #materialize()}.
     *
     * @param builder         adds the child boxes to this package
     * @param pendingElements full names of every class and package below this
     *                        one that does not exist as a box yet
     */
    public void setLazyContent(Runnable builder, Set<String> pendingElements) {
        this.lazyContent = Objects.requireNonNull(builder, "builder cannot be null");
        this.pendingElements = Objects.requireNonNull(pendingElements, "pendingElements cannot be null");
    }

    /** False while the child boxes of this package have not been created yet. */
    public boolean isMaterialized() {
        return lazyContent == null;
    }

    /** True when {@code fqn} is an element below this package whose box does not exist yet. */
    public boolean hasPendingElement(String fqn) {
        return lazyContent != null && pendingElements.contains(fqn);
    }

    /**
     * Creates the child boxes if that was deferred. Does not expand the
     * package: the new boxes stay hidden while it is collapsed. Idempotent.
     */
    public void materialize() {
        Runnable builder = lazyContent;
        if (builder == null) {
            return;
        }
        lazyContent = null;
        pendingElements = Set.of();
        builder.run();
    }

    public void setExpanded(boolean expanded) {
        if (this.isExpanded != expanded) {
            toggleExpanded();
        }
    }

    private void toggleExpanded() {
        isExpanded = !isExpanded;
        if (isExpanded) {
            materialize();
            toggleIcon.setText("−");
            contentContainer.setVisible(true);
            contentContainer.setManaged(true);
            contentContainer.applyCss();
        } else {
            toggleIcon.setText("+");
            contentContainer.setVisible(false);
            contentContainer.setManaged(false);
        }
        if (plain) {
            applyBaseStyle(); // the frame comes and goes with the content
        }

        if (onExpandChangeCallback != null) {
            // Defer until after the current pulse so listeners observe stable
            // bounds — running synchronously inside the pulse that just queued
            // the layout invalidation makes bounds-readers see stale values.
            Runnable callback = onExpandChangeCallback;
            javafx.application.Platform.runLater(callback);
        }
    }

    public void addToLevel(int levelNumber, Node node) {
        Objects.requireNonNull(node, "node cannot be null");

        HBox levelRow = levelRows.computeIfAbsent(levelNumber, level -> {
            HBox hbox = new HBox(plain ? PLAIN_SIBLING_GAP : SIBLING_GAP);
            // Always transparent — the row's bg used to mirror the package
            // bg (#fffacd), which becomes visible as a stripe once the package
            // switches to its selected bg. Letting the row inherit avoids that.
            hbox.setStyle("-fx-background-color: transparent;");
            hbox.setMaxWidth(Double.MAX_VALUE);
            hbox.setMaxHeight(Double.MAX_VALUE);
            hbox.setAlignment(Pos.CENTER);
            VBox.setVgrow(hbox, Priority.ALWAYS);
            ArchitectureDragController.markAsRow(hbox);

            insertLevelRowAtCorrectPosition(levelNumber, hbox);

            return hbox;
        });

        if (node instanceof ContainerBox box) {
            box.fillRow();
        }
        levelRow.getChildren().add(node);
    }

    private void insertLevelRowAtCorrectPosition(int newLevelNumber, HBox hbox) {
        int insertIndex = 0;
        for (int i = 0; i < contentContainer.getChildren().size(); i++) {
            Node child = contentContainer.getChildren().get(i);
            for (Integer levelNum : levelRows.keySet()) {
                if (levelRows.get(levelNum) == child && levelNum > newLevelNumber) {
                    insertIndex = i + 1;
                    break;
                }
            }
        }
        contentContainer.getChildren().add(insertIndex, hbox);
    }

    // ===== GraphSelection.Selectable =====

    @Override
    public String getFullName() {
        return fullName;
    }

    public void setSelectionChangeSink(Consumer<String> sink) {
        selectionChangeSink = sink;
    }

    @Override
    public Consumer<String> selectionChangeSink() {
        return selectionChangeSink;
    }

    @Override
    public void applySelectedStyle() {
        selected = true;
        applyBaseStyle();
    }

    @Override
    public void applyUnselectedStyle() {
        selected = false;
        applyBaseStyle();
    }

    /**
     * Toggle the "this box has been virtually moved" decoration. Switches to
     * a blue 2px border (3px when also selected). Blue rather than red
     * keeps the colour space reserved for actual architecture violations.
     * A previous DropShadow version of this indicator visually obscured the
     * 6px VBox-spacing gap between rows, making the drop-target gap
     * unaimable; a border stays inside the box bounds.
     */
    public void setVirtuallyMoved(boolean moved) {
        if (this.virtuallyMoved != moved) {
            this.virtuallyMoved = moved;
            applyBaseStyle();
        }
    }

    /**
     * Update the displayed architectural level. Pass {@code -1} to revert to
     * the static level baked in at construction. Idempotent.
     */
    public void setVirtualLevel(int level) {
        int shown = level >= 0 ? level : staticLevel;
        this.currentLocalLevel = shown;
        applyLabelText();
    }

    private void applyLabelText() {
        String text = BoxLabelFormatter.format(simpleName, currentLocalLevel, architectureLevel,
                ArchitectureViewSettings.showArchitectureLevelProperty().get());
        if (!text.equals(this.levelName)) {
            this.levelName = text;
            if (nameLabel != null) {
                nameLabel.setText(text);
            }
        }
    }
}
