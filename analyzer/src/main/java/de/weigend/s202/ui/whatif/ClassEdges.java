package de.weigend.s202.ui.whatif;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.DependencyModel.ClassInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridge from the static {@link DependencyModel} to a flat list of
 * {@link ClassEdge}s the What-If layer can iterate. Built once after
 * each analysis; never mutated by drops.
 *
 * <p>Phase 3 MVP uses {@code weight = 1} per class-to-class edge, which
 * matches the "↑ N" badge semantics (number of distinct upward class
 * edges). True method-call weighting can layer on top later if the level
 * back-edge heuristic needs it.
 */
public final class ClassEdges {

    private ClassEdges() {}

    public static List<ClassEdge> fromDependencyModel(DependencyModel model) {
        if (model == null) {
            return List.of();
        }
        Map<String, ClassInfo> classes = model.getAllClasses();
        List<ClassEdge> edges = new ArrayList<>();
        for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
            String source = entry.getKey();
            for (String target : entry.getValue().dependencies) {
                edges.add(new ClassEdge(source, target, 1));
            }
        }
        return edges;
    }
}
