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
package de.weigend.s202.domain;

/**
 * Names as the user knows them. A tab may order the classes otherwise than by
 * their packages - by the files they come from ({@link PhysicalHierarchy}) or
 * as a slice ({@link SliceHierarchy}) - and does so by renaming them. Wherever
 * a name or a text with names is shown, it goes through here and comes out as
 * in the analysis. Names of the analysis itself stay as they are.
 */
public final class ShownNames {

    private ShownNames() {
    }

    /** Text for the user, with every name in it as in the analysis. */
    public static String shown(String text) {
        return SliceHierarchy.toLogical(PhysicalHierarchy.shown(text));
    }

    /** The name of an element in the analysis, whatever order it comes from. */
    public static String toLogical(String name) {
        return SliceHierarchy.toLogical(PhysicalHierarchy.toLogical(name));
    }
}
