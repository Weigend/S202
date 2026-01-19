package de.weigend.s202.analysis.ui;

import java.util.*;

/**
 * UI-ready model: elements organized into lists by level.
 * Structure: List<List<UIElementInfo>> where outer list = levels, inner list = elements at that level.
 */
public class UIModel {
    private List<List<UIElementInfo>> elementsByLevel = new ArrayList<>();

    /**
     * Information about a UI element (class or package) ready for rendering.
     */
    public static class UIElementInfo {
        public final String fullName;
        public final String simpleName;
        public final String type; // "CLASS" or "PACKAGE"
        public final int level;
        public final Set<String> dependencies;
        public final Set<String> dependents;

        public UIElementInfo(String fullName, String simpleName, String type, int level,
                             Set<String> dependencies, Set<String> dependents) {
            this.fullName = fullName;
            this.simpleName = simpleName;
            this.type = type;
            this.level = level;
            this.dependencies = dependencies;
            this.dependents = dependents;
        }
    }

    // ===== Public API =====

    public void setElementsByLevel(List<List<UIElementInfo>> elementsByLevel) {
        this.elementsByLevel = elementsByLevel;
    }

    public List<List<UIElementInfo>> getAllLevels() {
        return new ArrayList<>(elementsByLevel);
    }

    public List<UIElementInfo> getElementsAtLevel(int level) {
        if (level < 0 || level >= elementsByLevel.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(elementsByLevel.get(level));
    }

    public int getLevelCount() {
        return elementsByLevel.size();
    }

    public int getMaxLevel() {
        return elementsByLevel.isEmpty() ? 0 : elementsByLevel.size() - 1;
    }

    public int getTotalElementCount() {
        return elementsByLevel.stream().mapToInt(List::size).sum();
    }

    public int getElementCountAtLevel(int level) {
        if (level < 0 || level >= elementsByLevel.size()) {
            return 0;
        }
        return elementsByLevel.get(level).size();
    }

    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("UIModel{levels=%d, maxLevel=%d, total=%d}\n",
            getLevelCount(), getMaxLevel(), getTotalElementCount()));

        for (int level = 0; level < elementsByLevel.size(); level++) {
            List<UIElementInfo> elements = elementsByLevel.get(level);
            sb.append(String.format("  Level %d: %d elements\n", level, elements.size()));
        }

        return sb.toString();
    }
}
