# The Window

[Handbook home](README.md) · [Loading code](loading-code.md) ·
[Layered Architecture View](layered-view.md) · [Reference](reference.md)

![The main window with its parts numbered](images/window-overview.png)

| | Part | What it is for |
| --- | --- | --- |
| 1 | [Menu bar](#menus) | Opening, exporting, saving; views and panels. |
| 2 | [Toolbar](#toolbar) | How the picture of the active tab is drawn. |
| 3 | [Outline Explorer](#outline-explorer) | Packages, classes and methods as a tree. |
| 4 | [Top Tangles](tangle-view.md#top-tangles) | The cycles of the analysis, and the planned cuts. |
| 5 | [Quality](quality.md#the-quality-panel) | The analysis as a point between healthy and unhealthy. |
| 6 | [Tabs](#tabs) | The views: layers, components, hexagon, tangles, scopes, reports, 3D. |
| 7 | [Dependencies](#dependencies), [From / To](#from--to), [Hotspots](#hotspots) | Violations and package tangles as a list; what lies between two elements; where to look first. |
| 8 | [Dependency Details](#dependency-details) | What carries the selected line. |
| 9 | [Status bar](#status-bar) | What was done last, and the progress of what is being done. |

The panels 3, 4, 5, 7 and 8 always belong to the active tab: the one you worked in last. They
keep showing it while you work in a panel.

## Menus

| ![The Edit menu](images/menu-edit.png) | ![The View menu](images/menu-view.png) | ![The Windows menu](images/menu-windows.png) |
| --- | --- | --- |

The **File** menu is shown in [Loading code](loading-code.md). **Edit** undoes and redoes
[moves](layered-view.md#moving-boxes) and [resets](refactoring.md#undo-and-reset) all planned
changes. **View** shows a panel that was closed, and opens the 3D, Component and Hexagonal View.
**Windows** handles tabs and layout. All entries are listed in the
[Reference](reference.md#menus).

## Toolbar

![The toolbar](images/window-toolbar.png)

| Control | Effect |
| --- | --- |
| Open | Opens JARs, a Maven or a Gradle project. |
| Depth | How many levels of packages are open after building the picture; applied with **Refresh**. |
| Refresh | Builds the picture of the tab anew. Zoom, position and moves stay. |
| Matrix | Opens the [dependency matrix](matrix-view.md) of the tab in a tab of its own. |
| ↶ ↷ | Undo and redo a move. |
| ⟲ | Resets everything that was planned in this analysis: moves, API marks, cuts. Asks first. |
| Plan | Off: the code [as it was analyzed](refactoring.md#the-code-and-the-plan), without what is planned. The number counts that. |
| Show Dependencies | The [arrows](layered-view.md#dependencies). |
| Class SCCs | Class cycles, in purple. |
| Package Cycles | Package cycles, in orange. |
| Show Violations | The dependencies that run against the layers, red; in the Component and the Hexagonal View those that break the rules of the view, black. |
| Show Icons | The symbols of packages, classes and interfaces. |
| Show Arch Level | The global level `G:n` beside the local level `L:n`. |
| Limit Box Width | Keeps boxes [compact](layered-view.md#box-width). |
| Edge Kinds | Which [kinds of dependency](layered-view.md#edge-kinds) are drawn. |
| − 100% + | Zoom. |
| 1:1 | Zoom 100 %. |
| Fit | Zooms so that the whole picture fits into the window. |
| Camera | Saves the view as a [picture](projects-and-export.md#pictures). |

The toolbar shows the settings of the active tab; every tab has its own. **Show Icons** and **Show
Arch Level** are common to all. Every control has a tooltip. If the window is too narrow, the last
controls are behind the arrows **»** at the right end.

## Tabs

A tab is closed with the cross on it or with Ctrl+W. The last tab of an analysis closes the
analysis, without asking.

Tabs and panels can be dragged by their title: onto another tab to share its place, to an edge of
an area to divide it, or out of the window to become a window of their own. The lines between the
areas are dragged with the mouse.

A closed panel comes back with the **View** menu. Size and position of the window and the
arrangement of the panels are remembered for the next start.

**Windows → Default Layout** puts the panels where they were at first, shows those that were closed
and hides the 3D view. Open analyses and reports stay; their tabs go back into the middle, also those
that had a window of their own.

**Windows → New** opens an empty tab; it has no use of its own.

## Outline Explorer

![The outline with the menu of a package](images/outline-open-scope.png)

The tree contains what the active tab shows: for the layered view everything, for a tangle or a
scope only its part.

| Action | Effect |
| --- | --- |
| Click on a package or class | Selects it in the view and scrolls to it. |
| Selecting in a view | Opens the tree at that place. |
| Right-click on a package, **Open Scope** | Opens a tab with [this package alone](layered-view.md#scopes). The boxes of the views have this, too. |

Below a class are its methods with their signatures. They are the goal of a click in
[Dependency Details](#dependency-details).

## Dependencies

![The panel Dependencies](images/dependencies-panel.png)

| Section | Content |
| --- | --- |
| Component violations / Hexagonal violations | Only in the [Component](component-view.md#violations) and the [Hexagonal View](hexagonal-view.md#violations): what breaks the rules of that view. |
| Wrong-direction edges | The violations of the layers, grouped by package. Below a group are the class dependencies, below these the calls with their number. In the Component and the Hexagonal View this section is empty. |
| Package tangles | The tangles with at least one package in them. |

With a class or package selected, the list is limited to it, and the headings say so. A click on
the selection in the view lifts that. In a tangle tab the list contains what lies between the
classes shown; in a scope tab without a selection it is that of the whole analysis.

A double-click on an entry of *Wrong-direction edges* or *Package tangles* opens it in a
[Tangle View](tangle-view.md).

The panel follows [moves](layered-view.md#moving-boxes) and [cuts](tangle-view.md#cutting): it shows
how many violations would be left.

## From / To

![What order uses of payment](images/from-to.png)

The panel is a tab beside *Dependencies*. It answers what lies between two packages or classes, and
what an element uses or is used by. On the left is the using side, on the right the used one; both
trees contain what the active tab shows.

| Action | Effect |
| --- | --- |
| Select on both sides | The dependencies from the left to the right. |
| Select on the left only | Everything it uses outside itself. |
| Select on the right only | Everything outside it that uses it. |
| Click on the selected element | Lifts the selection of its side. |
| The field above a tree | Searches: only what contains the text, in its package. |
| **Back** | The question before. |
| **◀ Users** | The left side becomes the right one: who uses that? |
| **Uses ▶** | The right side becomes the left one: what does that use? |
| **⇄** | The opposite direction. |

What is selected on one side greys out on the other what it has nothing to do with. The number of
dependencies stands above; [Dependency Details](#dependency-details) lists them. With **Users** and
**Uses** a chain of dependencies is followed step by step.

A selected arrow, line or [matrix](matrix-view.md) cell appears here, too. **Dependencies From Here**
and **Dependencies To Here** in the menu of a class or package open the panel with it.

![What order uses of java.util](images/from-to-external.png)

Below the packages of the analysis, the branch **External** holds what the classes use outside it:
the JDK, frameworks, other libraries, by package. It answers questions such as *where do we use
`java.sql`?* or *does the core use a framework?* Select the package of the library on the right
and nothing on the left, and the left side keeps only the classes that use it. What lies outside
uses nothing, so on the left the branch stays grey. External classes count for no layer and no
cycle; see [what is left out](loading-code.md#what-is-left-out).

## Hotspots

![The packages of the webshop, the largest breakout first](images/hotspots.png)

The panel is a tab beside *From / To*. It ranks what the active tab shows, with the plan if the tab
shows it.

| Table | Rows | Columns |
| --- | --- | --- |
| **Packages** | Every package of the tab, the largest breakout first. | *Classes* in it at any depth; *Breakout*, *Avg deps* and *Tangled* as in the [Quality panel](quality.md#the-quality-panel); *Against*: class dependencies inside the package that run against its layers; *External*: classes outside the analysis that it uses. |
| **Libraries** | Every package outside the analysis that the tab uses, the most used first. | its *Classes* that are used, the classes of the analysis that use them (*Used by*), and the *Packages* these are in. |
| **JARs** | Every file the classes were read from. | *Classes* and *Packages* read from it, the other files it *Uses* and is *Used by*, and its *Split packages*: those with classes in another file, too. |

A click on a column heading sorts by it. A click on a package selects it in the picture and in the
[matrix](matrix-view.md); its menu has **Open Scope**, **Dependencies From Here** and **Dependencies
To Here**. A double-click on a library asks [From / To](#from--to) who uses it.

![The libraries the webshop uses](images/hotspots-libraries.png)

The packages above the top level are left out: each holds only the next one. A project saved before
release 1.7.0 does not know the files of its classes; read the code again to fill the table *JARs*.

## Dependency Details

![What carries the dependency of RefundPolicy on OrderService](images/dependency-details.png)

A click on an arrow, a violation, the line of a cycle or a [matrix](matrix-view.md) cell shows what it
consists of, and so does an answer of [From / To](#from--to). The line below the name sums up: whether the dependency is a back-edge, on which kind of cycle it lies, and
its weight.

| Rows | Meaning |
| --- | --- |
| Calls | The calling method, the called one, and how often. |
| `extends`, `implements`, `imports` ... | Inheritance and imports. |
| Other references | The places that mention the class without calling it: fields, parameters, casts. They keep the dependency alive even when every call is removed. |
| Cycle closes via | The shortest way back, which makes the dependency part of a class cycle. |
| Package cycle closes via | The same for a package cycle. Every step can be opened down to the calls. |

The arrow of a closed package stands for many class dependencies. They are listed with the
back-edges first, and each can be opened.

A dependency on a class outside the analysis, from [From / To](#from--to) or a library row of the
[matrix](matrix-view.md#libraries), says *outside the analysis*. It shows how the class is used:
calls, `extends`, references. Which methods are called is not recorded for libraries.

The field **Filter** limits the list to the rows that contain the text. A click on a method or class
shows it in the outline. Every tab remembers its selected line.

## Status bar

The left side says what was done last, and the bar on the right shows progress while something is
running. A selected class adds the file it was read from. Texts such as *What-If: RefundPolicy → com.webshop.order.impl — marked as moved* confirm
every planned change.

## Leaving

![The question before S202 is closed](images/exit.png)

**File → Exit** and closing the window ask first. There is no question about unsaved projects:
save them with **File → Save Project...** beforehand.

[Next: Layered Architecture View →](layered-view.md)
