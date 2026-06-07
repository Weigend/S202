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
package de.weigend.s202.ui.wfx;

import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ArchitectureStyle;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupStrategy;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.windowmanager.api.ApplicationWindow;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import de.weigend.s202.reader.LanguageAnalyzer;
import de.weigend.s202.reader.ProjectScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WfxMigrationSmokeTest {

    @AfterEach
    void tearDown() {
        Lookup.init((LookupStrategy) null);
    }

    @Test
    void avajeLookupProvidesMigratedWfxWindowBeans() {
        Lookup.init();

        assertNotNull(Lookup.lookup(ApplicationWindow.class));
        assertNotNull(Lookup.lookup(WindowManager.class));

        List<Module> modules = Lookup.lookupAll(Module.class);
        assertTrue(modules.stream().anyMatch(S202Module.class::isInstance));
        assertTrue(modules.stream()
                .anyMatch(module -> module.getClass().getName()
                        .equals("io.softwareecg.wfx.extension.viewmenu.ViewMenuModule")));
    }

    @Test
    void avajeLookupProvidesReaderExtensionBeans() {
        Lookup.init();

        List<LanguageAnalyzer> analyzers = Lookup.lookupAll(LanguageAnalyzer.class);
        assertTrue(analyzers.stream().anyMatch(analyzer -> "Java bytecode".equals(analyzer.displayName())));
        assertTrue(analyzers.stream().anyMatch(analyzer -> "Python".equals(analyzer.displayName())));
        assertTrue(analyzers.stream().anyMatch(analyzer -> "C".equals(analyzer.displayName())));

        List<ProjectScanner> scanners = Lookup.lookupAll(ProjectScanner.class);
        assertTrue(scanners.stream().anyMatch(scanner -> "Maven".equals(scanner.displayName())));
        assertTrue(scanners.stream().anyMatch(scanner -> "Gradle".equals(scanner.displayName())));
    }

    @Test
    void avajeLookupProvidesDomainComponentBeans() {
        Lookup.init();

        assertNotNull(Lookup.lookup(DomainComputer.class));

        List<ArchitectureStyle> styles = Lookup.lookupAll(ArchitectureStyle.class);
        assertTrue(styles.stream().anyMatch(style -> style.kind() == ArchitectureKind.LAYERED));
        assertTrue(styles.stream().anyMatch(style -> style.kind() == ArchitectureKind.COMPONENT));
        assertTrue(styles.stream().anyMatch(style -> style.kind() == ArchitectureKind.HEXAGONAL));
    }
}
