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

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.HexagonalArchitecture;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import java.util.function.BiConsumer;

/**
 * Context menus of the hexagonal projection that edit the hexagonal role and
 * port annotations. Every action produces a new {@link ArchitectureAnnotations}
 * snapshot and pushes it through the change sink.
 */
final class HexagonalRoleMenus {

    private final ArchitectureAnnotations annotations;
    private final BiConsumer<ArchitectureAnnotations, String> annotationChangeSink;

    HexagonalRoleMenus(ArchitectureAnnotations annotations,
                       BiConsumer<ArchitectureAnnotations, String> annotationChangeSink) {
        this.annotations = annotations;
        this.annotationChangeSink = annotationChangeSink;
    }

    void installClassContextMenu(Node target,
                                 HexagonalArchitecture.HexElement element,
                                 String segmentId) {
        target.setOnContextMenuRequested(event -> {
            MenuItem inbound = new MenuItem("Mark as Inbound Port");
            inbound.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withPort(element.fqn(), ArchitectureAnnotations.PortDirection.INBOUND, segmentId)
                            .withElementRole(element.fqn(), ArchitectureAnnotations.ElementRole.INBOUND_PORT),
                    "Marked inbound port: " + element.fqn()));

            MenuItem outbound = new MenuItem("Mark as Outbound Port");
            outbound.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withPort(element.fqn(), ArchitectureAnnotations.PortDirection.OUTBOUND, segmentId)
                            .withElementRole(element.fqn(), ArchitectureAnnotations.ElementRole.OUTBOUND_PORT),
                    "Marked outbound port: " + element.fqn()));

            MenuItem generic = new MenuItem("Mark as Generic Port");
            generic.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withPort(element.fqn(), ArchitectureAnnotations.PortDirection.GENERIC, segmentId)
                            .withoutElementRole(element.fqn()),
                    "Marked generic port: " + element.fqn()));

            MenuItem removePort = new MenuItem("Remove Port");
            removePort.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withoutPort(element.fqn()).withoutElementRole(element.fqn()),
                    "Removed port: " + element.fqn()));

            MenuItem core = new MenuItem("Mark as Core");
            core.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withElementRole(element.fqn(), ArchitectureAnnotations.ElementRole.CORE),
                    "Marked core: " + element.fqn()));

            MenuItem adapter = new MenuItem("Mark as Adapter");
            adapter.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withElementRole(element.fqn(), ArchitectureAnnotations.ElementRole.ADAPTER),
                    "Marked adapter: " + element.fqn()));

            MenuItem clearRole = new MenuItem("Clear Hexagonal Role");
            clearRole.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withoutElementRole(element.fqn()),
                    "Cleared hexagonal role: " + element.fqn()));

            ContextMenu menu = new ContextMenu(inbound, outbound, generic, removePort, core, adapter, clearRole);
            menu.show(target, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    void installPackageRoleContextMenu(Node target, String fqn, String noun) {
        target.setOnContextMenuRequested(event -> {
            MenuItem core = new MenuItem("Mark " + capitalize(noun) + " as Core");
            core.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withElementRole(fqn, ArchitectureAnnotations.ElementRole.CORE),
                    "Marked " + noun + " core: " + fqn));

            MenuItem adapter = new MenuItem("Mark " + capitalize(noun) + " as Adapter");
            adapter.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withElementRole(fqn, ArchitectureAnnotations.ElementRole.ADAPTER),
                    "Marked " + noun + " adapter: " + fqn));

            MenuItem clear = new MenuItem("Clear " + capitalize(noun) + " Role");
            clear.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withoutElementRole(fqn),
                    "Cleared " + noun + " role: " + fqn));

            ContextMenu menu = new ContextMenu(core, adapter, clear);
            menu.show(target, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
