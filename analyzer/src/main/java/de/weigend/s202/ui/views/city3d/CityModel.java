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
package de.weigend.s202.ui.views.city3d;

import java.util.List;

/**
 * Renderer-agnostic, JSON-serialisable snapshot of the analysed architecture,
 * consumed by the City3D (Three.js) renderer. This is the stable data contract
 * between the Java analysis pipeline and the JavaScript city renderer; see
 * {@code docs/exploration/CITY3D_WEBVIEW_SPEC.md} §5.
 *
 * <p>Districts are packages, buildings are classes. {@code footprint} carries the
 * 2D layout rectangle when it is available (reused from the 2D architecture view);
 * it is {@code null} in headless exports, in which case the client computes a
 * level-row layout itself.
 */
public record CityModel(int maxLevel,
                        List<District> districts,
                        List<Building> buildings,
                        List<Dependency> dependencies) {

    /** 2D layout rectangle in the architecture view's unscaled layout space. */
    public record Footprint(double x, double y, double w, double h) {}

    /** A package. */
    public record District(String fullName,
                           String simpleName,
                           String parentFullName,
                           int architectureLevel,
                           int localLevel,
                           int nestingDepth,
                           int horizontalOrder,
                           boolean inCycle,
                           Footprint footprint) {}

    /** A class or interface. */
    public record Building(String fullName,
                           String simpleName,
                           String districtFullName,
                           int architectureLevel,
                           int localLevel,
                           int nestingDepth,
                           int horizontalOrder,
                           Footprint footprint,
                           int fanIn,
                           int fanOut,
                           int methodCount,
                           boolean isInterface,
                           boolean inCycle) {}

    /**
     * A directed class-to-class dependency (both endpoints are fully qualified names).
     *
     * <p>{@code violation} marks an architectural violation exactly as the layered
     * architecture defines it: an UPWARD edge by hierarchical <em>visual rank</em>
     * (the chain of package levels from the outermost ancestor down to the class),
     * the same rule the 2D architecture view uses. So the City3D "violations" overlay
     * matches the 2D "Verstöße" one-to-one. This includes package-tangle back-edges
     * — e.g. a class edge {@code sub1.A → sub2.C} that realises the upward package
     * edge {@code sub1 → sub2} — which a flat per-class {@code architectureLevel}
     * comparison would miss (A may sit above C by flat level yet below it by rank).
     */
    public record Dependency(String from, String to, boolean violation) {}
}
