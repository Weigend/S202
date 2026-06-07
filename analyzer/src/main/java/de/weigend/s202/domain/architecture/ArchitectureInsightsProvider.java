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
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.reader.DependencyModel;

import java.util.List;

/**
 * Public architecture-analysis facade for consumers that need architectural
 * findings but should not instantiate concrete architecture builders.
 */
public interface ArchitectureInsightsProvider {

    Architecture layered(DomainModel domain);

    ComponentArchitecture component(DomainModel domain,
                                    ArchitectureAnnotations annotations,
                                    DependencyModel rawModel);

    List<StronglyConnectedComponent> classSccs(DomainModel domain);
}
