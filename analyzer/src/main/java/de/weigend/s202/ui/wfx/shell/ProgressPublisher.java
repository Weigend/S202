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
package de.weigend.s202.ui.wfx.shell;

import de.weigend.s202.ui.core.canvas.ArchitectureView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.events.ProgressEvent;
import javafx.application.Platform;

import java.util.EventObject;

/**
 * Publishes status and progress messages onto the WFX {@link EventBus}
 * (picked up by the status bar), FX-thread-safe. Shared by all S202
 * controllers so progress reporting has exactly one shape.
 */
public final class ProgressPublisher {

    private final Object source;

    public ProgressPublisher(Object source) {
        this.source = source;
    }

    public void status(String message) {
        progress(message, 0.0);
    }

    @SuppressWarnings("unchecked")
    public void progress(String message, double progress) {
        Runnable publish = () -> Lookup.lookup(EventBus.class)
                .publish(new ProgressEvent(message, progress, source));
        if (Platform.isFxApplicationThread()) {
            publish.run();
        } else {
            Platform.runLater(publish);
        }
    }

    /**
     * Maps the incremental JavaFX tree-build progress into the tail end
     * (97%–99.5%) of the overall analysis progress bar.
     */
    public void javaFxBuildProgress(String label, ArchitectureView.BuildProgress buildProgress) {
        int total = Math.max(1, buildProgress.totalNodes());
        int processed = Math.min(buildProgress.processedNodes(), total);
        double fraction = Math.max(0.0, Math.min(1.0, (double) processed / total));
        double mapped = 0.97 + fraction * 0.025;
        progress(String.format("%s: %,d/%,d nodes", label, processed, total), mapped);
    }
}
