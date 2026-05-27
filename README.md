# S202 Code Analyzer

A JavaFX-based tool for analyzing Java bytecode and visualizing code architecture.

![UI Screenshot](docs/Beispiel.png)

## Features

- **Bytecode analysis**: Parses Java `.class` files with ASM 9.6
- **Dependency detection**: Extracts class and package dependencies
- **Cycle detection**: Finds cyclic dependencies (Strongly Connected Components)
- **Architecture layering**: Topological ordering by dependency depth
- **Hierarchical visualization**: JavaFX TreeView with expandable packages
- **Violation detection**: Marks architectural violations (backward dependencies)
- **Multi-project import**: Load Maven (`pom.xml`) and Gradle (`settings.gradle`) multi-module projects directly; all module JARs are collected automatically
- **Layout invariant check**: Five machine-checkable invariants act as plausibility alerts for developers; four of them (R1/R2/R3/R5) never fire on a correct pipeline and report algorithm bugs with a copyable reproducer block, while R1-visual fires only on remaining edges of broken cycles and shows real architectural violations

## Quick Start

```bash
# Build
mvn clean install

# Start application
mvn javafx:run

# Run tests
mvn test
```

Then use the **File** menu:

- **Open JAR…** - one or more JARs (multi-selection opens a staging dialog)
- **Open Maven Project…** - select the root `pom.xml`; all module JARs from `target/` are collected
- **Open Gradle Project…** - select `settings.gradle(.kts)` or `build.gradle(.kts)`; all module JARs from `build/libs/` are collected

The architecture is analyzed, visualized, and automatically checked against five layout invariants (plausibility alerts).

## Requirements

- **Java 21+**
- **Maven 3.9+**
- **JavaFX 21.0.1** (loaded automatically via Maven)

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
3. **Navigate**: Expand and collapse packages, inspect dependencies
4. **Violations**: Bold dashed arrows show architectural problems (package aggregates use a filled circle to bundle the call count); for pipeline bugs, a reproducer dialog opens with a copy button

## VS Code Integration

```bash
code .
# Ctrl+Shift+P → "Maven: Run from Terminal" → javafx:run
```

Details: [docs/VS_CODE_SETUP.md](docs/VS_CODE_SETUP.md)

## Documentation

- [QUICKSTART.md](QUICKSTART.md) - Quick introduction
- [docs/](docs/) - Additional technical documentation
