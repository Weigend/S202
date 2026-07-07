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
package de.weigend.s202.ui.graph;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Global, view-übergreifende Darstellungs-Toggles der Architektur-Ansicht.
 *
 * <p>Die Boxen ({@code LevelClassBox}, {@code LevelPackageBox}) binden ihre
 * Icon-/Label-Sichtbarkeit direkt an diese Properties — ein Toggle wirkt
 * damit sofort auf alle offenen Tabs, ohne Tree-Rebuild. Bewusst statisch:
 * es gibt genau einen Satz dieser Einstellungen pro Anwendung.</p>
 */
public final class ArchitectureViewSettings {

    // Icon visibility is shared across all open architecture views — boxes bind
    // their FontIcon visibility to this property so toggling refreshes every
    // open tab without rebuilding the tree.
    private static final BooleanProperty SHOW_ICONS = new SimpleBooleanProperty(true);

    // Global architecture-level label visibility. Boxes append "G:n" to their
    // header label when true. Local level "L:n" stays as the placement indicator;
    // the global level reveals depth the package-aligned 2D layout otherwise hides.
    private static final BooleanProperty SHOW_ARCHITECTURE_LEVEL = new SimpleBooleanProperty(false);

    private ArchitectureViewSettings() {
    }

    /**
     * Global icon visibility for all package/class boxes. Backed by a static
     * property so all open architecture tabs and freshly created boxes react
     * to the same toggle.
     */
    public static BooleanProperty showIconsProperty() {
        return SHOW_ICONS;
    }

    public static boolean isShowIcons() {
        return SHOW_ICONS.get();
    }

    public static void setShowIcons(boolean show) {
        SHOW_ICONS.set(show);
    }

    /**
     * Global toggle for the "G:n" architecture-level suffix shown alongside
     * "L:n" in each package and class box header. Boxes react live without a
     * tree rebuild, mirroring {@link #showIconsProperty()}.
     */
    public static BooleanProperty showArchitectureLevelProperty() {
        return SHOW_ARCHITECTURE_LEVEL;
    }

    public static boolean isShowArchitectureLevel() {
        return SHOW_ARCHITECTURE_LEVEL.get();
    }

    public static void setShowArchitectureLevel(boolean show) {
        SHOW_ARCHITECTURE_LEVEL.set(show);
    }
}
