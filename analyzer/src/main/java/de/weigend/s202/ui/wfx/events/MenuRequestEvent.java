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

    public static final class OpenPythonSource extends MenuRequestEvent {
        public OpenPythonSource(Object source) { super(source); }
    }

    public static final class OpenCSource extends MenuRequestEvent {
        public OpenCSource(Object source) { super(source); }
    }

    public static final class OpenMavenProject extends MenuRequestEvent {
        public OpenMavenProject(Object source) { super(source); }
    }

    public static final class OpenGradleProject extends MenuRequestEvent {
        public OpenGradleProject(Object source) { super(source); }
    }

    public static final class SaveProject extends MenuRequestEvent {
        public SaveProject(Object source) { super(source); }
    }

    public static final class LoadProject extends MenuRequestEvent {
        public LoadProject(Object source) { super(source); }
    }

    public static final class CloseProject extends MenuRequestEvent {
        public CloseProject(Object source) { super(source); }
    }

    public static final class Exit extends MenuRequestEvent {
        public Exit(Object source) { super(source); }
    }

    public static final class NewView extends MenuRequestEvent {
        public NewView(Object source) { super(source); }
    }

    public static final class OpenComponentView extends MenuRequestEvent {
        public OpenComponentView(Object source) { super(source); }
    }

    public static final class OpenHexagonalView extends MenuRequestEvent {
        public OpenHexagonalView(Object source) { super(source); }
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

    public static final class Undo extends MenuRequestEvent {
        public Undo(Object source) { super(source); }
    }

    public static final class Redo extends MenuRequestEvent {
        public Redo(Object source) { super(source); }
    }
}
