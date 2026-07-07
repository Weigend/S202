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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import de.weigend.s202.ui.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;

import java.nio.file.Path;

/**
 * Headless Phase-0 exporter for the City3D prototype: runs the plain-Java
 * analysis pipeline on a .jar and writes a {@link CityModel} JSON file that the
 * City3D (Three.js) renderer can load directly in a browser.
 *
 * <p>No JavaFX is involved, so there are no 2D footprints — the client computes
 * a level-row layout itself. This mirrors the pipeline exercised by
 * {@code FullPipelineTest}: {@code InputAnalyzer -> LevelCalculator ->
 * ArchitectureNodeBuilder}.
 *
 * <pre>
 *   Usage: CityModelExporter &lt;input.jar&gt; [output.json]
 *          (output defaults to ./city3d/city.json)
 * </pre>
 */
public final class CityModelExporter {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: CityModelExporter <input.jar> [output.json]");
            System.exit(2);
        }
        String jarPath = args[0];
        Path output = Path.of(args.length >= 2 ? args[1] : "city3d/public/city.json");

        System.out.println("City3D export: analysing " + jarPath);
        DependencyModel raw = new InputAnalyzer().analyze(jarPath);
        DomainModel calculated = new LevelCalculator().calculate(raw);
        ArchitectureNode root = new ArchitectureNodeBuilder().build(calculated);
        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);

        CityModelSerializer.Metrics metrics = CityModelSerializer.metricsFrom(raw, calculated);

        CityModelSerializer serializer = new CityModelSerializer();
        CityModel model = serializer.build(root, null, metrics); // headless: no footprints
        java.nio.file.Files.writeString(output, serializer.toJson(model));

        long cycleClasses = model.buildings().stream().filter(CityModel.Building::inCycle).count();
        System.out.printf("City3D export: wrote %s%n  maxLevel=%d, districts=%d, buildings=%d, dependencies=%d, classesInCycle=%d%n",
                output.toAbsolutePath(), model.maxLevel(),
                model.districts().size(), model.buildings().size(), model.dependencies().size(), cycleClasses);
    }

    private CityModelExporter() {
    }
}
