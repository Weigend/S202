package de.weigend.s202.ui.rendering.circuit;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Permutes children of each level-row inside every {@link LevelPackageBox}
 * to minimise dependency-edge crossings. Uses a simple barycentric heuristic
 * over a few iterations.
 *
 * <p>Only the order of children inside a row is changed. The mapping of
 * classes to rows (their level) is produced by the upstream layout and
 * remains untouched. SCC groups remain grouped as a side-effect of the
 * underlying HBox order already reflecting them.
 */
public final class ClassReorderer {

    private static final int ITERATIONS = 6;

    private final Map<String, ArchitectureNode> nodeIndex;
    private final Map<String, Node> elementRegistry;

    public ClassReorderer(ArchitectureNode root, Map<String, Node> elementRegistry) {
        this.nodeIndex = new HashMap<>();
        indexNodes(root);
        this.elementRegistry = elementRegistry;
    }

    private void indexNodes(ArchitectureNode n) {
        if (n == null) return;
        nodeIndex.put(n.getFullName(), n);
        for (ArchitectureNode c : n.getChildren()) indexNodes(c);
    }

    /** Walks the UI tree and reorders each level-row inside every package box. */
    public void reorder(Node contentRoot) {
        List<LevelPackageBox> packages = new ArrayList<>();
        collectPackages(contentRoot, packages);
        for (LevelPackageBox pkg : packages) {
            reorderPackage(pkg);
        }
    }

    private void reorderPackage(LevelPackageBox pkg) {
        VBox content = findContentContainer(pkg);
        if (content == null) return;

        List<HBox> rows = new ArrayList<>();
        for (Node child : content.getChildren()) {
            if (child instanceof HBox h) rows.add(h);
        }
        if (rows.size() < 2) return; // nothing to optimize with a single row

        // Aggregate class-name sets for each direct child of every row so
        // we can look up who is connected to whom in other rows of the pkg.
        Map<Node, Set<String>> childClassNames = new HashMap<>();
        for (HBox row : rows) {
            for (Node child : row.getChildren()) {
                childClassNames.put(child, collectClassNames(child));
            }
        }

        for (int it = 0; it < ITERATIONS; it++) {
            for (HBox row : rows) {
                reorderRow(row, rows, childClassNames);
            }
        }
    }

    private void reorderRow(HBox row, List<HBox> allRows, Map<Node, Set<String>> childClassNames) {
        List<Node> children = new ArrayList<>(row.getChildren());
        if (children.size() < 2) return;

        // Build position-by-class map over all OTHER rows
        Map<String, Double> classToIndex = new HashMap<>();
        for (HBox other : allRows) {
            if (other == row) continue;
            List<Node> otherChildren = other.getChildren();
            for (int i = 0; i < otherChildren.size(); i++) {
                Node oc = otherChildren.get(i);
                Set<String> names = childClassNames.get(oc);
                if (names == null) continue;
                double idx = i;
                for (String n : names) classToIndex.putIfAbsent(n, idx);
            }
        }

        Map<Node, Double> barycenter = new HashMap<>();
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            Set<String> myClasses = childClassNames.get(child);
            if (myClasses == null || myClasses.isEmpty()) {
                barycenter.put(child, (double) i);
                continue;
            }
            double sum = 0;
            int count = 0;
            for (String myClass : myClasses) {
                ArchitectureNode an = nodeIndex.get(myClass);
                if (an == null) continue;
                for (String dep : an.getDependencies()) {
                    Double pos = classToIndex.get(dep);
                    if (pos != null) { sum += pos; count++; }
                }
                for (String dep : an.getDependents()) {
                    Double pos = classToIndex.get(dep);
                    if (pos != null) { sum += pos; count++; }
                }
            }
            if (count == 0) {
                barycenter.put(child, (double) i);
            } else {
                barycenter.put(child, sum / count);
            }
        }

        children.sort(Comparator
            .comparingDouble((Node n) -> barycenter.get(n))
            .thenComparing((Node n) -> stableKey(n)));
        row.getChildren().setAll(children);
    }

    private static String stableKey(Node n) {
        if (n instanceof LevelClassBox lcb) return lcb.getText();
        if (n instanceof LevelPackageBox lpb) return lpb.toString();
        return n.toString();
    }

    /** Returns the full class names represented by (or contained in) a row child. */
    private Set<String> collectClassNames(Node child) {
        Set<String> out = new HashSet<>();
        if (child instanceof LevelClassBox lcb) {
            String fn = classBoxFullName(lcb);
            if (fn != null) out.add(fn);
            return out;
        }
        if (child instanceof LevelPackageBox pkg) {
            collectClassNamesRecursive(pkg, out);
        }
        return out;
    }

    private void collectClassNamesRecursive(Node n, Set<String> out) {
        if (n instanceof LevelClassBox lcb) {
            String fn = classBoxFullName(lcb);
            if (fn != null) out.add(fn);
            return;
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                collectClassNamesRecursive(c, out);
            }
        }
    }

    /** LevelClassBox stores fullClassName as a private field — reach it via elementRegistry reverse lookup. */
    private String classBoxFullName(LevelClassBox box) {
        // elementRegistry maps fullName -> Node; reverse-search is O(n) but called rarely
        for (Map.Entry<String, Node> e : elementRegistry.entrySet()) {
            if (e.getValue() == box) return e.getKey();
        }
        // Fallback via reflection (private field)
        try {
            Field f = LevelClassBox.class.getDeclaredField("fullClassName");
            f.setAccessible(true);
            Object v = f.get(box);
            return v != null ? v.toString() : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static VBox findContentContainer(LevelPackageBox pkg) {
        try {
            Field f = LevelPackageBox.class.getDeclaredField("contentContainer");
            f.setAccessible(true);
            Object v = f.get(pkg);
            return v instanceof VBox vb ? vb : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static void collectPackages(Node node, List<LevelPackageBox> out) {
        if (node == null) return;
        if (node instanceof LevelPackageBox pkg) out.add(pkg);
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collectPackages(c, out);
        }
    }
}
