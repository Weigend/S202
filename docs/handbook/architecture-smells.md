# Architecture Smells

[Handbook home](README.md) · [Why architecture matters](why-architecture.md) ·
[The web shop story](webshop-story.md) · [Quality](quality.md)

An architecture smell is evidence worth investigating, not proof that the design is wrong. The
same dependency can be harmless in one system and expensive in another. Its importance depends on
which parts should change independently, who owns them and which qualities the system needs.

## Smells S202 detects directly

### Cyclic dependency

**Signal.** Classes or packages depend on one another in a circle. S202 shows class cycles, package
cycles and their strongly connected groups as tangles.

**Why it matters.** There is no dependency order in which the members can be considered one at a
time. A change to one member can require understanding and testing the whole group. A cycle across
component or team boundaries is usually more consequential than one between two private classes.

**Ask.** Which direction expresses the intended responsibility? Is one class merely in the wrong
package? Does the lower part need a value or a narrow interface instead of the upper object?

**Use.** Open the [Tangle View](tangle-view.md), inspect the calls and references carrying each
direction, and preview a move or cut.

### Wrong-direction dependency

**Signal.** An edge runs upwards in the inferred layer order, or against a target order made with a
manual move.

**Why it matters.** In code as it was analyzed an upward edge has one reason only: its members form
a [cycle](concepts.md#violations). In a target arranged by hand it shows what has to change until
the code agrees with it.

**Ask.** Is the edge itself contrary to the design, or did S202 choose it only because every cycle
needs a cut? Would cutting a different edge express the responsibility better?

**Limit.** A downward edge is not automatically good. Computed layers cannot detect every forbidden
shortcut, such as an acyclic dependency `ui` → `database`.

### API bypass

**Signal.** Code outside a component uses a class that S202 considers private.

**Why it matters.** Clients become coupled to an implementation decision. Renaming, replacing or
reorganizing that implementation now requires coordinated changes outside its owner.

**Ask.** Does the API already have what the client needs? If not, is the missing concept genuinely
part of the component's contract? Is the client actually part of the component and merely misplaced?

**Use.** Inspect it in the [Component View](component-view.md#violations). Prefer using or designing
a contract over making implementation classes public merely to clear the finding.

### API leak

**Signal.** A public class depends on a private one, of its own component or of another.

**Why it matters.** If the private class is a parameter, a return type or a base class, whoever uses
the API has to know it. If it only appears within a method, as in a factory, clients do not see it,
but the API still cannot be separated from its implementation.

**Limit.** S202 reports both. Select the class with **Show Dependencies** on and click the arrow:
[Dependency Details](window.md#dependency-details) shows what carries it.

**Ask.** Can the API own the exposed type? Can the method return a type that is already in the API?
Does the method have to be public?

### Outward dependency and port bypass

**Signal.** In the Hexagonal View, an inner role depends on an outer one, or an adapter uses an
application class that is not a port.

**Why it matters.** Business or application logic becomes coupled to a delivery or infrastructure
mechanism. Replacing the UI, database or external service then reaches into the core.

**Ask.** Is the inferred role correct? Which side should own the port? Is this the composition root,
where creating concrete implementations may be an intentional exception?

## Smells for which S202 supplies evidence

These require interpretation; S202 does not claim to diagnose them conclusively.

| Smell | Evidence in S202 | What still needs judgment |
| --- | --- | --- |
| **Excessive coupling / link overload** | Many outgoing dependencies, a high *Fat* value, dense arrows. | Whether the dependencies represent one coherent responsibility. |
| **God package or component** | Disproportionate size, many levels, many incoming and outgoing dependencies. | Whether it owns too many business capabilities or is legitimately foundational. |
| **Common dumping ground** | A low-level package used widely but also depending on feature packages. | Which classes are truly generic and which belong to a feature. |
| **Missing component boundary** | Many packages with no explicit public/private distinction; outsiders use classes from all over them. | Where ownership and a stable API should be drawn. |
| **Dead or obsolete code** | A class has no incoming dependencies in the analyzed model. | Whether it is an entry point or is reached by reflection, configuration or another artifact. |
| **Architecture drift** | Current dependencies disagree with component rules, roles or a manually explored target. | Whether the old intent still serves the product. |
| **Big ball of mud** | Large tangles, high coupling, weak boundaries and no clear levels occur together. | The business and organizational causes; no single metric proves this smell. |

## Smells outside the model

Static source and bytecode dependencies cannot reveal every important coupling:

| Smell | Additional evidence needed |
| --- | --- |
| **Shotgun surgery / logical coupling** | Version history showing files that repeatedly change together. |
| **Shared database coupling** | Schema ownership, queries, migrations and service boundaries. |
| **Deployment coupling** | Build and deployment pipelines, version constraints, release history. |
| **Runtime cycles** | Traces, messages, callbacks and runtime topology. |
| **Organizational coupling** | Code ownership, team boundaries and coordination data. |
| **Performance or availability bottleneck** | Runtime measurements and operational architecture. |

A clean S202 picture therefore means that selected static structural properties are clean. It does
not certify the architecture as a whole.

## How to judge a finding

For each finding, write down five things, in an issue for example. The
[refactoring report](refactoring.md#refactoring-report) lists the planned changes only.

1. **Evidence:** the exact dependency and the code references carrying it.
2. **Intent:** the responsibility or boundary the team wants.
3. **Consequence:** what becomes harder if the dependency remains.
4. **Choice:** remove, invert, move, expose deliberately, or accept.
5. **Verification:** what the S202 view and the tests should show afterwards.

This turns a smell into an architectural decision.

The research and design principles behind cycles, modularity violations and architectural
technical debt are listed in [Literature](literature.md#architecture-smells).

[Next: Loading code →](loading-code.md)
