package de.weigend.s202.ui.model;

import de.weigend.s202.analysis.domain.DomainModel;

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
                classInfo.level,
                new HashSet<>(classInfo.dependencies),
                new HashSet<>(classInfo.dependents)
            );
            allLevels.get(classInfo.level).add(uiElement);
        }

        // Add packages to their levels
        for (DomainModel.CalculatedElementInfo pkgInfo : calculatedModel.getAllPackages().values()) {
            UIModel.UIElementInfo uiElement = new UIModel.UIElementInfo(
                pkgInfo.fullName,
                pkgInfo.simpleName,
                pkgInfo.type,
                pkgInfo.level,
                new HashSet<>(pkgInfo.dependencies),
                new HashSet<>(pkgInfo.dependents)
            );
            allLevels.get(pkgInfo.level).add(uiElement);
        }

        // Sort each level alphabetically
        for (List<UIModel.UIElementInfo> level : allLevels) {
            level.sort(Comparator.comparing(e -> e.fullName));
        }

        uiModel.setElementsByLevel(allLevels);
        return uiModel;
    }
}
