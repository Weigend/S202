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
package de.weigend.s202.ui.city3d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import javafx.geometry.Bounds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Builds the city model from an architecture tree.
     *
     * @param root       the architecture root (its direct children are depth 0)
     * @param footprints 2D layout bounds keyed by full name, or {@code null} for
     *                   a headless export (the client then lays the city out)
     */
    public CityModel build(ArchitectureNode root, Map<String, Bounds> footprints) {
        List<CityModel.District> districts = new ArrayList<>();
        List<CityModel.Building> buildings = new ArrayList<>();
        List<CityModel.Dependency> dependencies = new ArrayList<>();
        int maxLevel = 0;

        if (root != null) {
            for (ArchitectureNode child : root.getChildren()) {
                maxLevel = Math.max(maxLevel,
                        collect(child, null, 0, footprints, districts, buildings, dependencies));
            }
        }
        return new CityModel(maxLevel, districts, buildings, dependencies);
    }

    /** @return the maximum architecture level found in this subtree. */
    private int collect(ArchitectureNode node,
                        String parentPackageFqn,
                        int depth,
                        Map<String, Bounds> footprints,
                        List<CityModel.District> districts,
                        List<CityModel.Building> buildings,
                        List<CityModel.Dependency> dependencies) {

        int level = node.getArchitectureLevel();
        int maxLevel = Math.max(0, level);
        CityModel.Footprint fp = footprint(footprints, node.getFullName());

        if (node.getType() == NodeType.PACKAGE) {
            districts.add(new CityModel.District(
                    node.getFullName(), node.getSimpleName(), parentPackageFqn,
                    level, depth, node.getHorizontalLayoutOrder(), fp));
            String childParent = node.getFullName();
            for (ArchitectureNode child : node.getChildren()) {
                maxLevel = Math.max(maxLevel,
                        collect(child, childParent, depth + 1, footprints, districts, buildings, dependencies));
            }
        } else {
            buildings.add(new CityModel.Building(
                    node.getFullName(), node.getSimpleName(), parentPackageFqn,
                    level, depth, node.getHorizontalLayoutOrder(), fp,
                    node.getDependents().size(), node.getDependencies().size(), node.isInterfaceType()));
            for (String to : node.getDependencies()) {
                dependencies.add(new CityModel.Dependency(node.getFullName(), to));
            }
            // Classes may still nest (inner types); keep walking.
            for (ArchitectureNode child : node.getChildren()) {
                maxLevel = Math.max(maxLevel,
                        collect(child, parentPackageFqn, depth + 1, footprints, districts, buildings, dependencies));
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

    public String toJson(ArchitectureNode root, Map<String, Bounds> footprints) {
        try {
            return mapper.writeValueAsString(build(root, footprints));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise city model", e);
        }
    }

    public void writeTo(Path target, ArchitectureNode root, Map<String, Bounds> footprints) throws IOException {
        Files.writeString(target, toJson(root, footprints));
    }
}
