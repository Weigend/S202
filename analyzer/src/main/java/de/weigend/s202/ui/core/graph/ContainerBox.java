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
package de.weigend.s202.ui.core.graph;

import javafx.scene.layout.VBox;

/**
 * Kollabierbarer Paket-/Komponenten-Container im Architektur-Graphen.
 * Die Kanten-Renderer im Kern behandeln solche Container als Aggregations-
 * Endpunkte (Rollup versteckter Klassen, Zähl-Badge), ohne die konkreten
 * Box-Typen der Fachkomponenten zu kennen — {@code LevelPackageBox} (core)
 * und die ComponentBox der Component-Ansicht implementieren dies.
 */
public interface ContainerBox {

    /**
     * Breite, bis zu der ein begrenzter Container seine Zeile füllt
     * ({@link #setLimitRowWidth(boolean)}). Breiterer Inhalt behält seine
     * Breite; die Zeile zentriert, was übrig bleibt.
     */
    double MAX_ROW_FILL_WIDTH = 240.0;

    String getFullName();

    /** Ob der Inhalt zu sehen ist. */
    boolean isExpanded();

    /** Klappt den Container auf oder zu. */
    void setExpanded(boolean expanded);

    /**
     * Öffnet den Container so weit, dass {@code descendant}, ein Knoten
     * irgendwo in seinem Inhalt, zu sehen ist.
     */
    default void reveal(javafx.scene.Node descendant) {
        setExpanded(true);
    }

    /**
     * Der Zeilenstapel, der die Kinder aufnimmt. Was noch nicht gebaut ist
     * (zugeklappte Pakete), wird dafür gebaut.
     *
     * @param api der Stapel des API-Bereichs; Pakete haben nur den einen
     */
    VBox rowStack(boolean api);

    /** Lässt den Container Höhe und Breite der Zeile mitnutzen, in die er gesetzt wurde. */
    void fillRow();

    /**
     * Begrenzt, wie weit der Container mit seiner Zeile wächst; ohne Grenze —
     * der Vorgabe — füllen die Container einer Zeile deren ganze Breite.
     * Wirkt mit dem nächsten Layout-Durchlauf, ohne Neuaufbau.
     */
    void setLimitRowWidth(boolean limit);

    boolean isLimitRowWidth();
}
