# Concepts

[Handbook home](README.md) · [Getting started](getting-started.md) ·
[Layered Architecture View](layered-view.md) · [Tangles](tangle-view.md)

This page explains the terms the views use. The theory behind them is in the
[architecture concept paper](../concept/Software-Architektur-als-Schichtendarstellung-en.pdf).

## Dependencies

S202 works on classes and packages. A class depends on another one when its code mentions it:

| Kind | Examples |
| --- | --- |
| Calls | calling a method of the class, creating an instance |
| Inheritance | `extends`, `implements`, also by a nested class |
| Imports | an import, in languages that are read from source |
| References | every other mention: the type of a field, a parameter or a return value, a cast, an annotation, a caught exception, a class literal |

Every kind counts. A class that only stores another one in a field depends on it just as one that
calls it. The filter [Edge Kinds](layered-view.md#edge-kinds) hides kinds in the picture; it does
not change what is computed.

A class dependency has a **weight**: the number of method calls that carry it, and 1 if there
are none. The weight says what it would cost to remove the dependency.

Nested, local and anonymous classes are not shown. What they depend on counts for the class
around them. In languages without classes the files or modules take their place; see
[Loading code](loading-code.md).

The dependencies of packages are those of their classes. A dependency between two classes counts as
one between two members - packages or classes - of the deepest package that contains both.
`com.webshop.ui.RefundScreen → com.webshop.payment.impl.CardPayments` is therefore a dependency
of `ui` on `payment`, the two members of `com.webshop`.

## Levels and layers

Within a package, the members - classes and sub-packages alike - are put into rows:

- what depends on no other member is on level 0, the bottom row;
- everything else is one level above the highest member it depends on.

Without cycles the result is a picture in which every dependency points downwards, and members of the
same row do not depend on each other. What happens with cycles is said under
[Violations](#violations).

There are two numbers, both starting at 0:

| In the picture | Name | Meaning |
| --- | --- | --- |
| `L:2` | local level | the row within the surrounding package |
| `G:4` | global architecture level | the position in the whole system: for a class the longest chain of class dependencies below it, for a package the longest chain of package dependencies |

Local levels are always shown, global ones with **Show Arch Level**. The picture follows the
local levels only. Two classes with `L:0` in different packages have nothing to do with each
other; two classes with `G:0` are both at the very bottom of the system. For the level of a class
only the dependencies ignored because of a *class* cycle do not count; one that merely closes a
package cycle does. For the level of a package no ignored dependency counts.

## Violations

A dependency that runs upwards in the picture is a **violation**, also called a *wrong-direction
edge*. In code as it was analyzed this happens for one reason only: the members form a cycle.
Then no order exists in which all dependencies point down, and S202 has to ignore some to get an
order at all. The ignored ones are the **back-edges**. S202 chooses them deterministically and
tries to cut where little weight runs against much.

So without cycles there are no violations, and removing all back-edges leaves the system without
cycles. Which dependency of a cycle is *wrong* in the sense of the design is a decision for the
architect; the back-edges are a proposal.

A cycle is not always visible as a violation. If both directions weigh the same, all dependencies of
the cycle become back-edges. Its members may then stand in the same row with nothing running upwards
between them. Such a cycle is found in [Top Tangles](tangle-view.md#top-tangles) and with **Class
SCCs** and **Package Cycles**.

A violation can also be made by hand: when a box is [moved](layered-view.md#moving-boxes) to a
place where its dependencies run upwards.

## Cycles and tangles

| Term | Meaning | Remedy |
| --- | --- | --- |
| Class cycle | Classes depend on each other in a circle: `A → B → A`. | Only a change of the code. |
| Package cycle | Packages depend on each other in a circle, through different classes: `p.A → q.B` and `q.C → p.D`, without a circle among the classes. | Moving a class may be enough. |
| Tangle | A group of members of one package - packages, classes or both - that depend on each other in circles. | See [Tangles](tangle-view.md). |

A class cycle within one small package is often harmless: those classes belong together. A class
cycle across packages and a package cycle are not, because such packages can neither be
understood nor built nor replaced one at a time.

## Components

A package is a **component** when it has a direct sub-package named `api` or `spi`. What is in
them is public, everything else is private. `api` is what others call, `spi` what others
implement. The [Component View](component-view.md) draws this boundary and reports who ignores
it.

## Boxes and colours

| In the views | Meaning |
| --- | --- |
| Yellow box, yellow parcel | package |
| Blue box, blue **C** | class, or the module of another language |
| Green **I** | interface |
| Light blue box with the sign of a lollipop | component, with its API on top |
| Orange frame | selected |
| Dark blue frame | moved by you |
| Grey arrow | dependency |
| Green arrow | dependency on what is selected |
| Black dashed arrow | violation |
| Black dotted arrow | violation that is not a back-edge, such as one made by a move |
| Red arrow, thicker than the others | selected; in a Tangle View the selected arrow is blue, and red is a back-edge |
| Red line | class cycle |
| Orange line | package cycle |

[Next: Getting started →](getting-started.md)
