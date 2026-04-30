package de.weigend.s202.ui.wfx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;

/**
 * Single styled "Quit S202 Code Analyzer?" confirmation dialog. Reused by
 * both shutdown paths via {@link S202ShutdownConfirmation}: wfx's
 * {@code DefaultApplicationWindow} looks up the ShutdownConfirmation bean for
 * system-X close, and {@code S202MenuBar.confirmExit()} looks up the same
 * bean for the {@code File → Exit} menu item.
 */
public final class ExitConfirmationDialog {

    private ExitConfirmationDialog() {}

    /**
     * Show the modal dialog and block until the user picks Cancel or Quit.
     *
     * @param owner stage to attach modality to (may be null)
     * @return {@code true} if the user chose Quit; {@code false} on Cancel /
     *         Esc / window-close.
     */
    public static boolean confirm(Stage owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Quit S202 Code Analyzer");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("about-dialog");
        var css = ExitConfirmationDialog.class.getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        pane.setHeader(null);
        pane.setHeaderText(null);

        FontIcon icon = new FontIcon(MaterialDesignE.EXIT_TO_APP);
        icon.setIconSize(48);
        icon.setIconColor(Color.web("#ff9f43"));

        Label question = new Label("Quit S202 Code Analyzer?");
        question.getStyleClass().add("about-title");

        Label hint = new Label("All open analyses will be closed.");
        hint.getStyleClass().add("about-tagline");

        VBox titleBlock = new VBox(2, question, hint);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        HBox body = new HBox(18, icon, titleBlock);
        body.setAlignment(Pos.CENTER_LEFT);
        body.setPadding(new Insets(22, 26, 18, 26));
        body.setMinWidth(Region.USE_PREF_SIZE);

        pane.setContent(body);

        ButtonType quitButtonType = new ButtonType("Quit", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, quitButtonType);

        Button cancelButton = (Button) pane.lookupButton(ButtonType.CANCEL);
        if (cancelButton != null) {
            cancelButton.setDefaultButton(true);
        }
        Button quitButton = (Button) pane.lookupButton(quitButtonType);
        if (quitButton != null) {
            quitButton.setDefaultButton(false);
        }

        // Pre-warm CSS+layout so the very first show doesn't render with
        // default styles before the DialogPane skin initialises.
        pane.applyCss();
        pane.layout();

        return dialog.showAndWait().orElse(ButtonType.CANCEL) == quitButtonType;
    }
}
