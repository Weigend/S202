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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexagonalArchitectureBuilderTest {

    @Test
    void buildsSegmentsRingsPortsAndHexagonalViolations() {
        DomainModel domain = domainModel();
        ArchitectureAnnotations annotations = ArchitectureAnnotations.empty()
                .withPort("com.acme.order.application.CreateOrderPort",
                        ArchitectureAnnotations.PortDirection.INBOUND,
                        "com.acme.order")
                .withPort("com.acme.order.application.OrderRepositoryPort",
                        ArchitectureAnnotations.PortDirection.OUTBOUND,
                        "com.acme.order")
                .withElementRole("com.acme.web", ArchitectureAnnotations.ElementRole.ADAPTER)
                .withElementRole("com.acme.persistence", ArchitectureAnnotations.ElementRole.ADAPTER)
                .withElementRole("com.acme.order.domain", ArchitectureAnnotations.ElementRole.CORE);

        HexagonalArchitecture architecture = new HexagonalArchitectureBuilder()
                .build(domain, annotations);

        assertEquals(List.of("com.acme.order", "com.acme.persistence", "com.acme.web"),
                architecture.segments().stream()
                        .map(HexagonalArchitecture.HexSegment::rootFqn)
                        .toList());
        assertEquals(Set.of(
                        "com.acme.order.application.CreateOrderPort",
                        "com.acme.order.application.OrderRepositoryPort"),
                architecture.ports().stream()
                        .map(HexagonalArchitecture.HexPort::classFqn)
                        .collect(Collectors.toSet()));

        assertElementRing(architecture,
                "com.acme.order.domain.Order",
                HexagonalArchitecture.RingRole.CORE);
        assertElementRing(architecture,
                "com.acme.web.OrderController",
                HexagonalArchitecture.RingRole.ADAPTER);
        assertElementRing(architecture,
                "com.acme.order.application.CreateOrderPort",
                HexagonalArchitecture.RingRole.APPLICATION);

        assertViolation(architecture,
                "com.acme.order.application.OrderService",
                "com.acme.persistence.JpaOrderRepository",
                ViolationKind.HEXAGON_OUTWARD_DEPENDENCY);
        assertViolation(architecture,
                "com.acme.web.OrderController",
                "com.acme.order.application.OrderService",
                ViolationKind.HEXAGON_PORT_BYPASS);
        assertViolation(architecture,
                "com.acme.web.OrderController",
                "com.acme.order.application.OrderQueryApi",
                ViolationKind.HEXAGON_PORT_BYPASS);

        assertFalse(hasViolation(architecture,
                "com.acme.web.OrderController",
                "com.acme.order.application.CreateOrderPort",
                ViolationKind.HEXAGON_PORT_BYPASS));
        assertFalse(hasViolation(architecture,
                "com.acme.persistence.JpaOrderRepository",
                "com.acme.order.application.OrderRepositoryPort",
                ViolationKind.HEXAGON_PORT_BYPASS));
    }

    @Test
    void hexagonalProjectionDoesNotMutateCalculatedLevels() {
        DomainModel domain = domainModel();
        Map<String, Integer> before = classLevels(domain);

        new HexagonalArchitectureBuilder().build(domain, ArchitectureAnnotations.empty());

        assertEquals(before, classLevels(domain));
    }

    private static void assertElementRing(HexagonalArchitecture architecture,
                                          String fqn,
                                          HexagonalArchitecture.RingRole expected) {
        HexagonalArchitecture.HexElement element = architecture.elements().stream()
                .filter(e -> e.fqn().equals(fqn))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, element.ringRole());
    }

    private static void assertViolation(HexagonalArchitecture architecture,
                                        String source,
                                        String target,
                                        ViolationKind kind) {
        assertTrue(hasViolation(architecture, source, target, kind),
                () -> "Missing " + kind + " violation: " + source + " -> " + target);
    }

    private static boolean hasViolation(HexagonalArchitecture architecture,
                                        String source,
                                        String target,
                                        ViolationKind kind) {
        return architecture.violations().stream()
                .anyMatch(v -> v.sourceFqn().equals(source)
                        && v.targetFqn().equals(target)
                        && v.kind() == kind);
    }

    private static Map<String, Integer> classLevels(DomainModel domain) {
        Map<String, Integer> result = new LinkedHashMap<>();
        domain.getAllClasses().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().architectureLevel));
        return result;
    }

    private static DomainModel domainModel() {
        DomainModel model = new DomainModel();

        pkg(model, "com", 0);
        pkg(model, "com.acme", 0);
        pkg(model, "com.acme.order", 1);
        pkg(model, "com.acme.order.application", 1);
        pkg(model, "com.acme.order.domain", 0);
        pkg(model, "com.acme.persistence", 3);
        pkg(model, "com.acme.web", 3);

        cls(model, "com.acme.order.domain.Order", false, 0, Set.of());
        cls(model, "com.acme.order.application.CreateOrderPort", true, 1, Set.of());
        cls(model, "com.acme.order.application.OrderRepositoryPort", true, 1, Set.of());
        cls(model, "com.acme.order.application.OrderQueryApi", true, 1, Set.of());
        cls(model, "com.acme.order.application.OrderService", false, 1,
                Set.of(
                        "com.acme.order.domain.Order",
                        "com.acme.order.application.OrderRepositoryPort",
                        "com.acme.persistence.JpaOrderRepository"));
        cls(model, "com.acme.persistence.JpaOrderRepository", false, 3,
                Set.of("com.acme.order.application.OrderRepositoryPort"));
        cls(model, "com.acme.web.OrderController", false, 3,
                Set.of(
                        "com.acme.order.application.CreateOrderPort",
                        "com.acme.order.application.OrderService",
                        "com.acme.order.application.OrderQueryApi"));

        return model;
    }

    private static void pkg(DomainModel model, String fqn, int level) {
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        model.addPackage(fqn, new CalculatedElementInfo(fqn, simpleName, "PACKAGE", level, Set.of()));
    }

    private static void cls(DomainModel model,
                            String fqn,
                            boolean interfaceType,
                            int level,
                            Set<String> dependencies) {
        String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
        model.addClass(fqn, new CalculatedElementInfo(
                fqn, simpleName, "CLASS", level, dependencies, interfaceType));
    }
}
