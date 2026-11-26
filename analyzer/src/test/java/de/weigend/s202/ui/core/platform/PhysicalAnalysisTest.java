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
package de.weigend.s202.ui.core.platform;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.PhysicalHierarchy;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalAnalysisTest {

    private static final String ORDER = "order" + PhysicalHierarchy.DOT + "jar";
    private static final String BASE = "base" + PhysicalHierarchy.DOT + "jar";

    @BeforeAll
    static void lookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdown() {
        Lookup.shutdown();
    }

    @Test
    void theDocumentKeepsItsCutsUnderTheNamesOfTheAnalysis() {
        AnalysisDocument document = document(shop());

        assertTrue(document.addPreviewCut(new DependencyEdge(BASE + ".com.shop.base.Audit", ORDER + ".com.shop.order.Order")));

        assertEquals(Set.of(new DependencyEdge("com.shop.base.Audit", "com.shop.order.Order")), document.previewCuts());
        assertFalse(document.addPreviewCut(new DependencyEdge("com.shop.base.Audit", "com.shop.order.Order")),
                "the same cut, made in the layered view");
    }

    @Test
    void thePhysicalAnalysisIsMadeOnceWithTheFilesOnTop() {
        AnalysisDocument document = document(shop());

        DerivedAnalysis physical = document.physical();

        assertSame(physical, document.physical());
        assertEquals(Set.of(ORDER, BASE),
                Set.copyOf(physical.rootNode().getChildren().stream().map(ArchitectureNode::getFullName).toList()));
        assertEquals(Set.of(new DependencyEdge(BASE + ".com.shop.base.Audit", ORDER + ".com.shop.order.Order")),
                physical.toOwn(Set.of(new DependencyEdge("com.shop.base.Audit", "com.shop.order.Order"))));
        assertTrue(document.hasPhysicalView());
    }

    @Test
    void oneFileHasNoPhysicalView() {
        DependencyModel raw = new DependencyModel();
        add(raw, "com.a.A", "one.jar");
        add(raw, "com.b.B", "one.jar", "com.a.A");

        assertFalse(document(raw).hasPhysicalView());
    }

    private static AnalysisDocument document(DependencyModel raw) {
        return new AnalysisDocument("shop", new S202Project.Source("JAR", List.of(), null), raw, new DomainModel(),
                new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true), null, null, Set.of());
    }

    private static DependencyModel shop() {
        DependencyModel raw = new DependencyModel();
        add(raw, "com.shop.order.Order", "order.jar", "com.shop.base.Money");
        add(raw, "com.shop.base.Money", "order.jar");
        add(raw, "com.shop.base.Audit", "base.jar", "com.shop.order.Order");
        return raw;
    }

    private static void add(DependencyModel raw, String name, String origin, String... targets) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        info.setOrigin(origin);
        for (String target : targets) {
            info.addDependency(target, EdgeKind.CALLS);
        }
        raw.addClass(name, info);
    }
}
