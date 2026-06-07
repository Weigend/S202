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
package de.weigend.s202.domain.architecture;

import java.util.Map;

/**
 * Mutable what-if view of a {@link LayeredArchitecture}. Starts as a deep copy
 * of an immutable original; elements can be moved interactively and
 * {@link #violations()} reflects the updated layout immediately.
 */
public interface WhatIfArchitecture extends LayeredArchitecture {

    /** Restore the architecture to its initial post-analysis state. */
    void reset();

    /**
     * Move the element into {@code targetParentFqn}'s inner rows at the given
     * row and column index. Pass an empty target parent to move into the
     * top-level rows.
     */
    void moveElement(String fqn, String targetParentFqn, int targetRowIndex, int targetColumnIndex);

    /**
     * Move the element into {@code targetParentFqn} by inserting a brand-new
     * row at {@code targetRowIndex}.
     */
    void moveElementAsNewRow(String fqn, String targetParentFqn, int targetRowIndex);

    /** Returns the current (possibly virtual) package FQN that contains the class. */
    String packageOf(String classFqn);

    /** Snapshot of the current class-to-package mapping. */
    Map<String, String> classPackages();
}
