package de.weigend.s202.ui.wfx;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Modal dialog for staging a set of JAR files before kicking off an analysis.
 * Lets the user accumulate files from multiple folders, remove individual
 * entries, and confirm the final set. Auto-opens a FileChooser on first show
 * so the simple "load one or two JARs" path is no slower than the previous
 * single-shot file dialog.
 */
public final class SourceSetDialog {

    private SourceSetDialog() {}

    /**
     * Shows the modal dialog and returns the user's chosen JAR list.
     *
     * @param owner             stage to attach modality to
     * @param initialDirectory  start folder for the first FileChooser, or null
     * @param initialFiles      JARs to pre-populate the list with; pass an
     *                          empty list to start from scratch (the dialog
     *                          auto-opens a FileChooser in that case so the
     *                          user does not have to click "Add" first).
     * @return non-empty list when the user clicked Analyze; {@code Optional.empty()}
     *         when the user clicked Cancel or closed the dialog without files.
     */
    public static Optional<List<File>> chooseSourceSet(Stage owner,
                                                       File initialDirectory,
                                                       List<File> initialFiles) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Analyze JARs");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("about-dialog", "source-set-dialog");
        var css = SourceSetDialog.class.getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        pane.setHeader(null);
        pane.setHeaderText(null);

        // Header: icon + title block matching About/Exit dialog style.
        FontIcon icon = new FontIcon(MaterialDesignF.FOLDER_OPEN);
        icon.setIconSize(48);
        icon.setIconColor(Color.web("#ffd54f"));

        Label title = new Label("Select JARs to analyze");
        title.getStyleClass().add("about-title");

        Label tagline = new Label("All selected JARs are merged into a single architecture.");
        tagline.getStyleClass().add("about-tagline");

        VBox titleBlock = new VBox(2, title, tagline);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        HBox headerRow = new HBox(18, icon, titleBlock);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // List of staged files — pre-populated when the caller already has a
        // selection (e.g. user picked multiple files in the system FileChooser).
        ObservableList<File> files = FXCollections.observableArrayList();
        if (initialFiles != null) {
            for (File f : initialFiles) {
                if (f != null && !files.contains(f)) {
                    files.add(f);
                }
            }
        }
        ListView<File> listView = new ListView<>(files);
        listView.getStyleClass().add("source-set-list");
        listView.setPrefHeight(220);
        listView.setMinHeight(160);
        listView.setCellFactory(lv -> new FileCell());
        listView.setPlaceholder(emptyPlaceholder());

        // Add / Remove buttons next to the list.
        Button addButton = new Button("Add JAR(s)…");
        addButton.getStyleClass().add("source-set-action");
        addButton.setMaxWidth(Double.MAX_VALUE);

        Button removeButton = new Button("Remove");
        removeButton.getStyleClass().add("source-set-action");
        removeButton.setMaxWidth(Double.MAX_VALUE);
        removeButton.disableProperty().bind(
                listView.getSelectionModel().selectedItemProperty().isNull());

        VBox actionColumn = new VBox(8, addButton, removeButton);
        actionColumn.setMinWidth(120);
        actionColumn.setPrefWidth(120);

        HBox listRow = new HBox(10, listView, actionColumn);
        HBox.setHgrow(listView, Priority.ALWAYS);

        VBox body = new VBox(14, headerRow, listRow);
        body.setPadding(new Insets(22, 26, 18, 26));
        body.setMinWidth(Region.USE_PREF_SIZE);
        body.setPrefWidth(560);

        pane.setContent(body);

        ButtonType analyzeButtonType = new ButtonType("Analyze", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, analyzeButtonType);

        Button analyzeButton = (Button) pane.lookupButton(analyzeButtonType);
        if (analyzeButton != null) {
            analyzeButton.setDefaultButton(true);
            // Disable Analyze while the list is empty.
            analyzeButton.disableProperty().bind(
                    javafx.beans.binding.Bindings.isEmpty(files));
            // Live label: "Analyze 3 JARs" — feedback on size.
            files.addListener((javafx.collections.ListChangeListener<File>) c ->
                    analyzeButton.setText(files.size() == 1
                            ? "Analyze 1 JAR"
                            : "Analyze " + files.size() + " JARs"));
        }

        // Track the last-used directory across Add presses within the same dialog.
        File[] lastDir = { initialDirectory };

        addButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Add JAR file(s)");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            if (lastDir[0] != null && lastDir[0].isDirectory()) {
                chooser.setInitialDirectory(lastDir[0]);
            }
            List<File> picked = chooser.showOpenMultipleDialog(owner);
            if (picked != null && !picked.isEmpty()) {
                lastDir[0] = picked.get(0).getParentFile();
                // Skip duplicates while preserving insertion order.
                for (File f : picked) {
                    if (!files.contains(f)) {
                        files.add(f);
                    }
                }
            }
        });

        removeButton.setOnAction(e -> {
            File sel = listView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                files.remove(sel);
            }
        });

        // Auto-open the file chooser on first show so users who just want
        // "open one JAR" are not slowed down by an extra click.
        dialog.setOnShown(e -> {
            if (files.isEmpty()) {
                addButton.fire();
                // If user cancelled the auto-opened FileChooser without
                // picking anything, the dialog stays open with an empty list;
                // they can still click Add again or Cancel.
            }
        });

        // Pre-warm CSS+layout (same trick as About/Exit dialogs).
        pane.applyCss();
        pane.layout();

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == analyzeButtonType && !files.isEmpty()) {
            return Optional.of(new ArrayList<>(files));
        }
        return Optional.empty();
    }

    private static Label emptyPlaceholder() {
        Label l = new Label("No JARs added yet — click \"Add JAR(s)…\" to pick files.");
        l.getStyleClass().add("source-set-empty");
        return l;
    }

    /** Renders File entries with name primary and absolute path secondary. */
    private static final class FileCell extends javafx.scene.control.ListCell<File> {
        @Override
        protected void updateItem(File item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            FontIcon jar = new FontIcon(MaterialDesignF.FILE);
            jar.setIconSize(16);
            jar.setIconColor(Color.web("#cccccc"));

            Label name = new Label(item.getName());
            name.getStyleClass().add("source-set-name");

            Label path = new Label(parentPathLabel(item));
            path.getStyleClass().add("source-set-path");

            VBox text = new VBox(0, name, path);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox row = new HBox(10, jar, text);
            row.setAlignment(Pos.CENTER_LEFT);

            setText(null);
            setGraphic(row);
        }

        private static String parentPathLabel(File f) {
            File parent = f.getParentFile();
            return parent == null ? "" : parent.getAbsolutePath();
        }
    }

    /** For tests; production code goes through {@link #chooseSourceSet}. */
    static List<File> emptyForTests() {
        return Collections.emptyList();
    }
}
