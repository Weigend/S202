# S202 Code Analyzer - Copilot Instructions

## Project Overview
S202 is a **JavaFX-based bytecode analysis and architecture visualization tool**. It parses Java `.class` files, extracts dependency graphs, detects cyclic dependencies (SCC), and visualizes code architecture.

**Tech Stack**: Java 21, JavaFX 21.0.1, ASM 9.6, JUnit 5, Maven, WFX rich-client platform

## Architecture

### Package Structure
```
de.weigend.s202/
├── analysis/           # Analyse-Algorithmen
│   ├── scc/            # Tarjan SCC-Algorithmus + EdgeClassification
│   ├── strategy/       # Level-Berechnungsstrategien
│   ├── invariants/     # LayoutInvariantChecker (R1/R2/R3/R5)
│   └── quality/        # Quality metrics
├── domain/             # Kernmodelle (DomainModel, LevelCalculator)
├── reader/             # Input adapters
│   ├── InputAnalyzer       # JAR → DependencyModel
│   ├── MavenProjectScanner # Multi-module pom.xml walker
│   └── GradleProjectScanner# settings.gradle include walker
└── ui/                 # JavaFX UI (WFX-hosted)
    ├── model/          # ArchitectureNode, UIModel
    ├── wfx/            # WFX module + dialogs (SourceSet, InvariantReport)
    └── demo/           # Demo-Klassen
```

### Data Pipeline
```
JAR(s) → InputAnalyzer → DependencyModel
       → LevelCalculator → DomainModel
       → LayoutInvariantChecker → LayoutInvariantReport (parallel branch)
       → ArchitectureNodeBuilder → ArchitectureNode (tree)
       → ArchitectureView.setArchitectureRoot()
```

### Key Classes
- **InputAnalyzer** (`reader/`): Converts JAR to DependencyModel (packages, classes, dependencies)
- **MavenProjectScanner / GradleProjectScanner** (`reader/`): Walk multi-module project trees and return analyzable JARs
- **LevelCalculator** (`domain/`): Class-level SCC-aware levels, package-level rollup, then a 4b/5 convergence loop that equalises pkg-SCC peers and propagates parents
- **LayoutInvariantChecker** (`analysis/invariants/`): Verifies the level pipeline output (R1 non-back-edge inversion, R2 pkg-SCC equal level, R3 container ≥ content, R5 type-flag drift)
- **DomainModel** (`domain/`): Analysis result (levels, violations, cycles)
- **ArchitectureNode** (`ui/model/`): UI tree node (package/class with level + dependencies)
- **ArchitectureNodeBuilder** (`ui/model/`): Builds UI tree from DomainModel

## UI Model
The unified UI model is `ArchitectureNode` (in `ui/model/`):
- Tree structure with `children` list
- `NodeType`: PACKAGE or CLASS
- `level`: Topological layer (0 = leaf, higher = more dependencies)
- `dependencies` and `dependents` sets
- Utility methods: `getLevelCount()`, `getMaxLevel()`, `getStatistics()`

## Build Commands

| Task | Command |
|------|---------|
| Build | `mvn clean install` |
| Test | `mvn test` |
| Run | `mvn javafx:run` |

## When Contributing
1. **Analysis changes** → Run existing test suites
2. **UI changes** → Analysis layer must remain UI-free
3. **New classes** → Follow package structure, avoid circular dependencies
