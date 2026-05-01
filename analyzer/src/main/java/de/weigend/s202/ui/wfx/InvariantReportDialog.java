package de.weigend.s202.ui.wfx;

import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

/**
 * Modal dialog that shows a {@link LayoutInvariantReport} as a scrollable
 * text block with a Copy button. Mirrors the {@code InvariantReportPanel}
 * from the Software City Unity project — same layout intent (header +
 * scrollable monospace block + Copy/Close buttons), adapted to JavaFX and
 * the Structure202 dialog styling used by About/Exit/SourceSet dialogs.
 *
 * <p>Only opens when the report has at least one finding. With zero
 * findings the caller should just publish a status-bar message instead.</p>
 */
public final class InvariantReportDialog {

    private InvariantReportDialog() {}

    public static void show(Stage owner, LayoutInvariantReport report) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Layout Invariants");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("about-dialog", "invariant-report-dialog");
        var css = InvariantReportDialog.class.getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        pane.setHeader(null);
        pane.setHeaderText(null);

        FontIcon icon = new FontIcon(MaterialDesignA.ALERT);
        icon.setIconSize(48);
        icon.setIconColor(Color.web("#ffd54f"));

        int findingsCount = report.findings().size();
        Label title = new Label(findingsCount == 1
                ? "1 layout invariant finding"
                : findingsCount + " layout invariant findings");
        title.getStyleClass().add("about-title");

        Label tagline = new Label(
                "Algorithm-bug detector — these are level-pipeline drift cases, "
                        + "not architectural violations.");
        tagline.getStyleClass().add("about-tagline");
        tagline.setWrapText(true);
        tagline.setMaxWidth(560);

        VBox titleBlock = new VBox(2, title, tagline);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(18, icon, titleBlock);
        header.setAlignment(Pos.CENTER_LEFT);

        // Monospace text area for the rendered report. Plain text so the user
        // can grab a region without dragging through styled spans.
        TextArea reportArea = new TextArea(report.toReproducerText());
        reportArea.setEditable(false);
        reportArea.setWrapText(false);
        reportArea.getStyleClass().add("invariant-report-text");
        reportArea.setPrefRowCount(20);
        reportArea.setPrefColumnCount(80);
        VBox.setVgrow(reportArea, Priority.ALWAYS);

        VBox body = new VBox(14, header, reportArea);
        body.setPadding(new Insets(22, 26, 18, 26));
        body.setMinWidth(Region.USE_PREF_SIZE);
        body.setPrefWidth(720);
        body.setPrefHeight(520);

        pane.setContent(body);

        ButtonType copyButtonType = new ButtonType("Copy", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        pane.getButtonTypes().setAll(copyButtonType, ButtonType.CLOSE);

        // Intercept the Copy button so the dialog stays open after copying —
        // ButtonType.LEFT closes the dialog by default in DialogPane, which
        // would defeat the "copy then keep reading" use case.
        Button copyButton = (Button) pane.lookupButton(copyButtonType);
        if (copyButton != null) {
            copyButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(report.toReproducerText());
                Clipboard.getSystemClipboard().setContent(content);
                copyButton.setText("Copied ✓");
                javafx.animation.PauseTransition t =
                        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                t.setOnFinished(e -> copyButton.setText("Copy"));
                t.play();
                ev.consume();
            });
        }
        Button closeButton = (Button) pane.lookupButton(ButtonType.CLOSE);
        if (closeButton != null) {
            closeButton.setDefaultButton(true);
        }

        pane.applyCss();
        pane.layout();
        dialog.showAndWait();
    }
}
