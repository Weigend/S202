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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import javafx.geometry.Bounds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serialises an {@link ArchitectureNode} tree into the {@link CityModel} JSON
 * contract consumed by the City3D (Three.js) renderer.
 *
 * <p>Pure computation, no JavaFX toolkit required (the {@link Bounds} footprint
 * map, when supplied, is only read). Reuses exactly the inputs the 2D/3D views
 * already produce: the tree plus the optional 2D footprint bounds. The
 * expensive analysis (reader, SCC, level calculation) stays upstream in Java —
 * this only maps its result to the renderer contract.
 */
public final class CityModelSerializer {

    /**
     * Optional per-element metric source. Kept separate from the tree because
     * these values live on the {@code DependencyModel}/{@code DomainModel}, not
     * on {@link ArchitectureNode}. All methods must tolerate unknown names.
     */
    public interface Metrics {
        /** @return the class's method count, or {@code -1} if unknown. */
        int methodCount(String fqn);

        /** @return whether the class is involved in an architectural cycle. */
        boolean classInCycle(String fqn);

        /** @return whether the package is part of a package tangle. */
        boolean packageInCycle(String fqn);

        Metrics NONE = new Metrics() {
            public int methodCount(String fqn) { return -1; }
            public boolean classInCycle(String fqn) { return false; }
            public boolean packageInCycle(String fqn) { return false; }
        };
    }

    /**
     * Builds a {@link Metrics} adapter from the computed models: method count per
     * class (from the raw {@link DependencyModel}) and cycle membership (from the
     * {@link DomainModel} class back-edges and package tangles).
     */
    public static Metrics metricsFrom(DependencyModel raw, DomainModel calculated) {
        Set<String> classCycle = new HashSet<>();
        for (DependencyEdge e : calculated.getClassBackEdges()) {
            classCycle.add(e.from());
            classCycle.add(e.to());
        }
        Set<String> packageCycle = new HashSet<>();
        for (Set<String> tangle : calculated.getPackageTangles()) {
            packageCycle.addAll(tangle);
        }
        return new Metrics() {
            @Override public int methodCount(String fqn) {
                DependencyModel.ClassInfo ci = raw.getClass(fqn);
                return ci == null ? -1 : ci.methods.size();
            }
            @Override public boolean classInCycle(String fqn) { return classCycle.contains(fqn); }
            @Override public boolean packageInCycle(String fqn) { return packageCycle.contains(fqn); }
        };
    }

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public CityModel build(ArchitectureNode root, Map<String, Bounds> footprints) {
        return build(root, footprints, Metrics.NONE);
    }

    /**
     * Builds the city model from an architecture tree.
     *
     * @param root       the architecture root (its direct children are depth 0)
     * @param footprints 2D layout bounds keyed by full name, or {@code null} for
     *                   a headless export (the client then lays the city out)
     * @param metrics    per-element metric source; {@link Metrics#NONE} if none
     */
    public CityModel build(ArchitectureNode root, Map<String, Bounds> footprints, Metrics metrics) {
        Metrics m = metrics == null ? Metrics.NONE : metrics;
        List<CityModel.District> districts = new ArrayList<>();
        List<CityModel.Building> buildings = new ArrayList<>();
        List<CityModel.Dependency> dependencies = new ArrayList<>();
        int maxLevel = 0;

        if (root != null) {
            for (ArchitectureNode child : root.getChildren()) {
                maxLevel = Math.max(maxLevel,
                        collect(child, null, 0, footprints, m, districts, buildings, dependencies));
            }
        }
        return new CityModel(maxLevel, districts, buildings, dependencies);
    }

    /** @return the maximum architecture level found in this subtree. */
    private int collect(ArchitectureNode node,
                        String parentPackageFqn,
                        int depth,
                        Map<String, Bounds> footprints,
                        Metrics m,
                        List<CityModel.District> districts,
                        List<CityModel.Building> buildings,
                        List<CityModel.Dependency> dependencies) {

        int level = node.getArchitectureLevel();
        int maxLevel = Math.max(0, level);
        String fqn = node.getFullName();
        CityModel.Footprint fp = footprint(footprints, fqn);

        if (node.getType() == NodeType.PACKAGE) {
            districts.add(new CityModel.District(
                    fqn, node.getSimpleName(), parentPackageFqn,
                    level, node.getLevel(), depth, node.getHorizontalLayoutOrder(),
                    m.packageInCycle(fqn), fp));
            for (ArchitectureNode child : node.getChildren()) {
                maxLevel = Math.max(maxLevel,
                        collect(child, fqn, depth + 1, footprints, m, districts, buildings, dependencies));
            }
        } else {
            buildings.add(new CityModel.Building(
                    fqn, node.getSimpleName(), parentPackageFqn,
                    level, node.getLevel(), depth, node.getHorizontalLayoutOrder(), fp,
                    node.getDependents().size(), node.getDependencies().size(),
                    m.methodCount(fqn), node.isInterfaceType(), m.classInCycle(fqn)));
            for (String to : node.getDependencies()) {
                dependencies.add(new CityModel.Dependency(fqn, to));
            }
            // Classes may still nest (inner types); keep walking.
            for (ArchitectureNode child : node.getChildren()) {
                maxLevel = Math.max(maxLevel,
                        collect(child, parentPackageFqn, depth + 1, footprints, m, districts, buildings, dependencies));
            }
        }
        return maxLevel;
    }

    private static CityModel.Footprint footprint(Map<String, Bounds> footprints, String fqn) {
        if (footprints == null) return null;
        Bounds b = footprints.get(fqn);
        if (b == null) return null;
        return new CityModel.Footprint(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
    }

    public String toJson(CityModel model) {
        try {
            return mapper.writeValueAsString(model);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise city model", e);
        }
    }

    public String toJson(ArchitectureNode root, Map<String, Bounds> footprints, Metrics metrics) {
        try {
            return mapper.writeValueAsString(build(root, footprints, metrics));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise city model", e);
        }
    }

    public void writeTo(Path target, ArchitectureNode root,
                        Map<String, Bounds> footprints, Metrics metrics) throws IOException {
        Files.writeString(target, toJson(root, footprints, metrics));
    }
}
