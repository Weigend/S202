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

import de.weigend.s202.domain.Origins.Origin;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OriginsTest {

    @Test
    void theFilesUseEachOtherAndShareSplitPackages() {
        DependencyModel model = new DependencyModel();
        add(model, "app.ui.A", "ui.jar").addDependency("app.core.B", EdgeKind.CALLS);
        add(model, "app.core.B", "core.jar").addDependency("app.core.C", EdgeKind.CALLS);
        add(model, "app.core.C", "core.jar");
        add(model, "app.core.D", "extra.jar").addDependency("org.lib.Tool", EdgeKind.CALLS);
        add(model, "app.old.E", null);

        List<Origin> origins = Origins.of(model);

        assertEquals(List.of(Origins.UNKNOWN, "core.jar", "extra.jar", "ui.jar"),
                origins.stream().map(Origin::origin).toList());
        Origin core = origins.get(1);
        assertEquals(2, core.classes());
        assertEquals(1, core.packages());
        assertEquals(Set.of(), core.uses(), "inside the file it does not count");
        assertEquals(Set.of("ui.jar"), core.usedBy());
        assertEquals(Set.of("app.core"), core.split(), "D sits in extra.jar");
        assertEquals(Set.of("core.jar"), origins.get(3).uses());
        assertEquals(Set.of(), origins.get(2).uses(), "a library is no file of the analysis");
    }

    private static DependencyModel.ClassInfo add(DependencyModel model, String name, String origin) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        info.setOrigin(origin);
        model.addClass(name, info);
        return info;
    }
}
