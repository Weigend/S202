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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.ui.core.graph.ArchitectureDragController;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Static helpers shared by the tree builders in this package. Keeps the
 * top-level container/row plumbing, the transparent-package skipping and
 * small FQN utilities in one place instead of duplicating them per builder.
 */
public final class TreeBuilderSupport {

    // 60px gives enough room for up to 6 bypass lanes (24 base + 5×6px) before
    // they leave the visible content area.
    public static final double TOP_LEVEL_HORIZONTAL_PADDING = 60.0;
    // Seven 6px-spaced edge lanes need 36px span plus arrow-head clearance.
    public static final double TOP_LEVEL_VERTICAL_PADDING = 52.0;

    private TreeBuilderSupport() {
    }

    /**
     * Checks if a package should be displayed as transparent.
     * A package is transparent if it is the ONLY sub-package of its parent.
     * This visually de-emphasizes "pass-through" packages like de.weigend.s202.
     */
    public static boolean shouldChildrenBeTransparent(ArchitectureNode parentNode) {
        // Count how many sub-packages the parent has
        long packageCount = parentNode.getChildren().stream()
                .filter(c -> c.getType() == NodeType.PACKAGE)
                .count();
        // Children are transparent only if there's exactly one sub-package
        return packageCount == 1;
    }

    /**
     * Skips chains of transparent top-level packages (de, weigend, s202, etc.)
     * and returns the first "real" package as effective root. Every skipped
     * package is reported to {@code skippedPackageSink} so callers can keep
     * dependency lookups working for the hidden wrappers.
     *
     * @param skipTransparentTopLevelPackages When false the root is returned
     *        unchanged; scope views use this so the selected package remains
     *        visible as the root of the scoped chart.
     */
    public static ArchitectureNode effectiveRoot(ArchitectureNode rootNode,
                                          boolean skipTransparentTopLevelPackages,
                                          Consumer<ArchitectureNode> skippedPackageSink) {
        ArchitectureNode effectiveRoot = rootNode;
        while (skipTransparentTopLevelPackages && shouldChildrenBeTransparent(effectiveRoot)) {
            ArchitectureNode singleChild = effectiveRoot.getChildren().stream()
                    .filter(c -> c.getType() == NodeType.PACKAGE)
                    .findFirst().orElse(null);
            if (singleChild == null) break;
            if (skippedPackageSink != null) {
                skippedPackageSink.accept(singleChild);
            }
            effectiveRoot = singleChild;
        }
        return effectiveRoot;
    }

    /**
     * Tags the top-level stack with the effective root's fqcn so the
     * What-If drop handler can resolve a "dropped at top level" event.
     */
    public static void tagWhatIfRoot(VBox topLevelContainer, ArchitectureNode effectiveRoot) {
        topLevelContainer.getProperties().put("s202.whatif.rootFqcn",
                effectiveRoot.getFullName() == null ? "" : effectiveRoot.getFullName());
    }

    /**
     * Creates the padded, drag-aware VBox that stacks the top-level rows.
     */
    public static VBox createTopLevelContainer(double spacing, String backgroundColor) {
        VBox topLevelContainer = new VBox(spacing);
        topLevelContainer.setPadding(new Insets(
                TOP_LEVEL_VERTICAL_PADDING,
                TOP_LEVEL_HORIZONTAL_PADDING,
                TOP_LEVEL_VERTICAL_PADDING,
                TOP_LEVEL_HORIZONTAL_PADDING));
        topLevelContainer.setStyle("-fx-background-color: " + backgroundColor + ";");
        ArchitectureDragController.markAsRowStack(topLevelContainer);
        return topLevelContainer;
    }

    /**
     * Creates one top-level row (same level = side by side) and appends it to
     * the container.
     */
    public static HBox createTopLevelRow(VBox topLevelContainer, double spacing) {
        HBox hbox = new HBox(spacing);
        hbox.setMaxWidth(Double.MAX_VALUE);
        hbox.setAlignment(Pos.CENTER);
        VBox.setVgrow(hbox, Priority.ALWAYS);
        ArchitectureDragController.markAsRow(hbox);
        topLevelContainer.getChildren().add(hbox);
        return hbox;
    }

    /**
     * Extract parent package name from a fully qualified name.
     */
    public static String parentOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }

    /**
     * Simple (last segment) name of a fully qualified name.
     */
    public static String simpleName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
