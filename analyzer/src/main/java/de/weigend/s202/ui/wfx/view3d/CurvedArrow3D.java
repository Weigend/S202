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

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a 3D arrow from one tube mesh plus a cone arrowhead.
 *
 * <p>JavaFX has no native 3D curve primitive, so a quadratic Bezier is sampled
 * into a tube-shaped {@link TriangleMesh}. The curve rises toward negative Y,
 * which is "up" in the 3D architecture view.
 */
final class CurvedArrow3D {

    private static final Point3D Y_AXIS = new Point3D(0, 1, 0);
    private static final Point3D X_AXIS = new Point3D(1, 0, 0);
    private static final Point3D Z_AXIS = new Point3D(0, 0, 1);
    private static final int CURVE_STEPS = 18;
    private static final int CONE_SEGMENTS = 14;
    static final int TUBE_SEGMENTS = 10;
    private static final double END_CLEARANCE = 8.0;
    private static final double MIN_LIFT = 58.0;
    private static final double MAX_LIFT = 250.0;
    private static final double DISTANCE_LIFT_FACTOR = 0.12;
    private static final int LANE_COUNT = 7;
    private static final double LANE_LIFT = 5.0;
    private static final double LANE_SIDE_OFFSET = 10.0;
    private static final double ARROW_HEAD_LENGTH = 18.0;
    private static final double ARROW_HEAD_RADIUS = 6.0;

    private CurvedArrow3D() {}

    static Group build(SceneBuilder3D.EdgeTarget source,
                       SceneBuilder3D.EdgeTarget target,
                       double safeCeilingY,
                       int lane,
                       double radius,
                       Color color) {
        PhongMaterial material = material(color);
        Point3D start = anchor(source);
        Point3D end = anchor(target);
        if (start.distance(end) < 0.001) {
            return buildSelfLoop(source, safeCeilingY, lane, radius, material);
        }

        double horizontalDistance = Math.hypot(end.getX() - start.getX(), end.getZ() - start.getZ());
        int laneSlot = Math.floorMod(lane, LANE_COUNT);
        double laneLift = laneSlot * LANE_LIFT;
        double lift = Math.min(MAX_LIFT, Math.max(MIN_LIFT, horizontalDistance * DISTANCE_LIFT_FACTOR))
                + laneLift;
        double controlY = Math.min(safeCeilingY - laneLift,
                Math.min(start.getY(), end.getY()) - lift);
        double controlX = (start.getX() + end.getX()) / 2.0;
        double controlZ = (start.getZ() + end.getZ()) / 2.0;
        if (horizontalDistance > 0.001) {
            int offsetRank = (laneSlot + 1) / 2;
            double sideSign = laneSlot % 2 == 0 ? 1.0 : -1.0;
            double sideOffset = offsetRank * LANE_SIDE_OFFSET * sideSign;
            controlX += (start.getZ() - end.getZ()) / horizontalDistance * sideOffset;
            controlZ += (end.getX() - start.getX()) / horizontalDistance * sideOffset;
        }
        Point3D control = new Point3D(
                controlX,
                controlY,
                controlZ);

        return buildGeometry(quadratic(start, control, end, CURVE_STEPS), radius, material);
    }

    private static Group buildSelfLoop(SceneBuilder3D.EdgeTarget target,
                                       double safeCeilingY,
                                       int lane,
                                       double radius,
                                       PhongMaterial material) {
        Point3D base = anchor(target);
        int laneSlot = Math.floorMod(lane, LANE_COUNT);
        double loopRadius = 42.0 + laneSlot * 5.0;
        double topY = Math.min(safeCeilingY - laneSlot * LANE_LIFT, base.getY() - MIN_LIFT);
        List<Point3D> points = new ArrayList<>();
        for (int i = 0; i <= CURVE_STEPS; i++) {
            double a = Math.toRadians(35 + (290.0 * i / CURVE_STEPS));
            double x = base.getX() + Math.cos(a) * loopRadius;
            double z = base.getZ() + Math.sin(a) * loopRadius;
            double y = base.getY() + (topY - base.getY()) * Math.sin(Math.PI * i / CURVE_STEPS);
            points.add(new Point3D(x, y, z));
        }
        return buildGeometry(points, radius, material);
    }

    private static Point3D anchor(SceneBuilder3D.EdgeTarget target) {
        return new Point3D(target.centerX(), target.topY() - END_CLEARANCE, target.centerZ());
    }

    private static List<Point3D> quadratic(Point3D start, Point3D control, Point3D end, int steps) {
        List<Point3D> points = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double u = 1.0 - t;
            points.add(new Point3D(
                    u * u * start.getX() + 2 * u * t * control.getX() + t * t * end.getX(),
                    u * u * start.getY() + 2 * u * t * control.getY() + t * t * end.getY(),
                    u * u * start.getZ() + 2 * u * t * control.getZ() + t * t * end.getZ()));
        }
        return points;
    }

    private static Group buildGeometry(List<Point3D> points, double radius, PhongMaterial material) {
        Group group = new Group();
        group.setMouseTransparent(true);
        if (points.size() >= 2) {
            group.getChildren().add(tube(points, radius, material));
            Point3D end = points.get(points.size() - 1);
            Point3D beforeEnd = points.get(points.size() - 2);
            MeshView cone = cone(ARROW_HEAD_LENGTH, ARROW_HEAD_RADIUS, material);
            orientAlong(cone, beforeEnd, end);
            Point3D dir = end.subtract(beforeEnd).normalize();
            cone.setTranslateX(end.getX() - dir.getX() * ARROW_HEAD_LENGTH / 2.0);
            cone.setTranslateY(end.getY() - dir.getY() * ARROW_HEAD_LENGTH / 2.0);
            cone.setTranslateZ(end.getZ() - dir.getZ() * ARROW_HEAD_LENGTH / 2.0);
            group.getChildren().add(cone);
        }
        return group;
    }

    private static MeshView tube(List<Point3D> points, double radius, PhongMaterial material) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);

        for (int i = 0; i < points.size(); i++) {
            Point3D center = points.get(i);
            Point3D tangent = tangent(points, i);
            Point3D normal = normalFor(tangent);
            Point3D binormal = tangent.crossProduct(normal).normalize();
            for (int j = 0; j < TUBE_SEGMENTS; j++) {
                double a = 2.0 * Math.PI * j / TUBE_SEGMENTS;
                Point3D p = center
                        .add(normal.multiply(Math.cos(a) * radius))
                        .add(binormal.multiply(Math.sin(a) * radius));
                mesh.getPoints().addAll((float) p.getX(), (float) p.getY(), (float) p.getZ());
            }
        }

        for (int i = 0; i < points.size() - 1; i++) {
            for (int j = 0; j < TUBE_SEGMENTS; j++) {
                int next = (j + 1) % TUBE_SEGMENTS;
                int a = i * TUBE_SEGMENTS + j;
                int b = i * TUBE_SEGMENTS + next;
                int c = (i + 1) * TUBE_SEGMENTS + j;
                int d = (i + 1) * TUBE_SEGMENTS + next;
                mesh.getFaces().addAll(a, 0, c, 0, b, 0);
                mesh.getFaces().addAll(b, 0, c, 0, d, 0);
            }
        }

        MeshView shaft = new MeshView(mesh);
        shaft.setCullFace(CullFace.NONE);
        shaft.setMaterial(material);
        shaft.setMouseTransparent(true);
        return shaft;
    }

    private static Point3D tangent(List<Point3D> points, int index) {
        Point3D before = points.get(Math.max(0, index - 1));
        Point3D after = points.get(Math.min(points.size() - 1, index + 1));
        Point3D direction = after.subtract(before);
        if (direction.magnitude() < 0.001) {
            return Y_AXIS.multiply(-1);
        }
        return direction.normalize();
    }

    private static Point3D normalFor(Point3D tangent) {
        Point3D reference = Math.abs(tangent.dotProduct(Y_AXIS)) > 0.92 ? X_AXIS : Y_AXIS;
        Point3D normal = tangent.crossProduct(reference);
        if (normal.magnitude() < 0.001) {
            normal = tangent.crossProduct(Z_AXIS);
        }
        return normal.normalize();
    }

    private static void orientAlong(javafx.scene.Node node, Point3D start, Point3D end) {
        Point3D mid = start.midpoint(end);
        node.setTranslateX(mid.getX());
        node.setTranslateY(mid.getY());
        node.setTranslateZ(mid.getZ());
        Point3D direction = end.subtract(start).normalize();
        Point3D axis = Y_AXIS.crossProduct(direction);
        double dot = clamp(Y_AXIS.dotProduct(direction), -1.0, 1.0);
        double angle = Math.toDegrees(Math.acos(dot));
        if (axis.magnitude() < 0.0001) {
            if (dot < 0) {
                node.getTransforms().add(new Rotate(180, Rotate.X_AXIS));
            }
            return;
        }
        node.getTransforms().add(new Rotate(angle, axis));
    }

    private static MeshView cone(double height, double radius, PhongMaterial material) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        mesh.getPoints().addAll(0, (float) (height / 2.0), 0);
        for (int i = 0; i < CONE_SEGMENTS; i++) {
            double a = 2.0 * Math.PI * i / CONE_SEGMENTS;
            mesh.getPoints().addAll(
                    (float) (Math.cos(a) * radius),
                    (float) (-height / 2.0),
                    (float) (Math.sin(a) * radius));
        }
        for (int i = 0; i < CONE_SEGMENTS; i++) {
            int next = i == CONE_SEGMENTS - 1 ? 1 : i + 2;
            mesh.getFaces().addAll(0, 0, i + 1, 0, next, 0);
        }
        MeshView cone = new MeshView(mesh);
        cone.setCullFace(CullFace.NONE);
        cone.setMaterial(material);
        return cone;
    }

    private static PhongMaterial material(Color color) {
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        material.setSpecularPower(64.0);
        return material;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
