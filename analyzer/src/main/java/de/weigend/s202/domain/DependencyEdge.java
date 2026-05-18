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
 * Directed dependency edge between two fully-qualified model elements.
 */
public record DependencyEdge(String from, String to) {

    public DependencyEdge {
        if (from == null || from.isEmpty()) {
            throw new IllegalArgumentException("from must be non-empty");
        }
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("to must be non-empty");
        }
    }
}
