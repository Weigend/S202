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
import de.weigend.s202.ui.model.ArchitectureNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * First radial JavaFX projection for {@link HexagonalArchitecture}. It renders
 * concentric rings, non-nested top-level segments, explicit ports, and compact
 * class lists per ring. The builder owns geometry only; port/violation
 * semantics come from the domain projection.
 */
public final class HexagonalArchitectureTreeBuilder {

    private static final double WIDTH = 1160;
    private static final double HEIGHT = 980;
    private static final double CENTER_X = WIDTH / 2.0;
    private static final double CENTER_Y = 490;
    private static final double CORE_RADIUS = 145;
    private static final double APPLICATION_RADIUS = 285;
    private static final double ADAPTER_RADIUS = 420;
    private static final int MAX_CLASSES_PER_RING = 6;

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> selectionChangeSink;
    private final ArchitectureAnnotations annotations;
    private final HexagonalArchitecture architecture;
    private final BiConsumer<ArchitectureAnnotations, String> annotationChangeSink;
    private final Runnable layoutChangeCallback;

    public HexagonalArchitectureTreeBuilder(Map<String, Node> elementRegistry,
                                            Consumer<String> selectionChangeSink,
                                            ArchitectureAnnotations annotations,
                                            HexagonalArchitecture architecture,
                                            BiConsumer<ArchitectureAnnotations, String> annotationChangeSink,
                                            Runnable layoutChangeCallback) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.selectionChangeSink = selectionChangeSink;
        this.annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        this.architecture = architecture;
        this.annotationChangeSink = annotationChangeSink != null ? annotationChangeSink : (next, message) -> {};
        this.layoutChangeCallback = layoutChangeCallback != null ? layoutChangeCallback : () -> {};
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

        drawRings(radialPane);
        drawSegments(radialPane);
        drawElements(radialPane);
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
                legendItem("#d9eee5", "Application / Ports"),
                legendItem("#e4e9f0", "Adapters"),
                legendItem("#ffd28a", "Explicit Port"),
                legendItem("#fff7d6", "API candidate"));
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

        addRingLabel(pane, "Core", CORE_RADIUS * 0.45, -18);
        addRingLabel(pane, "Application / Ports", (CORE_RADIUS + APPLICATION_RADIUS) / 2.0, 12);
        addRingLabel(pane, "Adapters", (APPLICATION_RADIUS + ADAPTER_RADIUS) / 2.0, 42);
    }

    private Circle circle(double radius, String fill, String stroke, double width) {
        Circle circle = new Circle(CENTER_X, CENTER_Y, radius);
        circle.setFill(Color.web(fill));
        circle.setStroke(Color.web(stroke));
        circle.setStrokeWidth(width);
        circle.setMouseTransparent(true);
        return circle;
    }

    private void addRingLabel(Pane pane, String text, double radius, double yOffset) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");
        label.setMouseTransparent(true);
        label.relocate(CENTER_X - 72, CENTER_Y - radius + yOffset);
        pane.getChildren().add(label);
    }

    private void drawSegments(Pane pane) {
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

    private void drawElements(Pane pane) {
        Map<String, List<HexagonalArchitecture.HexElement>> elementsBySegment = new HashMap<>();
        for (HexagonalArchitecture.HexElement element : architecture.elements()) {
            elementsBySegment.computeIfAbsent(element.segmentId(), k -> new ArrayList<>()).add(element);
        }

        int count = architecture.segments().size();
        for (int i = 0; i < count; i++) {
            HexagonalArchitecture.HexSegment segment = architecture.segments().get(i);
            double start = angleForSegmentBoundary(i, count);
            double end = angleForSegmentBoundary(i + 1, count);
            double mid = normalizeAngle((start + end) / 2.0);
            List<HexagonalArchitecture.HexElement> segmentElements =
                    elementsBySegment.getOrDefault(segment.id(), List.of());
            SegmentVisual visual = createSegmentVisual(pane, segment, segmentElements, mid);
            drawSegmentPorts(pane, segment, segmentElements, start, end, visual.details());
            drawSegmentRingClasses(pane, segment, segmentElements, mid, visual.details());
        }
    }

    private SegmentVisual createSegmentVisual(Pane pane,
                                              HexagonalArchitecture.HexSegment segment,
                                              List<HexagonalArchitecture.HexElement> elements,
                                              double midAngle) {
        HexagonalSegmentHeader header = new HexagonalSegmentHeader(segment.label(), segment.rootFqn(), selectionChangeSink);
        installSegmentContextMenu(header, segment);
        elementRegistry.put(segment.rootFqn(), header);

        Label summary = new Label(summaryText(elements));
        summary.setStyle("-fx-font-size: 10px; -fx-text-fill: #334155;"
                + " -fx-background-color: rgba(255,255,255,0.82);"
                + " -fx-border-color: #9aa7b5; -fx-border-width: 1;"
                + " -fx-padding: 3 7; -fx-background-radius: 3; -fx-border-radius: 3;");
        summary.setVisible(false);

        List<Node> details = new ArrayList<>();
        Runnable toggle = () -> {
            boolean expanded = header.toggleExpanded();
            for (Node detail : details) {
                detail.setVisible(expanded);
                detail.setManaged(expanded);
            }
            summary.setVisible(!expanded);
            layoutChangeCallback.run();
        };
        header.setToggleAction(toggle);

        placeNode(header, ADAPTER_RADIUS + 58, midAngle, 90, 14);
        placeNode(summary, ADAPTER_RADIUS + 88, midAngle, 78, -8);
        pane.getChildren().addAll(header, summary);
        return new SegmentVisual(details);
    }

    private void drawSegmentPorts(Pane pane,
                                  HexagonalArchitecture.HexSegment segment,
                                  List<HexagonalArchitecture.HexElement> elements,
                                  double startAngle,
                                  double endAngle,
                                  List<Node> details) {
        List<HexagonalArchitecture.HexElement> ports = elements.stream()
                .filter(HexagonalArchitecture.HexElement::explicitPort)
                .sorted(Comparator.comparing(HexagonalArchitecture.HexElement::fqn))
                .toList();
        int count = ports.size();
        for (int i = 0; i < count; i++) {
            HexagonalArchitecture.HexElement element = ports.get(i);
            double angle = spreadAngle(startAngle, endAngle, i, count);
        LevelClassBox box = classBox(element);
        box.getStyleClass().add("hexagonal-port-box");
        box.setStyle("-fx-background-color: #ffd28a; -fx-border-color: #b45309; -fx-border-width: 2;");
        installClassContextMenu(box, element, segment.id());
        elementRegistry.put(element.fqn(), box);

            Label direction = new Label(portDirectionLabel(element.fqn()));
            direction.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: #7c2d12;"
                    + " -fx-background-color: #fff7ed; -fx-border-color: #b45309;"
                    + " -fx-border-width: 1; -fx-padding: 1 4;");
        VBox portStack = new VBox(2, direction, box);
        portStack.setAlignment(Pos.CENTER);
        portStack.getProperties().put("s202.rollupEndpointFqn", segment.rootFqn());
        placeNode(portStack, APPLICATION_RADIUS + 12, angle, 68, 18);
        pane.getChildren().add(portStack);
        details.add(portStack);
        }
    }

    private void drawSegmentRingClasses(Pane pane,
                                        HexagonalArchitecture.HexSegment segment,
                                        List<HexagonalArchitecture.HexElement> elements,
                                        double midAngle,
                                        List<Node> details) {
        Map<HexagonalArchitecture.RingRole, List<HexagonalArchitecture.HexElement>> byRing =
                new EnumMap<>(HexagonalArchitecture.RingRole.class);
        for (HexagonalArchitecture.HexElement element : elements) {
            if (element.explicitPort()) {
                continue;
            }
            byRing.computeIfAbsent(element.ringRole(), k -> new ArrayList<>()).add(element);
        }

        for (HexagonalArchitecture.RingRole ring : HexagonalArchitecture.RingRole.values()) {
            List<HexagonalArchitecture.HexElement> ringElements = byRing.getOrDefault(ring, List.of()).stream()
                    .sorted(Comparator
                            .comparing(HexagonalArchitecture.HexElement::portCandidate).reversed()
                            .thenComparing(HexagonalArchitecture.HexElement::fqn))
                    .toList();
            if (ringElements.isEmpty()) {
                continue;
            }

            VBox column = new VBox(3);
            column.setAlignment(Pos.CENTER);
            column.setMaxWidth(168);
            column.setStyle("-fx-background-color: rgba(255,255,255,0.72);"
                    + " -fx-border-color: rgba(83,99,122,0.45); -fx-border-width: 1;"
                    + " -fx-padding: 4; -fx-background-radius: 4; -fx-border-radius: 4;");
            column.getProperties().put("s202.rollupEndpointFqn", segment.rootFqn());

            int visible = Math.min(MAX_CLASSES_PER_RING, ringElements.size());
            for (int i = 0; i < visible; i++) {
                HexagonalArchitecture.HexElement element = ringElements.get(i);
                LevelClassBox box = classBox(element);
                if (element.portCandidate()) {
                    box.setStyle("-fx-background-color: #fff7d6; -fx-border-color: #ca8a04; -fx-border-width: 1.5;");
                }
                installClassContextMenu(box, element, segment.id());
                elementRegistry.put(element.fqn(), box);
                column.getChildren().add(box);
            }
            int hidden = ringElements.size() - visible;
            if (hidden > 0) {
                Label more = new Label("+" + hidden + " more");
                more.setStyle("-fx-font-size: 9px; -fx-text-fill: #475569;");
                column.getChildren().add(more);
            }
            placeNode(column, radiusFor(ring), midAngle, 80, Math.min(74, 12 + visible * 13));
            pane.getChildren().add(column);
            details.add(column);
        }
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

    private void installSegmentContextMenu(Node target, HexagonalArchitecture.HexSegment segment) {
        target.setOnContextMenuRequested(event -> {
            MenuItem core = new MenuItem("Mark Segment as Core");
            core.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withElementRole(segment.rootFqn(), ArchitectureAnnotations.ElementRole.CORE),
                    "Marked segment core: " + segment.rootFqn()));

            MenuItem adapter = new MenuItem("Mark Segment as Adapter");
            adapter.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withElementRole(segment.rootFqn(), ArchitectureAnnotations.ElementRole.ADAPTER),
                    "Marked segment adapter: " + segment.rootFqn()));

            MenuItem clear = new MenuItem("Clear Segment Role");
            clear.setOnAction(action -> annotationChangeSink.accept(
                    annotations.withoutElementRole(segment.rootFqn()),
                    "Cleared segment role: " + segment.rootFqn()));

            ContextMenu menu = new ContextMenu(core, adapter, clear);
            menu.show(target, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private String portDirectionLabel(String fqn) {
        ArchitectureAnnotations.PortSpec port = annotations.explicitPort(fqn);
        if (port == null) {
            return "PORT";
        }
        return switch (port.direction()) {
            case INBOUND -> "IN";
            case OUTBOUND -> "OUT";
            case GENERIC -> "PORT";
        };
    }

    private String summaryText(List<HexagonalArchitecture.HexElement> elements) {
        long ports = elements.stream().filter(HexagonalArchitecture.HexElement::explicitPort).count();
        long candidates = elements.stream()
                .filter(HexagonalArchitecture.HexElement::portCandidate)
                .filter(e -> !e.explicitPort())
                .count();
        return elements.size() + " classes, " + ports + " ports, " + candidates + " candidates";
    }

    private void placeNode(Node node, double radius, double angleDegrees, double halfWidth, double halfHeight) {
        double radians = Math.toRadians(angleDegrees);
        double x = CENTER_X + Math.cos(radians) * radius - halfWidth;
        double y = CENTER_Y + Math.sin(radians) * radius - halfHeight;
        node.relocate(x, y);
    }

    private double radiusFor(HexagonalArchitecture.RingRole ring) {
        return switch (ring) {
            case CORE -> CORE_RADIUS * 0.58;
            case APPLICATION -> (CORE_RADIUS + APPLICATION_RADIUS) / 2.0;
            case ADAPTER -> (APPLICATION_RADIUS + ADAPTER_RADIUS) / 2.0;
        };
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

    private record SegmentVisual(List<Node> details) {}

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
