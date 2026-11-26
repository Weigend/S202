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

import de.weigend.s202.analysis.metrics.Metric;
import de.weigend.s202.analysis.metrics.MetricCatalog;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The values of the metrics for the boxes of a picture. A package counts the
 * classes the view puts into it: after moves, and in planned packages too.
 */
final class MetricSource {

    /** No values: every metric unknown. */
    static final MetricSource NONE = new MetricSource(null, null, null);

    private final DependencyModel model;
    private final ArchitectureNode root;
    private final MetricCatalog catalog;
    private final Map<String, List<String>> members = new HashMap<>();
    private final Map<Metric, Map<String, Double>> packageValues = new EnumMap<>(Metric.class);
    private final Map<Metric, Map<String, Double>> highestValues = new EnumMap<>(Metric.class);

    private MetricSource(DependencyModel model, ArchitectureNode root, MetricCatalog catalog) {
        this.model = model;
        this.root = root;
        this.catalog = catalog;
    }

    /** The values for the picture of a view with this model and tree. */
    static MetricSource of(DependencyModel model, ArchitectureNode root) {
        if (model == null || root == null) {
            return NONE;
        }
        Map<String, Integer> levels = new HashMap<>();
        collectLevels(root, levels);
        MetricSource source = new MetricSource(model, root,
                new MetricCatalog(model, name -> levels.getOrDefault(name, -1)));
        source.collectMembers(root);
        return source;
    }

    /** Whether this source was made for the model and the tree; then it can be kept. */
    boolean isFor(DependencyModel otherModel, ArchitectureNode otherRoot) {
        return model == otherModel && root == otherRoot;
    }

    boolean isAvailable(Metric metric) {
        return catalog != null && catalog.isAvailable(metric);
    }

    /** The value of the box; {@code NaN} if it cannot be known. */
    double value(Metric metric, TopViewModel.Box box) {
        if (catalog == null || metric == null) {
            return Double.NaN;
        }
        if (metric == Metric.LEVEL) {
            return box.level() < 0 ? Double.NaN : box.level();
        }
        if (!box.isPackage()) {
            return catalog.classValue(metric, box.fqn());
        }
        return packageValues.computeIfAbsent(metric, m -> new HashMap<>()).computeIfAbsent(box.fqn(),
                name -> catalog.groupValue(metric, name, members.getOrDefault(name, List.of())));
    }

    /** The largest value of the classes in the package of the box; {@code NaN} if none is known. */
    double highestClass(Metric metric, TopViewModel.Box box) {
        if (catalog == null || metric == null) {
            return Double.NaN;
        }
        return highestValues.computeIfAbsent(metric, m -> new HashMap<>()).computeIfAbsent(box.fqn(), name -> {
            double highest = Double.NaN;
            for (String member : members.getOrDefault(name, List.of())) {
                double value = catalog.classValue(metric, member);
                if (!Double.isNaN(value) && (Double.isNaN(highest) || value > highest)) {
                    highest = value;
                }
            }
            return highest;
        });
    }

    private static void collectLevels(ArchitectureNode node, Map<String, Integer> levels) {
        levels.put(node.getFullName(), node.getArchitectureLevel());
        node.getChildren().forEach(child -> collectLevels(child, levels));
    }

    /** The classes under every package of the tree. */
    private List<String> collectMembers(ArchitectureNode node) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            return List.of(node.getFullName());
        }
        List<String> classes = new ArrayList<>();
        for (ArchitectureNode child : node.getChildren()) {
            classes.addAll(collectMembers(child));
        }
        members.put(node.getFullName(), classes);
        return classes;
    }
}
