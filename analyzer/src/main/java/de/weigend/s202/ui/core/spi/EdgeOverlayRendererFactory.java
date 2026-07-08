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
package de.weigend.s202.ui.core.spi;

import javafx.scene.Node;
import javafx.scene.layout.Pane;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Registrierungspunkt des Kanten-Overlays: die Tangle-Komponente stellt
 * eine {@code @Singleton}-Factory bereit; der Canvas erzeugt darüber pro
 * Root-Build den Renderer. Ist keine Factory registriert (Komponente
 * entfernt), läuft der Canvas ohne Overlay weiter.
 */
public interface EdgeOverlayRendererFactory {

    EdgeOverlayRenderer create(Pane targetPane,
                               Map<String, Node> elementRegistry,
                               Consumer<String> status);
}
