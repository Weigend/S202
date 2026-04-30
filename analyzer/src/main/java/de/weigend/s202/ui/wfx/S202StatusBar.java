package de.weigend.s202.ui.wfx;

import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.events.ProgressEvent;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Status bar widget for the WFX application shell. Listens on the global
 * {@link EventBus} for {@link ProgressEvent}s and updates its label and
 * progress bar accordingly. The bar replaces the wfx-builtin status item; the
 * orphaned wfx ProgressController stays subscribed but harmless.
 */
public class S202StatusBar {

    private final Label statusLabel = new Label("Ready");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final GridPane root;

    public S202StatusBar(EventBus<ProgressEvent> bus) {
        statusLabel.getStyleClass().add("status-bar");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setAlignment(Pos.CENTER_LEFT);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        // hide while idle (progress == 0 or finished == 1)
        progressBar.visibleProperty().bind(
                progressBar.progressProperty().isNotEqualTo(0)
                        .and(progressBar.progressProperty().isEqualTo(1).not()));

        ColumnConstraints leftCol = new ColumnConstraints();
        leftCol.setPercentWidth(70);
        ColumnConstraints rightCol = new ColumnConstraints();
        rightCol.setPercentWidth(30);

        root = new GridPane();
        root.getColumnConstraints().addAll(leftCol, rightCol);
        root.setHgap(8);
        root.setPadding(new Insets(2, 8, 2, 8));
        root.setMaxWidth(Double.MAX_VALUE);
        root.add(statusLabel, 0, 0);
        root.add(progressBar, 1, 0);
        GridPane.setHalignment(statusLabel, HPos.LEFT);
        GridPane.setHalignment(progressBar, HPos.RIGHT);
        GridPane.setFillWidth(statusLabel, true);
        GridPane.setFillWidth(progressBar, true);
        HBox.setHgrow(root, Priority.ALWAYS);

        bus.subscribe(ProgressEvent.class, ev -> {
            if (ev.getMessage() != null && !ev.getMessage().isEmpty()) {
                statusLabel.setText(ev.getMessage());
            }
            progressBar.setProgress(ev.getProgress());
            return true;
        });
    }

    public Node getNode() {
        return root;
    }

    public void setMessage(String message) {
        statusLabel.setText(message);
    }
}
