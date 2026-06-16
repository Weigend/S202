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
import de.weigend.s202.domain.impl.HexagonalArchitectureBuilder;
import de.weigend.s202.reader.DependencyModel;
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

        // Only order.domain classifies as core, so theme detection finds no
        // sibling group of two — the builder falls back to top-level segments.
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
    void packagesAreProjectedAsElementsWithRingFromPackageLevel() {
        DomainModel domain = new DomainModel();
        pkg(domain, "com", 0);
        pkg(domain, "com.acme", 0);
        pkg(domain, "com.acme.domain", 0);
        pkg(domain, "com.acme.app", 3);
        pkg(domain, "com.acme.rest", 6);

        // Class levels deliberately contradict their package level: the high-level
        // Entity must stay CORE and the level-0 AppService must stay APPLICATION,
        // because classes inherit the ring of their package.
        cls(domain, "com.acme.domain.Entity", false, 5, Set.of());
        cls(domain, "com.acme.app.AppService", false, 0, Set.of("com.acme.domain.Entity"));
        cls(domain, "com.acme.rest.RestController", false, 6, Set.of("com.acme.app.AppService"));

        HexagonalArchitecture architecture = new HexagonalArchitectureBuilder()
                .build(domain, ArchitectureAnnotations.empty());

        assertPackageRing(architecture, "com.acme.domain", HexagonalArchitecture.RingRole.CORE);
        assertPackageRing(architecture, "com.acme.app", HexagonalArchitecture.RingRole.APPLICATION);
        assertPackageRing(architecture, "com.acme.rest", HexagonalArchitecture.RingRole.ADAPTER);

        assertElementRing(architecture, "com.acme.domain.Entity", HexagonalArchitecture.RingRole.CORE);
        assertElementRing(architecture, "com.acme.app.AppService", HexagonalArchitecture.RingRole.APPLICATION);
        assertElementRing(architecture, "com.acme.rest.RestController", HexagonalArchitecture.RingRole.ADAPTER);
    }

    @Test
    void explicitAnnotationsOverrideInheritedPackageRing() {
        DomainModel domain = new DomainModel();
        pkg(domain, "com", 0);
        pkg(domain, "com.acme", 0);
        pkg(domain, "com.acme.domain", 0);
        pkg(domain, "com.acme.rest", 2);

        cls(domain, "com.acme.domain.Entity", false, 0, Set.of());
        cls(domain, "com.acme.domain.LoadPort", true, 0, Set.of());
        cls(domain, "com.acme.rest.RestController", false, 2, Set.of());
        cls(domain, "com.acme.rest.SharedRule", false, 2, Set.of());

        ArchitectureAnnotations annotations = ArchitectureAnnotations.empty()
                .withElementRole("com.acme.rest.SharedRule", ArchitectureAnnotations.ElementRole.CORE)
                .withPort("com.acme.domain.LoadPort",
                        ArchitectureAnnotations.PortDirection.OUTBOUND,
                        "com.acme.domain");

        HexagonalArchitecture architecture = new HexagonalArchitectureBuilder()
                .build(domain, annotations);

        assertPackageRing(architecture, "com.acme.rest", HexagonalArchitecture.RingRole.ADAPTER);
        // Explicit class role beats the inherited ADAPTER ring.
        assertElementRing(architecture, "com.acme.rest.SharedRule", HexagonalArchitecture.RingRole.CORE);
        assertElementRing(architecture, "com.acme.rest.RestController", HexagonalArchitecture.RingRole.ADAPTER);
        // Explicit ports sit on the application boundary regardless of package ring.
        assertElementRing(architecture, "com.acme.domain.LoadPort", HexagonalArchitecture.RingRole.APPLICATION);
    }

    @Test
    void segmentModelProjectsPackagesOfEverySegment() {
        DomainModel domain = domainModel();
        ArchitectureAnnotations annotations = ArchitectureAnnotations.empty()
                .withElementRole("com.acme.web", ArchitectureAnnotations.ElementRole.ADAPTER)
                .withElementRole("com.acme.order.domain", ArchitectureAnnotations.ElementRole.CORE);

        HexagonalArchitecture architecture = new HexagonalArchitectureBuilder()
                .build(domain, annotations);

        Map<String, HexagonalArchitecture.HexElement> packages = architecture.elements().stream()
                .filter(element -> !element.classElement())
                .collect(Collectors.toMap(HexagonalArchitecture.HexElement::fqn, element -> element));
        assertEquals(Set.of(
                        "com.acme.order.application",
                        "com.acme.order.domain",
                        "com.acme.persistence",
                        "com.acme.web"),
                packages.keySet());
        // Fallback segmentation (only one core package): packages map to their
        // top-level segment roots.
        assertEquals("com.acme.order", packages.get("com.acme.order.application").segmentId());
        assertEquals("com.acme.web", packages.get("com.acme.web").segmentId());
        assertEquals(HexagonalArchitecture.RingRole.CORE,
                packages.get("com.acme.order.domain").ringRole());
        assertEquals(HexagonalArchitecture.RingRole.ADAPTER,
                packages.get("com.acme.web").ringRole());

        // Every class element keeps pointing at a projected package.
        architecture.elements().stream()
                .filter(HexagonalArchitecture.HexElement::classElement)
                .forEach(element -> assertTrue(
                        packages.containsKey(element.fqn().substring(0, element.fqn().lastIndexOf('.'))),
                        () -> "No projected package for " + element.fqn()));
    }

    @Test
    void themeSegmentationAssignsClassesByDependencyVoting() {
        DomainModel domain = new DomainModel();
        pkg(domain, "com", 0);
        pkg(domain, "com.shop", 0);
        pkg(domain, "com.shop.domain", 0);
        pkg(domain, "com.shop.domain.book", 1);
        pkg(domain, "com.shop.domain.publisher", 0);
        pkg(domain, "com.shop.api", 2);
        pkg(domain, "com.shop.rest", 3);

        cls(domain, "com.shop.domain.publisher.Publisher", false, 0, Set.of());
        cls(domain, "com.shop.domain.book.Book", false, 1, Set.of("com.shop.domain.publisher.Publisher"));
        cls(domain, "com.shop.api.BookApi", true, 2, Set.of("com.shop.domain.book.Book"));
        cls(domain, "com.shop.api.PublisherApi", true, 2, Set.of("com.shop.domain.publisher.Publisher"));
        // No direct domain dependency: BookController reaches the book theme
        // only through BookApi, i.e. via the second voting round.
        cls(domain, "com.shop.rest.BookController", false, 3, Set.of("com.shop.api.BookApi"));

        HexagonalArchitecture architecture = new HexagonalArchitectureBuilder()
                .build(domain, ArchitectureAnnotations.empty());

        assertEquals(List.of("com.shop.domain.book", "com.shop.domain.publisher"),
                architecture.segments().stream()
                        .map(HexagonalArchitecture.HexSegment::rootFqn)
                        .toList());

        assertEquals("com.shop.domain.book", classElement(architecture, "com.shop.api.BookApi").segmentId());
        assertEquals("com.shop.domain.publisher", classElement(architecture, "com.shop.api.PublisherApi").segmentId());
        assertEquals("com.shop.domain.book", classElement(architecture, "com.shop.rest.BookController").segmentId());

        // The api package element lands in the majority theme of its classes
        // (tie between book and publisher resolves alphabetically to book).
        HexagonalArchitecture.HexElement apiPackage = architecture.elements().stream()
                .filter(e -> !e.classElement())
                .filter(e -> e.fqn().equals("com.shop.api"))
                .findFirst()
                .orElseThrow();
        assertEquals("com.shop.domain.book", apiPackage.segmentId());
    }

    private static HexagonalArchitecture.HexElement classElement(HexagonalArchitecture architecture, String fqn) {
        return architecture.elements().stream()
                .filter(HexagonalArchitecture.HexElement::classElement)
                .filter(e -> e.fqn().equals(fqn))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void hexagonalProjectionDoesNotMutateCalculatedLevels() {
        DomainModel domain = domainModel();
        Map<String, Integer> before = classLevels(domain);

        new HexagonalArchitectureBuilder().build(domain, ArchitectureAnnotations.empty());

        assertEquals(before, classLevels(domain));
    }

    @Test
    void implementationPackageInterfacesAreNotComponentApiPorts() {
        DomainModel domain = new DomainModel();
        pkg(domain, "com", 0);
        pkg(domain, "com.acme", 0);
        pkg(domain, "com.acme.payment", 1);
        pkg(domain, "com.acme.payment.api", 1);
        pkg(domain, "com.acme.payment.impl", 0);

        cls(domain, "com.acme.payment.api.PaymentFacade", false, 2, Set.of());
        cls(domain, "com.acme.payment.impl.MultiWindowManager", true, 1, Set.of());

        DependencyModel rawModel = new DependencyModel();
        DependencyModel.ModuleInfo module = new DependencyModel.ModuleInfo("com.acme.payment", null);
        module.addExportedPackage("com.acme.payment.api", Set.of());
        rawModel.addModule(module);

        HexagonalArchitecture architecture = new HexagonalArchitectureBuilder()
                .build(new ArchitectureContext(rawModel, domain, ArchitectureAnnotations.empty()));

        HexagonalArchitecture.HexElement implInterface = architecture.elements().stream()
                .filter(element -> element.fqn().equals("com.acme.payment.impl.MultiWindowManager"))
                .findFirst()
                .orElseThrow();
        assertFalse(implInterface.componentApi());
        assertFalse(implInterface.portCandidate());
    }

    private static void assertElementRing(HexagonalArchitecture architecture,
                                          String fqn,
                                          HexagonalArchitecture.RingRole expected) {
        HexagonalArchitecture.HexElement element = architecture.elements().stream()
                .filter(HexagonalArchitecture.HexElement::classElement)
                .filter(e -> e.fqn().equals(fqn))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, element.ringRole());
    }

    private static void assertPackageRing(HexagonalArchitecture architecture,
                                          String fqn,
                                          HexagonalArchitecture.RingRole expected) {
        HexagonalArchitecture.HexElement element = architecture.elements().stream()
                .filter(e -> !e.classElement())
                .filter(e -> e.fqn().equals(fqn))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No package element for " + fqn));
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
        // Level 2 of max 3 puts the application package into the APPLICATION
        // ring, so adapter classes reaching past the ports into it are
        // bypasses. With only one core package (order.domain) the builder
        // falls back to top-level segmentation.
        pkg(model, "com.acme.order.application", 2);
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
