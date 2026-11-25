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
package de.weigend.s202.domain.impl;

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations.PortDirection;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static de.weigend.s202.domain.architecture.ArchitectureAnnotations.PortDirection.GENERIC;
import static de.weigend.s202.domain.architecture.ArchitectureAnnotations.PortDirection.INBOUND;
import static de.weigend.s202.domain.architecture.ArchitectureAnnotations.PortDirection.OUTBOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortDirectionsTest {

    private final DependencyModel raw = new DependencyModel();

    @Test
    void apiAndSpiNameTheSide() {
        assertEquals(Map.of("com.acme.app.api.Sell", INBOUND, "com.acme.app.spi.Store", OUTBOUND),
                directions("com.acme.app.api.Sell", "com.acme.app.spi.Store", "com.acme.app.service.SellService"));
    }

    @Test
    void theSubPackageOfAPortPackageNamesTheSide() {
        assertEquals(Map.of(
                        "com.acme.app.port.in.Sell", INBOUND,
                        "com.acme.app.port.in.dto.Item", INBOUND,
                        "com.acme.app.port.out.Store", OUTBOUND,
                        "com.acme.domain.ports.inbound.Buy", INBOUND,
                        "com.acme.domain.ports.spi.Clock", OUTBOUND),
                directions("com.acme.app.port.in.Sell", "com.acme.app.port.in.dto.Item",
                        "com.acme.app.port.out.Store", "com.acme.domain.ports.inbound.Buy",
                        "com.acme.domain.ports.spi.Clock"));
    }

    @Test
    void otherwiseThePlaceOfTheImplementationDecides() {
        implement("com.acme.app.service.SellService", "com.acme.app.port.Sell");
        implement("com.acme.persistence.JdbcStore", "com.acme.app.port.Store");
        implement("com.acme.app.service.InMemoryStore", "com.acme.app.port.Store");

        assertEquals(Map.of(
                        "com.acme.app.port.Sell", INBOUND,
                        "com.acme.app.port.Store", OUTBOUND,
                        "com.acme.app.port.Item", INBOUND),
                directions("com.acme.app.port.Sell", "com.acme.app.port.Store", "com.acme.app.port.Item"));
    }

    @Test
    void aPortOfTheWholeSystemHasNoDirection() {
        implement("com.acme.service.SellService", "com.acme.ports.Sell");
        implement("com.acme.persistence.JdbcStore", "com.acme.ports.Store");

        assertEquals(Map.of("com.acme.ports.Sell", GENERIC, "com.acme.ports.Store", GENERIC),
                directions("com.acme.ports.Sell", "com.acme.ports.Store"));
    }

    @Test
    void aMarkOfTheUserGoesFirst() {
        ArchitectureAnnotations annotations = ArchitectureAnnotations.empty()
                .withPort("com.acme.app.port.in.Sell", OUTBOUND, null);

        assertEquals(Map.of("com.acme.app.port.in.Sell", OUTBOUND),
                PortDirections.of(List.of("com.acme.app.port.in.Sell"), "com.acme", annotations, raw));
    }

    @Test
    void aContractPackageIsOneAsAWhole() {
        assertTrue(PortDirections.isContract("com.acme.app.api"));
        assertTrue(PortDirections.isContract("com.acme.app.port.out.dto"));
        assertFalse(PortDirections.isContract("com.acme.portal"));
        assertFalse(PortDirections.isContract("com.acme.app.service"));
    }

    private Map<String, PortDirection> directions(String... classes) {
        return PortDirections.of(List.of(classes), "com.acme", ArchitectureAnnotations.empty(), raw);
    }

    private void implement(String implementer, String port) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(implementer, "", "");
        info.addDependency(port, EdgeKind.IMPLEMENTS);
        raw.addClass(implementer, info);
    }
}
