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
 * A single architectural violation as understood by a specific
 * {@link Architecture} style. Whether a given dependency edge counts
 * as a violation depends on the architecture in use — for a
 * {@link HierarchicalLayeredArchitecture}, an edge whose source level
 * is below its target level is an {@link ViolationKind#UPWARD}
 * violation; an edge inside a package-level cycle is a
 * {@link ViolationKind#PACKAGE_TANGLE} member.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code sourceFqn}, {@code targetFqn} — fully-qualified names
 *       at the granularity the architecture detected the violation
 *       on (class-class for upward edges; package-package for tangle
 *       members).</li>
 *   <li>{@code sourceLevel}, {@code targetLevel} — the levels the
 *       architecture computed for source and target, useful for
 *       sorting, grouping, and rendering hints.</li>
 *   <li>{@code backEdge} — true when this violation is also one of the
 *       dependency edges deliberately removed from the level-computation
 *       DAG to break a class-level SCC.</li>
 * </ul>
 */
public record Violation(
        String sourceFqn,
        String targetFqn,
        ViolationKind kind,
        int sourceLevel,
        int targetLevel,
        boolean backEdge) {

    public Violation(String sourceFqn,
                     String targetFqn,
                     ViolationKind kind,
                     int sourceLevel,
                     int targetLevel) {
        this(sourceFqn, targetFqn, kind, sourceLevel, targetLevel, false);
    }

    public Violation {
        if (sourceFqn == null || sourceFqn.isEmpty()) {
            throw new IllegalArgumentException("sourceFqn must be non-empty");
        }
        if (targetFqn == null || targetFqn.isEmpty()) {
            throw new IllegalArgumentException("targetFqn must be non-empty");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
    }
}
