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

import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Sichert den DI-Vertrag der geteilten Plattform-Dienste ab: Nachdem die
 * wfx-Module in eigene Fachkomponenten-Pakete gewandert sind, teilen sich
 * mehrere Module denselben {@link ArchitectureViewManager} /
 * {@link ProgressPublisher} / {@link RefactoringPreviewState}. Das
 * funktioniert nur, wenn diese als echte Singletons über {@link Lookup}
 * auflösbar sind — würde man sie (wie versehentlich geschehen) wieder als
 * {@code new …} oder als Feld-Initializer-Lookup verdrahten, driftet der
 * Zustand zwischen Toolbar und Tangle-Tabs auseinander. Dieser Test bricht,
 * bevor es die manuelle UI-Prüfung tut.
 */
class PlatformServicesDiTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void sharedPlatformServicesAreResolvableSingletons() {
        assertResolvableSingleton(ArchitectureViewManager.class);
        assertResolvableSingleton(ProgressPublisher.class);
        assertResolvableSingleton(RefactoringPreviewState.class);
    }

    private static <T> void assertResolvableSingleton(Class<T> type) {
        T first = Lookup.lookup(type);
        assertNotNull(first, type.getSimpleName() + " ist nicht über Lookup auflösbar "
                + "(fehlende @Singleton-Registrierung → Module bekämen getrennte Instanzen)");
        assertSame(first, Lookup.lookup(type),
                type.getSimpleName() + " ist kein Singleton — Module würden verschiedene Instanzen sehen");
    }
}
