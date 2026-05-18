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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Domain-level architectural model of a project — the polymorphic
 * representation the UI ultimately renders. An {@code Architecture}
 * carries both its structural shape (subtype-specific, e.g. layered
 * rows) and the {@link Violation}s that are defined for the chosen
 * architectural style.
 *
 * <p>Sealed to {@link HierarchicalLayeredArchitecture} (immutable,
 * built directly from analysis) and {@link WhatIfArchitecture}
 * (mutable, starts as a deep copy and reflects user rearrangements).
 * Additional styles (e.g. interface-above-implementation) plug in by
 * implementing this interface with their own structural payload and
 * their own definition of what counts as a violation.
 */
public sealed interface Architecture
        permits HierarchicalLayeredArchitecture, WhatIfArchitecture {

    /**
     * Edge-level architectural violations the chosen style detected on
     * the underlying dependency graph (e.g. an UPWARD class-to-class
     * dep). Empty when the model satisfies the style's expectations.
     */
    List<Violation> violations();

    /**
     * Group-level architectural problems — strongly-connected
     * components of size {@literal >} 1 in the package graph. Each
     * tangle reports the member packages so consumers can render or
     * inspect the cycle as a whole.
     */
    List<Tangle> tangles();

    /**
     * Aggregate UPWARD violations into groups keyed by a caller-supplied
     * endpoint resolver. The {@code rollup} function maps a class FQN to
     * whatever string the caller considers the "endpoint" of an aggregate
     * — e.g. the FQN of the closest currently-visible package box (chart
     * renderer) or simply the parent package FQN (Dependencies side
     * panel). Violations whose source or target rolls up to {@code null}
     * are dropped.
     *
     * <p>The architecture owns the aggregation so chart renderer and side
     * panel can share the same logic; only the visibility-aware rollup
     * function lives in the UI.
     */
    default Map<EndpointPair, List<Violation>> groupUpwardViolations(Function<String, String> rollup) {
        Map<EndpointPair, List<Violation>> grouped = new LinkedHashMap<>();
        for (Violation v : violations()) {
            if (v.kind() != ViolationKind.UPWARD) {
                continue;
            }
            String src = rollup.apply(v.sourceFqn());
            String tgt = rollup.apply(v.targetFqn());
            if (src == null || tgt == null) {
                continue;
            }
            grouped.computeIfAbsent(new EndpointPair(src, tgt), k -> new ArrayList<>())
                    .add(v);
        }
        return grouped;
    }
}
