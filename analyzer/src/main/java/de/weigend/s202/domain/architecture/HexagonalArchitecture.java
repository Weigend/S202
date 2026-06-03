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
 * Domain projection for a first hexagonal architecture prototype. The
 * projection keeps calculated levels intact and maps classes into radial rings,
 * top-level segments, and optional port sockets.
 */
public record HexagonalArchitecture(
        List<HexRing> rings,
        List<HexSegment> segments,
        List<HexPort> ports,
        List<HexElement> elements,
        List<Violation> violations,
        List<Tangle> tangles) implements Architecture {

    public HexagonalArchitecture {
        rings = rings == null ? List.of() : List.copyOf(rings);
        segments = segments == null ? List.of() : List.copyOf(segments);
        ports = ports == null ? List.of() : List.copyOf(ports);
        elements = elements == null ? List.of() : List.copyOf(elements);
        violations = violations == null ? List.of() : List.copyOf(violations);
        tangles = tangles == null ? List.of() : List.copyOf(tangles);
    }

    public record HexRing(String id, String label, RingRole role) {
        public HexRing {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must be non-empty");
            }
            label = label == null || label.isBlank() ? id : label;
            role = role == null ? RingRole.APPLICATION : role;
        }
    }

    public enum RingRole {
        CORE,
        APPLICATION,
        ADAPTER
    }

    public record HexSegment(String id, String label, String rootFqn, boolean explicit) {
        public HexSegment {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must be non-empty");
            }
            if (rootFqn == null || rootFqn.isBlank()) {
                throw new IllegalArgumentException("rootFqn must be non-empty");
            }
            label = label == null || label.isBlank() ? id : label;
        }
    }

    public record HexPort(String id,
                          String classFqn,
                          String segmentId,
                          ArchitectureAnnotations.PortDirection direction,
                          boolean explicit) {
        public HexPort {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must be non-empty");
            }
            if (classFqn == null || classFqn.isBlank()) {
                throw new IllegalArgumentException("classFqn must be non-empty");
            }
            if (segmentId == null || segmentId.isBlank()) {
                throw new IllegalArgumentException("segmentId must be non-empty");
            }
            direction = direction == null ? ArchitectureAnnotations.PortDirection.GENERIC : direction;
        }
    }

    public record HexElement(String fqn,
                             String simpleName,
                             boolean classElement,
                             String segmentId,
                             RingRole ringRole,
                             ArchitectureAnnotations.ElementRole elementRole,
                             int architectureLevel,
                             int localLevel,
                             boolean componentApi,
                             boolean portCandidate,
                             boolean explicitPort) {
        public HexElement {
            if (fqn == null || fqn.isBlank()) {
                throw new IllegalArgumentException("fqn must be non-empty");
            }
            simpleName = simpleName == null || simpleName.isBlank() ? fqn : simpleName;
            if (segmentId == null || segmentId.isBlank()) {
                throw new IllegalArgumentException("segmentId must be non-empty");
            }
            ringRole = ringRole == null ? RingRole.APPLICATION : ringRole;
            elementRole = elementRole == null ? ArchitectureAnnotations.ElementRole.NONE : elementRole;
            if (architectureLevel < 0) {
                throw new IllegalArgumentException("architectureLevel must be non-negative");
            }
            if (localLevel < 0) {
                throw new IllegalArgumentException("localLevel must be non-negative");
            }
        }
    }
}
