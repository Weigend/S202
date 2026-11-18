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
package de.weigend.s202.ui.shell;

import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Wie der Benutzer die Arbeitsfläche verlassen hat, von einer Sitzung zur
 * nächsten: Größe und Position des Hauptfensters sowie die Anordnung der
 * Views — welche Panels wo liegen, wie die Bereiche geteilt sind, abgelöste
 * Fenster. Gespeichert wird beim Beenden, wiederhergestellt beim Start.
 * <p>
 * Die Anordnung der Views schreibt und liest wfx
 * ({@link WindowManager#saveLayout()} / {@link WindowManager#restoreLayout});
 * das Hauptfenster gehört nicht dazu und wird hier selbst festgehalten. Beides
 * liegt als Textdatei im Einstellungsverzeichnis des Benutzers.
 * <p>
 * Die Anordnung nennt Views beim Namen. Beim Start gibt es nur die Panels;
 * die Tabs der Analysen der letzten Sitzung stehen zwar in der Datei, werden
 * aber übergangen. Was eine Analyse an Tabs hatte, steht in ihrer
 * Projektdatei.
 */
final class SessionLayout {

    /** Verzeichnis der Einstellungen, wenn es nicht {@code ~/.s202} sein soll (Tests). */
    static final String DIRECTORY_PROPERTY = "s202.settings.dir";

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionLayout.class);
    private static final String WINDOW_FILE = "window.properties";
    private static final String VIEWS_FILE = "layout.properties";

    private final Path directory;

    SessionLayout() {
        this(defaultDirectory());
    }

    SessionLayout(Path directory) {
        this.directory = directory;
    }

    private static Path defaultDirectory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".s202");
    }

    /* ----- Hauptfenster -------------------------------------------------------- */

    /**
     * Gibt dem Hauptfenster Größe und Position der letzten Sitzung. Liegt die
     * Stelle auf keinem Bildschirm mehr (Monitor abgezogen), bleibt das
     * Fenster, wo die Plattform es hinsetzt.
     */
    void restoreWindow(Stage stage) {
        Properties saved = read(WINDOW_FILE);
        if (saved == null) {
            return;
        }
        try {
            double x = Double.parseDouble(saved.getProperty("x"));
            double y = Double.parseDouble(saved.getProperty("y"));
            double width = Double.parseDouble(saved.getProperty("width"));
            double height = Double.parseDouble(saved.getProperty("height"));
            if (width > 0 && height > 0 && !Screen.getScreensForRectangle(x, y, width, height).isEmpty()) {
                stage.setX(x);
                stage.setY(y);
                stage.setWidth(width);
                stage.setHeight(height);
            }
        } catch (NullPointerException | NumberFormatException e) {
            LOGGER.warn("Ignoring the saved window bounds in {}: {}", directory.resolve(WINDOW_FILE), e.toString());
        }
        stage.setMaximized(Boolean.parseBoolean(saved.getProperty("maximized")));
    }

    private void saveWindow(Stage stage) {
        Properties window = new Properties();
        Properties before = read(WINDOW_FILE);
        if ((stage.isMaximized() || stage.isIconified()) && before != null) {
            // The bounds of a maximized or minimized window are not the ones
            // to come back to; keep those of the last time it was neither.
            window.putAll(before);
        } else {
            window.setProperty("x", Double.toString(stage.getX()));
            window.setProperty("y", Double.toString(stage.getY()));
            window.setProperty("width", Double.toString(stage.getWidth()));
            window.setProperty("height", Double.toString(stage.getHeight()));
        }
        window.setProperty("maximized", Boolean.toString(stage.isMaximized()));
        try {
            Files.createDirectories(directory);
            try (Writer out = Files.newBufferedWriter(directory.resolve(WINDOW_FILE))) {
                window.store(out, "Structure202 main window");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save the window bounds", e);
        }
    }

    /* ----- Anordnung der Views ------------------------------------------------- */

    /**
     * Ordnet die registrierten Views an wie am Ende der letzten Sitzung.
     * Aufzurufen, wenn alle Module ihre Panels registriert haben.
     */
    void restoreViews(WindowManager windowManager) {
        Path file = directory.resolve(VIEWS_FILE);
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            if (!windowManager.restoreLayout(Files.readString(file))) {
                LOGGER.warn("{} is not a layout this version can read; starting with the default one", file);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read the saved layout, starting with the default one", e);
        }
    }

    private void saveViews(WindowManager windowManager) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(VIEWS_FILE), windowManager.saveLayout());
        } catch (IOException e) {
            LOGGER.warn("Could not save the layout", e);
        }
    }

    /** Hält fest, wie die Arbeitsfläche jetzt aussieht. Auf dem FX-Thread, solange die Fenster noch offen sind. */
    void save(Stage stage, WindowManager windowManager) {
        if (stage != null) {
            saveWindow(stage);
        }
        saveViews(windowManager);
    }

    private Properties read(String name) {
        Path file = directory.resolve(name);
        if (!Files.isReadable(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            properties.load(in);
            return properties;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Could not read {}", file, e);
            return null;
        }
    }
}
