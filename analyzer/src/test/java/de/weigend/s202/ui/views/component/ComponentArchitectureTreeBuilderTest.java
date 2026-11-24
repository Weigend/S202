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

import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentArchitectureTreeBuilderTest {

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
