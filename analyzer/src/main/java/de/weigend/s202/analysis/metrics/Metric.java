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
package de.weigend.s202.analysis.metrics;

/**
 * What can be measured of a class and of a group of classes, a package.
 * The names are stable: projects store them.
 */
public enum Metric {

    LEVEL("Architecture level", "Level in the layers: how many levels lie below the element.", false),
    SIZE("Size", "Methods of a class, classes of a package.", false),
    LINES("Lines of code", "Lines with code, read from the bytecode.", true),
    COMPLEXITY("Cyclomatic complexity",
            "All methods together: one path per method, one more for every branch, case and catch.", true),
    MAX_COMPLEXITY("Max method complexity", "The cyclomatic complexity of the most complex method.", true),
    FAN_IN("Fan-in", "Classes that use the element; for a package, classes outside it.", false),
    FAN_OUT("Fan-out", "Classes the element uses; for a package, classes outside it.", false),
    INSTABILITY("Instability",
            "Fan-out / (fan-in + fan-out): 0 is only used, 1 only uses.", false),
    ABSTRACTNESS("Abstractness", "Share of interfaces.", false),
    DISTANCE("Distance from main sequence",
            "|abstractness + instability - 1|: 0 is balanced; 1 is concrete and much used, or abstract and unused.",
            false),
    CALLS("Calls", "Calls of methods of other classes, every place counted.", false),
    EXTERNALS("External dependencies", "Classes outside the analysis that are used.", false),
    IMPACT("Transitive users", "Classes that use the element over any number of steps: what a change can reach.",
            false),
    REACH("Transitive dependencies", "Classes the element uses over any number of steps.", false),
    CYCLE("Cycle size",
            "For a class the classes of the cycle it lies on; for a package the classes in it that lie on a cycle.",
            false);

    private final String label;
    private final String description;
    private final boolean fromCode;

    Metric(String label, String description, boolean fromCode) {
        this.label = label;
        this.description = description;
        this.fromCode = fromCode;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Measured in the code of the methods, which only the reader of Java bytecode does. */
    public boolean isFromCode() {
        return fromCode;
    }

    @Override
    public String toString() {
        return label;
    }
}
