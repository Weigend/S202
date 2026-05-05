# ICSOFT 2026 - Abstract (English Working Draft)

**Track:** Abstracts Track
**Submission deadline:** 22 May 2026
**Status:** Draft v2, translated and adapted from the German reference draft
**Language:** English

---

## Title

**Verifiable Layered Architecture Visualizations: Invariants for Correct Level Computation**

*Alternative:* "Correct Layer Computation for Hierarchical Architecture Visualizations: Constraints, Invariants, and Pitfalls"

## Authors

Johannes Weigend (Rosenheim Technical University of Applied Sciences / Weigend AM GmbH & Co.KG)

*Optional co-authors to confirm: Veronika Schwarz (published as Veronika Dashuber in the 2021 paper), Michael Philippsen.*

---

## Submission Abstract

Layered architecture visualizations communicate dependency structure through spatial order: elements that depend on others are placed higher. If the level-assignment pipeline is wrong, the picture can still look plausible while misleading the viewer: a package may appear below its own classes, cyclic peer packages may be placed on different levels, or a regular dependency may point against the intended layer direction. We argue that layout correctness should be verified by the tool, not by the user.

Our 2021 IVAPP Best Paper, "A Layered Software City for Dependency Visualization" (Dashuber, Philippsen, Weigend), introduced a city layout in which the X/Y position of class buildings follows dependency-derived levels, while building height encodes orthogonal metrics such as complexity or lines of code. This abstract presents Structure202, an open-source Java reference implementation under Apache 2.0, and a Layout Invariant Checker that verifies the layout after every analysis run.

The main implementation lesson is that correct level computation is not a single ordering problem. It requires coordinating three interdependent constraint systems: class ordering, package hierarchy, and equality of strongly connected components (SCCs). Structure202 therefore computes two semantically different level concepts: global architectural depth as a longest path in the SCC-collapsed class dependency DAG, and local layout position within each package, starting at zero and using only sibling dependencies. Highly cyclic code bases are handled by a heuristic SCC breaker; its cut edges are not hidden but marked as violations.

The Layout Invariant Checker verifies four postconditions: non-heuristic class dependencies must point downward (R1); packages in a filtered package-level SCC must share a level (R2); container levels must dominate their contents (R3); and edge classifications must match the current level and SCC state (R5). The checker distinguishes implementation bugs from tolerated heuristic violations and emits a copy-paste-ready reproducer block for every finding.

We will demonstrate the checker on real code bases. In the 92-module software-ekg-7 system, an R2 finding exposed a missing package-SCC equalization step and directly triggered a pipeline fix. The same four rules were ported to an independent Unity/.NET 3D software city and produced byte-identical findings on shared inputs. To our knowledge, no previous layered architecture visualization has provided this degree of machine-checkable self-validation.

**Word count:** ~360 words.

---

## Appendix A - Why Single-Pass Does Not Work: Failure Modes

### The Core Problem: Three Interacting Constraint Systems

A correct layered layout must satisfy three constraints at the same time:

1. **Class constraint:** If `A -> B` means that `A` depends on `B`, then `level(A) > level(B)`.
2. **Hierarchy constraint (R3):** A package must be placed at least as high as its highest contained element.
3. **SCC constraint (R2):** All classes in a cyclic dependency group receive the same level; packages that form a package-level SCC must also share a level.

Each constraint is individually manageable. Together they create a circular dependency system between the levels of computation themselves, not merely between classes.

### Failure Mode 1: Retroactive Correction

An algorithm iterates over classes and assigns levels. It first sees class `A` in package `P1`, computes `level(A) = 3`, and therefore sets `level(P1) = 3`. Later it reaches class `B` in package `P2` and discovers that `A` and `B` are in the same SCC and must share a level, now `level(A) = level(B) = 5`. This forces `P1` to be recomputed. If `P1` depends on `P3`, `P3` may need to move as well. A cascade follows.

In a single-pass implementation, however, `P1` and `P3` may already have been finalized. SCC membership is only known after the relevant graph has been traversed; if level assignment and SCC discovery are interleaved, the iteration order decides whether the correction is still applied in time.

### Failure Mode 2: Interleaved Cycle Detection Produces Classpath-Dependent Results

The most subtle practical failure mode occurs when cycle detection and level computation are not separated. The algorithm traverses the class graph, assigns levels, and handles discovered cycles on the fly, depending on which class happens to be processed first.

Classes come from JARs. The order in which the JVM sees classes follows the order in which they were packaged into the JAR. That order can depend on the build tool, module order in a multi-module build, filesystem ordering on the build server, and other implementation details. None of these orders is semantically meaningful; none encodes architectural dependency direction.

The result is dangerous: the same source code can produce different level layouts under different build configurations. The errors are subtle. The layout may still look plausible, arrows may mostly point in the expected direction, and no obvious visual break appears. Yet the precise hierarchy information is corrupted and may remain unnoticed for a long time.

The solution is to separate the phases strictly: first compute the SCC partition on the complete graph, then build the SCC-DAG, then assign levels. Tarjan's algorithm computes the SCC partition in `O(V+E)`; with deterministic input ordering, the implementation becomes reproducible and independent of classpath traversal artifacts.

### Failure Mode 3: Mutual Dependency Between Package and Class Levels

Package `P1` is placed on the level of its highest class (R3). But the level of that highest class depends on the classes it calls. Those classes live in packages whose levels are again derived from their contents and dependencies. The package graph is not available as a stable input beforehand; it is derived from the class graph.

This mutual dependency makes a shared single pass over classes and packages inherently unstable.

### Failure Mode 4: The Big Lake - SCCs Without Heuristics Collapse the Layout

Without a mechanism for breaking large SCCs, all members of a cyclic group must be placed on the same level. For small cycles of two to five classes this is acceptable. For Minecraft Forge, however, we observed a single SCC containing 4,038 classes. Without a breaker, more than 40 percent of the project would land on one flat level at the bottom of the layout. Everything else would stand above it, but the interesting internal structure of the core would be invisible.

The heuristic SCC breaker solves this practical problem. It identifies back edges, meaning dependencies that run against the natural architectural flow, and temporarily removes them for level computation. The removed edges are visualized as violations instead of being ignored. The layout remains honest about architectural debt while still producing a usable hierarchy.

### Formal Counterexample: Why a Naive Recursive Algorithm Fails

The intuitive level-computation algorithm is a recursive DFS with memoization:

```text
level(A):
  if A already computed: return level(A)
  if A has no dependencies: return 0
  return max(level(dep) for dep in A.dependencies) + 1
```

For acyclic graphs this is correct and equivalent to longest-path computation on a DAG. For cyclic graphs it fails structurally.

Counterexample with two SCCs:

```text
SCC_1 = {A, B}, with A -> B -> A
SCC_2 = {C, D}, with C -> D -> C
Cross dependency: A -> C
```

Correct levels:

```text
level(C) = level(D) = 0
level(A) = level(B) = 1
```

Recursive algorithm, starting at `A`:

```text
level(A)  [A "in progress", sentinel = 0]
  -> level(B)
      -> level(A): cycle detected, return 0
    B.level = 0 + 1 = 1
  -> level(C)  [C "in progress", sentinel = 0]
      -> level(D)
          -> level(C): cycle detected, return 0
        D.level = 0 + 1 = 1
    C.level = 1 + 1 = 2   <-- wrong; correct would be 0
  A.level = max(1, 2) + 1 = 3   <-- wrong; correct would be 1
```

After SCC equalization:

```text
SCC_1 = max(3, 1) = 3
SCC_2 = max(2, 1) = 2
```

Both results are wrong.

The cause is that the recursive algorithm enters `SCC_2` from the context of `SCC_1`. The sentinel-based local cycle handling inflates the level of `SCC_2`, and that inflated level then feeds into the computation of `SCC_1`. The correct level of `SCC_2`, namely zero, is only known after the graph has been collapsed into SCCs and evaluated as an SCC-DAG.

Thus, recursive level algorithms that mix traversal, cycle handling, and level assignment can produce traversal-order-dependent results on graphs with interacting SCCs. In JVM-based tools, that traversal order can be influenced by classpath and packaging order. The correct solution collapses SCCs before level assignment: Tarjan -> SCC-DAG -> longest path.

### The Solution: Two Semantically Different Level Concepts

Failure modes 1 to 3 share the same root cause: trying to use one single level concept for two different questions. Structure202 separates them:

- **Phase 1** computes architectural depth: the longest path in the global, cycle-free SCC-DAG across all classes of the analyzed project.
- **Phase 2** computes local layout position inside a package: a zero-based ordering derived only from sibling dependencies.

Both phases use the same conceptual algorithmic shape, but on different graphs with different constraints:

```text
Phase 1: class graph -> Tarjan (SCCs) -> SCC-DAG
              -> longest-path topological pass -> global levels
              -> assign level to all SCC members
              -> package-level propagation upward

Phase 2: for each package: sibling graph (internal edges only)
              -> Tarjan (local SCCs) -> local SCC-DAG
              -> topological pass -> local layout positions
```

Phase 1 is reproducible because SCCs are collapsed before levels are assigned. The SCC-DAG is acyclic by construction, and longest-path computation on that DAG is stable for the same graph.

Phase 2 intentionally ignores external dependencies. If package `ui` depends on `domain` and `domain` depends on `persistence`, external edges would otherwise pull many siblings into global levels and make their local structure unreadable. Phase 2 sees only sibling relationships and produces a package-local ordering that is independent of the global architectural depth.

The central argument is that no single level concept can serve both purposes well. A purely global level is often visually useless inside a package; a purely local level has no architectural meaning. The explicit separation enables a layout that is both structurally correct and readable.

The implemented invariants R1, R2, R3, and R5 are machine-checkable postconditions for exactly this pipeline separation. The fact that the checker found a real R2 bug in software-ekg-7 shows that the rules are not trivial to satisfy, even for the authors of the algorithm.

### Implementation: The Complete Pipeline

The conceptual two-phase structure expands into the following concrete implementation steps.

Phase 1 - Global levels (`LevelCalculator`):

```text
Step 1:  Create class objects             (all levels = 0)
Step 2:  Create package objects           (all levels = 0)
Step 3:  Compute class levels
           -> Tarjan on class graph
           -> SCCBreaker: identify back edges
           -> Build acyclic SCC-DAG
           -> Longest-path topological pass -> level per SCC
           -> Assign levels to all SCC members
Step 4:  Package level = max(level of contained classes)
         +-- stabilization loop (until stable, max. 20x) --+
         |  Step 5:  Lift parent package levels             |
         |             parent.level = max(child.level)      |
         |  Step 4b: Equalize package SCCs                  |
         |             Tarjan on filtered package graph      |
         |             lift SCC members to max(level)        |
         +--------------------------------------------------+
Step 6:  Set reverse dependencies (dependents)
```

After Step 3, level is no longer a property of an individual class alone. It is the level of the SCC node to which the class belongs, measured as the longest path in the cycle-free quotient graph. That graph covers all classes of the analyzed project, across packages. Cycles do not influence level assignment internally because they have already been collapsed before longest-path computation.

This is the fundamental difference from naive recursive approaches, and it is the reason why Phase 2 must be a separate computation.

Phase 2 - Local visualization positions (`DistrictRowLevelCalculator`, recursively per package):

```text
For each package P:
  Step 1: Collect subtree classes for each sibling
          (class: itself; package: all classes recursively)
  Step 2: Build sibling graph via distinct-target counting
          countAtoB = |{c in subtree(B) : A.deps contains c}|
          countBtoA = |{c in subtree(A) : B.deps contains c}|
          edge A -> B if countAtoB > countBtoA
  Step 3: Tarjan -> local SCC-DAG -> longest-path -> local levels
  Step 4: Apply levels to all siblings
  Step 5: Recurse into child packages
```

The Step 4b/5 loop is not an incidental implementation detail. It follows directly from Failure Mode 3: package-SCC equalization can lift a package level; that can lift its parent package; that can change the effective package-level structure; and that may require another equalization pass. The loop terminates because levels grow monotonically. No step lowers a level. The 20-iteration limit is therefore a guardrail rather than part of the algorithmic argument; in practice we expect one to three iterations.

The term "two phases" refers to the conceptual separation between global architectural depth and local layout position, not to the raw number of implementation steps. Phase 1 is an ordered pipeline with an iterative stabilization loop. Phase 2 is an independent local computation.

### R4: A Retired Rule and What It Teaches

An early version of the invariant checker contained R4:

```text
Cyclic child packages must share a level.
```

The rule sounds reasonable and is correct in simple cases. In practice it failed because the raw package graph contains relationships that should not be interpreted as peer-level architectural cycles:

- **Parent-child edges** create apparent bidirectional relationships because a parent contains its children and children belong to their parent. Without filtering, many parent/child pairs look cyclic.
- **SCCBreaker back edges** connect packages that are not necessarily architectural peers. A rule that includes these edges equalizes the wrong candidates.
- **Shared class SCCs** can make two packages appear package-dependent without representing a true structural peer relationship.

R4 was replaced by R2, which expresses the same intention on the correct filtered graph. R2 removes heuristic back edges, intra-subtree edges, and edges caused by shared class SCCs before running Tarjan on the remaining package graph.

The broader lesson is that specifying constraints for a complex constraint system is itself difficult. The checker provides a second level of validation: it does not only test the implementation against the rules; it also exposes when the rules are too broad or too coarse. False positives force the rule to become more precise. The later R2 finding in software-ekg-7 shows that the refined rule was precise enough to distinguish real pipeline bugs from apparent ones.

### Relation to Feedback Arc Set Heuristics

Failure Mode 4 is an instance of the Minimum Feedback Arc Set problem: remove a minimal set of directed edges so the graph becomes acyclic. The problem is NP-hard [Karp 1972], so any practical polynomial-time implementation must use a heuristic or approximation.

A well-known related heuristic is due to Eades, Lin, and Smyth [1993]. It orders nodes according to the difference between outgoing and incoming degree, then classifies edges that point backward in the resulting sequence as feedback edges. The intuition is similar to Structure202: high out-degree suggests a higher-level element; high in-degree suggests a foundational element.

Structure202 uses a deliberately simpler variant: a direct normalized score per node,

```text
(outDegree - inDegree) / max(1, outDegree + inDegree)
```

Edges from lower-ranked to higher-ranked nodes are classified as back-edge candidates. This is easier to implement and sufficient for software dependency graphs, where the heuristic reflects architectural intuition rather than claiming mathematical optimality.

The important distinction is transparency. Many tools break cycles implicitly, but Structure202 classifies heuristic cut edges explicitly. They are marked as `VIOLATION`, rendered in red, and known to the invariant checker. This makes algorithmic defects separable from tolerated heuristic artifacts.

---

## References

1. Dashuber, V., Philippsen, M., & Weigend, J. (2021). *A Layered Software City for Dependency Visualization.* IVAPP 2021, pp. 277-285. SCITEPRESS. Best Paper Award.

2. Dashuber, V., Philippsen, M., & Weigend, J. (2022). *Static and Dynamic Dependency Visualization in a Layered Software City.* SN Computer Science, 3(4), 305. Springer.

3. Eades, P., Lin, X., & Smyth, W. F. (1993). *A fast and effective heuristic for the feedback arc set problem.* Information Processing Letters, 47(6), 319-323. Elsevier.

4. Karp, R. M. (1972). *Reducibility among combinatorial problems.* In R. E. Miller & J. W. Thatcher (Eds.), Complexity of Computer Computations (pp. 85-103). Plenum Press.

5. Structure202 (this work) - Apache 2.0, https://github.com/jweigend/Structure202

---

## Glossary

### General Terms

**DAG (Directed Acyclic Graph)**
A directed graph without cycles. It enables topological sorting and deterministic longest-path computation. In this pipeline, the SCC-DAG is the target form after SCC collapse.

**Feedback Arc Set (FAS)**
A set of directed edges whose removal makes a directed graph acyclic. Finding a minimum feedback arc set is NP-hard [Karp 1972]; Structure202 approximates it with a heuristic.

**In-degree / Out-degree**
The number of incoming or outgoing edges of a node. High in-degree suggests a foundational element because many others depend on it. High out-degree suggests a high-level element because it depends on many others.

**Longest path**
The longest path in a DAG from a node to a leaf-like dependency base. On the SCC-DAG it defines the global level assignment.

**Memoization**
Caching already computed recursive results. On cyclic graphs, memoization combined with sentinel values can produce traversal-order-dependent level errors.

**SCC (Strongly Connected Component)**
A maximal set of nodes in a directed graph where every node can reach every other node. In architecture visualization, such cyclic dependency groups are often called tangles.

**SCC-DAG (Quotient Graph)**
An acyclic graph obtained by collapsing every SCC into one node while preserving edges between SCCs. This is the basis of deterministic level computation in Phase 1.

**Tarjan's Algorithm**
An `O(V+E)` algorithm for computing SCCs in a directed graph. Structure202 uses it for global class SCCs, package SCCs, and local sibling SCCs.

**Topological sorting**
A linear ordering of a DAG in which all edges point consistently in one direction. It is a prerequisite for longest-path level assignment on a DAG.

### Application-Specific Terms

**Architectural depth (Phase-1 level)**
The global level of a class computed in Phase 1: the longest path from its SCC to the dependency base in the global SCC-DAG. It is project-wide and can grow into the hundreds.

**Cut edge**
An edge identified by the SCC breaker as a candidate for temporarily breaking an SCC. Cut edges are visualized as violations and can become concrete refactoring targets.

**Distinct-target counting**
The Phase-2 heuristic for determining dependency direction between sibling elements. It counts distinct target classes in the other sibling's subtree instead of raw call frequency.

**Feedback Arc Set heuristic (SCCBreaker)**
The Structure202 approximation of the FAS problem. It computes a rank score `(outDegree - inDegree) / max(1, outDegree + inDegree)` and classifies edges from lower-ranked to higher-ranked nodes as back-edge candidates.

**Invariant R1 - ClassDepDownward**
Every class dependency `A -> B` must satisfy `level(A) > level(B)` unless the edge is a heuristic back edge or both endpoints belong to the same broken SCC.

**Invariant R2 - PkgSccEqualLevel**
All packages that form a package-level SCC after filtering heuristic edges, intra-subtree edges, and shared-class-SCC artifacts must have the same level.

**Invariant R3 - ContainerLevelGEContent**
A package level must be greater than or equal to the level of each contained class and sub-package.

**Invariant R5 - ViolationFlagConsistency**
The classification of an edge (`NORMAL`, `VIOLATION`, or `INTRA_SCC`) must match the current level state and SCC membership.

**Layout Invariant Checker**
The component that runs after each analysis and verifies R1, R2, R3, and R5. It distinguishes algorithmic bugs from tolerated heuristic artifacts and emits reproducer blocks for findings.

**Local layout position (Phase-2 level)**
The level computed inside one package for sibling layout. It starts at zero for the lowest sibling and has only local visual meaning.

**Reproducer block**
A checker output containing input paths, graph dimensions, and findings. It is intended to be copied directly into a regression test or diagnostic prompt.

**Back edge**
In this pipeline, a dependency edge that runs against the inferred architectural flow. Back edges are marked as violations and highlighted in the visualization.

**Layered layout**
A visualization approach in which elements are placed according to a vertical or depth-like order. Dependencies then tend to point consistently in one direction.

**Software City**
A visualization metaphor in which classes are buildings and packages are districts. In the 3D version, X/Y position encodes the layered layout while building height encodes metrics such as lines of code or complexity.

**Tangle**
An informal term for an SCC with more than one member: a cyclic dependency group and a primary refactoring target.

**Top-Cut-Targets**
A Structure202 panel that lists methods involved in the largest number of cut-edge occurrences. It connects visual diagnosis to targeted refactoring action.

---

## Open Points Before Submission

- **Co-authors:** Michael Philippsen is confirmed in the German notes; Veronika Schwarz still needs to be asked or confirmed.
- **Numbers to verify:** 92 modules for software-ekg-7; 4,038 classes and 3,585 back edges for Minecraft Forge.
- **Final abstract length:** keep the submission abstract near one page in the INSTICC template after references and author block are included.
- **Complementing material:** attach the 2021 IVAPP paper and, if useful, the 2022 SN Computer Science extension or a short video/demo link.
- **AI disclosure:** because this English draft was prepared with AI assistance, check the current INSTICC AI policy and add the required disclosure if this wording is used.
- **Keywords for PRIMORIS:** software architecture visualization, layout invariants, software city, strongly connected components, dependency analysis, software engineering tools.
