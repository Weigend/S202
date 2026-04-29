package de.weigend.s202.ui.wfx;

import io.softwareecg.wfx.windowmtg.windows.DefaultApplicationWindow;
import jakarta.inject.Singleton;

/**
 * Application shell for S202. Inherits the default WFX FXML scene
 * (menu bar, tool bar, status bar, central docking area). Customize
 * by overriding {@code init()} when a branded shell is needed.
 */
@Singleton
public class S202ApplicationWindow extends DefaultApplicationWindow {
}
