# S202 Level Calculation

Every class and package is assigned an integer **ArchitectureLevel**.
Low level = foundation, high level = consumer of the foundation.
A separate **LocalLevel** controls the visual position of each element within
its parent container.

---

## The Core Problem

Without cycles the solution is trivial: longest-path on the dependency graph.
Cycles (SCCs) make this impossible — within a cycle, not all edges can point
downward at the same time. Some edges must be ignored for the level
calculation. The question is: which ones, and why exactly those?

The answer must not be arbitrary. S202 derives the cut decision from the
**package graph** — it provides the architectural direction.

---

## The Three Phases

### Phase 1 — Global Package ArchitectureLevel

S202 builds a weighted directed graph across **all** packages. Each edge P→Q
has weight equal to the total method-call count from classes in P's subtree to
classes in Q's subtree. Where no method-call data exists, a fallback weight of
1 is used. Weights are propagated up the ancestor chain of the source package:
a call from `com.a.X` to `com.b.Y` contributes to `com.a→com.b` and also to
`com→com.b`.

Cycles in this graph are broken using the **rank score**:

```
rank(P) = (Σ outgoing weights − Σ incoming weights)
          / max(1, Σ outgoing + Σ incoming)
```

- High rank → P uses more than it is used → belongs higher
- Low rank → P is more often used → belongs lower

**Asymmetric SCC** (ranks differ): all edges where
`rank(from) < rank(to)` are cut in one pass.

**Symmetric SCC** (all ranks equal → topology provides no direction):
all internal edges are removed. Levels then arise from external
dependencies; if there are none, nodes remain at the same level.

**Important:** Symmetry is detected via the rank, not via equal weights.
An SCC with uniformly weighted edges can still have different ranks
(when in/out-degrees differ) — and therefore a clear cut direction.

After cycle handling: longest-path on the DAG → `architectureLevel` per package.

---

### Phase 2 — Class ArchitectureLevel (guided by package order)

**Case A:** Classes A and B are in the same SCC but in packages with
different `architectureLevel` values:

```
pkgLevel(A) < pkgLevel(B)  →  edge A→B is a back-edge → cut
```

The package `architectureLevel` from Phase 1 is the decision basis. The class
SCC is broken up by the package DAG.

**Case B:** All SCC members are in the same package level — no package
context provides a direction. All internal edges are removed; levels arise
from external dependencies.

All removed edges (Case A and Case B) are recorded as **classBackEdge** —
they appear in the UI as dashed violation edges.

After cycle handling: longest-path on the cleaned graph → `architectureLevel`
per class.

---

### Phase 3 — Local Display Position (LocalLevelCalculator)

After class levels are computed, S202 runs the **LocalLevelCalculator** over
every parent container separately. For each container, it builds a weighted
sibling-only graph of the direct children (packages or classes). Dependencies
that leave the container or stay within the same child subtree are excluded.

The same rank-score algorithm from Phase 1 is applied to break any cycles
within each sibling group. The result is a `localLevel` per element.

`localLevel` determines the **visual position** within the parent container.
`architectureLevel` is the **global semantic level** — it drives violation
detection.

---

## Key Points

**No threshold.** The old algorithm used ε = 0.1 as a cutoff for the rank
score. Without a threshold, S202 cuts every edge that is architecturally
justifiable — including marginally asymmetric cases. Result on Minecraft 1.12:
−6 % wrong-direction edges.

**Rank beats weight.** `minWeight == maxWeight` sounds like symmetry but is
not necessarily so. If nodes have different numbers of in- and out-edges,
different ranks emerge — and with them a clear direction. Symmetric treatment
only applies when all ranks are truly equal.

**All in one pass.** Within an SCC, all qualifying edges are cut simultaneously
(not one at a time with a restart). Cutting one edge and recalculating can
destroy the global dominance order in multi-node SCCs.

**Deterministic.** Every iteration runs over alphabetically sorted node lists.
No random element, no threshold, no size-dependent special case — the same
input always produces the same result.
