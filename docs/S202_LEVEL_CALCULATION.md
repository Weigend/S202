# S202 Level Calculation

Developer notes for `domain.impl.LevelCalculator` and `ContainerLevelizer`.
The concept paper in `docs/concept/*.tex` is the authoritative description;
this file is the short map from concept to code.

---

## The Model: Containers, Siblings, One Decision per Dependency

S202 levelizes container by container: **every container orders its own
direct children**. A container is a package (or the root); its children —
classes and sub-packages alike — are its *siblings*.

Every class dependency `X → Y` is attributed to exactly **one** container: the
deepest one that holds both X and Y. There it becomes a weighted edge between
the two siblings whose subtrees contain X and Y (`ContainerProjection.project`).

```
com.a.X   -> com.b.c.Y     container com      edge com.a   -> com.b     (packages)
com.a.X   -> com.a.Y       container com.a    edge com.a.X -> com.a.Y   (classes)
com.a.X   -> com.a.b.Y     container com.a    edge com.a.X -> com.a.b   (class -> package)
```

Nothing is propagated to other containers. Edge weight = method-call count
from X to Y, fallback 1.

---

## Pipeline

| Step | What | Where |
|---|---|---|
| 1, 2 | Create class and package elements. Every edge kind counts as a dependency, references by type (`USES`) included. | `LevelCalculator` |
| 3 | **Container levelization** per container: Tarjan → *container tangles*; deterministic rank cut → *container back-edges*; longest path → `localLevel`. | `ContainerLevelizer` |
| 4 | **Class back-edges**: a class dependency is a back-edge iff its projection is a cut sibling edge. | `LevelCalculator` |
| 5 | **Class `architectureLevel`**: longest path on the class graph minus the *level cuts* — the back-edges that lie inside a class cycle (Tarjan on the class graph). A back-edge outside any class cycle still counts. Acyclic by construction: every class cycle contains at least one back-edge. | `LevelCalculator` |
| 6 | **Package `architectureLevel`**: longest path on the package graph where `A → B` iff a non-back-edge class dependency leads from A's subtree into B's subtree and neither contains the other. Acyclic by construction. | `LevelCalculator` |
| 7 | Reverse dependencies. | `LevelCalculator` |

### Rank cut (Step 3)

Inside an SCC of a sibling graph:

```
rank(S) = (out(S) - in(S)) / max(1, out(S) + in(S))      weights within the SCC
```

Every edge with `rank(to) > rank(from)` is cut; if no edge qualifies, all
internal edges are cut (symmetric tangle, no justified direction). SCCs are
recomputed after each round until none remains. Deterministic: all iteration is
over sorted names, no thresholds.

This is a deterministic stand-in for a minimal feedback arc set; it is not a
minimisation.

---

## Properties (checked by `LayoutInvariantChecker`)

| Rule | Statement |
|---|---|
| R1 | Class dependency that is not a level cut ⇒ `level(A) > level(B)`; every level cut is a class back-edge |
| R2 | Non-cut sibling edge ⇒ `localLevel(from) > localLevel(to)` |
| R3 | Every container back-edge lies inside a container tangle |
| R4 | Non-back-edge class dependency across different package subtrees ⇒ `pkgLevel(A) > pkgLevel(B)` |
| R5 | Every dependency that runs upward by nested visual rank is a class back-edge |

Consequences:

- **Violation = back-edge.** The red upward edges in the view are exactly the
  class back-edges; there is no second notion. (Dashed means only that a line
  lies on a package cycle.)
- **Back-edges only inside tangles.** Outside cycles every dependency runs
  downward.
- **No containment inflation.** A package's `architectureLevel` counts only
  dependencies leaving its subtree. A root package that contains everything it
  uses is level 0.
- `localLevel` is the architecture hypothesis and drives the layout; it is
  local to each container.
- `architectureLevel` is global: the real depth of the dependency chain below
  an element. The class level therefore ignores a dependency only where a class
  cycle forces it to (*level cut*). A back-edge that merely runs against the
  order of two packages, without closing a class cycle, is a violation in the
  picture but still counts towards the class level. The package level follows
  the same rule one level up: it ignores exactly the dependencies cut to break
  a cycle between sibling subtrees.

---

## Two Kinds of Cycles

- **Class cycles (purple overlay)**: SCCs of the flat class graph (Tarjan). Answer
  *which classes* are entangled.
- **Package cycles (orange overlay)**: container tangles with at least one
  package member (`DomainModel.getPackageTangles()`). Members are siblings of
  one container — packages covering their subtree, possibly together with
  classes of that container. Class-only container tangles are left to the purple
  overlay (`getContainerTangles()` returns all of them).

A package cycle can exist without any class cycle (two packages using each
other through different classes); a class cycle inside one package is not a
package cycle.

---

## Order Independence

Classes, packages, dependencies and SCC members are always iterated in sorted
order; the result does not depend on jar packing order.
