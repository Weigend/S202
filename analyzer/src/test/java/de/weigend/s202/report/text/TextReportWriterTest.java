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
package de.weigend.s202.report.text;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.report.text.RefactoringReportInput.ElementMove;
import de.weigend.s202.report.text.RefactoringReportInput.ViewChanges;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextReportWriterTest {

    private static TextReportWriter writer;

    @BeforeAll
    static void initLookup() {
        Lookup.init();
        writer = Lookup.lookup(TextReportWriter.class);
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void refactoringReportNamesMovesWithTheirComponents() {
        String report = refactoring(ArchitectureAnnotations.empty(), Set.of(), new ViewChanges(
                "app.jar · Component View",
                true,
                Map.of("virtual-1", "com.acme.fresh"),
                List.of(
                        new ElementMove("com.acme.pay.store.Store", "com.acme.web", 0, false),
                        // the last position of an element counts
                        new ElementMove("com.acme.pay.store.Store", "com.acme.ship", 0, true),
                        new ElementMove("com.acme.web.Controller", "virtual-1", 0, false),
                        new ElementMove("com.acme.pay.store", "com.acme.pay", 2, false),
                        // into a planned package that is gone by now
                        new ElementMove("com.acme.ship.Shipper", "virtual-deleted", 0, false))));

        assertContains(report, "New packages\n", "- com.acme.fresh\n");
        assertContains(report, """
                - class com.acme.pay.store.Store
                    from: com.acme.pay.store (component pay)
                    to:   com.acme.ship (API of component ship)
                """);
        assertContains(report, """
                - class com.acme.web.Controller
                    from: com.acme.web
                    to:   new package com.acme.fresh
                """);
        assertContains(report, "same package\n", "- package com.acme.pay.store: row 3 from the top\n");
        assertFalse(report.contains("to:   com.acme.web"), "an overtaken move");
        assertFalse(report.contains("Shipper"), "a void move");
        // Store is public now, in the component it was moved to
        assertContains(report, "access past the API of a component: 1 -> 0\n",
                "solved:\n    com.acme.web.Controller -> com.acme.pay.store.Store\n");
    }

    @Test
    void refactoringReportShowsTheEffectOfTheMapping() {
        String report = refactoring(
                ArchitectureAnnotations.empty()
                        .withComponentApiIncluded("com.acme.pay.store")
                        .withComponentApiExcluded("com.acme.ship.api.Ship"),
                Set.of(new DependencyEdge("com.acme.web.Controller", "com.acme.pay.store.Store")));

        assertContains(report,
                "- add to API:      package com.acme.pay.store\n",
                "- remove from API: class com.acme.ship.api.Ship\n",
                "- component com.acme.pay\n    now public:  com.acme.pay.store.Store\n",
                "- component com.acme.ship\n    now private: com.acme.ship.api.Ship\n",
                "past the API of a component: 1 -> 0\n",
                "Dependencies to remove\n",
                "- com.acme.web.Controller -> com.acme.pay.store.Store\n");
    }

    @Test
    void refactoringReportWithoutChangesSaysSo() {
        assertContains(refactoring(ArchitectureAnnotations.empty(), Set.of()), "No changes.");
    }

    @Test
    void violationReportNamesRuleBoundaryAndUsage() {
        String report = violations(ArchitectureAnnotations.empty(),
                Set.of(new DependencyEdge("com.acme.web.Controller", "com.acme.pay.store.Store")));

        assertContains(report,
                "1 past a component API",
                "Component com.acme.pay\n  public: com.acme.pay.api\n",
                "- com.acme.web.Controller -> com.acme.pay.store.Store (calls save) [planned to be removed]\n");
    }

    @Test
    void violationReportFollowsTheMapping() {
        String report = violations(
                ArchitectureAnnotations.empty().withComponentApiIncluded("com.acme.pay.store"), Set.of());

        assertContains(report, "0 past a component API", "No violations.");
    }

    private static void assertContains(String report, String... parts) {
        for (String part : parts) {
            assertTrue(report.contains(part), () -> "Missing:\n" + part + "\nin:\n" + report);
        }
    }

    private static String refactoring(ArchitectureAnnotations annotations,
                                      Set<DependencyEdge> cuts,
                                      ViewChanges... views) {
        return writer.refactoringReport(new RefactoringReportInput(
                "app.jar", List.of("/tmp/app.jar"), rawModel(), domainModel(), annotations, cuts, List.of(views)));
    }

    private static String violations(ArchitectureAnnotations annotations, Set<DependencyEdge> cuts) {
        return writer.violationReport(new ViolationReportInput(
                "app.jar", List.of("/tmp/app.jar"), rawModel(), domainModel(), annotations, cuts));
    }

    private static DomainModel domainModel() {
        DomainModel model = new DomainModel();
        for (String fqn : List.of("com", "com.acme", "com.acme.pay", "com.acme.pay.api", "com.acme.pay.store",
                "com.acme.ship", "com.acme.ship.api", "com.acme.web")) {
            model.addPackage(fqn, info(fqn, "PACKAGE", Set.of()));
        }
        model.addClass("com.acme.pay.api.Pay", info("com.acme.pay.api.Pay", "CLASS", Set.of()));
        model.addClass("com.acme.pay.store.Store", info("com.acme.pay.store.Store", "CLASS", Set.of()));
        model.addClass("com.acme.ship.api.Ship", info("com.acme.ship.api.Ship", "CLASS", Set.of()));
        model.addClass("com.acme.ship.Shipper", info("com.acme.ship.Shipper", "CLASS", Set.of()));
        model.addClass("com.acme.web.Controller", info("com.acme.web.Controller", "CLASS",
                Set.of("com.acme.pay.store.Store")));
        return model;
    }

    private static CalculatedElementInfo info(String fqn, String type, Set<String> dependencies) {
        return new CalculatedElementInfo(fqn, fqn.substring(fqn.lastIndexOf('.') + 1), type, 0, dependencies);
    }

    private static DependencyModel rawModel() {
        DependencyModel model = new DependencyModel();
        DependencyModel.ClassInfo controller = new DependencyModel.ClassInfo(
                "com.acme.web.Controller", "Controller", "com.acme.web");
        controller.addDependency("com.acme.pay.store.Store", EdgeKind.CALLS);
        controller.addMethod("run", "()V");
        controller.getMethod("run", "()V").methodCalls.put("com.acme.pay.store.Store.save", 2);
        model.addClass(controller.fullName, controller);
        return model;
    }
}
