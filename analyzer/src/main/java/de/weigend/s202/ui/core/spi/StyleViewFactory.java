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

import de.weigend.s202.domain.architecture.ArchitectureKind;

/**
 * Registrierungspunkt einer Stil-Ansicht: Fachkomponenten unter
 * {@code ui.views.*} stellen eine {@code @Singleton}-Factory bereit
 * (Lookup/DI, wie beim Domain-SPI {@code ArchitectureStyle}); der Canvas
 * erzeugt daraus pro View eine zustandsbehaftete {@link StyleView}.
 */
public interface StyleViewFactory {

    ArchitectureKind kind();

    StyleView create(ViewServices services);
}
