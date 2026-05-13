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
