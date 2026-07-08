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

import de.weigend.s202.ui.core.events.MenuRequestEvent;
import io.softwareecg.wfx.extension.uiutils.MenuUtil;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.windowmanager.api.ApplicationWindow;
import io.softwareecg.wfx.windowmanager.api.ShutdownConfirmation;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.Group;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.util.Collections;
import java.util.EventObject;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Builds the application menu bar (File, Edit, View, Windows, Help) and publishes
 * {@link MenuRequestEvent}s when the user picks a command. Owns purely
 * presentational concerns (About dialog, opening URLs in the browser) so the
 * application module does not need to know about them.
 */
public class S202MenuBar {

    private static final Logger LOGGER = LoggerFactory.getLogger(S202MenuBar.class);

    private static final String STRUCTURE202_REPO_URL = "https://github.com/weigend/S202";
    private static final String WFX_REPO_URL = "https://github.com/jweigend/wfx";
    private static final String VIEW_MENU_ID = "view";
    private static final String VIEW_MENU_TITLE = "View";
    private static final String COMPONENT_VIEW_ITEM_ID = "view.componentView";
    private static final String HEXAGONAL_VIEW_ITEM_ID = "view.hexagonalView";

    private final ApplicationWindow applicationWindow;
    private final EventBus<EventObject> eventBus;
    private final BooleanProperty canUndo;
    private final BooleanProperty canRedo;
    private final Set<Menu> watchedViewMenus = Collections.newSetFromMap(new IdentityHashMap<>());

    public S202MenuBar(ApplicationWindow applicationWindow, EventBus<EventObject> eventBus,
                       BooleanProperty canUndo, BooleanProperty canRedo) {
        this.applicationWindow = applicationWindow;
        this.eventBus = eventBus;
        this.canUndo = canUndo;
        this.canRedo = canRedo;
    }

    public void install() {
        installFileMenu();
        installEditMenu();
        installViewMenu();
        installWindowsMenu();
        installHelpMenu();
    }

    private void installEditMenu() {
        MenuItem undoItem = MenuUtil.createMenuItem(
                "edit.undo", "Undo", e -> publish(new MenuRequestEvent.Undo(this)));
        undoItem.setAccelerator(new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN));
        undoItem.disableProperty().bind(canUndo.not());

        MenuItem redoItem = MenuUtil.createMenuItem(
                "edit.redo", "Redo", e -> publish(new MenuRequestEvent.Redo(this)));
        redoItem.setAccelerator(new KeyCodeCombination(KeyCode.Z,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        redoItem.disableProperty().bind(canRedo.not());

        Menu editMenu = MenuUtil.createMenu("edit", "Edit");
        editMenu.getItems().addAll(undoItem, redoItem);
        applicationWindow.getMenu().add(1, editMenu);
    }

    private void installFileMenu() {
        MenuItem saveProjectItem = MenuUtil.createMenuItem(
                "file.saveProject", "Save Project...",
                e -> publish(new MenuRequestEvent.SaveProject(this)));
        saveProjectItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));

        MenuItem loadProjectItem = MenuUtil.createMenuItem(
                "file.loadProject", "Load Project...",
                e -> publish(new MenuRequestEvent.LoadProject(this)));

        MenuItem exportQualityReportItem = MenuUtil.createMenuItem(
                "file.exportQualityReport", "Analyze Quality",
                e -> publish(new MenuRequestEvent.ExportQualityReport(this)));

        MenuItem openCity3DItem = MenuUtil.createMenuItem(
                "file.city3d", "Show City3D View",
                e -> publish(new MenuRequestEvent.OpenCity3DView(this)));

        MenuItem closeProjectItem = MenuUtil.createMenuItem(
                "file.closeProject", "Close Project",
                e -> publish(new MenuRequestEvent.CloseProject(this)));

        MenuItem openItem = MenuUtil.createMenuItem(
                "file.open", "Open JAR...", e -> publish(new MenuRequestEvent.OpenJar(this)));
        openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));

        MenuItem openPythonItem = MenuUtil.createMenuItem(
                "file.openPython", "Open Python Source...",
                e -> publish(new MenuRequestEvent.OpenPythonSource(this)));

        MenuItem openCItem = MenuUtil.createMenuItem(
                "file.openC", "Open C Source...",
                e -> publish(new MenuRequestEvent.OpenCSource(this)));

        MenuItem openGoItem = MenuUtil.createMenuItem(
                "file.openGo", "Open Go Module...",
                e -> publish(new MenuRequestEvent.OpenGoSource(this)));

        MenuItem openMavenItem = MenuUtil.createMenuItem(
                "file.openMaven", "Open Maven Project...",
                e -> publish(new MenuRequestEvent.OpenMavenProject(this)));
        MenuItem openGradleItem = MenuUtil.createMenuItem(
                "file.openGradle", "Open Gradle Project...",
                e -> publish(new MenuRequestEvent.OpenGradleProject(this)));

        MenuItem exitItem = MenuUtil.createMenuItem(
                "file.exit", "Exit", e -> {
                    if (confirmExit()) {
                        publish(new MenuRequestEvent.Exit(this));
                    }
                });
        exitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));

        Menu openSourceMenu = MenuUtil.createMenu("file.openSource", "Open Source Code");
        openSourceMenu.getItems().addAll(openGoItem, openPythonItem, openCItem);

        Menu fileMenu = MenuUtil.createMenu("file", "File");
        fileMenu.getItems().addAll(
                openItem, openMavenItem, openGradleItem,
                new SeparatorMenuItem(),
                openSourceMenu,
                new SeparatorMenuItem(),
                exportQualityReportItem, openCity3DItem,
                new SeparatorMenuItem(),
                saveProjectItem, loadProjectItem, closeProjectItem,
                new SeparatorMenuItem(),
                exitItem);

        applicationWindow.getMenu().add(0, fileMenu);
    }

    private void installWindowsMenu() {
        MenuItem newItem = MenuUtil.createMenuItem(
                "windows.new", "New", e -> publish(new MenuRequestEvent.NewView(this)));
        newItem.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN));

        MenuItem closeItem = MenuUtil.createMenuItem(
                "windows.close", "Close", e -> publish(new MenuRequestEvent.CloseFocusedView(this)));
        closeItem.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));

        MenuItem closeAllItem = MenuUtil.createMenuItem(
                "windows.closeAll", "Close All", e -> publish(new MenuRequestEvent.CloseAllViews(this)));
        closeAllItem.setAccelerator(new KeyCodeCombination(
                KeyCode.W, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));

        MenuItem defaultLayoutItem = MenuUtil.createMenuItem(
                "windows.defaultLayout", "Default Layout",
                e -> publish(new MenuRequestEvent.RestoreDefaultLayout(this)));

        Menu windowsMenu = MenuUtil.createMenu("windows", "Windows");
        windowsMenu.getItems().addAll(newItem,
                new SeparatorMenuItem(), closeItem, closeAllItem,
                new SeparatorMenuItem(), defaultLayoutItem);

        applicationWindow.getMenu().add(windowsMenu);
    }

    private void installViewMenu() {
        applicationWindow.getMenu().addListener((ListChangeListener<Menu>) change ->
                Platform.runLater(this::ensureArchitectureViewMenuItems));
        ensureArchitectureViewMenuItems();
        Platform.runLater(this::ensureArchitectureViewMenuItems);
    }

    private void ensureArchitectureViewMenuItems() {
        Menu viewMenu = findMenu(VIEW_MENU_ID, VIEW_MENU_TITLE);
        if (viewMenu == null) {
            viewMenu = MenuUtil.createMenu(VIEW_MENU_ID, VIEW_MENU_TITLE);
            applicationWindow.getMenu().add(viewMenuInsertIndex(), viewMenu);
        }

        watchViewMenuItems(viewMenu);
        if (!containsMenuItem(viewMenu, COMPONENT_VIEW_ITEM_ID)) {
            MenuItem componentViewItem = MenuUtil.createMenuItem(
                    COMPONENT_VIEW_ITEM_ID, "Component View",
                    e -> publish(new MenuRequestEvent.OpenComponentView(this)));
            viewMenu.getItems().add(componentViewItem);
        }
        if (!containsMenuItem(viewMenu, HEXAGONAL_VIEW_ITEM_ID)) {
            MenuItem hexagonalViewItem = MenuUtil.createMenuItem(
                    HEXAGONAL_VIEW_ITEM_ID, "Hexagonal View",
                    e -> publish(new MenuRequestEvent.OpenHexagonalView(this)));
            viewMenu.getItems().add(hexagonalViewItem);
        }
    }

    private void watchViewMenuItems(Menu viewMenu) {
        if (!watchedViewMenus.add(viewMenu)) {
            return;
        }

        viewMenu.getItems().addListener((ListChangeListener<MenuItem>) change -> {
            if (!containsMenuItem(viewMenu, COMPONENT_VIEW_ITEM_ID)
                    || !containsMenuItem(viewMenu, HEXAGONAL_VIEW_ITEM_ID)) {
                Platform.runLater(this::ensureArchitectureViewMenuItems);
            }
        });
    }

    private boolean containsMenuItem(Menu menu, String id) {
        for (MenuItem item : menu.getItems()) {
            if (id.equals(item.getId())) {
                return true;
            }
        }
        return false;
    }

    private Menu findMenu(String id, String text) {
        for (Menu menu : applicationWindow.getMenu()) {
            if (id.equals(menu.getId()) || text.equals(menu.getText())) {
                return menu;
            }
        }
        return null;
    }

    private int viewMenuInsertIndex() {
        int windowsIndex = menuIndex("windows", "Windows");
        if (windowsIndex >= 0) {
            return windowsIndex;
        }
        int helpIndex = menuIndex("help", "Help");
        if (helpIndex >= 0) {
            return helpIndex;
        }
        return applicationWindow.getMenu().size();
    }

    private int menuIndex(String id, String text) {
        for (int i = 0; i < applicationWindow.getMenu().size(); i++) {
            Menu menu = applicationWindow.getMenu().get(i);
            if (id.equals(menu.getId()) || text.equals(menu.getText())) {
                return i;
            }
        }
        return -1;
    }

    private void installHelpMenu() {
        MenuItem contributeItem = MenuUtil.createMenuItem(
                "help.contribute", "Contribute...", e -> openUrl(STRUCTURE202_REPO_URL));
        MenuItem aboutItem = MenuUtil.createMenuItem(
                "help.about", "About...", e -> showAboutDialog());

        Menu helpMenu = MenuUtil.createMenu("help", "Help");
        helpMenu.getItems().addAll(contributeItem, new SeparatorMenuItem(), aboutItem);

        applicationWindow.getMenu().add(helpMenu);
    }

    private void publish(EventObject event) {
        eventBus.publish(event);
    }

    private void showAboutDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("About S202 Code Analyzer");
        dialog.initOwner(applicationWindow.getStage());

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("about-dialog");
        var css = getClass().getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        // Suppress the native Alert-style header bar; we render our own.
        pane.setHeader(null);
        pane.setHeaderText(null);

        // Yellow hexagon (pointy-top) with inner circle
        Color yellow = Color.web("#ffd54f");
        double r = 26.0;   // outer radius
        double cx = r, cy = r;
        Polygon hex = new Polygon();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i - 90);
            hex.getPoints().addAll(cx + r * Math.cos(a), cy + r * Math.sin(a));
        }
        hex.setFill(Color.TRANSPARENT);
        hex.setStroke(yellow);
        hex.setStrokeWidth(3.5);
        Circle innerCircle = new Circle(cx, cy, r * 0.38);
        innerCircle.setFill(Color.TRANSPARENT);
        innerCircle.setStroke(yellow);
        innerCircle.setStrokeWidth(3.0);
        Group logo = new Group(hex, innerCircle);

        Label title = new Label("S202 Code Analyzer");
        title.getStyleClass().add("about-title");

        Label tagline = new Label("Multi-language architecture visualization");
        tagline.getStyleClass().add("about-tagline");

        VBox titleBlock = new VBox(2, title, tagline);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(18, logo, titleBlock);
        header.setAlignment(Pos.CENTER_LEFT);

        Label description = new Label(
                "Extracts structural dependencies from Java, Go, Python and C source trees. "
              + "Detects architectural violations, dependency cycles and smells — "
              + "and visualizes the layered architecture of your codebase.");
        description.setWrapText(true);
        description.getStyleClass().add("about-description");
        description.setMaxWidth(420);

        Hyperlink wfxLink = new Hyperlink("WFX Rich Client Platform");
        wfxLink.getStyleClass().add("about-link");
        wfxLink.setOnAction(e -> {
            openUrl(WFX_REPO_URL);
            wfxLink.setVisited(false);
        });
        Text builtPrefix = new Text("Built on the ");
        builtPrefix.getStyleClass().add("about-meta");
        Text builtSuffix = new Text(".");
        builtSuffix.getStyleClass().add("about-meta");
        TextFlow builtOn = new TextFlow(builtPrefix, wfxLink, builtSuffix);
        builtOn.setMaxWidth(420);

        Text creditsText = new Text(
                "Lead Architect: Johannes Weigend\n"
              + "Advise and Programming: Claude and Codex\n"
              + "QS: Johannes Weigend\n"
              + "License: Apache License 2.0");
        creditsText.getStyleClass().add("about-meta");
        TextFlow credits = new TextFlow(creditsText);
        credits.setMaxWidth(420);

        Separator divider = new Separator();
        divider.getStyleClass().add("about-divider");

        VBox body = new VBox(14, header, divider, description, builtOn, credits);
        body.setPadding(new Insets(22, 26, 18, 26));
        body.setMinWidth(Region.USE_PREF_SIZE);

        pane.setContent(body);
        pane.getButtonTypes().setAll(ButtonType.CLOSE);

        // Force a CSS+layout pass before show. Without this the DialogPane
        // skin is initialised lazily on first show, so the very first dialog
        // render uses default styles/sizes; only the second invocation looks
        // right.
        pane.applyCss();
        pane.layout();

        dialog.showAndWait();
    }

    private boolean confirmExit() {
        // Look up the ShutdownConfirmation singleton instead of calling
        // ExitConfirmationDialog directly, so File → Exit and the system
        // window-X handler in DefaultApplicationWindow share the exact same
        // bean — and any future swap (veto logic, persistence prompts, …)
        // automatically takes effect for both.
        return Lookup.lookup(ShutdownConfirmation.class).confirm(applicationWindow.getStage());
    }

    /**
     * Open {@code url} in the system browser. Runs on a daemon background
     * thread because {@code Desktop.browse} on Linux performs lazy AWT init
     * that can deadlock against the JavaFX toolkit (the visible symptom: UI
     * freezes immediately after the click). Platform-native commands are
     * tried first to avoid AWT entirely.
     */
    private void openUrl(String url) {
        Thread opener = new Thread(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
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
                    } else {
                        LOGGER.warn("No URL opener available for OS '{}'", os);
                    }
                    return;
                }
                new ProcessBuilder(cmd).inheritIO().start();
            } catch (Exception ex) {
                LOGGER.warn("Could not open URL {}", url, ex);
            }
        }, "url-opener");
        opener.setDaemon(true);
        opener.start();
    }
}
