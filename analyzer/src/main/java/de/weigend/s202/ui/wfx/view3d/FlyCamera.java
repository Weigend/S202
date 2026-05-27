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

import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SubScene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.robot.Robot;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;

import java.util.HashSet;
import java.util.Set;

/**
 * First-person fly camera for the 3D architecture view.
 *
 * <p>Controls:
 * <ul>
 *   <li>Left-click in the SubScene to grab the mouse pointer</li>
 *   <li>WASD to move forward/backward/strafe (only while grabbed)</li>
 *   <li>Mouse movement (while grabbed) to rotate yaw/pitch</li>
 *   <li>Scroll wheel to zoom (always active over the SubScene)</li>
 *   <li>ESC to release the mouse pointer</li>
 * </ul>
 *
 * <p>Key events are only intercepted at the outer-scene level while the
 * grab is active. Outside grab mode no keyboard events are consumed, so
 * the rest of the application stays fully interactive.
 */
class FlyCamera {

    private static final double MOVE_SPEED  = 15.0;
    private static final double MOUSE_SENS  = 0.25;
    private static final double ZOOM_SPEED  = 30.0;
    private static final double PITCH_LIMIT = 89.0;

    private static final double START_X     =  0;
    private static final double START_Y     = -400;
    private static final double START_Z     = -600;
    private static final double START_PITCH = -30;
    private static final double START_YAW   =  0;

    private final PerspectiveCamera camera;
    private final Translate position;
    private final Rotate rotY;
    private final Rotate rotX;

    private double yaw   = START_YAW;
    private double pitch = START_PITCH;

    private boolean grabbed = false;
    /** Suppresses the re-center echo that robot.mouseMove() generates. */
    private boolean recentering = false;
    private double lastScreenX;
    private double lastScreenY;

    private SubScene attachedScene;
    private Stage attachedStage;
    private Robot robot;

    private final Set<KeyCode> pressedKeys = new HashSet<>();

    // Handlers registered on the SubScene permanently (mouse only)
    private EventHandler<MouseEvent>  clickHandler;
    private EventHandler<ScrollEvent> scrollHandler;
    private EventHandler<MouseEvent>  movedHandler;
    private EventHandler<MouseEvent>  draggedHandler;

    // Handlers registered on the outer Scene only while grabbed
    private EventHandler<KeyEvent> grabKeyPressedFilter;
    private EventHandler<KeyEvent> grabKeyReleasedFilter;

    FlyCamera() {
        camera = new PerspectiveCamera(true);
        camera.setNearClip(1);
        camera.setFarClip(100_000);

        position = new Translate(START_X, START_Y, START_Z);
        rotY = new Rotate(START_YAW,   Rotate.Y_AXIS);
        rotX = new Rotate(START_PITCH, Rotate.X_AXIS);

        camera.getTransforms().addAll(position, rotY, rotX);
    }

    PerspectiveCamera getCamera() {
        return camera;
    }

    void attach(SubScene subScene, Stage stage) {
        this.attachedScene = subScene;
        this.attachedStage = stage;
        this.robot = new Robot();

        clickHandler = e -> {
            if (e.getButton() == MouseButton.PRIMARY && !grabbed) {
                grab();
                e.consume();
            }
        };
        movedHandler = e -> {
            if (grabbed) handleMouseDelta(e.getScreenX(), e.getScreenY());
        };
        draggedHandler = e -> {
            if (grabbed) handleMouseDelta(e.getScreenX(), e.getScreenY());
        };
        scrollHandler = e -> {
            double delta = e.getDeltaY() * ZOOM_SPEED * 0.1;
            translateAlongView(0, 0, delta);
            e.consume();
        };

        grabKeyPressedFilter = e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                release();
                e.consume();
            } else {
                pressedKeys.add(e.getCode());
                e.consume();
            }
        };
        grabKeyReleasedFilter = e -> {
            pressedKeys.remove(e.getCode());
            e.consume();
        };

        subScene.addEventHandler(MouseEvent.MOUSE_PRESSED, clickHandler);
        subScene.addEventHandler(MouseEvent.MOUSE_MOVED,   movedHandler);
        subScene.addEventHandler(MouseEvent.MOUSE_DRAGGED, draggedHandler);
        subScene.addEventHandler(ScrollEvent.SCROLL,       scrollHandler);
        subScene.setFocusTraversable(true);
    }

    void detach() {
        if (attachedScene == null) return;
        release(); // removes outer-scene key filters if grabbed
        attachedScene.removeEventHandler(MouseEvent.MOUSE_PRESSED, clickHandler);
        attachedScene.removeEventHandler(MouseEvent.MOUSE_MOVED,   movedHandler);
        attachedScene.removeEventHandler(MouseEvent.MOUSE_DRAGGED, draggedHandler);
        attachedScene.removeEventHandler(ScrollEvent.SCROLL,       scrollHandler);
        attachedScene = null;
        attachedStage = null;
        pressedKeys.clear();
    }

    void reset() {
        resetTo(START_X, START_Y, START_Z);
    }

    void resetTo(double x, double y, double z) {
        resetTo(x, y, z, START_PITCH, START_YAW);
    }

    void resetTo(double x, double y, double z, double pitch, double yaw) {
        this.yaw = yaw;
        this.pitch = clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT);
        position.setX(x);
        position.setY(y);
        position.setZ(z);
        rotY.setAngle(this.yaw);
        rotX.setAngle(this.pitch);
    }

    void resetToLookAt(double x, double y, double z,
                       double targetX, double targetY, double targetZ) {
        double dx = targetX - x;
        double dy = targetY - y;
        double dz = targetZ - z;
        double horizontalDistance = Math.hypot(dx, dz);
        double computedYaw = Math.toDegrees(Math.atan2(dx, dz));
        double computedPitch = -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        resetTo(x, y, z, computedPitch, computedYaw);
    }

    /** Called each animation frame to apply held-key movement. */
    void tick() {
        if (!grabbed || pressedKeys.isEmpty()) return;
        double forward = 0, strafe = 0;
        if (pressedKeys.contains(KeyCode.W)) forward += MOVE_SPEED;
        if (pressedKeys.contains(KeyCode.S)) forward -= MOVE_SPEED;
        if (pressedKeys.contains(KeyCode.A)) strafe  -= MOVE_SPEED;
        if (pressedKeys.contains(KeyCode.D)) strafe  += MOVE_SPEED;
        if (forward != 0 || strafe != 0) {
            translateAlongView(strafe, 0, forward);
        }
    }

    private void handleMouseDelta(double screenX, double screenY) {
        if (recentering) {
            recentering = false;
            return;
        }
        double dx = screenX - lastScreenX;
        double dy = screenY - lastScreenY;
        if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return;

        yaw   += dx * MOUSE_SENS;
        pitch  = clamp(pitch + dy * MOUSE_SENS, -PITCH_LIMIT, PITCH_LIMIT);
        rotY.setAngle(yaw);
        rotX.setAngle(pitch);

        centerMouse();
    }

    private void centerMouse() {
        if (attachedStage == null) return;
        double cx = attachedStage.getX() + attachedStage.getWidth()  / 2.0;
        double cy = attachedStage.getY() + attachedStage.getHeight() / 2.0;
        lastScreenX = cx;
        lastScreenY = cy;
        recentering = true;
        robot.mouseMove(cx, cy);
    }

    private void translateAlongView(double rightDelta, double upDelta, double forwardDelta) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(-pitch);
        double horizontal = Math.cos(pitchRad);
        double fx =  Math.sin(yawRad) * horizontal;
        double fy =  Math.sin(pitchRad);
        double fz =  Math.cos(yawRad) * horizontal;
        double rx =  Math.cos(yawRad);
        double rz = -Math.sin(yawRad);

        position.setX(position.getX() + fx * forwardDelta + rx * rightDelta);
        position.setY(position.getY() + fy * forwardDelta + upDelta);
        position.setZ(position.getZ() + fz * forwardDelta + rz * rightDelta);
    }

    private void grab() {
        if (attachedScene == null || attachedStage == null) return;
        grabbed = true;
        attachedScene.setCursor(Cursor.NONE);
        centerMouse();
        // Intercept all key events at outer-scene level while grabbed
        attachedStage.getScene().addEventFilter(KeyEvent.KEY_PRESSED,  grabKeyPressedFilter);
        attachedStage.getScene().addEventFilter(KeyEvent.KEY_RELEASED, grabKeyReleasedFilter);
        attachedScene.requestFocus();
    }

    private void release() {
        if (!grabbed) return;
        grabbed = false;
        pressedKeys.clear();
        if (attachedScene != null) {
            attachedScene.setCursor(Cursor.DEFAULT);
        }
        if (attachedStage != null && attachedStage.getScene() != null) {
            attachedStage.getScene().removeEventFilter(KeyEvent.KEY_PRESSED,  grabKeyPressedFilter);
            attachedStage.getScene().removeEventFilter(KeyEvent.KEY_RELEASED, grabKeyReleasedFilter);
            // Return focus to the main window root so the rest of the UI is responsive
            attachedStage.getScene().getRoot().requestFocus();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
