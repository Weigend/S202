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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.architecture.LocalLevelCalculator;
import de.weigend.s202.reader.DependencyModel;

import java.util.Objects;

/**
 * Recalculates local levels for a projected {@link ArchitectureNode} subtree
 * without changing the analyzed domain model.
 */
public final class ArchitectureNodeLocalLevelCalculator {

    public void assign(ArchitectureNode root, DependencyModel rawModel) {
        Objects.requireNonNull(root, "root cannot be null");

        DomainModel projection = new DomainModel();
        addToProjection(root, projection);
        new LocalLevelCalculator().assign(
                projection,
                rawModel == null ? new DependencyModel() : rawModel);
        applyProjectedLevels(root, projection);
    }

    private static void addToProjection(ArchitectureNode node, DomainModel projection) {
        CalculatedElementInfo info = new CalculatedElementInfo(
                node.getFullName(),
                node.getSimpleName(),
                node.getType().name(),
                node.getArchitectureLevel(),
                node.getDependencies(),
                node.isInterfaceType());
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            projection.addClass(node.getFullName(), info);
        } else {
            projection.addPackage(node.getFullName(), info);
        }

        for (ArchitectureNode child : node.getChildren()) {
            addToProjection(child, projection);
        }
    }

    private static void applyProjectedLevels(ArchitectureNode node, DomainModel projection) {
        CalculatedElementInfo info = node.getType() == ArchitectureNode.NodeType.CLASS
                ? projection.getClass(node.getFullName())
                : projection.getPackage(node.getFullName());
        if (info != null) {
            node.setLevel(info.localLevel);
        }
        for (ArchitectureNode child : node.getChildren()) {
            applyProjectedLevels(child, projection);
        }
    }
}
