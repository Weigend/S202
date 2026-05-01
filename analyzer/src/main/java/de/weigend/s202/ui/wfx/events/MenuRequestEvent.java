package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;

/**
 * Base type for events fired by the application menu bar when the user picks a
 * command. The menu bar publishes concrete subtypes; the application module
 * subscribes and reacts. Decouples the menu UI from the analysis pipeline and
 * window-management glue.
 */
public abstract class MenuRequestEvent extends EventObject {

    protected MenuRequestEvent(Object source) {
        super(source);
    }

    public static final class OpenJar extends MenuRequestEvent {
        public OpenJar(Object source) { super(source); }
    }

    public static final class OpenMavenProject extends MenuRequestEvent {
        public OpenMavenProject(Object source) { super(source); }
    }

    public static final class OpenGradleProject extends MenuRequestEvent {
        public OpenGradleProject(Object source) { super(source); }
    }

    public static final class Exit extends MenuRequestEvent {
        public Exit(Object source) { super(source); }
    }

    public static final class NewView extends MenuRequestEvent {
        public NewView(Object source) { super(source); }
    }

    public static final class CloseFocusedView extends MenuRequestEvent {
        public CloseFocusedView(Object source) { super(source); }
    }

    public static final class CloseAllViews extends MenuRequestEvent {
        public CloseAllViews(Object source) { super(source); }
    }

    public static final class RestoreDefaultLayout extends MenuRequestEvent {
        public RestoreDefaultLayout(Object source) { super(source); }
    }
}
