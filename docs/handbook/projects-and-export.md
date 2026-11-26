# Projects and Export

[Handbook home](README.md) · [Loading code](loading-code.md) ·
[Planning a refactoring](refactoring.md) · [Reference](reference.md)

## Project files

**File → Save Project...** writes the analysis you are working on into a file that ends with
`.s202.json`. **File → Load Project...** opens it as a further analysis. The code is not needed
again: the file contains the dependencies.

| In the file | Not in the file |
| --- | --- |
| classes, packages and dependencies, as they were read, with what they use outside the analysis and the JAR of each class | levels, cycles and violations: they are computed on loading |
| the tabs: layered, component, hexagonal, scope and tangle views | 3D, City3D and Quality Report tabs |
| of each tab: Depth, zoom, position, open packages and its own switches, whether its [matrix](matrix-view.md) is open and how, with or without libraries; the selection of the active tab | where the tab was in the window; Show Icons and Show Arch Level, which are common to all tabs |
| API marks, roles and ports, cuts; of each tab its moves and planned packages | |

A project keeps what was read when it was made. After a change of the code, or with a new version of
S202 that reads more, open the code again.

Files of earlier versions can be loaded, and their architecture is computed anew. They do not say
which packages carry the annotations of S202, so components declared that way are missing. A file
saved with this version cannot be opened with release 1.6.0 or older.

## What is remembered

Without any project file, S202 remembers the size and place of the window and the arrangement of
the panels from one start to the next, in the directory `.s202` of your home directory. Open
analyses are not remembered. **Windows → Default Layout** goes back to the first arrangement; see
[Tabs](window.md#tabs).

The file dialogs begin in the directory that was used last, as long as S202 runs.

## Pictures

**File → Export Image...**, Ctrl+E or the camera in the toolbar saves the active view as a PNG file.
The picture contains the whole view, also what is outside the visible part, with its arrows and
overlays, in a size that does not depend on the zoom. In the tab of a
[matrix](matrix-view.md) it is the whole matrix. If the 3D tab has the focus, it is saved
instead.

## Reports

| Report | Made with | Form |
| --- | --- | --- |
| [Violations](refactoring.md#violation-report) | **File → Export Violations...** | text |
| [Planned refactoring](refactoring.md#refactoring-report) | **File → Export Refactoring Report...** | text |
| [Planned refactoring of a Component View](refactoring.md#json) | menu of its background | JSON, to copy |
| [Quality Report](quality.md#the-quality-report) | **File → Analyze Quality**, then **Export Report...** | HTML with pictures |

All of them belong to the analysis you are working on.

[Next: Reference →](reference.md)
