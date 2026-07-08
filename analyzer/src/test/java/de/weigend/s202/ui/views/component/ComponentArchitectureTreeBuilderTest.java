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
package de.weigend.s202.ui.views.component;

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.core.model.ArchitectureNodeLocalLevelCalculator;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentArchitectureTreeBuilderTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void topLevelElementsMatchTheRegularPackageProjection() {
        ArchitectureNode root = sourceRoot();

        List<String> topLevel = ComponentArchitectureTreeBuilder.topLevelElements(root).stream()
                .map(ArchitectureNode::getFullName)
                .toList();

        assertEquals(List.of("com.acme.payment", "com.acme.shipping"), topLevel);
        assertTrue(ComponentArchitectureTreeBuilder.isComponentRoot(find(root, "com.acme.payment")));
        assertFalse(ComponentArchitectureTreeBuilder.isComponentRoot(find(root, "com.acme.shipping")));
    }

    @Test
    void apiClassesAreCollectedFromInterfaceAndApiNamedClasses() {
        ArchitectureNode component = find(sourceRoot(), "com.acme.payment");

        List<String> api = ComponentArchitectureTreeBuilder.apiClasses(component).stream()
                .map(ArchitectureNode::getFullName)
                .sorted()
                .toList();

        assertEquals(List.of(
                "com.acme.payment.PaymentApi",
                "com.acme.payment.api.PaymentDto",
                "com.acme.payment.api.PaymentPort"), api);
    }

    @Test
    void instanceApiSelectionUsesJpmsExports() {
        ArchitectureNode component = find(sourceRoot(), "com.acme.payment");
        DependencyModel rawModel = new DependencyModel();
        DependencyModel.ModuleInfo module = new DependencyModel.ModuleInfo("com.acme.payment", null);
        module.addExportedPackage("com.acme.payment.contract", Set.of());
        rawModel.addModule(module);

        ComponentArchitectureTreeBuilder builder = new ComponentArchitectureTreeBuilder(
                new HashMap<>(),
                null,
                ArchitectureAnnotations.empty(),
                rawModel,
                null);

        List<String> api = builder.selectedApiClasses(component).stream()
                .map(ArchitectureNode::getFullName)
                .sorted()
                .toList();

        assertEquals(List.of(
                "com.acme.payment.PaymentApi",
                "com.acme.payment.api.PaymentDto",
                "com.acme.payment.api.PaymentPort",
                "com.acme.payment.contract.PaymentFacade"), api);
    }

    @Test
    void implementationCloneExcludesApiClassesButKeepsNestedPackages() {
        ArchitectureNode component = find(sourceRoot(), "com.acme.payment");

        ArchitectureNode implementation = ComponentArchitectureTreeBuilder.cloneImplementation(
                component,
                Set.of(
                        "com.acme.payment.PaymentApi",
                        "com.acme.payment.api.PaymentDto",
                        "com.acme.payment.api.PaymentPort"),
                true);

        assertNotNull(implementation);
        assertFalse(contains(implementation, "com.acme.payment.PaymentApi"));
        assertFalse(contains(implementation, "com.acme.payment.api.PaymentDto"));
        assertFalse(contains(implementation, "com.acme.payment.api.PaymentPort"));
        assertTrue(contains(implementation, "com.acme.payment.internal.PaymentService"));
    }

    @Test
    void manualPackageAndClassSelectionsKeepTheirPackageHierarchyInApiProjection() {
        ArchitectureNode component = find(sourceRoot(), "com.acme.payment");
        ArchitectureAnnotations annotations = new ArchitectureAnnotations(
                List.of(),
                Set.of(
                        "com.acme.payment.contract.PaymentFacade",
                        "com.acme.payment.internal"),
                Set.of(
                        "com.acme.payment.PaymentApi",
                        "com.acme.payment.api"),
                List.of(),
                List.of());
        ComponentArchitectureTreeBuilder builder = new ComponentArchitectureTreeBuilder(
                new HashMap<>(),
                null,
                annotations,
                null);

        Set<String> selectedNames = new LinkedHashSet<>(builder.selectedApiClasses(component).stream()
                .map(ArchitectureNode::getFullName)
                .toList());
        ArchitectureNode apiProjection = ComponentArchitectureTreeBuilder.cloneApi(
                component,
                selectedNames,
                true);

        assertEquals(Set.of(
                "com.acme.payment.contract.PaymentFacade",
                "com.acme.payment.internal.PaymentService"), selectedNames);
        assertTrue(contains(apiProjection, "com.acme.payment.contract"));
        assertTrue(contains(apiProjection, "com.acme.payment.contract.PaymentFacade"));
        assertTrue(contains(apiProjection, "com.acme.payment.internal"));
        assertTrue(contains(apiProjection, "com.acme.payment.internal.PaymentService"));
        assertEquals(List.of("com.acme.payment.contract.PaymentFacade"),
                find(apiProjection, "com.acme.payment.contract").getChildren().stream()
                        .map(ArchitectureNode::getFullName)
                        .toList());
        assertEquals(List.of("com.acme.payment.internal.PaymentService"),
                find(apiProjection, "com.acme.payment.internal").getChildren().stream()
                        .map(ArchitectureNode::getFullName)
                        .toList());
        assertFalse(contains(apiProjection, "com.acme.payment.api"));
        assertFalse(contains(apiProjection, "com.acme.payment.PaymentApi"));
    }

    @Test
    void apiProjectionRecalculatesClassAndPackageLevelsWithoutChangingSourceTree() {
        ArchitectureNode component = pkg("com.acme.payment", "payment");
        ArchitectureNode api = pkg("com.acme.payment.api", "api");
        ArchitectureNode bridge = pkg("com.acme.payment.bridge", "bridge");
        ArchitectureNode low = cls("com.acme.payment.api.Low", "Low", false);
        ArchitectureNode high = cls("com.acme.payment.api.High", "High", false);
        ArchitectureNode client = cls("com.acme.payment.bridge.Client", "Client", false);

        api.setLevel(8);
        bridge.setLevel(8);
        low.setLevel(8);
        high.setLevel(8);
        client.setLevel(8);
        high.setDependencies(Set.of(low.getFullName()));
        client.setDependencies(Set.of(high.getFullName()));
        api.addChild(low);
        api.addChild(high);
        bridge.addChild(client);
        component.addChild(api);
        component.addChild(bridge);

        ArchitectureNode apiProjection = ComponentArchitectureTreeBuilder.cloneApi(
                component,
                Set.of(low.getFullName(), high.getFullName(), client.getFullName()),
                true);
        new ArchitectureNodeLocalLevelCalculator().assign(apiProjection, null);

        assertEquals(1, find(apiProjection, high.getFullName()).getLevel());
        assertEquals(0, find(apiProjection, low.getFullName()).getLevel());
        assertEquals(1, find(apiProjection, bridge.getFullName()).getLevel());
        assertEquals(0, find(apiProjection, api.getFullName()).getLevel());
        assertEquals(8, high.getLevel(), "The source tree must keep its analyzed local levels");
        assertEquals(8, bridge.getLevel(), "The source tree must keep its analyzed local levels");
    }

    @Test
    void domainProjectionAdapterUsesComponentArchitectureAsSourceOfApiAndImplementation() {
        ArchitectureNode root = sourceRoot();
        Map<String, ArchitectureNode> sourceIndex = new HashMap<>();
        index(root, sourceIndex);
        ComponentArchitecture.ComponentElement component =
                new ComponentArchitecture.ComponentElement(
                        "payment",
                        "Payment",
                        "com.acme.payment",
                        List.of(new Element.ClassElement(
                                "com.acme.payment.contract.PaymentFacade", 2, 0)),
                        List.of(List.of(
                                new Element.ClassElement(
                                        "com.acme.payment.PaymentApi", 2, 0),
                                new Element.PackageElement(
                                        "com.acme.payment.internal", 1, 0,
                                        List.of(List.of(new Element.ClassElement(
                                                "com.acme.payment.internal.PaymentService", 1, 0)))))));

        ArchitectureNode apiProjection =
                ComponentArchitectureTreeBuilder.apiProjectionRoot(component, sourceIndex);
        ArchitectureNode implementationProjection =
                ComponentArchitectureTreeBuilder.implementationProjectionRoot(component, sourceIndex);

        assertTrue(contains(apiProjection, "com.acme.payment.contract.PaymentFacade"));
        assertTrue(contains(apiProjection, "com.acme.payment.contract"));
        assertFalse(contains(apiProjection, "com.acme.payment.PaymentApi"),
                "UI heuristics must not put PaymentApi into the API projection");

        assertTrue(contains(implementationProjection, "com.acme.payment.PaymentApi"));
        assertTrue(contains(implementationProjection, "com.acme.payment.internal.PaymentService"));
        assertFalse(contains(implementationProjection, "com.acme.payment.contract.PaymentFacade"));
    }

    private static ArchitectureNode sourceRoot() {
        ArchitectureNode root = pkg("root", "root");
        ArchitectureNode com = pkg("com", "com");
        ArchitectureNode acme = pkg("com.acme", "acme");
        ArchitectureNode payment = pkg("com.acme.payment", "payment");
        ArchitectureNode api = pkg("com.acme.payment.api", "api");
        ArchitectureNode contract = pkg("com.acme.payment.contract", "contract");
        ArchitectureNode internal = pkg("com.acme.payment.internal", "internal");
        ArchitectureNode shipping = pkg("com.acme.shipping", "shipping");

        payment.addChild(cls("com.acme.payment.PaymentApi", "PaymentApi", false));
        api.addChild(cls("com.acme.payment.api.PaymentDto", "PaymentDto", false));
        api.addChild(cls("com.acme.payment.api.PaymentPort", "PaymentPort", true));
        contract.addChild(cls("com.acme.payment.contract.PaymentFacade", "PaymentFacade", false));
        internal.addChild(cls("com.acme.payment.internal.PaymentService", "PaymentService", false));
        shipping.addChild(cls("com.acme.shipping.ShippingService", "ShippingService", false));

        payment.addChild(api);
        payment.addChild(contract);
        payment.addChild(internal);
        acme.addChild(payment);
        acme.addChild(shipping);
        com.addChild(acme);
        root.addChild(com);
        return root;
    }

    private static boolean contains(ArchitectureNode node, String fullName) {
        if (fullName.equals(node.getFullName())) {
            return true;
        }
        for (ArchitectureNode child : node.getChildren()) {
            if (contains(child, fullName)) {
                return true;
            }
        }
        return false;
    }

    private static ArchitectureNode find(ArchitectureNode node, String fullName) {
        if (fullName.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findOrNull(child, fullName);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalArgumentException("Missing test node: " + fullName);
    }

    private static ArchitectureNode findOrNull(ArchitectureNode node, String fullName) {
        if (fullName.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findOrNull(child, fullName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void index(ArchitectureNode node, Map<String, ArchitectureNode> index) {
        index.put(node.getFullName(), node);
        for (ArchitectureNode child : node.getChildren()) {
            index(child, index);
        }
    }

    private static ArchitectureNode pkg(String fullName, String simpleName) {
        return new ArchitectureNode(fullName, simpleName, NodeType.PACKAGE, true, 0);
    }

    private static ArchitectureNode cls(String fullName, String simpleName, boolean interfaceType) {
        return new ArchitectureNode(fullName, simpleName, NodeType.CLASS, true, 0, interfaceType);
    }
}
