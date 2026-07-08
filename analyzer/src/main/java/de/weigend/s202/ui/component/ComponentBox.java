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
package de.weigend.s202.ui.component;

import de.weigend.s202.ui.core.graph.ArchitectureDragController;
import de.weigend.s202.ui.core.graph.GraphSelection;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

import java.util.function.Consumer;

/**
 * Top-level component container for the component architecture projection.
 * Components are not nested; packages remain nested inside the implementation area.
 */
public class ComponentBox extends VBox implements GraphSelection.Selectable {

    private static final String API_DROP_TARGET_TAG = "s202.component.api.dropTarget";
    private static final String API_ELEMENT_TAG = "s202.component.api.element";
    private static final String EXPANDED_MARK = "\u2212";
    private static final String COLLAPSED_MARK = "+";

    private static final String STYLE_NORMAL =
            "-fx-background-color: #eef6ff; -fx-border-color: #2f6fae; -fx-border-width: 2;"
          + " -fx-background-radius: 7; -fx-border-radius: 7;";
    private static final String STYLE_SELECTED =
            "-fx-background-color: #f7fbff; -fx-border-color: #ff8a00; -fx-border-width: 3;"
          + " -fx-background-radius: 7; -fx-border-radius: 7;";
    private static final String API_STYLE_NORMAL =
            "-fx-background-color: #d9ecff; -fx-border-color: #9bc5ef;"
          + " -fx-border-width: 1; -fx-background-radius: 5; -fx-border-radius: 5;";
    private static final String API_STYLE_SELECTED =
            "-fx-background-color: #e9f4ff; -fx-border-color: #ff8a00;"
          + " -fx-border-width: 2; -fx-background-radius: 5; -fx-border-radius: 5;";

    private final String fullName;
    private Label componentToggleIcon;
    private Label apiToggleIcon;
    private final VBox componentContentContainer = new VBox(8);
    private final ApiSurfaceBox apiContainer;
    private final VBox apiRowsContainer = new VBox(6);
    private final VBox implementationContainer = new VBox(6);
    private boolean componentExpanded = true;
    private boolean apiExpanded = true;
    private Consumer<String> selectionChangeSink;
    private String styleNormal = STYLE_NORMAL;
    private String styleSelected = STYLE_SELECTED;

    private static Runnable onExpandChangeCallback = null;

    public static void setOnExpandChangeCallback(Runnable callback) {
        onExpandChangeCallback = callback;
    }

    public ComponentBox(String displayName, String fullName, int apiCount) {
        this.fullName = fullName;
        this.apiContainer = new ApiSurfaceBox(fullName);

        setSpacing(8);
        setPadding(new Insets(8));
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setStyle(STYLE_NORMAL);
        setCursor(Cursor.HAND);

        Label title = new Label("Component: " + displayName);
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #17324d;");

        Label meta = new Label(apiCount + " API " + (apiCount == 1 ? "class" : "classes"));
        meta.setStyle("-fx-font-size: 11px; -fx-text-fill: #486581;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        componentToggleIcon = createToggleIcon();
        componentToggleIcon.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                toggleComponentExpanded();
                event.consume();
            }
        });

        HBox header = new HBox(8, componentToggleIcon, createLollipop(), title, spacer, meta);
        header.setAlignment(Pos.CENTER_LEFT);

        componentContentContainer.setMaxWidth(Double.MAX_VALUE);

        apiContainer.setPadding(new Insets(8));
        apiContainer.getProperties().put(API_DROP_TARGET_TAG, Boolean.TRUE);
        ArchitectureDragController.markAsRowStack(apiContainer);
        Label apiLabel = new Label("API");
        apiLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #1f5e9d;");

        apiToggleIcon = createToggleIcon();
        apiToggleIcon.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                toggleApiExpanded();
                event.consume();
            }
        });
        HBox apiHeader = new HBox(6, apiToggleIcon, apiLabel);
        apiHeader.setAlignment(Pos.CENTER_LEFT);

        apiRowsContainer.setMaxWidth(Double.MAX_VALUE);
        apiRowsContainer.setMinHeight(28);
        apiRowsContainer.getProperties().put(API_DROP_TARGET_TAG, Boolean.TRUE);
        ArchitectureDragController.markAsRowStack(apiRowsContainer);

        apiContainer.getChildren().addAll(apiHeader, apiRowsContainer);

        implementationContainer.setPadding(new Insets(4, 0, 0, 0));

        componentContentContainer.getChildren().addAll(apiContainer, implementationContainer);

        getChildren().addAll(header, componentContentContainer);

        addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            GraphSelection.select(this);
            event.consume();
        });
    }

    public void setApiContent(Node node) {
        apiRowsContainer.getChildren().setAll(node);
    }

    public static void markApiElement(Node node) {
        if (node != null) {
            node.getProperties().put(API_ELEMENT_TAG, Boolean.TRUE);
        }
    }

    public static boolean isApiElement(Node node) {
        return node != null && Boolean.TRUE.equals(node.getProperties().get(API_ELEMENT_TAG));
    }

    public static boolean isApiDropTarget(Node node) {
        Node current = node;
        while (current != null) {
            if (Boolean.TRUE.equals(current.getProperties().get(API_DROP_TARGET_TAG))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    public void setImplementationContent(Node node) {
        implementationContainer.getChildren().setAll(node);
    }

    public boolean isExpanded() {
        return componentExpanded;
    }

    public void setExpanded(boolean expanded) {
        if (componentExpanded != expanded) {
            toggleComponentExpanded();
        }
    }

    public boolean isApiExpanded() {
        return apiExpanded;
    }

    public void setApiExpanded(boolean expanded) {
        if (apiExpanded != expanded) {
            toggleApiExpanded();
        }
    }

    public void setSelectionChangeSink(Consumer<String> sink) {
        selectionChangeSink = sink;
        apiContainer.setSelectionChangeSink(sink);
    }

    @Override
    public Consumer<String> selectionChangeSink() {
        return selectionChangeSink;
    }

    @Override
    public String getFullName() {
        return fullName;
    }

    public void setCustomStyles(String normalStyle, String selectedStyle) {
        this.styleNormal = normalStyle;
        this.styleSelected = selectedStyle;
        setStyle(styleNormal);
    }

    @Override
    public void applySelectedStyle() {
        setStyle(styleSelected);
    }

    @Override
    public void applyUnselectedStyle() {
        setStyle(styleNormal);
    }

    private void toggleComponentExpanded() {
        componentExpanded = !componentExpanded;
        setVisibleManaged(componentContentContainer, componentExpanded);
        componentToggleIcon.setText(componentExpanded ? EXPANDED_MARK : COLLAPSED_MARK);
        notifyExpandChanged();
    }

    private void toggleApiExpanded() {
        apiExpanded = !apiExpanded;
        setVisibleManaged(apiRowsContainer, apiExpanded);
        apiToggleIcon.setText(apiExpanded ? EXPANDED_MARK : COLLAPSED_MARK);
        notifyExpandChanged();
    }

    private static void setVisibleManaged(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
        if (visible) {
            node.applyCss();
        }
    }

    private static Label createToggleIcon() {
        Label toggle = new Label(EXPANDED_MARK);
        toggle.setStyle("-fx-text-fill: #000000; -fx-font-size: 10px; -fx-font-weight: bold;");
        toggle.setMinWidth(12);
        toggle.setPrefWidth(12);
        toggle.setAlignment(Pos.CENTER);
        toggle.setCursor(Cursor.HAND);
        return toggle;
    }

    private static void notifyExpandChanged() {
        if (onExpandChangeCallback != null) {
            Runnable callback = onExpandChangeCallback;
            Platform.runLater(callback);
        }
    }

    private static Node createLollipop() {
        Circle circle = new Circle(5);
        circle.setFill(Color.WHITE);
        circle.setStroke(Color.web("#1f5e9d"));
        circle.setStrokeWidth(2);

        Line stem = new Line(0, 0, 0, 14);
        stem.setStroke(Color.web("#1f5e9d"));
        stem.setStrokeWidth(2);

        VBox glyph = new VBox(-1, circle, stem);
        glyph.setAlignment(Pos.TOP_CENTER);

        StackPane holder = new StackPane(glyph);
        holder.setMinSize(18, 26);
        holder.setPrefSize(18, 26);
        return holder;
    }

    private static final class ApiSurfaceBox extends VBox implements GraphSelection.Selectable {

        private final String fullName;
        private Consumer<String> selectionChangeSink;

        private ApiSurfaceBox(String fullName) {
            super(6);
            this.fullName = fullName;
            setMaxWidth(Double.MAX_VALUE);
            setStyle(API_STYLE_NORMAL);
            setCursor(Cursor.HAND);
            addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                if (event.getClickCount() == 2) {
                    GraphSelection.ensureSelected(this);
                    GraphSelection.fireDoubleClick(fullName);
                } else {
                    GraphSelection.select(this);
                }
                event.consume();
            });
        }

        private void setSelectionChangeSink(Consumer<String> sink) {
            selectionChangeSink = sink;
        }

        @Override
        public String getFullName() {
            return fullName;
        }

        @Override
        public void applySelectedStyle() {
            setStyle(API_STYLE_SELECTED);
        }

        @Override
        public void applyUnselectedStyle() {
            setStyle(API_STYLE_NORMAL);
        }

        @Override
        public Consumer<String> selectionChangeSink() {
            return selectionChangeSink;
        }
    }
}
