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
package de.weigend.s202.ui.core.model;

import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeExtensionModelTest {

    @Test
    void candidatesExcludeEverythingAlreadyInScope() {
        ArchitectureNode sourceRoot = sourceRoot();
        ArchitectureNode scopeRoot = ArchitectureNodeCloner.cloneShallow(sourceRoot);
        scopeRoot.addChild(ArchitectureNodeCloner.cloneTree(find(sourceRoot, "com.acme.impl")));

        List<String> candidateNames = ScopeExtensionModel.candidates(sourceRoot, scopeRoot).stream()
                .map(ScopeExtensionModel.Candidate::fullName)
                .toList();

        assertFalse(candidateNames.contains("com.acme.impl"));
        assertFalse(candidateNames.contains("com.acme.impl.ImplA"));
        assertFalse(candidateNames.contains("com.acme.impl.ImplB"));
        assertTrue(candidateNames.contains("com.acme.api"));
        assertTrue(candidateNames.contains("com.acme.api.Api"));
    }

    @Test
    void addPackageSubtractsAlreadyScopedChildren() {
        ArchitectureNode sourceRoot = sourceRoot();
        ArchitectureNode scopeRoot = ArchitectureNodeCloner.cloneShallow(sourceRoot);
        scopeRoot.addChild(ArchitectureNodeCloner.cloneTree(find(sourceRoot, "com.acme.impl.ImplB")));

        boolean added = ScopeExtensionModel.addToScope(scopeRoot, sourceRoot, "com.acme.impl");

        assertTrue(added);
        ArchitectureNode implPackage = scopeRoot.getChildren().stream()
                .filter(child -> "com.acme.impl".equals(child.getFullName()))
                .findFirst()
                .orElseThrow();
        List<String> implChildren = implPackage.getChildren().stream()
                .map(ArchitectureNode::getFullName)
                .toList();
        assertTrue(implChildren.contains("com.acme.impl.ImplA"));
        assertFalse(implChildren.contains("com.acme.impl.ImplB"));
    }

    private static ArchitectureNode sourceRoot() {
        ArchitectureNode root = pkg("root", "root");
        ArchitectureNode com = pkg("com", "com");
        ArchitectureNode acme = pkg("com.acme", "acme");
        ArchitectureNode api = pkg("com.acme.api", "api");
        ArchitectureNode impl = pkg("com.acme.impl", "impl");

        api.addChild(cls("com.acme.api.Api", "Api"));
        impl.addChild(cls("com.acme.impl.ImplA", "ImplA"));
        impl.addChild(cls("com.acme.impl.ImplB", "ImplB"));
        acme.addChild(api);
        acme.addChild(impl);
        com.addChild(acme);
        root.addChild(com);
        return root;
    }

    private static ArchitectureNode find(ArchitectureNode node, String fullName) {
        ArchitectureNode found = findOrNull(node, fullName);
        if (found == null) {
            throw new IllegalArgumentException("Missing test node: " + fullName);
        }
        return found;
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

    private static ArchitectureNode cls(String fullName, String simpleName) {
        return new ArchitectureNode(fullName, simpleName, NodeType.CLASS, true, 0);
    }
}
