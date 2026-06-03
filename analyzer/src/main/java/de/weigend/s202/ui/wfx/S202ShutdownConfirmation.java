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

import io.softwareecg.wfx.windowmanager.api.ShutdownConfirmation;
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
