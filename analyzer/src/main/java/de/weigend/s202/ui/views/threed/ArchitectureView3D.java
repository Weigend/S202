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
package de.weigend.s202.ui.views.threed;

import de.weigend.s202.analysis.metrics.MetricMapping;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.platform.SnapshotView;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.ViewKind;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.net.URL;
import java.util.function.Consumer;

/**
 * WFX view that shows the picture of the active tab tilted back: the 2D
 * picture with heights, drawn by {@link View25D}. From above it is the 2D
 * picture; tilted, classes stand as high as a metric says, and the sides of
 * the boxes take the colour of another. {@link MappingPanel} sets which.
 *
 * <p>The view computes no layout of its own. It reads the picture off the 2D
 * view, {@link TopViewModel}, and hands the mouse back to it: what happens in
 * 2D on a click, a hover or a right-click happens here too.
 */
public class ArchitectureView3D implements SnapshotView {

    public static final String VIEW_ID = "s202-3d-view";

    private final StackPane root = new StackPane();
    private final View25D picture = new View25D();
    private MetricSource metrics = MetricSource.NONE;
    private MetricMapping mapping = MetricMapping.DEFAULT;
    private Consumer<MetricMapping> onMappingChanged = m -> {};
    /** Sets what heights and colours show; every change is drawn at once. */
    private final MappingPanel mappingPanel = new MappingPanel(newMapping -> {
        mapping = newMapping;
        picture.setMetrics(metrics, mapping);
        onMappingChanged.accept(newMapping);
    });

    public ArchitectureView3D() {
        root.setStyle("-fx-background-color: #101319;");
        HBox controls = picture.controls();
        StackPane.setAlignment(controls, Pos.TOP_RIGHT);
        StackPane.setMargin(controls, new Insets(12));
        StackPane.setAlignment(mappingPanel, Pos.BOTTOM_LEFT);
        StackPane.setMargin(mappingPanel, new Insets(12));
        picture.setOnLegend(mappingPanel::setLegend);
        root.getChildren().addAll(picture, controls, mappingPanel);
    }

    /** Told the element the user selected, {@code null} when the user took the selection away. */
    public void setOnElementSelected(Consumer<String> selectionSink) {
        picture.setOnSelect(selectionSink);
    }

    /** Selects the element without telling anyone; the look of the selection comes from 2D. */
    public void selectByFullName(String fqn) {
        picture.select(fqn);
    }

    /** Called when the mouse made 2D draw something else, which has to be read anew. */
    public void setOnPictureChanged(Runnable onPictureChanged) {
        picture.setOnPictureChanged(onPictureChanged);
    }

    /** Called when the user sets another mapping of metrics. */
    public void setOnMappingChanged(Consumer<MetricMapping> listener) {
        onMappingChanged = listener == null ? m -> {} : listener;
    }

    /**
     * The metrics of the picture, and what heights and colours show.
     *
     * @param model the classes of the bound view
     * @param tree  the tree of the bound view
     */
    public void setMetrics(DependencyModel model, ArchitectureNode tree, MetricMapping newMapping) {
        MetricMapping wanted = newMapping == null ? MetricMapping.DEFAULT : newMapping;
        if (metrics.isFor(model, tree) && wanted.equals(mapping)) {
            return;
        }
        if (!metrics.isFor(model, tree)) {
            metrics = MetricSource.of(model, tree);
        }
        mapping = wanted;
        mappingPanel.setAvailable(metrics::isAvailable);
        mappingPanel.setMapping(mapping);
        picture.setMetrics(metrics, mapping);
    }

    /**
     * The picture to show; {@code null} for none.
     *
     * @param reset back to the view from above, the whole picture in the tab
     */
    public void setPicture(TopViewModel model, boolean reset) {
        picture.setModel(model, reset);
    }

    // -----------------------------------------------------------------------
    // View
    // -----------------------------------------------------------------------

    @Override public String getViewId()            { return VIEW_ID; }
    @Override public WritableImage snapshot()      { return picture.snapshot(null, null); }
    @Override public String getTitle()             { return "3D Architecture"; }
    @Override public String getToolTipInfo()       {
        return "The picture tilted back: drag to tilt and turn, right drag or wheel to move, Ctrl+wheel to zoom, "
                + "Metrics for what heights and colours show";
    }
    @Override public Position getDefaultPosition() { return Position.CENTER; }
    @Override public ViewKind getKind()            { return ViewKind.TOOL; }
    @Override public double getViewAreaSize()      { return 0.5; }
    @Override public Parent getRootNode()          { return root; }
    @Override public URL getViewImagePath()        { return null; }
}
