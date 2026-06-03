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
package de.weigend.s202.ui.model;

import java.util.Objects;
import java.util.Set;

/**
 * Copy helpers for {@link ArchitectureNode} trees.
 */
public final class ArchitectureNodeCloner {

    private ArchitectureNodeCloner() {
    }

    public static ArchitectureNode cloneTree(ArchitectureNode source) {
        ArchitectureNode clone = cloneShallow(source);
        for (ArchitectureNode child : source.getChildren()) {
            clone.addChild(cloneTree(child));
        }
        return clone;
    }

    public static ArchitectureNode cloneTreeExcluding(ArchitectureNode source, Set<String> excludedFullNames) {
        Objects.requireNonNull(excludedFullNames, "excludedFullNames cannot be null");

        ArchitectureNode clone = cloneShallow(source);
        for (ArchitectureNode child : source.getChildren()) {
            if (!excludedFullNames.contains(child.getFullName())) {
                clone.addChild(cloneTreeExcluding(child, excludedFullNames));
            }
        }
        return clone;
    }

    public static ArchitectureNode cloneShallow(ArchitectureNode source) {
        Objects.requireNonNull(source, "source cannot be null");

        ArchitectureNode clone = new ArchitectureNode(
                source.getFullName(),
                source.getSimpleName(),
                source.getType(),
                source.isAutoExpanded(),
                source.getLevel(),
                source.isInterfaceType());
        clone.setArchitectureLevel(source.getArchitectureLevel());
        clone.setHorizontalLayoutOrder(source.getHorizontalLayoutOrder());
        clone.setDependencies(source.getDependencies());
        clone.setDependents(source.getDependents());
        return clone;
    }
}
