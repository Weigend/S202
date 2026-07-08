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
package de.weigend.s202.ui.views.layered;

import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.ui.core.spi.StyleView;
import de.weigend.s202.ui.core.spi.StyleViewFactory;
import de.weigend.s202.ui.core.spi.ViewServices;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Registriert die Schichten-Ansicht am StyleView-SPI. */
@Singleton
public final class LayeredStyleViewFactory implements StyleViewFactory {

    @Inject
    LayeredStyleViewFactory() {
    }

    @Override
    public ArchitectureKind kind() {
        return ArchitectureKind.LAYERED;
    }

    @Override
    public StyleView create(ViewServices services) {
        return new LayeredStyleView(services);
    }
}
