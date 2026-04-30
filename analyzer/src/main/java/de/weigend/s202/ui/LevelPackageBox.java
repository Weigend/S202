package de.weigend.s202.ui;

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

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reusable architecture level containing 4 rows with 1, 2, 3, 4 elements.
 * Row 2 can contain a nested ArchitectureLevel at position 2 (Element 2.2).
 * Can be nested inside other ArchitectureLevel instances.
 */
public class LevelPackageBox extends VBox implements GraphSelection.Selectable {

    private static final String STYLE_NORMAL =
            "-fx-background-color: #fffacd; -fx-border-color: #999999; -fx-border-width: 1;";
    private static final String STYLE_SELECTED =
            "-fx-background-color: #fff3a0; -fx-border-color: #ff6600; -fx-border-width: 2;";
    private static final String STYLE_TRANSPARENT =
            "-fx-background-color: transparent; -fx-border-width: 0;";

    private Label toggleIcon;
    private VBox contentContainer;
    private boolean isExpanded = true;
    private final String levelName;
    private final String fullName;
    private final Map<Integer, HBox> levelRows;
    private final boolean transparent;
    private boolean selected;

    // Static callback for notifying when expand/collapse changes
    private static Runnable onExpandChangeCallback = null;

    /**
     * Sets a global callback that is called whenever any LevelPackageBox is expanded/collapsed.
     * Used to refresh dependency arrows in ArchitectureView.
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

    /**
     * Create a new LevelPackageBox.
     *
     * @param levelName    Display name (simple name + optional level marker).
     * @param level        Architectural level, -1 if unspecified.
     * @param transparent  Pass-through visual (no border / no background).
     * @param fullName     Fully qualified package name; required for selection.
     */
    public LevelPackageBox(String levelName, int level, boolean transparent, String fullName) {
        super(transparent ? 0 : 3);
        this.levelName = level >= 0 ? levelName + " (L:" + level + ")" : levelName;
        this.transparent = transparent;
        this.fullName = fullName;

        // Apply styles directly - CSS doesn't override VBox defaults reliably
        applyBaseStyle();
        this.setPadding(new Insets(0));
        this.setMaxWidth(Double.MAX_VALUE);

        this.levelRows = new TreeMap<>();

        createHeader();

        contentContainer = new VBox(6);
        contentContainer.setPadding(transparent ? new Insets(0, 0, 0, 10) : new Insets(6, 6, 6, 20));
        contentContainer.setMaxWidth(Double.MAX_VALUE);

        this.getChildren().add(contentContainer);

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

    private void applyBaseStyle() {
        if (transparent) {
            this.setStyle(STYLE_TRANSPARENT);
        } else {
            this.setStyle(selected ? STYLE_SELECTED : STYLE_NORMAL);
        }
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
        header.setPadding(transparent ? new Insets(0) : new Insets(4));
        header.setMaxWidth(Double.MAX_VALUE);
        header.setAlignment(Pos.CENTER_LEFT);

        // Toggle icon (- for expanded, + for collapsed)
        toggleIcon = new Label("−");
        toggleIcon.getStyleClass().add("toggle-icon");
        toggleIcon.setCursor(Cursor.HAND);
        toggleIcon.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                toggleExpanded();
                event.consume();
            }
        });

        Label nameLabel = new Label(levelName);
        nameLabel.getStyleClass().add("package-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        header.getChildren().addAll(toggleIcon, nameLabel);

        this.getChildren().add(0, header);
    }

    public void setExpanded(boolean expanded) {
        if (this.isExpanded != expanded) {
            toggleExpanded();
        }
    }

    private void toggleExpanded() {
        isExpanded = !isExpanded;
        if (isExpanded) {
            toggleIcon.setText("−");
            contentContainer.setVisible(true);
            contentContainer.setManaged(true);
            contentContainer.applyCss();
        } else {
            toggleIcon.setText("+");
            contentContainer.setVisible(false);
            contentContainer.setManaged(false);
        }

        if (onExpandChangeCallback != null) {
            onExpandChangeCallback.run();
        }
    }

    public void addToLevel(int levelNumber, Node node) {
        Objects.requireNonNull(node, "node cannot be null");

        HBox levelRow = levelRows.computeIfAbsent(levelNumber, level -> {
            HBox hbox = new HBox(10);
            // Always transparent — the row's bg used to mirror the package
            // bg (#fffacd), which becomes visible as a stripe once the package
            // switches to its selected bg. Letting the row inherit avoids that.
            hbox.setStyle("-fx-background-color: transparent;");
            hbox.setMaxWidth(Double.MAX_VALUE);
            hbox.setMaxHeight(Double.MAX_VALUE);
            hbox.setAlignment(Pos.CENTER);
            VBox.setVgrow(hbox, Priority.ALWAYS);

            insertLevelRowAtCorrectPosition(levelNumber, hbox);

            return hbox;
        });

        if (node instanceof LevelPackageBox) {
            ((Region) node).setMaxWidth(Double.MAX_VALUE);
            ((Region) node).setMaxHeight(Double.MAX_VALUE);
            HBox.setHgrow(node, Priority.ALWAYS);
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
}
