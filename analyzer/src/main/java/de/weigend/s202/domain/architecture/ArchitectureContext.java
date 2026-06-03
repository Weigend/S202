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
package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;

import java.util.Objects;

/**
 * Immutable input bundle for architecture-style builders. The calculated
 * {@link DomainModel} is the source of levels and dependencies; annotations
 * add user-controlled architectural roles without changing the level model.
 */
public record ArchitectureContext(
        DependencyModel rawModel,
        DomainModel domainModel,
        ArchitectureAnnotations annotations) {

    public ArchitectureContext {
        Objects.requireNonNull(domainModel, "domainModel cannot be null");
        annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
    }
}
