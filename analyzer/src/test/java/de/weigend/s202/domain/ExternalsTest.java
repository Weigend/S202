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
package de.weigend.s202.domain;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalsTest {

    private final DependencyModel model = model();
    private final Externals externals = Externals.of(model);

    @Test
    void excludedAndUnreadTargetsAreExternalByPackage() {
        assertEquals(Map.of("java.util", Set.of("java.util.List", "java.util.Map"),
                "org.lib", Set.of("org.lib.Tool")), externals.packages());
        assertTrue(externals.isExternalClass("org.lib.Tool"));
        assertTrue(externals.isExternalPackage("java.util"));
        assertFalse(externals.isExternalClass("app.core.B"), "analyzed");
    }

    @Test
    void whatIsMissingInAPackageOfTheAnalysisIsNoLibrary() {
        assertFalse(externals.isExternalClass("app.core.Generated"));
        assertFalse(externals.isExternalPackage("app.core"));
    }

    @Test
    void theKindsAndTheUsersAreKnown() {
        assertEquals(Set.of(EdgeKind.USES, EdgeKind.CALLS), externals.kinds("app.ui.A", "java.util.List"));
        assertEquals(Set.of(EdgeKind.CALLS), externals.kinds("app.ui.A", "org.lib.Tool"));
        assertEquals(Set.of("java.util.List", "org.lib.Tool"), externals.usedBy("app.ui.A").keySet());
        assertEquals(Set.of("app.ui.A", "app.core.B"), externals.usersOf("java.util.List"));
        assertEquals(Set.of("app.ui.A", "app.core.B"), externals.usersOfPackage("java.util"));
    }

    @Test
    void theyAreMadeOncePerModel() {
        assertSame(externals, Externals.of(model));
        assertTrue(Externals.of(null).isEmpty());
    }

    private static DependencyModel model() {
        DependencyModel model = new DependencyModel();
        DependencyModel.ClassInfo a = add(model, "app.ui.A");
        a.addDependency("app.core.B", EdgeKind.CALLS);
        a.addDependency("org.lib.Tool", EdgeKind.CALLS);
        a.addDependency("app.core.Generated", EdgeKind.CALLS);
        a.addExternalDependency("java.util.List", EdgeKind.USES);
        a.addExternalDependency("java.util.List", EdgeKind.CALLS);
        DependencyModel.ClassInfo b = add(model, "app.core.B");
        b.addExternalDependency("java.util.List", EdgeKind.USES);
        b.addExternalDependency("java.util.Map", EdgeKind.USES);
        PackageHierarchyBuilder.buildPackageHierarchy(model);
        return model;
    }

    private static DependencyModel.ClassInfo add(DependencyModel model, String name) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        model.addClass(name, info);
        return info;
    }
}
