package de.weigend.s202.ui.whatif;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the user's What-If overrides and resolves any fully-qualified name
 * (class or package) to its virtual identity. Phase 3 of the What-If
 * refactor (ADR §2.4, §4.2, §4.5).
 *
 * <p>An override entry {@code (fqcn → newVirtualParent)} means: treat the
 * node identified by {@code fqcn} as if its parent package were
 * {@code newVirtualParent}. So {@code setOverride("a.b.C", "x.y")} reads as
 * "virtually, class C now lives in package x.y" — its virtual fullName
 * becomes {@code "x.y.C"}.
 *
 * <p>Hierarchy semantics per §4.5: when a package is moved, only the package
 * wrapper carries an override. Its descendants inherit the new path via the
 * parent chain — no per-descendant override is created. Resolution walks
 * upwards from a fqcn through its ancestors and uses the closest ancestor
 * with an override to compute the virtual fullName, preserving the suffix
 * below that ancestor.
 *
 * <p>Override target paths are <b>not</b> recursively resolved: dropping
 * onto package {@code p.q} anchors the dragged node at the literal path
 * {@code p.q}, even if {@code p} itself happens to be overridden. The user
 * picked the visible target.
 *
 * <p>Resolution is cached and the cache is cleared on every override
 * mutation. Phase 3 MVP — fine-grained invalidation can come later if
 * profiling shows it matters.
 */
public final class VirtualIdentity {

    private final Map<String, String> overrides = new HashMap<>();
    private final Map<String, String> resolutionCache = new HashMap<>();

    /**
     * Set the virtual parent path for {@code fqcn}. Pass {@code null} to
     * remove the override and revert to the static identity.
     */
    public void setOverride(String fqcn, String newVirtualParent) {
        if (fqcn == null || fqcn.isEmpty()) {
            throw new IllegalArgumentException("fqcn must be non-empty");
        }
        if (newVirtualParent == null) {
            overrides.remove(fqcn);
        } else {
            overrides.put(fqcn, newVirtualParent);
        }
        resolutionCache.clear();
    }

    /** Drop all overrides (e.g. on re-analysis, per §4.4). */
    public void clear() {
        overrides.clear();
        resolutionCache.clear();
    }

    /** Number of currently active overrides — useful for tests / UX hints. */
    public int size() {
        return overrides.size();
    }

    /** True if any override has been set. */
    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    /** True if {@code fqcn} carries a direct override (not just an inherited one). */
    public boolean hasOverride(String fqcn) {
        return overrides.containsKey(fqcn);
    }

    /**
     * The virtual fully-qualified name of {@code fqcn}. If no override on
     * the path applies, returns {@code fqcn} unchanged.
     */
    public String virtualFullName(String fqcn) {
        return resolutionCache.computeIfAbsent(fqcn, this::resolve);
    }

    /** The virtual parent package of {@code fqcn} (empty if top-level). */
    public String virtualParent(String fqcn) {
        return parentOf(virtualFullName(fqcn));
    }

    private String resolve(String fqcn) {
        String direct = overrides.get(fqcn);
        if (direct != null) {
            return direct + "." + simpleName(fqcn);
        }
        String ancestor = parentOf(fqcn);
        while (!ancestor.isEmpty()) {
            String ancestorOverride = overrides.get(ancestor);
            if (ancestorOverride != null) {
                String virtualAncestor = ancestorOverride + "." + simpleName(ancestor);
                String suffix = fqcn.substring(ancestor.length() + 1);
                return virtualAncestor + "." + suffix;
            }
            ancestor = parentOf(ancestor);
        }
        return fqcn;
    }

    static String parentOf(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? "" : fqcn.substring(0, dot);
    }

    static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }
}
