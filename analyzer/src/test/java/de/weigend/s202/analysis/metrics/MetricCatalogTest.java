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
package de.weigend.s202.analysis.metrics;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p.A uses p.B and the interface p.I, p.B and p.C use each other, q.D uses p.A.
 */
class MetricCatalogTest {

    private MetricCatalog catalog;
    private final List<String> p = List.of("p.A", "p.B", "p.C", "p.I");

    @BeforeEach
    void model() {
        DependencyModel model = new DependencyModel();
        DependencyModel.ClassInfo a = add(model, "p.A", false);
        DependencyModel.ClassInfo b = add(model, "p.B", false);
        DependencyModel.ClassInfo c = add(model, "p.C", false);
        add(model, "p.I", true);
        DependencyModel.ClassInfo d = add(model, "q.D", false);
        a.addDependency("p.B", EdgeKind.CALLS);
        a.addDependency("p.I", EdgeKind.CALLS);
        b.addDependency("p.C", EdgeKind.CALLS);
        c.addDependency("p.B", EdgeKind.CALLS);
        d.addDependency("p.A", EdgeKind.CALLS);
        a.addExternalDependency("java.util.List", EdgeKind.CALLS);
        catalog = new MetricCatalog(model, name -> name.equals("p") ? 1 : name.equals("p.A") ? 2 : -1);
    }

    private static DependencyModel.ClassInfo add(DependencyModel model, String name, boolean isInterface) {
        int dot = name.lastIndexOf('.');
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(name, name.substring(dot + 1),
                name.substring(0, dot), isInterface);
        model.addClass(name, info);
        return info;
    }

    @Test
    void fanInAndOutOfClasses() {
        assertEquals(2, catalog.classValue(Metric.FAN_IN, "p.B"));
        assertEquals(2, catalog.classValue(Metric.FAN_OUT, "p.A"));
        assertEquals(1.0 / 3, catalog.classValue(Metric.INSTABILITY, "p.B"), 1e-9);
        assertEquals(1, catalog.classValue(Metric.EXTERNALS, "p.A"));
    }

    @Test
    void cyclesAndWhatIsReachedOverAnyNumberOfSteps() {
        assertEquals(2, catalog.classValue(Metric.CYCLE, "p.B"));
        assertEquals(0, catalog.classValue(Metric.CYCLE, "p.A"));
        assertEquals(3, catalog.classValue(Metric.REACH, "p.A"), "B, C and I");
        assertEquals(1, catalog.classValue(Metric.REACH, "p.B"), "C, not itself");
        assertEquals(3, catalog.classValue(Metric.IMPACT, "p.B"), "A, C and D");
        assertEquals(2, catalog.classValue(Metric.IMPACT, "p.I"), "A and D");
    }

    @Test
    void aPackageCountsWhatLiesOutsideIt() {
        assertEquals(4, catalog.groupValue(Metric.SIZE, "p", p));
        assertEquals(1, catalog.groupValue(Metric.FAN_IN, "p", p), "D");
        assertEquals(0, catalog.groupValue(Metric.FAN_OUT, "p", p));
        assertEquals(0, catalog.groupValue(Metric.INSTABILITY, "p", p));
        assertEquals(0.25, catalog.groupValue(Metric.ABSTRACTNESS, "p", p));
        assertEquals(0.75, catalog.groupValue(Metric.DISTANCE, "p", p), 1e-9);
        assertEquals(2, catalog.groupValue(Metric.CYCLE, "p", p), "B and C");
        assertEquals(1, catalog.groupValue(Metric.IMPACT, "p", p), "D");
    }

    @Test
    void theLevelComesFromTheView() {
        assertEquals(2, catalog.classValue(Metric.LEVEL, "p.A"));
        assertEquals(1, catalog.groupValue(Metric.LEVEL, "p", p));
        assertTrue(Double.isNaN(catalog.classValue(Metric.LEVEL, "p.B")));
    }

    @Test
    void withoutBytecodeThereIsNoComplexity() {
        assertFalse(catalog.isAvailable(Metric.COMPLEXITY));
        assertTrue(catalog.isAvailable(Metric.FAN_IN));
        assertTrue(Double.isNaN(catalog.classValue(Metric.COMPLEXITY, "p.A")));
        assertTrue(Double.isNaN(catalog.groupValue(Metric.LINES, "p", p)));
    }

    @Test
    void scalesPlaceValuesBetweenTheSmallestAndTheLargest() {
        assertEquals(0.5, MetricMapping.Scale.LINEAR.position(5, 0, 10), 1e-9);
        assertEquals(Math.sqrt(0.5), MetricMapping.Scale.SQRT.position(5, 0, 10), 1e-9);
        assertEquals(1, MetricMapping.Scale.LOG.position(10, 0, 10), 1e-9);
        assertEquals(1, MetricMapping.Scale.LINEAR.position(3, 3, 3), "all the same and something there");
        assertEquals(0, MetricMapping.Scale.LINEAR.position(Double.NaN, 0, 10));
    }
}
