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
package de.weigend.s202.analysis.quality;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.Placement;
import de.weigend.s202.domain.SCCFinder;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QualityCalculatorTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    /** a.A and b.B use each other; a.C uses a.A and b.B; b.D uses nothing. */
    private final DomainModel model = model();
    private final QualityCalculator calculator = new QualityCalculator(model, Lookup.lookup(SCCFinder.class));

    @Test
    void aPartIsMeasuredLikeTheWhole() {
        QualityMetrics all = calculator.ofAll();
        QualityMetrics a = calculator.ofPackage("a");
        QualityMetrics b = calculator.ofPackage("b");

        assertEquals(all.getClassCount(), a.getClassCount() + b.getClassCount());
        assertEquals(all.getDependencyCount(), a.getDependencyCount() + b.getDependencyCount());
        assertEquals(all.getIntraSccDependencyCount(), a.getIntraSccDependencyCount() + b.getIntraSccDependencyCount());
    }

    @Test
    void dependenciesThatLeaveThePackageCount() {
        QualityMetrics a = calculator.ofPackage("a");

        assertEquals(3, a.getDependencyCount(), "A -> B, C -> A, C -> B");
        assertEquals(1.5, a.getAverageDependencies(), 0.001);
        assertEquals(0.15, a.getFat(), 0.001);
    }

    @Test
    void aCycleThatLeavesThePackageCounts() {
        assertEquals(1.0 / 3, calculator.ofPackage("a").getTangled(), 0.001, "A -> B lies on the cycle with b.B");
        assertEquals(1.0 / 3, calculator.ofClasses(List.of("a.A", "a.C")).getTangled(), 0.001, "the same classes");
    }

    @Test
    void theBreakoutCountsTheDependenciesBetweenTheMembers() {
        assertEquals(2, calculator.ofAll().getBreakout(), "a -> b and b -> a, however many classes");
        assertEquals(1, calculator.ofPackage("a").getBreakout(), "C -> A");
        assertEquals(0, calculator.ofPackage("b").getBreakout());
        assertEquals(QualityMetrics.NO_BREAKOUT, calculator.ofClasses(List.of("a.A")).getBreakout());
    }

    @Test
    void everyPackageAtOnceIsMeasuredAsEachAlone() {
        Placement plan = Placement.of(Map.of("a.A", "b"));
        for (QualityCalculator measured : List.of(calculator,
                new QualityCalculator(model, Lookup.lookup(SCCFinder.class), plan))) {
            Map<String, QualityMetrics> every = measured.ofEveryPackage();

            assertEquals(Set.of("a", "b"), every.keySet());
            every.forEach((pkg, metrics) -> assertEquals(measured.ofPackage(pkg).toString(), metrics.toString(), pkg));
        }
    }

    @Test
    void theBreakoutFollowsAMove() {
        Placement plan = Placement.of(Map.of("a.A", "b"));
        QualityCalculator planned = new QualityCalculator(model, Lookup.lookup(SCCFinder.class), plan);

        assertEquals(1, planned.ofAll().getBreakout(), "only a -> b is left");
        assertEquals(2, planned.ofPackage("b").getBreakout(), "A and B use each other inside b now");
        assertEquals(1, planned.ofPackage("a").getClassCount());
        assertEquals(calculator.ofAll().getFat(), planned.ofAll().getFat(), 0.001, "an average over the classes does not");
    }

    private static DomainModel model() {
        DomainModel model = new DomainModel();
        model.addPackage("a", new CalculatedElementInfo("a", "a", "PACKAGE", 0, new HashSet<>()));
        model.addPackage("b", new CalculatedElementInfo("b", "b", "PACKAGE", 0, new HashSet<>()));
        add(model, "a.A", "b.B");
        add(model, "b.B", "a.A", "java.lang.String");
        add(model, "a.C", "a.A", "b.B");
        add(model, "b.D");
        return model;
    }

    private static void add(DomainModel model, String name, String... targets) {
        model.addClass(name, new CalculatedElementInfo(
                name, name.substring(name.lastIndexOf('.') + 1), "CLASS", 0, new HashSet<>(Set.of(targets))));
    }
}
