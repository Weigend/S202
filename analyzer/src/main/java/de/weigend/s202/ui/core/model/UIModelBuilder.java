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
package de.weigend.s202.ui.core.model;

import de.weigend.s202.domain.DomainModel;

import java.util.*;

/**
 * Builds UI-friendly model: List<List<UIElementInfo>> organized by level.
 */
public class UIModelBuilder {

    /**
     * Builds a UIModel from a DomainModel.
     */
    public UIModel build(DomainModel calculatedModel) {
        UIModel uiModel = new UIModel();

        // Find maximum level
        int maxLevel = calculatedModel.getMaxLevel();

        // Initialize lists for each level
        List<List<UIModel.UIElementInfo>> allLevels = new ArrayList<>();
        for (int i = 0; i <= maxLevel; i++) {
            allLevels.add(new ArrayList<>());
        }

        // Add classes to their levels
        for (DomainModel.CalculatedElementInfo classInfo : calculatedModel.getAllClasses().values()) {
            UIModel.UIElementInfo uiElement = new UIModel.UIElementInfo(
                classInfo.fullName,
                classInfo.simpleName,
                classInfo.type,
                classInfo.architectureLevel,
                new HashSet<>(classInfo.dependencies),
                new HashSet<>(classInfo.dependents)
            );
            allLevels.get(classInfo.architectureLevel).add(uiElement);
        }

        // Add packages to their levels
        for (DomainModel.CalculatedElementInfo pkgInfo : calculatedModel.getAllPackages().values()) {
            UIModel.UIElementInfo uiElement = new UIModel.UIElementInfo(
                pkgInfo.fullName,
                pkgInfo.simpleName,
                pkgInfo.type,
                pkgInfo.architectureLevel,
                new HashSet<>(pkgInfo.dependencies),
                new HashSet<>(pkgInfo.dependents)
            );
            allLevels.get(pkgInfo.architectureLevel).add(uiElement);
        }

        // Sort each level alphabetically
        for (List<UIModel.UIElementInfo> level : allLevels) {
            level.sort(Comparator.comparing(e -> e.fullName));
        }

        uiModel.setElementsByLevel(allLevels);
        return uiModel;
    }
}
