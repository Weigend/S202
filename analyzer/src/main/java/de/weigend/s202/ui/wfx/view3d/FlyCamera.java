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
import javafx.geometry.Bounds;
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
 *   <li>Left-click in the SubScene to focus/capture the 3D view</li>
 *   <li>WASD to move forward/backward/strafe (only while grabbed)</li>
 *   <li>CTRL + mouse movement (while grabbed) to rotate yaw/pitch</li>
 *   <li>CTRL + scroll wheel to zoom over the SubScene</li>
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
    private static final double FOV_MARGIN  = 0.98;

    private static final double START_X     =  0;
    private static final double START_Y     = -400;
    private static final double START_Z     = -600;
    private static final double START_PITCH = -30;
    private static final double START_YAW   =  0;

    record Pose(double x, double y, double z, double pitch, double yaw) {}

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
                lastScreenX = e.getScreenX();
                lastScreenY = e.getScreenY();
                grab();
            }
        };
        movedHandler = e -> {
            if (grabbed) handleMouseMove(e);
        };
        draggedHandler = e -> {
            if (grabbed) handleMouseMove(e);
        };
        scrollHandler = e -> {
            if (!e.isControlDown()) {
                return;
            }
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
                updateGrabCursor();
                e.consume();
            }
        };
        grabKeyReleasedFilter = e -> {
            pressedKeys.remove(e.getCode());
            updateGrabCursor();
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
        Pose pose = lookAtPose(x, y, z, targetX, targetY, targetZ);
        resetTo(pose.x(), pose.y(), pose.z(), pose.pitch(), pose.yaw());
    }

    boolean isWorldPointVisible(double targetX,
                                double targetY,
                                double targetZ,
                                double viewportWidth,
                                double viewportHeight) {
        return isWorldPointVisible(
                position.getX(), position.getY(), position.getZ(), pitch, yaw,
                targetX, targetY, targetZ,
                viewportWidth, viewportHeight,
                camera.getFieldOfView(),
                camera.isVerticalFieldOfView(),
                camera.getNearClip());
    }

    static Pose lookAtPose(double x,
                           double y,
                           double z,
                           double targetX,
                           double targetY,
                           double targetZ) {
        double dx = targetX - x;
        double dy = targetY - y;
        double dz = targetZ - z;
        double horizontalDistance = Math.hypot(dx, dz);
        double computedYaw = Math.toDegrees(Math.atan2(dx, dz));
        double computedPitch = -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        return new Pose(x, y, z, clamp(computedPitch, -PITCH_LIMIT, PITCH_LIMIT), computedYaw);
    }

    static boolean isWorldPointVisible(double cameraX,
                                       double cameraY,
                                       double cameraZ,
                                       double pitch,
                                       double yaw,
                                       double targetX,
                                       double targetY,
                                       double targetZ,
                                       double viewportWidth,
                                       double viewportHeight,
                                       double fieldOfView,
                                       boolean verticalFieldOfView,
                                       double nearClip) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return true;
        }

        Basis basis = basis(pitch, yaw);
        double dx = targetX - cameraX;
        double dy = targetY - cameraY;
        double dz = targetZ - cameraZ;
        double forwardDistance = dot(dx, dy, dz, basis.forwardX, basis.forwardY, basis.forwardZ);
        if (forwardDistance <= nearClip) {
            return false;
        }

        double horizontalDistance = Math.abs(dot(dx, dy, dz, basis.rightX, basis.rightY, basis.rightZ));
        double verticalDistance = Math.abs(dot(dx, dy, dz, basis.downX, basis.downY, basis.downZ));
        double aspect = viewportWidth / viewportHeight;
        double fovRadians = Math.toRadians(fieldOfView);
        double halfHorizontalFov;
        double halfVerticalFov;
        if (verticalFieldOfView) {
            halfVerticalFov = fovRadians / 2.0;
            halfHorizontalFov = Math.atan(Math.tan(halfVerticalFov) * aspect);
        } else {
            halfHorizontalFov = fovRadians / 2.0;
            halfVerticalFov = Math.atan(Math.tan(halfHorizontalFov) / aspect);
        }

        double horizontalAngle = Math.atan2(horizontalDistance, forwardDistance);
        double verticalAngle = Math.atan2(verticalDistance, forwardDistance);
        return horizontalAngle <= halfHorizontalFov * FOV_MARGIN
                && verticalAngle <= halfVerticalFov * FOV_MARGIN;
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

    private void handleMouseMove(MouseEvent event) {
        if (recentering) {
            handleMouseDelta(event.getScreenX(), event.getScreenY());
            event.consume();
            return;
        }
        if (event.isControlDown()) {
            if (attachedScene != null) {
                attachedScene.setCursor(Cursor.NONE);
            }
            handleMouseDelta(event.getScreenX(), event.getScreenY());
            event.consume();
            return;
        }
        updateGrabCursor();
        lastScreenX = event.getScreenX();
        lastScreenY = event.getScreenY();
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
        Bounds screenBounds = attachedScene == null
                ? null
                : attachedScene.localToScreen(attachedScene.getBoundsInLocal());
        double cx = screenBounds == null
                ? attachedStage.getX() + attachedStage.getWidth() / 2.0
                : (screenBounds.getMinX() + screenBounds.getMaxX()) / 2.0;
        double cy = screenBounds == null
                ? attachedStage.getY() + attachedStage.getHeight() / 2.0
                : (screenBounds.getMinY() + screenBounds.getMaxY()) / 2.0;
        lastScreenX = cx;
        lastScreenY = cy;
        recentering = true;
        robot.mouseMove(cx, cy);
    }

    private void translateAlongView(double rightDelta, double upDelta, double forwardDelta) {
        Basis basis = basis();

        position.setX(position.getX() + basis.forwardX * forwardDelta + basis.rightX * rightDelta);
        position.setY(position.getY() + basis.forwardY * forwardDelta + upDelta);
        position.setZ(position.getZ() + basis.forwardZ * forwardDelta + basis.rightZ * rightDelta);
    }

    private Basis basis() {
        return basis(pitch, yaw);
    }

    private static Basis basis(double pitch, double yaw) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(-pitch);
        double horizontal = Math.cos(pitchRad);
        double forwardX = Math.sin(yawRad) * horizontal;
        double forwardY = Math.sin(pitchRad);
        double forwardZ = Math.cos(yawRad) * horizontal;
        double rightX = Math.cos(yawRad);
        double rightY = 0.0;
        double rightZ = -Math.sin(yawRad);

        double downX = forwardY * rightZ - forwardZ * rightY;
        double downY = forwardZ * rightX - forwardX * rightZ;
        double downZ = forwardX * rightY - forwardY * rightX;
        return new Basis(
                forwardX, forwardY, forwardZ,
                rightX, rightY, rightZ,
                downX, downY, downZ);
    }

    private static double dot(double ax,
                              double ay,
                              double az,
                              double bx,
                              double by,
                              double bz) {
        return ax * bx + ay * by + az * bz;
    }

    private record Basis(double forwardX,
                         double forwardY,
                         double forwardZ,
                         double rightX,
                         double rightY,
                         double rightZ,
                         double downX,
                         double downY,
                         double downZ) {}

    private void grab() {
        if (attachedScene == null || attachedStage == null) return;
        grabbed = true;
        updateGrabCursor();
        // Intercept all key events at outer-scene level while grabbed
        attachedStage.getScene().addEventFilter(KeyEvent.KEY_PRESSED,  grabKeyPressedFilter);
        attachedStage.getScene().addEventFilter(KeyEvent.KEY_RELEASED, grabKeyReleasedFilter);
        attachedScene.requestFocus();
    }

    private void release() {
        if (!grabbed) return;
        grabbed = false;
        recentering = false;
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

    private void updateGrabCursor() {
        if (attachedScene == null || !grabbed) return;
        boolean controlDown = pressedKeys.contains(KeyCode.CONTROL);
        attachedScene.setCursor(controlDown ? Cursor.NONE : Cursor.CROSSHAIR);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
