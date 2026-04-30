package de.weigend.s202.ui.wfx;

import io.softwareecg.wfx.windowmtg.api.ShutdownConfirmation;
import jakarta.inject.Singleton;
import javafx.stage.Stage;

/**
 * Replaces wfx's default Yes/No FXML shutdown dialog with the styled
 * {@link ExitConfirmationDialog} used elsewhere in S202. Picked up by
 * {@code DefaultApplicationWindow.platformShutdownRequestHandler} via Avaje
 * DI; the wfx default is annotated {@code @Secondary} so this {@code @Singleton}
 * wins automatically without further configuration.
 */
@Singleton
public class S202ShutdownConfirmation implements ShutdownConfirmation {

    @Override
    public boolean confirm(Stage owner) {
        return ExitConfirmationDialog.confirm(owner);
    }
}
