# Why Architecture Matters

[Handbook home](README.md) · [Concepts](concepts.md) · [The web shop story](webshop-story.md) ·
[Architecture smells](architecture-smells.md)

A change sounds local and is not: one more condition for a refund, and both the payment and the
order component have to be changed. Every class involved looks reasonable on its own. The problem
is in the relationships between them, and that is the scale at which software architecture becomes
visible. [The web shop story](webshop-story.md) follows this change through the code.

## Architecture is about the cost of change

Software architecture is sometimes presented as a diagram made before the code. For an existing
system, a more useful question is:

> When this part changes, which other parts must be understood, tested and changed with it?

Dependencies carry that cost. If `A` depends on `B`, a change to the contract or behaviour of `B`
may force work in `A`. The arrow does not say that a change will propagate; it shows where it can.
Long chains, wide fan-out and cycles increase the amount of code that may have to be considered
together.

Good architecture does not mean having few dependencies. Useful software necessarily connects
things. It means that the dependencies express deliberate responsibilities and that likely changes
remain local:

- business rules do not depend on screens or databases;
- clients know a component's contract, not its implementation;
- a foundation does not know the business concepts built on top of it;
- parts intended to change independently do not form a cycle.

## Three different architectures

When reading an S202 view, keep three things separate:

| Kind | Question | Where it comes from |
| --- | --- | --- |
| **Observed architecture** | Which dependencies exist in the analyzed code? | Bytecode or source code. |
| **Inferred architecture** | Which order and groups best explain those dependencies? | Levels, back-edges, rings and themes computed by S202. |
| **Intended architecture** | Which dependencies and boundaries should exist? | Design decisions and team knowledge; in S202 package names, annotations, API marks, roles and planned moves. |

S202 begins with the first and computes the second. It cannot invent the third. A back-edge is a
candidate for investigation, not a verdict that this particular dependency is wrong; the architect
decides which direction expresses the design.

It works the other way as well. A dependency may run down through the layers and still be
undesirable: `ui` → `database` can be ordered without a cycle, but may go past the application that
was meant to lie between. A computed layer order cannot know that. The
[Component View](component-view.md) finds such a dependency if it enters a private implementation.

## Four lenses on the same code

S202 shows the same dependencies through different lenses. None replaces the others.

| Lens | It asks | Its principal rule | Typical finding |
| --- | --- | --- | --- |
| [Layers](layered-view.md) | What rests on what? | Dependencies run in one direction. | Back-edge, cycle, surprising depth. |
| [Components](component-view.md) | Who may know this implementation decision? | Outsiders use the API; the API does not expose the implementation. | API bypass, API leak. |
| [Hexagonal architecture](hexagonal-view.md) | What is core, application and adapter? | Dependencies point inward, and adapters meet the application at ports. | Outward dependency, port bypass. |
| [Tangles](tangle-view.md) | What cannot be ordered independently? | Parts meant to be separate do not depend on one another in a circle. | Class or package cycle. |

Layers order dependencies, components hide decisions. These two boundaries are independent: a layer
can contain several components, and a component has layers of its own. Hexagonal architecture gives
the parts a role. A tangle is no architecture style at all; it can occur in any of them.

## Reviewing an unknown system

A review of code has many parts: tests, error handling, security, performance, the code itself. The
structure is only one of them, but a central one, because it tells where to look and how to read
what is found there. The author of S202 has used a tool of this kind in every code review.

It answers three questions quickly.

**Is there an architecture?** Open the code and choose a small **Depth**, so that the main packages
are closed. Rows alone prove nothing: S202 finds an order even in a big ball of mud, because it
ignores [back-edges](concepts.md#violations) to get one. How much of the order is real, the cycles
say.

| | An architecture | A big ball of mud |
| --- | --- | --- |
| [Show Violations](layered-view.md#violations) | A few arrows, each with a story. | Arrows from everywhere to everywhere. |
| [Top Tangles](tangle-view.md#top-tangles) | Small groups. | One tangle that contains most of the main packages. |
| [Quality panel](quality.md#the-quality-panel) | The point is low: *Tangled* near 0. | The point is high: a large share of the dependencies lies on class cycles. |
| [Component View](component-view.md) | Packages with an API that is used. | No boundaries, or boundaries everybody ignores. |

**What is it about?** The names of packages and classes are the terms of the domain as the developers
understood them, and their places say how the terms are related. The bottom rows hold what everything
is built on, the top rows what starts and controls. In the [web shop](webshop-story.md#the-picture)
one sees without reading any code that an order is about a catalog, customers and payments, and that
one arrow, from payment back to order, does not fit.
A term that appears in many places, or a package called `util` in the middle of the business, is a
question for the first talk with the team. Packages named after technology only, such as `controller`,
`service` and `dao`, say that the domain was not the principle of division.

**How good is it?** The [Quality Report](quality.md#the-quality-report) gives size, distribution,
coupling and cycles in numbers. The views give the examples: a dependency can be followed down to the
[calls and references](window.md#dependency-details) that carry it. With these examples a finding can
be discussed with the developers, instead of a number.

## What static dependencies can and cannot say

Every relationship S202 draws comes from the analyzed code and can be followed down to the calls and
references that carry it. It is still a partial model. From static code alone it does not know

- which business capabilities should change together;
- which dependencies exist only through configuration or reflection;
- which files repeatedly change together in version history;
- which services share a database, a deployment or a failure mode;
- whether a finding is an intentional trade-off.

Treat the picture as an architecture hypothesis: inspect it, compare it with the team's intent, then
make that intent more explicit. S202 does not replace the architect. It makes the architect's
questions answerable in concrete code.

The ideas behind this page are collected in [Literature](literature.md).

[Next: Concepts →](concepts.md)
