package de.weigend.s202.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

/**
 * Reusable box representing a single class element in the architecture
 * hierarchy. Selection is delegated to {@link GraphSelection} so packages and
 * classes share the same single-selection slot (mutually exclusive).
 */
public class LevelClassBox extends HBox implements GraphSelection.Selectable {

    private static final Color CLASS_COLOR = Color.web("#7fb3ff");
    private static final Color INTERFACE_COLOR = Color.web("#4caf50");

    private final String fullClassName;
    private final Label nameLabel;

    public LevelClassBox(String name) {
        this(name, -1, null, false);
    }

    public LevelClassBox(String name, int level) {
        this(name, level, null, false);
    }

    public LevelClassBox(String name, int level, String fullClassName) {
        this(name, level, fullClassName, false);
    }

    public LevelClassBox(String name, int level, String fullClassName, boolean isInterface) {
        super(4);
        this.fullClassName = fullClassName;

        this.getStyleClass().add("class-box");
        this.setAlignment(Pos.CENTER);
        this.setCursor(Cursor.HAND);
        // .class-box's font-size and HBox's default maxHeight=MAX_VALUE would
        // otherwise let the levelRow's vgrow stretch us vertically.
        this.setMaxHeight(Region.USE_PREF_SIZE);

        FontIcon icon = isInterface
                ? new FontIcon(MaterialDesignA.ALPHA_I_CIRCLE)
                : new FontIcon(MaterialDesignA.ALPHA_C_CIRCLE);
        icon.getStyleClass().add("architecture-icon");
        icon.setIconColor(isInterface ? INTERFACE_COLOR : CLASS_COLOR);
        icon.visibleProperty().bind(ArchitectureView.showIconsProperty());
        icon.managedProperty().bind(ArchitectureView.showIconsProperty());

        nameLabel = new Label(level >= 0 ? name + " (L:" + level + ")" : name);
        nameLabel.setWrapText(true);
        // text-fill doesn't cascade through HBox to descendant Label, so the
        // dark theme's derived text color (light on -fx-base #3c3f41) would win.
        nameLabel.setStyle("-fx-text-fill: #000000;");

        this.getChildren().addAll(icon, nameLabel);

        // Single click toggles selection. Double click ensures the class stays
        // selected (no toggle off) and notifies the double-click listener so
        // external panels — e.g. the outline explorer — can reveal the class.
        this.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (event.getClickCount() == 2) {
                GraphSelection.ensureSelected(this);
                GraphSelection.fireDoubleClick(fullClassName);
            } else {
                GraphSelection.select(this);
            }
            // Prevent the click from bubbling up to the enclosing package's
            // selection handler.
            event.consume();
        });
    }

    /** Displayed label text (incl. "(L:n)" suffix when set). */
    public String getText() {
        return nameLabel.getText();
    }

    /** @deprecated use {@link GraphSelection#getCurrentFullName()} */
    @Deprecated
    public static String getSelectedClassName() {
        return GraphSelection.getCurrentFullName();
    }

    // ===== GraphSelection.Selectable =====

    @Override
    public String getFullName() {
        return fullClassName;
    }

    @Override
    public void applySelectedStyle() {
        this.getStyleClass().remove("class-box");
        if (!this.getStyleClass().contains("class-box-selected")) {
            this.getStyleClass().add("class-box-selected");
        }
    }

    @Override
    public void applyUnselectedStyle() {
        this.getStyleClass().remove("class-box-selected");
        if (!this.getStyleClass().contains("class-box")) {
            this.getStyleClass().add("class-box");
        }
    }
}
