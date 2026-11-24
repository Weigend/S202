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
import java.util.Objects;
import java.util.Set;

/**
 * What-if moves of the component view: where the user put which element. They
 * change the violations, never the components, levels or dependencies.
 *
 * @param moves           in the order they were made
 * @param plannedPackages ids of packages the user added; each is a component of its own
 */
public record ComponentMoves(List<Move> moves, Set<String> plannedPackages) {

    private static final ComponentMoves NONE = new ComponentMoves(List.of(), Set.of());

    public ComponentMoves {
        moves = moves == null ? List.of() : List.copyOf(moves);
        plannedPackages = plannedPackages == null ? Set.of() : Set.copyOf(plannedPackages);
    }

    public static ComponentMoves none() {
        return NONE;
    }

    /**
     * @param elementFqn   the moved class or package, by the name it has in the code
     * @param containerFqn the package it was dropped into, by the name that one has in the code,
     *                     the id of a planned package, or empty for the top level
     * @param api          dropped into the API area of a component
     */
    public record Move(String elementFqn, String containerFqn, boolean api) {
        public Move {
            Objects.requireNonNull(elementFqn, "elementFqn cannot be null");
            containerFqn = containerFqn == null ? "" : containerFqn;
        }
    }
}
