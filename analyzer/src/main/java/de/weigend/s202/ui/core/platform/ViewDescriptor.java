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
package de.weigend.s202.ui.core.platform;

import de.weigend.s202.domain.architecture.ArchitectureKind;

import java.util.List;
import java.util.Set;

/**
 * Was ein Architektur-Tab zeigt, so beschrieben, dass er sich daraus neu
 * aufbauen lässt: welche Art von Sicht, auf welchen Ausschnitt des Dokuments.
 * Jeder Tab trägt seine Beschreibung; eine Projektdatei speichert die
 * Beschreibungen der Tabs ihres Dokuments, und
 * {@link ArchitectureViewManager#open} baut aus einer Beschreibung wieder
 * denselben Tab — auf demselben Weg, auf dem ihn die Menü-Aktion gebaut hat.
 * <p>
 * Nicht Teil der Beschreibung ist, was der Benutzer danach mit dem Bild
 * gemacht hat (Zoom, aufgeklappte Pakete, Schalter); das hält der Canvas.
 *
 * @param kind            die Art der Sicht
 * @param label           der Teil des Tab-Titels hinter dem Dokumentnamen;
 *                        {@code null} für die Schichten-Sicht des ganzen
 *                        Dokuments, deren Tab nur den Dokumentnamen trägt
 * @param scope           das Paket, auf das der Baum beschränkt ist;
 *                        {@code null} für das ganze Dokument
 * @param scopeExtensions was per „Add to Scope“ zum Paket hinzukam
 * @param tangleMembers   die Mitglieder des Tangles (nur {@link Kind#TANGLE})
 * @param tangleKey       der Schlüssel, unter dem der Tangle-Tab
 *                        wiederverwendet wird (nur {@link Kind#TANGLE})
 * @param tangleContext   ob der Tangle-Tab den Kontext der Mitglieder zeigt
 */
public record ViewDescriptor(Kind kind,
                             String label,
                             String scope,
                             List<String> scopeExtensions,
                             Set<String> tangleMembers,
                             String tangleKey,
                             boolean tangleContext) {

    /** Die Arten von Architektur-Tabs. */
    public enum Kind {
        /** Die Schichten-Sicht des ganzen Dokuments — der Tab, mit dem eine Analyse aufgeht. */
        LAYERED,
        /** Die Component-Projektion. */
        COMPONENT,
        /** Die Hexagonal-Projektion. */
        HEXAGONAL,
        /** Die Schichten-Sicht eines Pakets, erweiterbar per „Add to Scope“. */
        SCOPE,
        /** Ein Tangle als Graph. */
        TANGLE
    }

    public ViewDescriptor {
        scopeExtensions = scopeExtensions == null ? List.of() : List.copyOf(scopeExtensions);
        tangleMembers = tangleMembers == null ? Set.of() : Set.copyOf(tangleMembers);
    }

    /** Die Schichten-Sicht des ganzen Dokuments. */
    public static ViewDescriptor layered() {
        return new ViewDescriptor(Kind.LAYERED, null, null, List.of(), Set.of(), null, false);
    }

    /** Eine Stil-Projektion des Ausschnitts, den {@code base} zeigt. */
    public static ViewDescriptor projection(ArchitectureKind style, String label, ViewDescriptor base) {
        Kind kind = style == ArchitectureKind.HEXAGONAL ? Kind.HEXAGONAL : Kind.COMPONENT;
        return new ViewDescriptor(kind, label, base.scope(), base.scopeExtensions(), Set.of(), null, false);
    }

    /** Die Schichten-Sicht des Pakets {@code scope}. */
    public static ViewDescriptor scope(String label, String scope) {
        return new ViewDescriptor(Kind.SCOPE, label, scope, List.of(), Set.of(), null, false);
    }

    /** Der Tangle aus {@code members}, gesucht in dem Ausschnitt, den {@code base} zeigt. */
    public static ViewDescriptor tangle(String label, Set<String> members, String key, ViewDescriptor base) {
        return new ViewDescriptor(Kind.TANGLE, label, base.scope(), base.scopeExtensions(), members, key, false);
    }

    /** Der Darstellungs-Stil, den ein Tab dieser Art benutzt. */
    public ArchitectureKind style() {
        return switch (kind) {
            case COMPONENT -> ArchitectureKind.COMPONENT;
            case HEXAGONAL -> ArchitectureKind.HEXAGONAL;
            default -> ArchitectureKind.LAYERED;
        };
    }

    /** Dieselbe Beschreibung mit dem Stand, den der Tab jetzt hat. */
    public ViewDescriptor withState(List<String> currentScopeExtensions, boolean currentTangleContext) {
        return new ViewDescriptor(kind, label, scope, currentScopeExtensions, tangleMembers, tangleKey,
                currentTangleContext);
    }
}
