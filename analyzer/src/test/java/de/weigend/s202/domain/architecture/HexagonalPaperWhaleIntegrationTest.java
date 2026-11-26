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
import de.weigend.s202.domain.impl.HexagonalArchitectureBuilder;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end check of the theme-based hexagonal projection against the
 * test-hexagon showcase (The Paper Whale): bytecode -> levels -> hexagonal
 * architecture. Requires the test-hexagon jar (mvn -pl test-hexagon package).
 */
class HexagonalPaperWhaleIntegrationTest {

    private static final String JAR = "../test-hexagon/target/test-hexagon-1.7.0.jar";

    /** The levels the README of test-hexagon gives for its packages. */
    @Test
    void packageLevelsAreTheOnesOfTheHexagon() throws Exception {
        assumeTrue(Files.exists(Path.of(JAR)), "test-hexagon jar not built");

        DomainModel domain = new LevelCalculator().calculate(new InputAnalyzer().analyze(JAR));

        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("domain.publisher", 0), Map.entry("domain.book", 1),
                Map.entry("domain.inventory", 2), Map.entry("domain.logistics", 2),
                Map.entry("application.api", 3), Map.entry("application.spi", 3),
                Map.entry("application.service", 4), Map.entry("platform", 4),
                Map.entry("persistence", 4), Map.entry("rest", 5), Map.entry("ui", 5),
                Map.entry("shipping", 5), Map.entry("bootstrap", 6));
        expected.forEach((name, level) -> assertEquals(level,
                domain.getPackage("com.paperwhale." + name).architectureLevel, name));
    }

    @Test
    void paperWhaleProjectsFourBusinessThemes() throws Exception {
        assumeTrue(Files.exists(Path.of(JAR)), "test-hexagon jar not built");

        DependencyModel rawModel = new InputAnalyzer().analyze(JAR);
        DomainModel domain = new LevelCalculator().calculate(rawModel);
        HexagonalArchitecture architecture = new HexagonalArchitectureBuilder()
                .build(domain, ArchitectureAnnotations.empty(), rawModel);

        assertEquals(List.of(
                        "com.paperwhale.domain.book",
                        "com.paperwhale.domain.inventory",
                        "com.paperwhale.domain.logistics",
                        "com.paperwhale.domain.publisher"),
                architecture.segments().stream()
                        .map(HexagonalArchitecture.HexSegment::rootFqn)
                        .sorted()
                        .toList());

        Map<String, HexagonalArchitecture.HexElement> classes = architecture.elements().stream()
                .filter(HexagonalArchitecture.HexElement::classElement)
                .collect(Collectors.toMap(HexagonalArchitecture.HexElement::fqn, e -> e));

        // Dependency voting pulls each adapter class into its business theme.
        assertEquals("com.paperwhale.domain.book",
                classes.get("com.paperwhale.persistence.JdbcBookRepository").segmentId());
        assertEquals("com.paperwhale.domain.publisher",
                classes.get("com.paperwhale.persistence.JdbcPublisherDirectory").segmentId());
        assertEquals("com.paperwhale.domain.logistics",
                classes.get("com.paperwhale.shipping.SeagullExpressCarrier").segmentId());
        assertEquals("com.paperwhale.domain.logistics",
                classes.get("com.paperwhale.rest.TrackingResource").segmentId());

        // Rings stay package-based. The contract interfaces (api/spi) name
        // domain types in their signatures, so they sit above the domain and
        // classify as APPLICATION; the view renders them as sockets on the
        // application boundary regardless of ring.
        assertEquals(HexagonalArchitecture.RingRole.CORE,
                classes.get("com.paperwhale.domain.book.Book").ringRole());
        assertEquals(HexagonalArchitecture.RingRole.APPLICATION,
                classes.get("com.paperwhale.application.api.SellBookUseCase").ringRole());
        assertEquals(HexagonalArchitecture.RingRole.APPLICATION,
                classes.get("com.paperwhale.application.service.SalesService").ringRole());
        assertEquals(HexagonalArchitecture.RingRole.ADAPTER,
                classes.get("com.paperwhale.bootstrap.PaperWhaleApp").ringRole());

        // The contract signal separates the adapters from the service layer
        // even though both sit one level above the ports: implementing an SPI
        // (persistence, shipping, the ui notifier) or merely using the API
        // (rest, ui, platform glue) puts a package into the outer ring.
        // Adapters may use the domain model freely: a repository must know the
        // types that appear in its port signatures. persistence -> domain.book
        // is NOT a port bypass.
        assertTrue(architecture.violations().stream()
                        .filter(violation -> violation.kind() == ViolationKind.HEXAGON_PORT_BYPASS)
                        .noneMatch(violation -> violation.sourceFqn().startsWith("com.paperwhale.persistence.")
                                && violation.targetFqn().startsWith("com.paperwhale.domain.")),
                "adapter use of domain model types must not be flagged as port bypass");

        // The packages api and spi are the ports without being marked; what is
        // left is the composition root, which knows the services it wires.
        assertTrue(architecture.violations().stream()
                        .allMatch(violation -> violation.sourceFqn().startsWith("com.paperwhale.bootstrap.")
                                && violation.targetFqn().startsWith("com.paperwhale.application.service.")),
                () -> "unexpected violations: " + architecture.violations());
        assertEquals(4, architecture.violations().size());

        assertEquals(HexagonalArchitecture.RingRole.ADAPTER,
                classes.get("com.paperwhale.persistence.JdbcBookRepository").ringRole());
        assertEquals(HexagonalArchitecture.RingRole.ADAPTER,
                classes.get("com.paperwhale.rest.CatalogResource").ringRole());
        assertEquals(HexagonalArchitecture.RingRole.ADAPTER,
                classes.get("com.paperwhale.ui.StoreFrontConsole").ringRole());
        assertEquals(HexagonalArchitecture.RingRole.ADAPTER,
                classes.get("com.paperwhale.shipping.SeagullExpressCarrier").ringRole());
        assertEquals(HexagonalArchitecture.RingRole.ADAPTER,
                classes.get("com.paperwhale.platform.JsonCodec").ringRole());
    }
}
