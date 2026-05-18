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

import de.weigend.s202.ui.ArchitectureView;
import io.softwareecg.wfx.windowmtg.api.Position;
import io.softwareecg.wfx.windowmtg.api.View;
import io.softwareecg.wfx.windowmtg.api.ViewKind;
import javafx.scene.Parent;

import java.net.URL;

/**
 * WFX {@link View} wrapper around the programmatic {@link ArchitectureView}.
 * Lets WFX dock the existing UI without forcing it into an FXML round-trip.
 */
public class ArchitectureWfxView implements View {

    public static final String VIEW_ID_PREFIX = "s202-architecture-";

    private final String viewId;
    private final String title;
    private final ArchitectureView architectureView;

    public ArchitectureWfxView(String viewId, String title, ArchitectureView architectureView) {
        this.viewId = viewId;
        this.title = title;
        this.architectureView = architectureView;
    }

    public ArchitectureView getArchitectureView() {
        return architectureView;
    }

    @Override
    public String getViewId() {
        return viewId;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getToolTipInfo() {
        return "Java bytecode architecture view";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.CENTER;
    }

    /**
     * Architecture tabs are content-bound: a fresh instance is created per
     * Open JAR / Windows → New, multiple may coexist, and closing one means
     * "I'm done with this analysis" — not "I'd like to find this view in
     * the View menu later". Marking it {@link ViewKind#DOCUMENT} makes wfx
     * fully unregister on tab-X (no leak), exclude it from the auto-built
     * View menu, and close it on Restore Default Layout.
     */
    @Override
    public ViewKind getKind() {
        return ViewKind.DOCUMENT;
    }

    @Override
    public Parent getRootNode() {
        return architectureView;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 1.0;
    }
}
