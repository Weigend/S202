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
package de.weigend.s202.ui.views.hexagonal;

import de.weigend.s202.ui.core.canvas.ArchitectureTreeBuilder;
import de.weigend.s202.ui.core.canvas.TreeBuilderSupport;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.HexagonalArchitecture;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Radial JavaFX projection for {@link HexagonalArchitecture}. Each segment is
 * a BUSINESS THEME and occupies one sector across all rings. Inside a sector
 * every ring shows collapsible groups: the core packages of the theme, the
 * application implementation, and the adapter packages contributing to the
 * theme. The API and SPI interfaces of a theme sit as two separately
 * expandable sockets on the outer boundary of the application ring. Expanding
 * a group shows its classes in the familiar layered representation (rows by
 * local level, as in the Architecture View) as a detail card on an overlay
 * layer above the radial geometry — the polar layout never reflows.
 */
public final class HexagonalArchitectureTreeBuilder {

    private static final String SEGMENT_REGISTRY_PREFIX = "s202.hex.segment#";

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> selectionChangeSink;
    private final ArchitectureAnnotations annotations;
    private final HexagonalArchitecture architecture;
    private final HexagonalRoleMenus roleMenus;
    private final Runnable layoutChangeCallback;
    private final Map<String, Boolean> packageExpansionState;

    public HexagonalArchitectureTreeBuilder(Map<String, Node> elementRegistry,
                                            Consumer<String> selectionChangeSink,
                                            ArchitectureAnnotations annotations,
                                            HexagonalArchitecture architecture,
                                            BiConsumer<ArchitectureAnnotations, String> annotationChangeSink,
                                            Runnable layoutChangeCallback,
                                            Map<String, Boolean> packageExpansionState) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.selectionChangeSink = selectionChangeSink;
        this.annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        this.architecture = architecture;
        this.roleMenus = new HexagonalRoleMenus(this.annotations,
                annotationChangeSink != null ? annotationChangeSink : (next, message) -> {});
        this.layoutChangeCallback = layoutChangeCallback != null ? layoutChangeCallback : () -> {};
        this.packageExpansionState = packageExpansionState != null ? packageExpansionState : new HashMap<>();
    }

    public VBox buildTree(ArchitectureNode rootNode, int maxDepth) {
        elementRegistry.clear();

        VBox topLevel = new VBox(8);
        topLevel.setPadding(new Insets(28, 42, 32, 42));
        topLevel.setStyle("-fx-background-color: #eef2f6;");

        HBox legend = HexBackdropRenderer.buildLegend();
        Pane radialPane = new Pane();
        radialPane.setMinSize(HexLayoutGeometry.WIDTH, HexLayoutGeometry.HEIGHT);
        radialPane.setPrefSize(HexLayoutGeometry.WIDTH, HexLayoutGeometry.HEIGHT);
        radialPane.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        radialPane.setStyle("-fx-background-color: #eef2f6;");

        topLevel.getChildren().addAll(legend, radialPane);

        if (architecture == null || architecture.segments().isEmpty()) {
            Label empty = new Label("No hexagonal projection available");
            empty.setStyle("-fx-font-size: 14px; -fx-text-fill: #425466;");
            empty.relocate(HexLayoutGeometry.CENTER_X - 120, HexLayoutGeometry.CENTER_Y - 16);
            radialPane.getChildren().add(empty);
            return topLevel;
        }

        // Detail cards live on a dedicated layer that is added LAST, so expanded
        // group contents always render above rings, boxes, and neighbour
        // sectors — the overlay behaviour, with zero coordinate mapping.
        Group detailLayer = new Group();
        detailLayer.setPickOnBounds(false);

        HexBackdropRenderer.drawRings(radialPane);
        HexBackdropRenderer.drawSegmentBoundaries(radialPane, architecture.segments().size());
        drawSegments(radialPane, detailLayer);
        radialPane.getChildren().add(detailLayer);
        return topLevel;
    }

    public void buildTreeAsync(ArchitectureNode rootNode,
                               int maxDepth,
                               ArchitectureTreeBuilder.ProgressSink progressSink,
                               Consumer<VBox> onComplete) {
        Objects.requireNonNull(onComplete, "onComplete cannot be null");
        Runnable build = () -> {
            VBox tree = buildTree(rootNode, maxDepth);
            if (progressSink != null) {
                int total = rootNode == null ? 1 : Math.max(1, rootNode.getTotalNodeCount());
                progressSink.accept(total, total, "hexagonal");
            }
            onComplete.accept(tree);
        };
        if (Platform.isFxApplicationThread()) {
            build.run();
        } else {
            Platform.runLater(build);
        }
    }

    private void drawSegments(Pane pane, Group detailLayer) {
        Map<String, List<HexagonalArchitecture.HexElement>> classesBySegment = new HashMap<>();
        Map<String, HexagonalArchitecture.HexElement> packagesByFqn = new HashMap<>();
        for (HexagonalArchitecture.HexElement element : architecture.elements()) {
            if (element.classElement()) {
                classesBySegment.computeIfAbsent(element.segmentId(), k -> new ArrayList<>()).add(element);
            } else {
                packagesByFqn.put(element.fqn(), element);
            }
        }

        int count = architecture.segments().size();
        for (int i = 0; i < count; i++) {
            HexagonalArchitecture.HexSegment segment = architecture.segments().get(i);
            double start = HexLayoutGeometry.angleForSegmentBoundary(i, count);
            double end = HexLayoutGeometry.angleForSegmentBoundary(i + 1, count);
            double mid = HexLayoutGeometry.normalizeAngle((start + end) / 2.0);
            List<HexagonalArchitecture.HexElement> segmentClasses =
                    classesBySegment.getOrDefault(segment.id(), List.of());

            Group segmentCardGroup = new Group();
            segmentCardGroup.setPickOnBounds(false);
            segmentCardGroup.getProperties().put("s202.rollupEndpointFqn",
                    SEGMENT_REGISTRY_PREFIX + segment.id());
            detailLayer.getChildren().add(segmentCardGroup);

            Group boxGroup = new Group();
            boxGroup.setPickOnBounds(false);
            boxGroup.getProperties().put("s202.rollupEndpointFqn",
                    SEGMENT_REGISTRY_PREFIX + segment.id());
            pane.getChildren().add(boxGroup);

            SegmentVisual visual = createSegmentHeader(pane, segment, mid);
            drawSectorGroups(boxGroup, segmentCardGroup, segment, segmentClasses,
                    packagesByFqn, start, end, mid, visual);
        }
    }

    private SegmentVisual createSegmentHeader(Pane pane,
                                              HexagonalArchitecture.HexSegment segment,
                                              double midAngle) {
        HexagonalSegmentHeader header = new HexagonalSegmentHeader(segment.label(), segment.rootFqn(), selectionChangeSink);
        roleMenus.installPackageRoleContextMenu(header, segment.rootFqn(), "segment");
        // The header is reachable for arrow rollup under a synthetic key so the
        // plain package FQN stays free for the theme's core group box.
        elementRegistry.put(SEGMENT_REGISTRY_PREFIX + segment.id(), header);

        List<Node> details = new ArrayList<>();
        List<Runnable> cardVisibilityUpdaters = new ArrayList<>();
        Runnable toggle = () -> {
            boolean expanded = header.toggleExpanded();
            for (Node detail : details) {
                detail.setVisible(expanded);
                detail.setManaged(expanded);
            }
            for (Runnable updater : cardVisibilityUpdaters) {
                updater.run();
            }
            layoutChangeCallback.run();
        };
        header.setToggleAction(toggle);

        HexLayoutGeometry.placeNode(header, HexLayoutGeometry.ADAPTER_RADIUS + 58, midAngle, 90, 14);
        pane.getChildren().add(header);
        return new SegmentVisual(header, details, cardVisibilityUpdaters);
    }

    private void drawSectorGroups(Group boxGroup,
                                  Group segmentCardGroup,
                                  HexagonalArchitecture.HexSegment segment,
                                  List<HexagonalArchitecture.HexElement> classes,
                                  Map<String, HexagonalArchitecture.HexElement> packagesByFqn,
                                  double startAngle,
                                  double endAngle,
                                  double midAngle,
                                  SegmentVisual visual) {
        Map<HexagonalArchitecture.RingRole, Map<String, List<HexagonalArchitecture.HexElement>>> ringGroups =
                new EnumMap<>(HexagonalArchitecture.RingRole.class);
        List<HexagonalArchitecture.HexElement> apiSocket = new ArrayList<>();
        List<HexagonalArchitecture.HexElement> spiSocket = new ArrayList<>();

        for (HexagonalArchitecture.HexElement element : classes) {
            PortSide side = portSide(element);
            if (side == PortSide.API) {
                apiSocket.add(element);
            } else if (side == PortSide.SPI) {
                spiSocket.add(element);
            } else {
                ringGroups.computeIfAbsent(element.ringRole(), k -> new TreeMap<>())
                        .computeIfAbsent(TreeBuilderSupport.parentOf(element.fqn()), k -> new ArrayList<>())
                        .add(element);
            }
        }

        for (HexagonalArchitecture.RingRole ring : HexagonalArchitecture.RingRole.values()) {
            Map<String, List<HexagonalArchitecture.HexElement>> groups =
                    ringGroups.getOrDefault(ring, Map.of());
            if (groups.isEmpty()) {
                continue;
            }
            List<Map.Entry<String, List<HexagonalArchitecture.HexElement>>> ordered =
                    groups.entrySet().stream()
                            .sorted(Comparator.comparingInt(
                                            (Map.Entry<String, List<HexagonalArchitecture.HexElement>> e)
                                                    -> packageLevel(packagesByFqn, e.getKey()))
                                    .thenComparing(Map.Entry::getKey))
                            .toList();
            int minLevel = packageLevel(packagesByFqn, ordered.get(0).getKey());
            int maxLevel = packageLevel(packagesByFqn, ordered.get(ordered.size() - 1).getKey());
            for (int i = 0; i < ordered.size(); i++) {
                Map.Entry<String, List<HexagonalArchitecture.HexElement>> group = ordered.get(i);
                int level = packageLevel(packagesByFqn, group.getKey());
                double levelT = maxLevel == minLevel
                        ? 0.5
                        : (level - minLevel) / (double) (maxLevel - minLevel);
                double radius = HexLayoutGeometry.bandRadius(ring, levelT);
                double angle = HexLayoutGeometry.spreadAngle(startAngle, endAngle, i, ordered.size());
                addGroup(boxGroup, segmentCardGroup, segment, visual,
                        new GroupSpec(
                                group.getKey() + "#" + segment.id(),
                                TreeBuilderSupport.simpleName(group.getKey()),
                                group.getKey(),
                                group.getValue(),
                                false),
                        radius, angle);
            }
        }

        // The theme's contracts sit ON the application boundary: API (inbound)
        // and SPI (outbound) as separately expandable sockets.
        double socketSpread = Math.abs(endAngle - startAngle) * 0.16;
        if (!apiSocket.isEmpty()) {
            double angle = spiSocket.isEmpty()
                    ? midAngle
                    : HexLayoutGeometry.normalizeAngle(midAngle - socketSpread);
            addGroup(boxGroup, segmentCardGroup, segment, visual,
                    new GroupSpec(segment.id() + "#api", "API", null, apiSocket, true),
                    HexLayoutGeometry.APPLICATION_RADIUS, angle);
        }
        if (!spiSocket.isEmpty()) {
            double angle = apiSocket.isEmpty()
                    ? midAngle
                    : HexLayoutGeometry.normalizeAngle(midAngle + socketSpread);
            addGroup(boxGroup, segmentCardGroup, segment, visual,
                    new GroupSpec(segment.id() + "#spi", "SPI", null, spiSocket, true),
                    HexLayoutGeometry.APPLICATION_RADIUS, angle);
        }
    }

    /**
     * Which socket a class belongs to. Explicit port directions win; otherwise
     * the conventional package names api/spi decide. No ring precondition:
     * contract interfaces sit at architecture level 0 by design (everything
     * rests on them), yet they belong on the application boundary sockets.
     * Everything else stays in its implementation group.
     */
    private PortSide portSide(HexagonalArchitecture.HexElement element) {
        ArchitectureAnnotations.PortSpec port = annotations.explicitPort(element.fqn());
        if (port != null) {
            return port.direction() == ArchitectureAnnotations.PortDirection.OUTBOUND
                    ? PortSide.SPI
                    : PortSide.API;
        }
        String packageName = TreeBuilderSupport.simpleName(TreeBuilderSupport.parentOf(element.fqn()));
        if ("spi".equalsIgnoreCase(packageName)) {
            return PortSide.SPI;
        }
        if ("api".equalsIgnoreCase(packageName)) {
            return PortSide.API;
        }
        return PortSide.NONE;
    }

    private int packageLevel(Map<String, HexagonalArchitecture.HexElement> packagesByFqn, String packageFqn) {
        HexagonalArchitecture.HexElement pkg = packagesByFqn.get(packageFqn);
        return pkg == null ? 0 : pkg.architectureLevel();
    }

    private void addGroup(Group boxGroup,
                          Group segmentCardGroup,
                          HexagonalArchitecture.HexSegment segment,
                          SegmentVisual visual,
                          GroupSpec spec,
                          double radius,
                          double angle) {
        boolean initiallyExpanded = packageExpansionState.getOrDefault(spec.key(), Boolean.FALSE);
        HexGroupBox box = new HexGroupBox(spec.label(), selectionFqnFor(spec), spec.classes().size(),
                initiallyExpanded, spec.socket(), selectionChangeSink);
        if (spec.packageFqn() != null) {
            roleMenus.installPackageRoleContextMenu(box, spec.packageFqn(), "package");
        }
        elementRegistry.put(spec.key(), box);
        if (spec.packageFqn() != null) {
            // Keep the plain package FQN reachable as an arrow endpoint; the
            // first group of a package wins, which is fine for rollup purposes.
            elementRegistry.putIfAbsent(spec.packageFqn(), box);
        }

        Runnable[] closeAction = new Runnable[1];
        Node card = buildOverlayCard(segment, spec,
                () -> {
                    if (closeAction[0] != null) {
                        closeAction[0].run();
                    }
                });
        double radians = Math.toRadians(angle);
        double cardRadius = radius + HexLayoutGeometry.CARD_RADIAL_OFFSET;
        Line leader = new Line(
                HexLayoutGeometry.CENTER_X + Math.cos(radians) * radius,
                HexLayoutGeometry.CENTER_Y + Math.sin(radians) * radius,
                HexLayoutGeometry.CENTER_X + Math.cos(radians) * cardRadius,
                HexLayoutGeometry.CENTER_Y + Math.sin(radians) * cardRadius);
        leader.setStroke(Color.web("#64748b"));
        leader.setStrokeWidth(1.2);
        leader.getStrokeDashArray().setAll(3.0, 4.0);
        leader.setMouseTransparent(true);

        HexLayoutGeometry.placeNode(box, radius, angle, 58, 12);
        HexLayoutGeometry.placeNode(card, cardRadius, angle, 110, 0);
        boxGroup.getChildren().add(box);
        segmentCardGroup.getChildren().addAll(leader, card);

        Runnable updateCardVisibility = () -> {
            boolean visible = visual.header().isExpanded()
                    && packageExpansionState.getOrDefault(spec.key(), Boolean.FALSE);
            card.setVisible(visible);
            card.setManaged(visible);
            leader.setVisible(visible);
        };
        visual.cardVisibilityUpdaters().add(updateCardVisibility);
        updateCardVisibility.run();

        box.setToggleAction(() -> {
            boolean expanded = !packageExpansionState.getOrDefault(spec.key(), Boolean.FALSE);
            packageExpansionState.put(spec.key(), expanded);
            box.setExpandedVisual(expanded);
            updateCardVisibility.run();
            layoutChangeCallback.run();
        });
        closeAction[0] = () -> {
            packageExpansionState.put(spec.key(), Boolean.FALSE);
            box.setExpandedVisual(false);
            updateCardVisibility.run();
            layoutChangeCallback.run();
        };
        visual.details().add(box);
    }

    private String selectionFqnFor(GroupSpec spec) {
        return spec.packageFqn() != null ? spec.packageFqn() : spec.classes().get(0).fqn();
    }

    /**
     * The expand overlay: a real {@link LevelPackageBox} — the exact package
     * representation of the Architecture View — filled with the group's
     * classes, one row per ARCHITECTURE level (the global semantic depth,
     * not the layout index inside the parent container), highest level on
     * top. A close button floats over the top-right corner so a card can
     * always be dismissed even when its group box is covered.
     */
    private Node buildOverlayCard(HexagonalArchitecture.HexSegment segment, GroupSpec spec, Runnable closeAction) {
        String title = spec.socket()
                ? spec.label() + " — " + segment.label()
                : spec.label();
        LevelPackageBox packageBox = new LevelPackageBox(title, -1, false, spec.packageFqn(), -1);
        packageBox.setSelectionChangeSink(selectionChangeSink);

        spec.classes().stream()
                .sorted(Comparator.comparing(HexagonalArchitecture.HexElement::fqn))
                .forEach(element -> {
                    LevelClassBox box = classBox(element);
                    if (element.explicitPort()) {
                        box.setStyle("-fx-background-color: #ffd28a; -fx-border-color: #b45309;"
                                + " -fx-border-width: 2;");
                    } else if (element.portCandidate()) {
                        box.setStyle("-fx-background-color: #fff7d6; -fx-border-color: #ca8a04;"
                                + " -fx-border-width: 1.5;");
                    }
                    roleMenus.installClassContextMenu(box, element, segment.id());
                    elementRegistry.put(element.fqn(), box);
                    packageBox.addToLevel(element.architectureLevel(), box);
                });

        Label close = new Label("✕");
        close.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #475569;"
                + " -fx-background-color: rgba(255,255,255,0.85); -fx-padding: 0 3;");
        close.setCursor(Cursor.HAND);
        close.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                closeAction.run();
                event.consume();
            }
        });

        StackPane card = new StackPane(packageBox, close);
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        StackPane.setMargin(close, new Insets(2, 3, 0, 0));
        card.setMaxWidth(380);
        card.setEffect(new javafx.scene.effect.DropShadow(9, 0, 2, Color.web("#0f172a59")));
        // Hidden card contents roll arrows up to the group box.
        card.getProperties().put("s202.rollupEndpointFqn", spec.key());
        return card;
    }

    private LevelClassBox classBox(HexagonalArchitecture.HexElement element) {
        LevelClassBox box = new LevelClassBox(
                element.simpleName(),
                element.localLevel(),
                element.fqn(),
                element.elementRole() == ArchitectureAnnotations.ElementRole.INBOUND_PORT
                        || element.elementRole() == ArchitectureAnnotations.ElementRole.OUTBOUND_PORT,
                element.architectureLevel());
        box.setSelectionChangeSink(selectionChangeSink);
        return box;
    }

    private enum PortSide {
        API,
        SPI,
        NONE
    }

    /**
     * One collapsible unit inside a sector: a (package x theme) group or an
     * API/SPI socket. {@code key} is the stable expansion-state and registry
     * key; {@code packageFqn} is null for sockets.
     */
    private record GroupSpec(String key,
                             String label,
                             String packageFqn,
                             List<HexagonalArchitecture.HexElement> classes,
                             boolean socket) {}

    private record SegmentVisual(HexagonalSegmentHeader header,
                                 List<Node> details,
                                 List<Runnable> cardVisibilityUpdaters) {}
}
