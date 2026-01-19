package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.analysis.scc.EdgeClassification.ClassifiedEdge;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.Objects;

/**
 * Main UI component for displaying the architecture graph.
 */
public class ArchitectureView extends BorderPane {
    private PackageTreeView treeView;
    private Label statusLabel;
    private Spinner<Integer> depthSpinner;
    private Stage parentStage;
    private java.util.function.Consumer<File> onFileSelected;

    public ArchitectureView(Stage parentStage) {
        this.parentStage = Objects.requireNonNull(parentStage, "parentStage cannot be null");
        setupUI();
    }

    private void setupUI() {
        // Top toolbar
        HBox toolbar = createToolbar();
        setTop(toolbar);

        // Center: Tree View
        treeView = new PackageTreeView();
        
        setCenter(treeView);

        // Bottom: Status bar
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-padding: 5; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        setBottom(statusLabel);
    }

    private HBox createToolbar() {
        HBox toolbar = new HBox(10);
        toolbar.setPadding(new Insets(10));
        toolbar.setStyle("-fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");

        Button loadButton = new Button("📂 Load JAR");
        loadButton.setStyle("-fx-font-size: 11;");
        loadButton.setOnAction(e -> openFileChooser());

        Label depthLabel = new Label("Auto-Expand Depth:");
        depthSpinner = new Spinner<>(1, 10, 3);
        depthSpinner.setPrefWidth(80);

        Button refreshButton = new Button("🔄 Refresh");
        refreshButton.setStyle("-fx-font-size: 11;");
        refreshButton.setOnAction(e -> {
            String depth = String.valueOf(depthSpinner.getValue());
            setStatus("Analyzing with depth: " + depth);
        });

        Separator separator = new Separator();
        separator.setStyle("-fx-padding: 0 5; -fx-opacity: 0.3;");

        toolbar.getChildren().addAll(
            loadButton, new Separator(), depthLabel, depthSpinner, refreshButton
        );

        return toolbar;
    }

    /**
     * Opens a file chooser for selecting JAR files.
     */
    private void openFileChooser() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select JAR File to Analyze");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File selectedFile = fileChooser.showOpenDialog(parentStage);
        if (selectedFile != null && onFileSelected != null) {
            onFileSelected.accept(selectedFile);
        }
    }

    /**
     * Sets callback when a JAR file is selected.
     */
    public void setOnFileSelected(java.util.function.Consumer<File> callback) {
        this.onFileSelected = Objects.requireNonNull(callback, "callback cannot be null");
    }

    /**
     * Sets the root node of the architecture graph.
     * Uses the modern analysis pipeline (no layer recalculation needed).
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        
        // In the modern pipeline, layers are already calculated by LevelCalculator
        // We pass empty classified edges for now; violations will be added later
        java.util.List<ClassifiedEdge> classifiedEdges = new java.util.ArrayList<>();
        
        treeView.setArchitectureRoot(rootNode, classifiedEdges);
        setStatus("Architecture loaded: " + rootNode.getSimpleName());
    }

    /**
     * Updates the status bar message.
     */
    public void setStatus(String message) {
        statusLabel.setText(Objects.requireNonNull(message, "message cannot be null"));
    }

    /**
     * Returns the selected architecture node.
     */
    public ArchitectureNode getSelectedNode() {
        return null; // Graph view doesn't have selection yet
    }

    /**
     * Returns the current auto-expand depth setting.
     */
    public int getAutoExpandDepth() {
        return depthSpinner.getValue();
    }

    /**
     * Sets the auto-expand depth.
     */
    public void setAutoExpandDepth(int depth) {
        if (depth < 1 || depth > 10) {
            throw new IllegalArgumentException("Depth must be between 1 and 10");
        }
        depthSpinner.getValueFactory().setValue(depth);
    }
}
