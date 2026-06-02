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
package de.weigend.s202.ui.tree;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentArchitectureTreeBuilderTest {

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

    private static ArchitectureNode sourceRoot() {
        ArchitectureNode root = pkg("root", "root");
        ArchitectureNode com = pkg("com", "com");
        ArchitectureNode acme = pkg("com.acme", "acme");
        ArchitectureNode payment = pkg("com.acme.payment", "payment");
        ArchitectureNode api = pkg("com.acme.payment.api", "api");
        ArchitectureNode internal = pkg("com.acme.payment.internal", "internal");
        ArchitectureNode shipping = pkg("com.acme.shipping", "shipping");

        payment.addChild(cls("com.acme.payment.PaymentApi", "PaymentApi", false));
        api.addChild(cls("com.acme.payment.api.PaymentDto", "PaymentDto", false));
        api.addChild(cls("com.acme.payment.api.PaymentPort", "PaymentPort", true));
        internal.addChild(cls("com.acme.payment.internal.PaymentService", "PaymentService", false));
        shipping.addChild(cls("com.acme.shipping.ShippingService", "ShippingService", false));

        payment.addChild(api);
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

    private static ArchitectureNode pkg(String fullName, String simpleName) {
        return new ArchitectureNode(fullName, simpleName, NodeType.PACKAGE, true, 0);
    }

    private static ArchitectureNode cls(String fullName, String simpleName, boolean interfaceType) {
        return new ArchitectureNode(fullName, simpleName, NodeType.CLASS, true, 0, interfaceType);
    }
}
