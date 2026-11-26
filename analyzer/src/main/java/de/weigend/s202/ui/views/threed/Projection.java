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

/**
 * Parallel projection of the picture: turned about its middle, tilted
 * back, zoomed and moved. The middle of the picture on the ground lies at
 * {@code panX, panY} on screen.
 */
record Projection(double turn, double cosTurn, double sinTurn, double cosTilt, double sinTilt,
                  double zoom, double panX, double panY, double cx, double cy) {

    /**
     * @param tilt degrees tilted back, 0 from above
     * @param turn degrees turned about the vertical axis
     * @param cx   middle of the picture, which it is turned about
     */
    static Projection of(double tilt, double turn, double zoom, double panX, double panY, double cx, double cy) {
        double phi = Math.toRadians(tilt);
        double theta = Math.toRadians(turn);
        return new Projection(turn, Math.cos(theta), Math.sin(theta), Math.cos(phi), Math.sin(phi),
                zoom, panX, panY, cx, cy);
    }

    double sx(double x, double y) {
        return panX + zoom * ((x - cx) * cosTurn - (y - cy) * sinTurn);
    }

    double sy(double x, double y, double height) {
        return panY + zoom * (((x - cx) * sinTurn + (y - cy) * cosTurn) * cosTilt - height * sinTilt);
    }

    /** The transform that draws the plane at {@code height} as the screen shows it. */
    javafx.scene.transform.Affine affine(double height) {
        double mxx = zoom * cosTurn;
        double mxy = -zoom * sinTurn;
        double myx = zoom * cosTilt * sinTurn;
        double myy = zoom * cosTilt * cosTurn;
        double mxt = panX - zoom * (cx * cosTurn - cy * sinTurn);
        double myt = panY - zoom * cosTilt * (cx * sinTurn + cy * cosTurn) - zoom * height * sinTilt;
        return new javafx.scene.transform.Affine(mxx, mxy, mxt, myx, myy, myt);
    }

    /** The point of the plane at {@code height} that is seen at the screen point. */
    double[] plane(double sx, double sy, double height) {
        double u = (sx - panX) / zoom;
        double v = ((sy - panY) / zoom + height * sinTilt) / Math.max(1e-6, cosTilt);
        return new double[]{cx + u * cosTurn + v * sinTurn, cy - u * sinTurn + v * cosTurn};
    }

    /** The point of the ground at the screen point. */
    double[] ground(double sx, double sy) {
        double u = (sx - panX) / zoom;
        double v = (sy - panY) / (zoom * Math.max(1e-6, cosTilt));
        return new double[]{cx + u * cosTurn + v * sinTurn, cy - u * sinTurn + v * cosTurn};
    }
}
