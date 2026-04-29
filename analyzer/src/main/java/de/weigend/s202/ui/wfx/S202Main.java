package de.weigend.s202.ui.wfx;

import io.softwareecg.wfx.main.AvajeMain;
import javafx.application.Application;

/**
 * Entry point for the S202 Code Analyzer running on the WFX rich-client platform
 * with Avaje Inject as the bean discovery strategy.
 */
public class S202Main extends AvajeMain {

    public static void main(String[] args) {
        Application.launch(S202Main.class, args);
    }
}
