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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.Placement;
import de.weigend.s202.ui.core.model.PlanState;
import de.weigend.s202.ui.core.model.WhatIfMove;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Keeps the {@link PlanState} of one canvas: what is planned comes from the
 * moves, the planned packages and the cuts, and whether it is applied from the
 * canvas's switch.
 */
final class PlanController {

    private final BooleanProperty showPlan;
    private final Supplier<List<WhatIfMove>> moves;
    private final Supplier<Map<String, String>> plannedPackages;
    private final Supplier<Set<DependencyEdge>> cuts;
    private final Supplier<DomainModel> analyzed;
    private final ReadOnlyObjectWrapper<PlanState> plan = new ReadOnlyObjectWrapper<>(PlanState.NOTHING_PLANNED);

    private DomainModel topLevelOf;
    private String topLevel = "";

    PlanController(BooleanProperty showPlan,
                   Supplier<List<WhatIfMove>> moves,
                   Supplier<Map<String, String>> plannedPackages,
                   Supplier<Set<DependencyEdge>> cuts,
                   Supplier<DomainModel> analyzed) {
        this.showPlan = showPlan;
        this.moves = moves;
        this.plannedPackages = plannedPackages;
        this.cuts = cuts;
        this.analyzed = analyzed;
    }

    ReadOnlyObjectProperty<PlanState> planProperty() {
        return plan.getReadOnlyProperty();
    }

    PlanState plan() {
        return plan.get();
    }

    /** The state as it is now; {@link #publish} tells everybody. */
    PlanState current() {
        List<WhatIfMove> made = moves.get();
        Map<String, String> packages = plannedPackages.get();
        Set<DependencyEdge> cut = cuts.get();
        int planned = made.size() + packages.size() + cut.size();
        return showPlan.get()
                ? new PlanState(true, cut, placement(made, packages.keySet()), planned)
                : new PlanState(false, Set.of(), Placement.OF_THE_CODE, planned);
    }

    void publish(PlanState state) {
        plan.set(state);
    }

    private Placement placement(List<WhatIfMove> made, Set<String> packages) {
        if (made.isEmpty()) {
            return Placement.OF_THE_CODE;
        }
        Map<String, String> moved = new HashMap<>();
        packages.forEach(id -> moved.put(id, topLevel()));
        for (WhatIfMove move : made) {
            moved.put(move.fqn(), move.containerFqn() == null || move.containerFqn().isEmpty()
                    ? topLevel() : move.containerFqn());
        }
        moved.entrySet().removeIf(entry -> entry.getValue().equals(Placement.OF_THE_CODE.containerOf(entry.getKey())));
        return Placement.of(moved);
    }

    private String topLevel() {
        DomainModel model = analyzed.get();
        if (model == null) {
            return "";
        }
        if (model != topLevelOf) {
            topLevelOf = model;
            topLevel = Placement.topLevel(model.getAllPackages().keySet());
        }
        return topLevel;
    }
}
