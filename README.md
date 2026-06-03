![S202 Teaser](docs/s202-teaser.png)

# S202 Code Analyzer

A JavaFX-based tool for analyzing Java bytecode and visualizing code architecture.


![UI Screenshot](docs/image1.png)

## Features

- **Bytecode analysis**: Parses Java `.class` files with ASM 9.6
- **Dependency detection**: Extracts class and package dependencies
- **Cycle detection**: Finds cyclic dependencies (Strongly Connected Components)
- **Architecture layering**: Topological ordering by dependency depth
- **Hierarchical visualization**: JavaFX TreeView with expandable packages
- **Component View**: Shows top-level components with an explicit API area above the implementation packages
- **Violation detection**: Marks architectural violations (backward dependencies)
- **Component policy checks**: Detects calls into another component's implementation and API classes depending on implementation classes
- **Multi-project import**: Load Maven (`pom.xml`) and Gradle (`settings.gradle`) multi-module projects directly; all module JARs are collected automatically
- **Layout invariant check**: Five machine-checkable invariants act as plausibility alerts for developers; four of them (R1/R2/R3/R5) never fire on a correct pipeline and report algorithm bugs with a copyable reproducer block, while R1-visual fires only on remaining edges of broken cycles and shows real architectural violations

## Quick Start

**First time setup** — WFX (the UI platform dependency) is not on Maven Central.
Run the bootstrap script once; it clones WFX, builds it, then builds S202:

**Linux / macOS**
```bash
chmod +x build-all.sh && ./build-all.sh
# then start with:
./run-ui.sh
# or pass a JAR directly:
./run-ui.sh path/to/your.jar
```

**Windows** (cmd or double-click)
```
build-all.bat
rem then start with:
run-ui.bat
```

After the initial bootstrap, the standard Maven commands also work:
```bash
mvn clean install        # build
mvn javafx:run -pl analyzer  # start
mvn test                 # tests
```

Then use the **File** menu:

- **Open JAR…** - one or more JARs (multi-selection opens a staging dialog)
- **Open Maven Project…** - select the root `pom.xml`; all module JARs from `target/` are collected
- **Open Gradle Project…** - select `settings.gradle(.kts)` or `build.gradle(.kts)`; all module JARs from `build/libs/` are collected

The architecture is analyzed, visualized, and automatically checked against five layout invariants (plausibility alerts).

## Component View

The **Component View** is available from the **View -> Component View** menu after loading a JAR or project. It keeps the normal layered package ordering, but projects packages with a public API into component boxes: the API is shown in a blue section at the top, the implementation keeps the regular nested package layout below it.

![Component View showing WFX modules](docs/wfx-architecture.png)

Component roots are top-level packages that contain API classes. Components are not nested into other components; packages inside the implementation area remain collapsible and use the same local hierarchy as the normal architecture view.

API membership is determined in this order:

- Manual markings from the context menu: **Add To Api** and **Remove From Api**
- JPMS `exports` from `module-info.class`
- Packages named `api`, `apis`, `port`, or `ports`
- Interfaces and classes whose name ends in `Api` or `API`

You can also drag classes between the API area and the implementation area. These manual API decisions are stored with the project and are reapplied when the project is loaded again.

Component-specific findings are shown in the dependencies side view under **Component violations** while the Component View is active. The current checks flag calls from outside a component into its implementation and API classes that depend on implementation classes. Regular package-layer violations and package tangles are still shown separately, because the Component View does not change the underlying dependency graph or level calculation.

## Requirements

- **Java 21+**
- **Maven 3.9+**
- **JavaFX 21.0.5** (loaded automatically via Maven)

## Project Structure

```
analyzer/src/main/java/de/weigend/s202/
├── analysis/       # Algorithms (SCC, level strategies)
├── domain/         # Core models (DomainModel, LevelCalculator)
├── reader/         # JAR loading, dependency extraction
└── ui/             # JavaFX UI
```

## Usage

1. **Load code**: Use `File -> Open JAR…` for individual JARs, or `Open Maven Project…` / `Open Gradle Project…` for complete multi-module builds
2. **Analyze**: Packages and classes are analyzed automatically; a layout invariant check reports plausibility alerts to the developer
3. **Navigate**: Expand and collapse packages or component boxes, inspect dependencies
4. **Change views**: Open **View -> Component View** to inspect API-vs-implementation boundaries
5. **Violations**: Bold dashed arrows show architectural problems (package aggregates use a filled circle to bundle the call count); for pipeline bugs, a reproducer dialog opens with a copy button

## VS Code Integration

```bash
code .
# Ctrl+Shift+P → "Maven: Run from Terminal" → javafx:run
```

Details: [docs/VS_CODE_SETUP.md](docs/VS_CODE_SETUP.md)

## Case Studies

**[→ docs/S202_CASE_STUDIES.md](docs/S202_CASE_STUDIES.md)** — Real-world codebases analyzed with S202: which architectural problems became visible and which refactoring decisions follow.

## Documentation

- [QUICKSTART.md](QUICKSTART.md) - Quick introduction
- Architecture concept paper:
  [Deutsch](docs/concept/Software-Architektur-als-Schichtendarstellung.pdf),
  [English](docs/concept/Software-Architektur-als-Schichtendarstellung-en.pdf),
  [Português](docs/concept/Software-Architektur-als-Schichtendarstellung-pt.pdf)
- [docs/](docs/) - Additional technical documentation
