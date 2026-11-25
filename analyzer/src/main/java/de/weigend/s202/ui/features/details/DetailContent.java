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
package de.weigend.s202.ui.features.details;

import java.util.List;

/**
 * What the Dependency Details panel shows for one selected arrow.
 *
 * @param title   the two ends of the arrow
 * @param summary what is known about the dependency as a whole
 */
record DetailContent(String title, String summary, List<Row> rows) {

    enum Style { GROUP, EDGE, BACK_EDGE, CALL, REFERENCE }

    /**
     * A place in the code a row leads to.
     *
     * @param method {@code null} for the class or package itself
     */
    record Place(String className, String method, String descriptor) {
        static Place of(String fqn) {
            return new Place(fqn, null, null);
        }
    }

    /**
     * @param from      left column; leads to {@code fromPlace}
     * @param to        middle column; leads to {@code toPlace}
     * @param calls     right column
     * @param tooltip   {@code null} for none
     * @param fromPlace {@code null} when the column leads nowhere, likewise {@code toPlace}
     */
    record Row(Style style, String from, String to, String calls, String tooltip,
               Place fromPlace, Place toPlace, boolean expanded, List<Row> children) {

        static Row group(String label, List<Row> children) {
            return new Row(Style.GROUP, label, "", "", null, null, null, true, children);
        }
    }
}
