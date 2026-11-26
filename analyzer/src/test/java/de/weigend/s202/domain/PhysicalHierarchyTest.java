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
package de.weigend.s202.domain;

import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * order.jar holds com.shop.order and half of com.shop.base, base.jar the
 * other half of com.shop.base - the package is split - and base.jar uses
 * order.jar back: the two files are tangled, the packages are not.
 */
class PhysicalHierarchyTest {

    private static final String ORDER = "order-1" + PhysicalHierarchy.DOT + "0" + PhysicalHierarchy.DOT + "jar";
    private static final String BASE = "base" + PhysicalHierarchy.DOT + "jar";

    @Test
    void everyClassLiesInTheFileItWasReadFrom() {
        PhysicalHierarchy physical = PhysicalHierarchy.of(shop());

        assertEquals(ORDER + ".com.shop.order.Order", physical.toPhysical("com.shop.order.Order"));
        assertEquals(Set.of(ORDER + ".com.shop.order.Order", ORDER + ".com.shop.base.Money",
                        BASE + ".com.shop.base.Audit"),
                physical.model().getAllClassNames());
        assertEquals("java.util.List", physical.toPhysical("java.util.List"), "outside the analysis");
    }

    @Test
    void dependenciesCallsAndKindsFollowTheirClasses() {
        DependencyModel.ClassInfo order = PhysicalHierarchy.of(shop()).model().getClass(ORDER + ".com.shop.order.Order");

        assertEquals(Set.of(ORDER + ".com.shop.base.Money", BASE + ".com.shop.base.Audit"), order.dependencies);
        assertEquals(Set.of(EdgeKind.CALLS), order.getKinds(BASE + ".com.shop.base.Audit"));
        assertEquals(Set.of("java.util.List"), order.externalDependencies.keySet());
        assertEquals(Integer.valueOf(2), order.getMethod("run", "()V").methodCalls.get(BASE + ".com.shop.base.Audit.log"));
        assertEquals("order-1.0.jar", order.getOrigin());
    }

    @Test
    void aSplitPackageIsAPackageInEachFile() {
        DependencyModel model = PhysicalHierarchy.of(shop()).model();

        assertTrue(model.getAllPackageNames().containsAll(Set.of(ORDER + ".com.shop.base", BASE + ".com.shop.base")));
        assertTrue(model.getAllPackageNames().contains(ORDER), "the file is the outermost package");
    }

    @Test
    void filesAreLevelledAndTangledLikePackages() {
        DomainModel domain = new LevelCalculator().calculate(PhysicalHierarchy.of(shop()).model());

        List<Set<String>> tangles = domain.getContainerTangles();
        assertTrue(tangles.contains(Set.of(ORDER, BASE)), "order.jar and base.jar use each other: " + tangles);
        assertFalse(tangles.stream().anyMatch(t -> t.stream().anyMatch(n -> n.endsWith("com.shop.order"))));
    }

    @Test
    void shownNamesAreThoseOfTheAnalysis() {
        assertEquals("com.shop.order.Order", PhysicalHierarchy.toLogical(ORDER + ".com.shop.order.Order"));
        assertEquals("order-1.0.jar", PhysicalHierarchy.toLogical(ORDER));
        assertEquals("com.shop.order.Order", PhysicalHierarchy.toLogical("com.shop.order.Order"));
        assertEquals("com.shop.order.Order → com.shop.base.Audit (2), order-1.0.jar",
                PhysicalHierarchy.shown(ORDER + ".com.shop.order.Order → " + BASE + ".com.shop.base.Audit (2), " + ORDER));
        assertTrue(PhysicalHierarchy.isFile(ORDER));
        assertFalse(PhysicalHierarchy.isFile(ORDER + ".com"));
        assertEquals(2, PhysicalHierarchy.files(shop()));
    }

    private static DependencyModel shop() {
        DependencyModel raw = new DependencyModel();
        DependencyModel.ClassInfo order = add(raw, "com.shop.order.Order", "order-1.0.jar",
                "com.shop.base.Money", "com.shop.base.Audit");
        order.addExternalDependency("java.util.List", EdgeKind.USES);
        order.addMethod("run", "()V");
        order.getMethod("run", "()V").methodCalls.put("com.shop.base.Audit.log", 2);
        add(raw, "com.shop.base.Money", "order-1.0.jar");
        add(raw, "com.shop.base.Audit", "base.jar", "com.shop.order.Order");
        return raw;
    }

    private static DependencyModel.ClassInfo add(DependencyModel raw, String name, String origin, String... targets) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        info.setOrigin(origin);
        for (String target : targets) {
            info.addDependency(target, EdgeKind.CALLS);
        }
        raw.addClass(name, info);
        return info;
    }
}
