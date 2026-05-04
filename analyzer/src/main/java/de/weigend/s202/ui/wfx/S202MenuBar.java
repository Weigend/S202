package de.weigend.s202.ui.wfx;

import de.weigend.s202.ui.wfx.events.MenuRequestEvent;
import io.softwareecg.wfx.extension.uiutils.MenuUtil;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.windowmtg.api.ApplicationWindow;
import io.softwareecg.wfx.windowmtg.api.ShutdownConfirmation;
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
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.util.EventObject;

/**
 * Builds the application menu bar (File, Windows, Help) and publishes
 * {@link MenuRequestEvent}s when the user picks a command. Owns purely
 * presentational concerns (About dialog, opening URLs in the browser) so the
 * application module does not need to know about them.
 */
public class S202MenuBar {

    private static final Logger LOGGER = LoggerFactory.getLogger(S202MenuBar.class);

    private static final String STRUCTURE202_REPO_URL = "https://github.com/jweigend/Structure202";
    private static final String WFX_REPO_URL = "https://github.com/jweigend/wfx";

    private final ApplicationWindow applicationWindow;
    private final EventBus<EventObject> eventBus;

    public S202MenuBar(ApplicationWindow applicationWindow, EventBus<EventObject> eventBus) {
        this.applicationWindow = applicationWindow;
        this.eventBus = eventBus;
    }

    public void install() {
        installFileMenu();
        installWindowsMenu();
        installHelpMenu();
    }

    private void installFileMenu() {
        MenuItem saveProjectItem = MenuUtil.createMenuItem(
                "file.saveProject", "Save Project...",
                e -> publish(new MenuRequestEvent.SaveProject(this)));
        saveProjectItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));

        MenuItem loadProjectItem = MenuUtil.createMenuItem(
                "file.loadProject", "Load Project...",
                e -> publish(new MenuRequestEvent.LoadProject(this)));

        MenuItem closeProjectItem = MenuUtil.createMenuItem(
                "file.closeProject", "Close Project",
                e -> publish(new MenuRequestEvent.CloseProject(this)));

        MenuItem openItem = MenuUtil.createMenuItem(
                "file.open", "Open JAR...", e -> publish(new MenuRequestEvent.OpenJar(this)));
        openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));

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

        Menu fileMenu = MenuUtil.createMenu("file", "File");
        fileMenu.getItems().addAll(saveProjectItem, loadProjectItem, closeProjectItem,
                new SeparatorMenuItem(),
                openItem, openMavenItem, openGradleItem,
                new SeparatorMenuItem(), exitItem);

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
        windowsMenu.getItems().addAll(newItem, closeItem, closeAllItem,
                new SeparatorMenuItem(), defaultLayoutItem);

        applicationWindow.getMenu().add(windowsMenu);
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

        FontIcon logo = new FontIcon(MaterialDesignS.SITEMAP);
        logo.setIconSize(56);
        logo.setIconColor(Color.web("#ffd54f"));

        Label title = new Label("S202 Code Analyzer");
        title.getStyleClass().add("about-title");

        Label tagline = new Label("Java bytecode architecture viewer");
        tagline.getStyleClass().add("about-tagline");

        VBox titleBlock = new VBox(2, title, tagline);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(18, logo, titleBlock);
        header.setAlignment(Pos.CENTER_LEFT);

        Label description = new Label(
                "Analyzes JAR files, extracts package and class dependencies, "
              + "detects cyclic dependencies (SCCs) and visualizes the layered "
              + "architecture.");
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

        Separator divider = new Separator();
        divider.getStyleClass().add("about-divider");

        VBox body = new VBox(14, header, divider, description, builtOn);
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
