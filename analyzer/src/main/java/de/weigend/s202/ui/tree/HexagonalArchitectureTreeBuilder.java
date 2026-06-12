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
import de.weigend.s202.ui.GraphSelection;
import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

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

    private static final double WIDTH = 1160;
    private static final double HEIGHT = 980;
    private static final double CENTER_X = WIDTH / 2.0;
    private static final double CENTER_Y = 490;
    private static final double CORE_RADIUS = 145;
    private static final double APPLICATION_RADIUS = 285;
    private static final double ADAPTER_RADIUS = 420;
    private static final double CARD_RADIAL_OFFSET = 72;
    private static final String SEGMENT_REGISTRY_PREFIX = "s202.hex.segment#";

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> selectionChangeSink;
    private final ArchitectureAnnotations annotations;
    private final HexagonalArchitecture architecture;
    private final BiConsumer<ArchitectureAnnotations, String> annotationChangeSink;
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
        this.annotationChangeSink = annotationChangeSink != null ? annotationChangeSink : (next, message) -> {};
        this.layoutChangeCallback = layoutChangeCallback != null ? layoutChangeCallback : () -> {};
        this.packageExpansionState = packageExpansionState != null ? packageExpansionState : new HashMap<>();
    }

    public VBox buildTree(ArchitectureNode rootNode, int maxDepth) {
        elementRegistry.clear();

        VBox topLevel = new VBox(8);
        topLevel.setPadding(new Insets(28, 42, 32, 42));
        topLevel.setStyle("-fx-background-color: #eef2f6;");

        HBox legend = buildLegend();
        Pane radialPane = new Pane();
        radialPane.setMinSize(WIDTH, HEIGHT);
        radialPane.setPrefSize(WIDTH, HEIGHT);
        radialPane.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        radialPane.setStyle("-fx-background-color: #eef2f6;");

        topLevel.getChildren().addAll(legend, radialPane);

        if (architecture == null || architecture.segments().isEmpty()) {
            Label empty = new Label("No hexagonal projection available");
            empty.setStyle("-fx-font-size: 14px; -fx-text-fill: #425466;");
            empty.relocate(CENTER_X - 120, CENTER_Y - 16);
            radialPane.getChildren().add(empty);
            return topLevel;
        }

        // Detail cards live on a dedicated layer that is added LAST, so expanded
        // group contents always render above rings, boxes, and neighbour
        // sectors — the overlay behaviour, with zero coordinate mapping.
        Group detailLayer = new Group();
        detailLayer.setPickOnBounds(false);

        drawRings(radialPane);
        drawSegmentBoundaries(radialPane);
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

    private HBox buildLegend() {
        HBox legend = new HBox(12);
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.setPadding(new Insets(0, 0, 8, 0));
        legend.getChildren().addAll(
                legendItem("#f8f1cf", "Core"),
                legendItem("#d9eee5", "Application"),
                legendItem("#e4e9f0", "Adapters"),
                legendItem("#ffd28a", "API / SPI sockets"),
                legendItem("#ffffff", "Group (click + to expand)"));
        return legend;
    }

    private HBox legendItem(String color, String text) {
        HBox item = new HBox(5);
        item.setAlignment(Pos.CENTER_LEFT);
        Region swatch = new Region();
        swatch.setMinSize(14, 9);
        swatch.setPrefSize(14, 9);
        swatch.setStyle("-fx-background-color: " + color + "; -fx-border-color: #6b7280; -fx-border-width: 1;");
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #253040;");
        item.getChildren().addAll(swatch, label);
        return item;
    }

    private void drawRings(Pane pane) {
        Circle adapter = circle(ADAPTER_RADIUS, "#e4e9f0", "#8a98a8", 1.4);
        Circle application = circle(APPLICATION_RADIUS, "#d9eee5", "#4a9b82", 1.6);
        Circle core = circle(CORE_RADIUS, "#f8f1cf", "#b7932e", 1.8);
        pane.getChildren().addAll(adapter, application, core);
    }

    private Circle circle(double radius, String fill, String stroke, double width) {
        Circle circle = new Circle(CENTER_X, CENTER_Y, radius);
        circle.setFill(Color.web(fill));
        circle.setStroke(Color.web(stroke));
        circle.setStrokeWidth(width);
        circle.setMouseTransparent(true);
        return circle;
    }

    private void drawSegmentBoundaries(Pane pane) {
        int count = architecture.segments().size();
        for (int i = 0; i < count; i++) {
            double angle = angleForSegmentBoundary(i, count);
            double radians = Math.toRadians(angle);
            Line line = new Line(
                    CENTER_X,
                    CENTER_Y,
                    CENTER_X + Math.cos(radians) * ADAPTER_RADIUS,
                    CENTER_Y + Math.sin(radians) * ADAPTER_RADIUS);
            line.setStroke(Color.web("#b0bac8"));
            line.setStrokeWidth(1);
            line.getStrokeDashArray().setAll(5.0, 7.0);
            line.setMouseTransparent(true);
            pane.getChildren().add(line);
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
            double start = angleForSegmentBoundary(i, count);
            double end = angleForSegmentBoundary(i + 1, count);
            double mid = normalizeAngle((start + end) / 2.0);
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
        installPackageRoleContextMenu(header, segment.rootFqn(), "segment");
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

        placeNode(header, ADAPTER_RADIUS + 58, midAngle, 90, 14);
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
                        .computeIfAbsent(parentOf(element.fqn()), k -> new ArrayList<>())
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
                double radius = bandRadius(ring, levelT);
                double angle = spreadAngle(startAngle, endAngle, i, ordered.size());
                String simpleName = group.getKey().substring(group.getKey().lastIndexOf('.') + 1);
                addGroup(boxGroup, segmentCardGroup, segment, visual,
                        new GroupSpec(
                                group.getKey() + "#" + segment.id(),
                                simpleName,
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
            double angle = spiSocket.isEmpty() ? midAngle : normalizeAngle(midAngle - socketSpread);
            addGroup(boxGroup, segmentCardGroup, segment, visual,
                    new GroupSpec(segment.id() + "#api", "API", null, apiSocket, true),
                    APPLICATION_RADIUS, angle);
        }
        if (!spiSocket.isEmpty()) {
            double angle = apiSocket.isEmpty() ? midAngle : normalizeAngle(midAngle + socketSpread);
            addGroup(boxGroup, segmentCardGroup, segment, visual,
                    new GroupSpec(segment.id() + "#spi", "SPI", null, spiSocket, true),
                    APPLICATION_RADIUS, angle);
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
        String parent = parentOf(element.fqn());
        String packageName = parent.substring(parent.lastIndexOf('.') + 1);
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
            installPackageRoleContextMenu(box, spec.packageFqn(), "package");
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
        double cardRadius = radius + CARD_RADIAL_OFFSET;
        Line leader = new Line(
                CENTER_X + Math.cos(radians) * radius,
                CENTER_Y + Math.sin(radians) * radius,
                CENTER_X + Math.cos(radians) * cardRadius,
                CENTER_Y + Math.sin(radians) * cardRadius);
        leader.setStroke(Color.web("#64748b"));
        leader.setStrokeWidth(1.2);
        leader.getStrokeDashArray().setAll(3.0, 4.0);
        leader.setMouseTransparent(true);

        placeNode(box, radius, angle, 58, 12);
        placeNode(card, cardRadius, angle, 110, 0);
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
                    installClassContextMenu(box, element, segment.id());
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

    private void installClassContextMenu(Node target,
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

    private void installPackageRoleContextMenu(Node target, String fqn, String noun) {
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

    private void placeNode(Node node, double radius, double angleDegrees, double halfWidth, double halfHeight) {
        double radians = Math.toRadians(angleDegrees);
        double x = CENTER_X + Math.cos(radians) * radius - halfWidth;
        double y = CENTER_Y + Math.sin(radians) * radius - halfHeight;
        node.relocate(x, y);
    }

    /**
     * Radius inside the ring band for a group whose normalized level position
     * is {@code levelT} (0 = lowest level in the ring). Low-level packages are
     * the ring's API and sit towards the band's OUTER edge — the shell presents
     * its contact surface to the next ring (see HEXAGONAL_PACKAGE_LEVEL_CONCEPT).
     */
    private double bandRadius(HexagonalArchitecture.RingRole ring, double levelT) {
        double inner;
        double outer;
        switch (ring) {
            case CORE -> {
                inner = CORE_RADIUS * 0.32;
                outer = CORE_RADIUS - 32;
            }
            case APPLICATION -> {
                inner = CORE_RADIUS + 30;
                outer = APPLICATION_RADIUS - 36;
            }
            default -> {
                inner = APPLICATION_RADIUS + 30;
                outer = ADAPTER_RADIUS - 30;
            }
        }
        return outer - levelT * (outer - inner);
    }

    private double angleForSegmentBoundary(int index, int segmentCount) {
        return -90.0 + (360.0 / Math.max(1, segmentCount)) * index;
    }

    private double spreadAngle(double startAngle, double endAngle, int index, int count) {
        if (count <= 1) {
            return normalizeAngle((startAngle + endAngle) / 2.0);
        }
        double span = endAngle - startAngle;
        double padding = Math.min(14.0, Math.abs(span) * 0.18);
        double usableStart = startAngle + padding;
        double usableSpan = span - padding * 2.0;
        return normalizeAngle(usableStart + usableSpan * (index + 0.5) / count);
    }

    private double normalizeAngle(double angle) {
        double normalized = angle % 360.0;
        return normalized < -180.0 ? normalized + 360.0 : normalized;
    }

    private static String parentOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
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

    private static final class HexGroupBox extends HBox implements GraphSelection.Selectable {
        private final String fullName;
        private final Label toggle = new Label();
        private final boolean socket;
        private Consumer<String> selectionChangeSink;
        private Runnable toggleAction = () -> {};

        HexGroupBox(String label,
                    String fullName,
                    int classCount,
                    boolean expanded,
                    boolean socket,
                    Consumer<String> selectionChangeSink) {
            super(5);
            this.fullName = fullName;
            this.socket = socket;
            this.selectionChangeSink = selectionChangeSink;
            setAlignment(Pos.CENTER_LEFT);
            setCursor(Cursor.HAND);
            setMaxWidth(150);
            getStyleClass().add(socket ? "hexagonal-socket-box" : "hexagonal-package-box");
            getProperties().put("s202.aggregateEndpoint", Boolean.TRUE);

            toggle.setMinWidth(12);
            toggle.setAlignment(Pos.CENTER);
            toggle.setStyle("-fx-font-weight: bold; -fx-text-fill: #172033;");
            toggle.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    toggleAction.run();
                    event.consume();
                }
            });

            Label nameLabel = new Label(label);
            nameLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #172033;");
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            Label count = new Label("(" + classCount + ")");
            count.setStyle("-fx-font-size: 9px; -fx-text-fill: #475569;");

            if (socket) {
                // API/SPI contracts ARE the ports of the hexagon — say so.
                FontIcon portIcon = new FontIcon(MaterialDesignP.POWER_PLUG);
                portIcon.setIconColor(Color.web("#7c2d12"));
                portIcon.setIconSize(11);
                getChildren().addAll(toggle, portIcon, nameLabel, count);
            } else {
                getChildren().addAll(toggle, nameLabel, count);
            }
            setExpandedVisual(expanded);
            applyUnselectedStyle();
            setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                GraphSelection.select(this);
                event.consume();
            });
        }

        void setToggleAction(Runnable action) {
            toggleAction = action == null ? () -> {} : action;
        }

        void setExpandedVisual(boolean expanded) {
            toggle.setText(expanded ? "−" : "+");
            getProperties().put("s202.collapsed", !expanded);
        }

        @Override
        public String getFullName() {
            return fullName;
        }

        @Override
        public void applySelectedStyle() {
            setStyle("-fx-background-color: #fff3a0; -fx-border-color: #ff6600;"
                    + " -fx-border-width: 2; -fx-padding: 3 6;"
                    + " -fx-background-radius: 4; -fx-border-radius: 4;");
        }

        @Override
        public void applyUnselectedStyle() {
            if (socket) {
                setStyle("-fx-background-color: #ffd28a; -fx-border-color: #b45309;"
                        + " -fx-border-width: 1.6; -fx-padding: 3 6;"
                        + " -fx-background-radius: 4; -fx-border-radius: 4;");
            } else {
                setStyle("-fx-background-color: rgba(255,255,255,0.94); -fx-border-color: #3b5371;"
                        + " -fx-border-width: 1.2; -fx-padding: 3 6;"
                        + " -fx-background-radius: 4; -fx-border-radius: 4;");
            }
        }

        @Override
        public Consumer<String> selectionChangeSink() {
            return selectionChangeSink;
        }
    }

    private static final class HexagonalSegmentHeader extends HBox implements GraphSelection.Selectable {
        private final String fullName;
        private final Label toggle = new Label("−");
        private final Label nameLabel;
        private Consumer<String> selectionChangeSink;
        private Runnable toggleAction = () -> {};
        private boolean expanded = true;

        HexagonalSegmentHeader(String label, String fullName, Consumer<String> selectionChangeSink) {
            super(6);
            this.fullName = fullName;
            this.selectionChangeSink = selectionChangeSink;
            setAlignment(Pos.CENTER_LEFT);
            setCursor(Cursor.HAND);
            setMaxWidth(180);
            setMinWidth(150);
            getStyleClass().add("hexagonal-segment-header");
            getProperties().put("s202.aggregateEndpoint", Boolean.TRUE);
            getProperties().put("s202.collapsed", Boolean.FALSE);

            toggle.setMinWidth(12);
            toggle.setAlignment(Pos.CENTER);
            toggle.setStyle("-fx-font-weight: bold; -fx-text-fill: #172033;");
            toggle.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    toggleAction.run();
                    event.consume();
                }
            });

            nameLabel = new Label(label);
            nameLabel.setWrapText(true);
            nameLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #172033;");
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            getChildren().addAll(toggle, nameLabel);
            applyUnselectedStyle();
            setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                GraphSelection.select(this);
                event.consume();
            });
        }

        void setToggleAction(Runnable action) {
            toggleAction = action == null ? () -> {} : action;
        }

        boolean toggleExpanded() {
            expanded = !expanded;
            toggle.setText(expanded ? "−" : "+");
            getProperties().put("s202.collapsed", !expanded);
            return expanded;
        }

        boolean isExpanded() {
            return expanded;
        }

        @Override
        public String getFullName() {
            return fullName;
        }

        @Override
        public void applySelectedStyle() {
            setStyle("-fx-background-color: #fff3a0; -fx-border-color: #ff6600;"
                    + " -fx-border-width: 2; -fx-padding: 4 7;"
                    + " -fx-background-radius: 4; -fx-border-radius: 4;");
        }

        @Override
        public void applyUnselectedStyle() {
            setStyle("-fx-background-color: rgba(255,255,255,0.9); -fx-border-color: #607086;"
                    + " -fx-border-width: 1; -fx-padding: 4 7;"
                    + " -fx-background-radius: 4; -fx-border-radius: 4;");
        }

        @Override
        public Consumer<String> selectionChangeSink() {
            return selectionChangeSink;
        }
    }
}
