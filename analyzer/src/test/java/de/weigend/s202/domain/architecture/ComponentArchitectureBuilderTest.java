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

class ComponentArchitectureBuilderTest {

    @Test
    void detectsComponentApiBypassAndApiImplementationLeaks() {
        DomainModel domain = domainModel();

        ComponentArchitecture architecture = new ComponentArchitectureBuilder()
                .build(domain, ArchitectureAnnotations.empty());

        assertEquals(List.of("com.acme.payment", "com.acme.shipping"),
                architecture.components().stream()
                        .map(ComponentArchitecture.ComponentElement::rootPackageFqn)
                        .toList());

        assertViolation(architecture,
                "com.acme.web.Controller",
                "com.acme.payment.internal.PaymentService",
                ViolationKind.COMPONENT_API_BYPASS);
        assertViolation(architecture,
                "com.acme.shipping.ShippingService",
                "com.acme.payment.internal.PaymentService",
                ViolationKind.COMPONENT_API_BYPASS);
        assertViolation(architecture,
                "com.acme.payment.PaymentApi",
                "com.acme.payment.internal.PaymentService",
                ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION);
        assertViolation(architecture,
                "com.acme.shipping.ShippingApi",
                "com.acme.payment.internal.PaymentService",
                ViolationKind.COMPONENT_API_BYPASS);
        assertViolation(architecture,
                "com.acme.shipping.ShippingApi",
                "com.acme.payment.internal.PaymentService",
                ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION);

        assertFalse(hasViolation(architecture,
                "com.acme.payment.internal.PaymentService",
                "com.acme.payment.PaymentApi",
                ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION));
        assertFalse(hasViolation(architecture,
                "com.acme.shipping.ShippingService",
                "com.acme.payment.PaymentApi",
                ViolationKind.COMPONENT_API_BYPASS));
    }

    @Test
    void manualAnnotationsOverrideApiHeuristics() {
        DomainModel domain = domainModel();
        ArchitectureAnnotations annotations = new ArchitectureAnnotations(
                List.of(),
                Set.of("com.acme.payment.PaymentFacade"),
                Set.of("com.acme.payment.PaymentApi"),
                List.of(),
                List.of());

        ComponentArchitecture architecture = new ComponentArchitectureBuilder()
                .build(domain, annotations);

        ComponentArchitecture.ComponentElement payment = architecture.components().stream()
                .filter(component -> component.rootPackageFqn().equals("com.acme.payment"))
                .findFirst()
                .orElseThrow();
        Set<String> apiFqns = payment.api().stream()
                .map(Element::fqn)
                .collect(Collectors.toSet());

        assertTrue(apiFqns.contains("com.acme.payment.PaymentFacade"));
        assertFalse(apiFqns.contains("com.acme.payment.PaymentApi"));
    }

    @Test
    void jpmsExportsMarkExactPackageClassesAsApi() {
        DomainModel domain = jpmsDomainModel();
        DependencyModel rawModel = jpmsRawModel();

        ComponentArchitecture architecture = new ComponentArchitectureBuilder()
                .build(new ArchitectureContext(rawModel, domain, ArchitectureAnnotations.empty()));

        Set<String> apiFqns = apiFqns(architecture, "com.acme.payment");
        assertTrue(apiFqns.contains("com.acme.payment.contract.PaymentFacade"));
        assertTrue(apiFqns.contains("com.acme.payment.PaymentApi"));
        assertFalse(apiFqns.contains("com.acme.payment.contract.internal.InternalDto"));
        assertFalse(apiFqns.contains("com.acme.payment.reflect.ReflectionHook"));
    }

    @Test
    void manualApiExcludesOverrideJpmsExports() {
        DomainModel domain = jpmsDomainModel();
        DependencyModel rawModel = jpmsRawModel();
        ArchitectureAnnotations annotations = new ArchitectureAnnotations(
                List.of(),
                Set.of(),
                Set.of("com.acme.payment.contract.PaymentFacade"),
                List.of(),
                List.of());

        ComponentArchitecture architecture = new ComponentArchitectureBuilder()
                .build(new ArchitectureContext(rawModel, domain, annotations));

        Set<String> apiFqns = apiFqns(architecture, "com.acme.payment");
        assertFalse(apiFqns.contains("com.acme.payment.contract.PaymentFacade"));
        assertTrue(apiFqns.contains("com.acme.payment.PaymentApi"));
    }

    @Test
    void componentProjectionDoesNotMutateCalculatedLevels() {
        DomainModel domain = domainModel();
        Map<String, Integer> before = classLevels(domain);

        new ComponentArchitectureBuilder().build(domain, ArchitectureAnnotations.empty());

        assertEquals(before, classLevels(domain));
    }

    private static void assertViolation(ComponentArchitecture architecture,
                                        String source,
                                        String target,
                                        ViolationKind kind) {
        assertTrue(hasViolation(architecture, source, target, kind),
                () -> "Missing " + kind + " violation: " + source + " -> " + target);
    }

    private static boolean hasViolation(ComponentArchitecture architecture,
                                        String source,
                                        String target,
                                        ViolationKind kind) {
        return architecture.violations().stream()
                .anyMatch(v -> v.sourceFqn().equals(source)
                        && v.targetFqn().equals(target)
                        && v.kind() == kind);
    }

    private static Set<String> apiFqns(ComponentArchitecture architecture, String rootPackageFqn) {
        ComponentArchitecture.ComponentElement component = architecture.components().stream()
                .filter(c -> c.rootPackageFqn().equals(rootPackageFqn))
                .findFirst()
                .orElseThrow();
        return component.api().stream()
                .map(Element::fqn)
                .collect(Collectors.toSet());
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
        pkg(model, "com.acme.payment", 1);
        pkg(model, "com.acme.payment.internal", 0);
        pkg(model, "com.acme.shipping", 1);
        pkg(model, "com.acme.web", 2);

        cls(model, "com.acme.payment.PaymentApi", true, 2,
                Set.of("com.acme.payment.internal.PaymentService"));
        cls(model, "com.acme.payment.PaymentFacade", false, 2, Set.of());
        cls(model, "com.acme.payment.internal.PaymentService", false, 1,
                Set.of("com.acme.payment.PaymentApi"));
        cls(model, "com.acme.shipping.ShippingApi", true, 2,
                Set.of("com.acme.payment.internal.PaymentService"));
        cls(model, "com.acme.shipping.ShippingService", false, 1,
                Set.of("com.acme.payment.PaymentApi", "com.acme.payment.internal.PaymentService"));
        cls(model, "com.acme.web.Controller", false, 3,
                Set.of("com.acme.payment.internal.PaymentService"));

        return model;
    }

    private static DomainModel jpmsDomainModel() {
        DomainModel model = new DomainModel();

        pkg(model, "com", 0);
        pkg(model, "com.acme", 0);
        pkg(model, "com.acme.payment", 1);
        pkg(model, "com.acme.payment.contract", 1);
        pkg(model, "com.acme.payment.contract.internal", 0);
        pkg(model, "com.acme.payment.reflect", 0);
        pkg(model, "com.acme.web", 2);

        cls(model, "com.acme.payment.PaymentApi", true, 2, Set.of());
        cls(model, "com.acme.payment.contract.PaymentFacade", false, 2, Set.of());
        cls(model, "com.acme.payment.contract.internal.InternalDto", false, 1, Set.of());
        cls(model, "com.acme.payment.reflect.ReflectionHook", false, 1, Set.of());
        cls(model, "com.acme.web.Controller", false, 3, Set.of());

        return model;
    }

    private static DependencyModel jpmsRawModel() {
        DependencyModel model = new DependencyModel();
        DependencyModel.ModuleInfo module = new DependencyModel.ModuleInfo("com.acme.payment", null);
        module.addExportedPackage("com.acme.payment.contract", Set.of());
        module.addOpenedPackage("com.acme.payment.reflect", Set.of());
        model.addModule(module);
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
