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

/**
 * The kind of architectural violation a specific {@link Architecture}
 * style is reporting on an edge. Each style picks the subset that
 * applies; the enum is shared so cross-style consumers (renderers,
 * panels, tests) speak one vocabulary.
 */
public enum ViolationKind {

    /**
     * The dependency goes against the architecture's layer direction
     * — source sits below target in the layered model.
     */
    UPWARD,

    /**
     * A dependency targets an implementation class of a foreign component
     * instead of the component's API surface.
     */
    COMPONENT_API_BYPASS,

    /**
     * An API class depends on an implementation class, making implementation
     * detail part of the API surface.
     */
    COMPONENT_API_LEAKS_IMPLEMENTATION,

    /**
     * Reserved for internal component dependencies that break the local
     * layering rules and are not covered by an API exception.
     */
    COMPONENT_INTERNAL_LAYER_BREAK,

    /**
     * Reserved for hexagonal styles: an inner ring depends outward on an
     * adapter implementation.
     */
    HEXAGON_OUTWARD_DEPENDENCY,

    /**
     * Reserved for hexagonal styles: a segment communicates past a port.
     */
    HEXAGON_PORT_BYPASS,

    /**
     * Fallback for style-specific findings that do not yet have a stable kind.
     */
    STYLE_UNCLASSIFIED
}
