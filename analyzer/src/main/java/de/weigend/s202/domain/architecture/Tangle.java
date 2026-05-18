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

import java.util.Set;
import java.util.TreeSet;

/**
 * A group of packages that are mutually interconnected — a
 * strongly-connected component of size {@literal >} 1 in the
 * package-level dependency graph. Tangles are reported separately
 * from {@link Violation} because they are a group-level concept
 * (member set), not an edge-level one.
 *
 * <p>Members are stored as an alphabetically sorted set so equal
 * tangles compare equal across runs.
 */
public record Tangle(Set<String> members) {

    public Tangle {
        if (members == null || members.size() < 2) {
            throw new IllegalArgumentException("a tangle must have at least 2 members");
        }
        members = java.util.Collections.unmodifiableSet(new TreeSet<>(members));
    }
}
