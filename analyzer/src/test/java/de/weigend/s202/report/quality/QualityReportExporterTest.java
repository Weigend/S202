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
package de.weigend.s202.report.quality;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityReportExporterTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void exportsHtmlReportWithSvgAssetsAndReportableComponentFindings() throws Exception {
        DomainModel domain = domainModel();
        DependencyModel raw = rawModel();
        QualityReportInput input = new QualityReportInput(
                "Quality Report Test",
                "test",
                new S202Project.Source("JAR", List.of("/tmp/sample.jar"), null),
                raw,
                domain,
                ArchitectureAnnotations.empty(),
                null,
                null);

        Path html = new QualityReportExporter().export(input, tempDir);

        assertTrue(Files.exists(html));
        assertTrue(Files.exists(tempDir.resolve("assets/quality.svg")));
        assertTrue(Files.exists(tempDir.resolve("assets/component-overview.svg")));
        assertTrue(Files.exists(tempDir.resolve("assets/scopes/component-violation-001.svg")));

        String content = Files.readString(html);
        assertTrue(content.contains("Overall Quality Score"));
        assertTrue(content.contains("of 100"));
        assertTrue(content.contains("Assessment Drivers"));
        assertTrue(content.contains("Recommended Focus"));
        assertTrue(content.contains("Component Findings"));
        assertTrue(content.contains("API Bypass"));
        assertFalse(content.toLowerCase().contains("hexagonal"));
    }

    private static DomainModel domainModel() {
        DomainModel domain = new DomainModel();
        pkg(domain, "com", 0);
        pkg(domain, "com.acme", 0);
        pkg(domain, "com.acme.payment", 1);
        pkg(domain, "com.acme.payment.impl", 0);
        pkg(domain, "com.acme.shipping", 1);
        pkg(domain, "com.acme.shipping.impl", 0);
        pkg(domain, "com.acme.web", 2);

        cls(domain, "com.acme.payment.PaymentApi", true, 1,
                Set.of("com.acme.payment.impl.PaymentService"));
        cls(domain, "com.acme.payment.impl.PaymentService", false, 0, Set.of());
        cls(domain, "com.acme.shipping.ShippingApi", true, 1,
                Set.of("com.acme.payment.impl.PaymentService"));
        cls(domain, "com.acme.shipping.impl.ShippingService", false, 0,
                Set.of("com.acme.payment.PaymentApi"));
        cls(domain, "com.acme.web.Controller", false, 2,
                Set.of("com.acme.payment.impl.PaymentService"));
        return domain;
    }

    private static DependencyModel rawModel() {
        DependencyModel raw = new DependencyModel();
        raw.addClass("com.acme.payment.PaymentApi",
                rawClass("com.acme.payment.PaymentApi", true,
                        "com.acme.payment.impl.PaymentService"));
        raw.addClass("com.acme.payment.impl.PaymentService",
                rawClass("com.acme.payment.impl.PaymentService", false));
        raw.addClass("com.acme.shipping.ShippingApi",
                rawClass("com.acme.shipping.ShippingApi", true,
                        "com.acme.payment.impl.PaymentService"));
        raw.addClass("com.acme.shipping.impl.ShippingService",
                rawClass("com.acme.shipping.impl.ShippingService", false,
                        "com.acme.payment.PaymentApi"));
        raw.addClass("com.acme.web.Controller",
                rawClass("com.acme.web.Controller", false,
                        "com.acme.payment.impl.PaymentService"));
        return raw;
    }

    private static DependencyModel.ClassInfo rawClass(String fqn, boolean interfaceType, String... deps) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(fqn, simple(fqn), parentOf(fqn), interfaceType);
        for (String dep : deps) {
            info.addDependency(dep, EdgeKind.USES);
        }
        return info;
    }

    private static void pkg(DomainModel domain, String fqn, int level) {
        domain.addPackage(fqn, new CalculatedElementInfo(fqn, simple(fqn), "PACKAGE", level, Set.of()));
    }

    private static void cls(DomainModel domain, String fqn, boolean interfaceType, int level, Set<String> deps) {
        domain.addClass(fqn, new CalculatedElementInfo(fqn, simple(fqn), "CLASS", level, deps, interfaceType));
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simple(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
