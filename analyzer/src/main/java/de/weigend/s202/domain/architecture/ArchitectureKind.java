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

/**
 * Stable ids for architecture styles. These ids are intentionally independent
 * from UI view names so project data and style policies can outlive a renderer.
 */
public enum ArchitectureKind {
    LAYERED("layered"),
    COMPONENT("component"),
    HEXAGONAL("hexagonal"),
    WHAT_IF_LAYERED("what-if-layered");

    private final String id;

    ArchitectureKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
