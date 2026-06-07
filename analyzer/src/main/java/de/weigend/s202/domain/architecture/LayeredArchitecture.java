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

import java.util.List;

/**
 * Architecture style that organises elements as a hierarchy of layers.
 * The structure is Rows-of-Cols: the top-level view is a list of rows
 * (each row holds the elements at one architectural level), and every
 * nested package carries its own inner rows.
 *
 * <p>Concrete implementations include the immutable
 * {@link HierarchicalLayeredArchitecture} and the mutable
 * {@link WhatIfArchitecture} used for interactive what-if modelling.
 */
public interface LayeredArchitecture extends Architecture {

    /**
     * Rows sorted from highest level (index 0) to lowest. Within a row
     * the column order reflects the horizontal-layout decision made by
     * the rendering pipeline.
     */
    List<List<Element>> rows();
}
