package de.weigend.s202.ui.wfx;

import de.weigend.s202.ui.wfx.events.MenuRequestEvent;
import io.softwareecg.wfx.extension.uiutils.MenuUtil;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.windowmtg.api.ApplicationWindow;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
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
        MenuItem openItem = MenuUtil.createMenuItem(
                "file.open", "Open JAR...", e -> publish(new MenuRequestEvent.OpenJar(this)));
        openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));

        MenuItem exitItem = MenuUtil.createMenuItem(
                "file.exit", "Exit", e -> publish(new MenuRequestEvent.Exit(this)));
        exitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));

        Menu fileMenu = MenuUtil.createMenu("file", "File");
        fileMenu.getItems().addAll(openItem, new SeparatorMenuItem(), exitItem);

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
        Alert about = new Alert(Alert.AlertType.NONE);
        about.setTitle("About S202 Code Analyzer");
        about.setHeaderText("S202 Code Analyzer");

        Text intro = new Text("""
                Java bytecode architecture viewer.

                Analyzes JAR files, extracts package and class dependencies,
                detects cyclic dependencies (SCCs) and visualizes the layered
                architecture.

                Built on the """);
        Hyperlink wfxLink = new Hyperlink("WFX Rich Client Platform");
        wfxLink.setOnAction(e -> openUrl(WFX_REPO_URL));
        Text trailing = new Text(".");

        TextFlow content = new TextFlow(intro, wfxLink, trailing);
        content.setMaxWidth(420);
        about.getDialogPane().setContent(content);

        about.getButtonTypes().setAll(ButtonType.CLOSE);
        about.initOwner(applicationWindow.getStage());
        about.showAndWait();
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ex) {
            LOGGER.warn("Could not open URL {}", url, ex);
        }
    }
}
