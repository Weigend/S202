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
package de.weigend.s202.ui.wfx.view3d;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.ui.model.ArchitectureNode;
import io.softwareecg.wfx.windowmtg.api.Position;
import io.softwareecg.wfx.windowmtg.api.View;
import io.softwareecg.wfx.windowmtg.api.ViewKind;
import javafx.animation.AnimationTimer;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.net.URL;
import java.util.Map;

/**
 * WFX {@link View} that renders the architecture as an interactive 3D landscape.
 *
 * <p>The 3D layout is built directly from the 2D element bounds read out of
 * {@link de.weigend.s202.ui.ArchitectureView#getElementFootprintBoundsInScene()}
 * after each layout pulse:
 * <pre>
 *   2D scene X  →  3D world X
 *   2D scene Y  →  3D world -Z  ("tip horizontal")
 *   new 3D Y    →  thin stack offset by package nesting depth
 * </pre>
 *
 * <p>The 3D view deliberately uses the 2D bounds as the source of truth:
 * packages become thin stacked slabs, classes become thin rectangles on their
 * package slab. It must not recompute package or class ordering.
 *
 * <p>Navigation: click to grab the pointer, WASD + mouse to fly, scroll to
 * zoom, ESC to release.
 */
public class ArchitectureView3D implements View {

    public static final String VIEW_ID = "s202-3d-view";

    private final StackPane root     = new StackPane();
    private final Group     scene3D  = new Group();
    private final SubScene  subScene;
    private final FlyCamera flyCamera;

    public ArchitectureView3D() {
        subScene = new SubScene(scene3D, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.rgb(20, 20, 30));
        subScene.widthProperty().bind(root.widthProperty());
        subScene.heightProperty().bind(root.heightProperty());

        flyCamera = new FlyCamera();
        subScene.setCamera(flyCamera.getCamera());
        root.getChildren().add(subScene);

        AnimationTimer timer = new AnimationTimer() {
            @Override public void handle(long now) { flyCamera.tick(); }
        };
        timer.start();
    }

    /**
     * Rebuilds the 3D scene from pre-read 2D layout bounds.
     * Pass {@code null} maps to clear the view.
     *
     * @param elementBounds bounds per FQN read from the 2D ArchitectureView
     * @param root          root of the ArchitectureNode tree
     * @param architecture  domain model (for tangle/SCC detection)
     * @param stage         owning stage (needed for mouse-grab centering)
     */
    public void setData(Map<String, Bounds> elementBounds,
                        ArchitectureNode root,
                        Architecture architecture,
                        Stage stage) {
        flyCamera.detach();
        scene3D.getChildren().clear();

        if (elementBounds == null || elementBounds.isEmpty()) return;

        SceneBuilder3D.SceneResult result = new SceneBuilder3D().build(elementBounds, root, architecture);
        scene3D.getChildren().add(result.group());

        flyCamera.attach(subScene, stage);
        SceneBuilder3D.CameraHint h = result.cameraHint();
        flyCamera.resetToLookAt(h.x(), h.y(), h.z(), h.targetX(), h.targetY(), h.targetZ());
    }

    // -----------------------------------------------------------------------
    // View interface
    // -----------------------------------------------------------------------

    @Override public String getViewId()            { return VIEW_ID; }
    @Override public String getTitle()             { return "3D Architecture"; }
    @Override public String getToolTipInfo()       {
        return "3D view — click to grab mouse, WASD+mouse to fly, scroll to zoom, ESC to release";
    }
    @Override public Position getDefaultPosition() { return Position.CENTER; }
    @Override public ViewKind getKind()            { return ViewKind.TOOL; }
    @Override public double getViewAreaSize()      { return 0.5; }
    @Override public javafx.scene.Parent getRootNode() { return root; }
    @Override public URL getViewImagePath()        { return null; }
}
