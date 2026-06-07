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

import java.util.List;

/**
 * Immutable implementation of {@link HexagonalArchitecture}.
 */
public record HexagonalArchitectureModel(
        List<HexagonalArchitecture.HexRing> rings,
        List<HexagonalArchitecture.HexSegment> segments,
        List<HexagonalArchitecture.HexPort> ports,
        List<HexagonalArchitecture.HexElement> elements,
        List<Violation> violations,
        List<Tangle> tangles) implements HexagonalArchitecture {

    public HexagonalArchitectureModel {
        rings = rings == null ? List.of() : List.copyOf(rings);
        segments = segments == null ? List.of() : List.copyOf(segments);
        ports = ports == null ? List.of() : List.copyOf(ports);
        elements = elements == null ? List.of() : List.copyOf(elements);
        violations = violations == null ? List.of() : List.copyOf(violations);
        tangles = tangles == null ? List.of() : List.copyOf(tangles);
    }
}
