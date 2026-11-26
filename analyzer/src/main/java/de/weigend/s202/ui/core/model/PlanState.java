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
package de.weigend.s202.ui.core.model;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.Placement;

import java.util.Set;

/**
 * Which state of the code a view shows: the one that was analyzed, or that
 * with everything planned applied. Panels that follow a view show the same.
 *
 * @param shown     whether the plan is applied
 * @param cuts      the dependencies that count as gone; none while the plan is not shown
 * @param placement where the elements sit; that of the code while the plan is not shown
 * @param planned   how many changes are planned, shown or not
 */
public record PlanState(boolean shown, Set<DependencyEdge> cuts, Placement placement, int planned) {

    public static final PlanState NOTHING_PLANNED = new PlanState(true, Set.of(), Placement.OF_THE_CODE, 0);

    public PlanState {
        cuts = Set.copyOf(cuts);
    }

    /** Whether what is shown differs from the analyzed code. */
    public boolean changesTheCode() {
        return !cuts.isEmpty() || !placement.isOfTheCode();
    }
}
