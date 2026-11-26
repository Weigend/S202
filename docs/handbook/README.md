# S202 Handbook

A complex system can be mastered as long as it can be taken apart, into parts that are understood,
developed and replaced one at a time. Whether that is possible is not said by the names of the
packages but by the dependencies between them. S202 makes them visible.

It reads compiled Java code or the source code of another language, computes which part
depends on which, and draws the result as layers: what is used lies below what uses it. A
dependency that runs upwards is a finding, and so is a group of packages or classes that depend
on each other in a circle.

It is a tool for understanding and judging an existing system. The picture of the whole shows whether
there is an architecture at all, parts with an order and boundaries, or a big ball of mud in which
everything is tied to everything. It shows the terms of the domain as they are in the code: which
there are, which are central, what rests on what, and where business and technology are mixed. And
it shows the quality of the structure, from the whole down to the single call. That makes S202 a tool
for [architecture reviews](why-architecture.md#reviewing-an-unknown-system) and for analyses of
quality in general.

Where something is to be changed, it goes on: *which dependency breaks the layers*, *what would have
to be cut to get rid of this cycle*, *what happens if this class moves there*. The answers become a
list of changes that can be given to the developers. The analyzed code is never changed.

S202 finds the dependencies; the team decides which of them are wrong. The first pages explain this
reasoning, the others describe every view, panel, menu and dialog with pictures of the running
application. The handbook describes release 1.9.0.

![The main window of S202 with an analysis](images/window-overview.png)

## Choose a path

- **Why structure matters:** [Why architecture matters](why-architecture.md) →
  [The web shop story](webshop-story.md) → [Architecture smells](architecture-smells.md)
- **First use:** [Concepts](concepts.md) → [Getting started](getting-started.md) →
  [The window](window.md)
- **Understand and review a system:**
  [Reviewing an unknown system](why-architecture.md#reviewing-an-unknown-system) →
  [Loading code](loading-code.md) → [Layered Architecture View](layered-view.md) →
  [Tangles](tangle-view.md) → [Quality](quality.md)
- **Resolve cycles:** [Tangles](tangle-view.md) → [Planning a refactoring](refactoring.md)
- **Draw component boundaries:** [Component View](component-view.md) →
  [Planning a refactoring](refactoring.md)
- **Look up every menu, button and file:** [Reference](reference.md)

## What is covered

| Topic | Page |
| --- | --- |
| Divide and conquer, and why dependencies decide whether it works; the cost of change; observed, inferred and intended architecture; reviewing an unknown system | [Why architecture matters](why-architecture.md) |
| Dependencies, levels, violations, cycles, tangles, components | [Concepts](concepts.md) |
| Installation, the first analysis step by step | [Getting started](getting-started.md) |
| The five defects of the sample, what they mean and how to judge them | [The web shop story](webshop-story.md) |
| Cycles, API leaks, excessive coupling and what static analysis cannot decide | [Architecture smells](architecture-smells.md) |
| JARs, Maven and Gradle projects, Python, Go, JavaScript and C source, what is left out | [Loading code](loading-code.md) |
| The window part by part: menus, toolbar, tabs, panels, status bar | [The window](window.md) |
| Where to look first: packages by breakout, libraries used, JARs read | [Hotspots](window.md#hotspots) |
| Reading the layers, arrows, overlays, filters, scopes, moving boxes | [Layered Architecture View](layered-view.md) |
| The dependencies as a matrix, with the libraries used | [Dependency matrix](matrix-view.md) |
| Top Tangles, the Tangle View, cutting dependencies | [Tangles](tangle-view.md) |
| API and implementation, violations of the boundary, planned packages | [Component View](component-view.md) |
| Core, application and adapters, ports | [Hexagonal View](hexagonal-view.md) |
| The modules a system is built into: their layers, cycles between JARs, split packages | [Physical View](physical-view.md) |
| All packages of one depth side by side; which tangles lie in the grouping and which in the code | [Horizontal Slice](slice-view.md) |
| The architecture in three dimensions, in the window and as a city in the browser | [3D views](3d-views.md) |
| The fat/tangled plot and the Quality Report | [Quality](quality.md) |
| From a finding to a list of changes; the reports, undo and reset | [Planning a refactoring](refactoring.md) |
| Project files, what is remembered, pictures and reports | [Projects and export](projects-and-export.md) |
| Menus, toolbar, mouse and keyboard, files, settings, status of the features, known limitations | [Reference](reference.md) |
| The ideas and research behind modules, dependency models and layered visualization | [Literature](literature.md) |

## The sample project

Most pictures show the same small system, a web shop of 29 classes. Its source code is in
[example/webshop](example/webshop), so every picture can be reproduced:

```bash
cd docs/handbook/example/webshop
mvn package                      # produces target/webshop-1.0.jar
```

The shop is mostly well structured: `order`, `payment`, `catalog` and `customer` are components
with an `api` and an `impl` package, above them are `ui`, `persistence` and `app`, below them is
`common`. It has five deliberate defects, each marked in the source code, and the handbook finds
all of them. [The web shop story](webshop-story.md) tells how to judge them:

| Defect | Where | Kind |
| --- | --- | --- |
| `RefundPolicy` in `payment` asks `order` back, which uses `payment` itself | `payment` → `order` | package cycle |
| `AuditLog` in the foundation knows `Customer`, which calls it in turn | `common` → `customer` | class cycle across packages |
| `OrderValidator` calls back its `OrderProcessor` | inside `order.impl` | class cycle inside a package |
| The interface `CatalogService` hands out a class of the implementation | `catalog.api` → `catalog.impl` | API leak, and a package cycle |
| `RefundScreen` uses the implementation of `payment` instead of its API | `ui` → `payment.impl` | API bypass |

Other code is used now and then: the [Hexagonal View](hexagonal-view.md) shows the project
[test-hexagon](../../test-hexagon) of this repository, and a few pictures show S202 itself.

## Status language

| Status | Meaning |
| --- | --- |
| **Stable** | Used routinely. |
| **Beta** | Complete and usable; details may still change. |
| **Alpha** | Works for typical projects; results should be checked. |
| **PoC** | A proof of concept, for trying out. |
| not rated | The feature is there, but the project has not given it a status yet. |

The status of every feature is in the [Reference](reference.md#status-of-the-features). What is
known not to work is listed in [Known limitations](reference.md#known-limitations), and mentioned
where it matters.

[Next: Why architecture matters →](why-architecture.md)
