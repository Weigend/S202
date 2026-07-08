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

import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.views.city3d.CityModelSerializer;
import de.weigend.s202.ui.views.city3d.CityView3DServer;
import de.weigend.s202.ui.core.events.NodeSelectionEvent;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.Dialogs;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.Locale;

/**
 * Öffnet die fokussierte Analyse als City3D-Ansicht im System-Browser:
 * serialisiert das Modell in den Loopback-{@link CityView3DServer} und
 * verdrahtet die bidirektionale Selektions-Synchronisation. Aus S202Module
 * extrahiert.
 */
@jakarta.inject.Singleton
public final class City3DModule implements io.softwareecg.wfx.platform.api.Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(City3DModule.class);

    /** Sentinel source for selection events injected from the browser (to skip echoing them back). */
    private static final Object BROWSER_SELECTION_SOURCE = new Object();

    private final ArchitectureViewManager viewManager;
    private boolean city3dSyncWired;

    @jakarta.inject.Inject
    City3DModule(ArchitectureViewManager viewManager) {
        this.viewManager = viewManager;
    }

    @Override
    public String getName() {
        return "City3D View";
    }

    @Override
    public void preload() {
        // nichts vorzubereiten
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        io.softwareecg.wfx.platform.api.EventBus<java.util.EventObject> bus =
                Lookup.lookup(io.softwareecg.wfx.platform.api.EventBus.class);
        bus.subscribe(de.weigend.s202.ui.core.events.MenuRequestEvent.OpenCity3DView.class, ev -> {
            openCity3DView();
            return true;
        });
    }

    @Override
    public void stop() {
        // nichts freizugeben
    }

    /** City3D in the system browser (loopback bundle). */
    public void openCity3DView() {
        CityView3DServer server = prepareCity3DServer();
        if (server != null) {
            openUrlInBrowser(server.url());
        }
    }

    /**
     * Serialise the focused analysis into the loopback City3D server and return it, or {@code null}
     * (after showing an error) when there is nothing to show or the web bundle is missing.
     */
    private CityView3DServer prepareCity3DServer() {
        ArchitectureWfxView focused = viewManager.focusedSourceArchitectureView();
        ArchitectureCanvas view = focused == null ? null : focused.getArchitectureView();
        if (view == null || view.getArchitectureRoot() == null
                || view.getDomainModel() == null || view.getRawDependencyModel() == null) {
            Dialogs.showError("City3D View", "There is no loaded analysis to show.");
            return null;
        }
        Path distDir = resolveCity3DDist();
        if (distDir == null) {
            Dialogs.showError("City3D View",
                    "Could not find the City3D web bundle (city3d/dist).\n"
                    + "Build it once with:  cd city3d && npm install && npm run build");
            return null;
        }
        try {
            CityModelSerializer serializer = new CityModelSerializer();
            CityModelSerializer.Metrics metrics =
                    CityModelSerializer.metricsFrom(view.getRawDependencyModel(), view.getDomainModel());
            String json = serializer.toJson(view.getArchitectureRoot(), null, metrics);

            CityView3DServer server = CityView3DServer.getOrStart(distDir);
            server.setCityJson(json);
            wireCity3DSelectionSync(server, view);
            return server;
        } catch (IOException ex) {
            LOGGER.error("Could not start the City3D view", ex);
            Dialogs.showError("City3D View", "Could not start the City3D view:\n" + ex.getMessage());
            return null;
        }
    }

    /**
     * Bidirectional selection sync with the browser City3D view over the loopback server:
     * <ul>
     *   <li>browser → app: a pick in the city is republished as a normal {@link NodeSelectionEvent}
     *       (so the 2D chart and the outline both follow it);</li>
     *   <li>app → browser: every selection on the bus (chart or outline) is pushed to the city,
     *       except our own browser-originated injections (avoids a feedback loop).</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void wireCity3DSelectionSync(CityView3DServer server, ArchitectureCanvas view) {
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        server.setSelectionListener(fqn -> Platform.runLater(() ->
                bus.publish(new NodeSelectionEvent(fqn, BROWSER_SELECTION_SOURCE))));
        if (!city3dSyncWired) {
            city3dSyncWired = true;
            bus.subscribe(NodeSelectionEvent.class, ev -> {
                if (ev.getSource() != BROWSER_SELECTION_SOURCE) {
                    server.pushSelection(ev.getFullName());
                }
                return true;
            });
        }
        server.pushSelection(view.getSelectedFullName());   // reflect the current 2D selection
    }

    /** Locates the built City3D bundle (city3d/dist) relative to the working directory. */
    private static Path resolveCity3DDist() {
        List<Path> candidates = new ArrayList<>();
        String override = System.getProperty("s202.city3d.dist");
        if (override != null && !override.isBlank()) {
            candidates.add(Path.of(override));
        }
        candidates.add(Path.of("city3d", "dist"));
        candidates.add(Path.of("..", "city3d", "dist"));
        candidates.add(Path.of("..", "..", "city3d", "dist"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p.resolve("index.html"))) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * Opens {@code url} in the system browser on a daemon thread (native command
     * first; {@code Desktop.browse} would risk deadlocking the JavaFX toolkit on
     * Linux). Mirrors {@code S202MenuBar#openUrl}.
     */
    private void openUrlInBrowser(String url) {
        Thread opener = new Thread(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                String[] cmd;
                if (os.contains("linux")) {
                    cmd = new String[] { "xdg-open", url };
                } else if (os.contains("mac") || os.contains("darwin")) {
                    cmd = new String[] { "open", url };
                } else if (os.contains("win")) {
                    cmd = new String[] { "rundll32", "url.dll,FileProtocolHandler", url };
                } else {
                    if (Desktop.isDesktopSupported()
                            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI.create(url));
                    }
                    return;
                }
                new ProcessBuilder(cmd).inheritIO().start();
            } catch (Exception ex) {
                LOGGER.warn("Could not open URL {}", url, ex);
            }
        }, "city3d-url-opener");
        opener.setDaemon(true);
        opener.start();
    }
}
