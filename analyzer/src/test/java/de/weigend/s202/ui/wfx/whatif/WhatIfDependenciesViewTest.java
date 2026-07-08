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
package de.weigend.s202.ui.features.whatif;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.EndpointPair;
import de.weigend.s202.domain.impl.HierarchicalLayeredArchitecture;
import de.weigend.s202.domain.architecture.Tangle;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.reader.DependencyModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatIfDependenciesViewTest {

    @Test
    void classSelectionKeepsIncomingAndOutgoingViolationsOnly() {
        Architecture architecture = architectureWithViolations(List.of(
                upward("app.api.Api", "app.impl.Service"),
                upward("app.impl.Client", "app.api.Api"),
                upward("app.api.Other", "app.impl.Helper"),
                upward("other.Source", "other.Target")));
        DependencyModel rawModel = rawModelWithClass("app.api.Api", "Api", "app.api");

        Map<EndpointPair, List<Violation>> grouped = WhatIfDependenciesView.groupScopedViolations(
                architecture, rawModel, "app.api.Api", Set.of(ViolationKind.UPWARD));

        assertEquals(2, grouped.values().stream().mapToInt(List::size).sum());
        assertTrue(grouped.containsKey(new EndpointPair("app.api", "app.impl")));
        assertTrue(grouped.containsKey(new EndpointPair("app.impl", "app.api")));
    }

    @Test
    void packageSelectionIncludesSubpackagesButNotSimilarPackageNames() {
        Architecture architecture = architectureWithViolations(List.of(
                upward("app.feature.Source", "core.Target"),
                upward("app.feature.sub.Source", "core.Other"),
                upward("core.Source", "app.feature.Target"),
                upward("app.featured.Source", "core.NotInScope")));

        Map<EndpointPair, List<Violation>> grouped = WhatIfDependenciesView.groupScopedViolations(
                architecture, new DependencyModel(), "app.feature", Set.of(ViolationKind.UPWARD));

        assertEquals(3, grouped.values().stream().mapToInt(List::size).sum());
        assertTrue(grouped.containsKey(new EndpointPair("app.feature", "core")));
        assertTrue(grouped.containsKey(new EndpointPair("app.feature.sub", "core")));
        assertTrue(grouped.containsKey(new EndpointPair("core", "app.feature")));
    }

    @Test
    void tanglesFollowSelectedClassOrPackageScope() {
        Architecture architecture = new HierarchicalLayeredArchitecture(
                List.of(),
                List.of(),
                List.of(
                        new Tangle(Set.of("app.feature", "core")),
                        new Tangle(Set.of("app.feature.sub", "shared")),
                        new Tangle(Set.of("app.featured", "other")),
                        new Tangle(Set.of("app.api", "app.impl")),
                        new Tangle(Set.of("app.api.sub", "internal"))));
        DependencyModel rawModel = rawModelWithClass("app.api.Api", "Api", "app.api");

        List<Tangle> packageTangles = WhatIfDependenciesView.scopedTangles(
                architecture, rawModel, "app.feature");
        List<Tangle> classTangles = WhatIfDependenciesView.scopedTangles(
                architecture, rawModel, "app.api.Api");

        assertEquals(2, packageTangles.size());
        assertEquals(Set.of("app.feature", "core"), packageTangles.get(0).members());
        assertEquals(Set.of("app.feature.sub", "shared"), packageTangles.get(1).members());
        assertEquals(1, classTangles.size());
        assertEquals(Set.of("app.api", "app.impl"), classTangles.get(0).members());
    }

    private static Architecture architectureWithViolations(List<Violation> violations) {
        return new HierarchicalLayeredArchitecture(List.of(), violations, List.of());
    }

    private static Violation upward(String source, String target) {
        return new Violation(source, target, ViolationKind.UPWARD, 1, 0);
    }

    private static DependencyModel rawModelWithClass(String fullName, String simpleName, String packageName) {
        DependencyModel model = new DependencyModel();
        model.addClass(fullName, new DependencyModel.ClassInfo(fullName, simpleName, packageName));
        return model;
    }
}
