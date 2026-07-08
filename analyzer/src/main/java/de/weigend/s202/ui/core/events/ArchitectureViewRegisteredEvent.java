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
package de.weigend.s202.ui.core.events;

import de.weigend.s202.ui.core.platform.ArchitectureWfxView;

import java.util.EventObject;

/**
 * Wird nach jeder Registrierung eines Architektur-Tabs publiziert. Module
 * (z. B. das What-If-Dependencies-Panel), die sich an neue Architektur-Views
 * anhängen wollen, abonnieren dieses Event — statt dass der ViewManager sie
 * kennt und direkt andockt.
 */
public class ArchitectureViewRegisteredEvent extends EventObject {

    private final ArchitectureWfxView wrapper;

    public ArchitectureViewRegisteredEvent(ArchitectureWfxView wrapper, Object source) {
        super(source);
        this.wrapper = wrapper;
    }

    public ArchitectureWfxView getWrapper() {
        return wrapper;
    }
}
