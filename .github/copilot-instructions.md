# S202 Code Analyzer - Copilot Instructions

## Project Overview
S202 is a **JavaFX-based bytecode analysis and architecture visualization tool**. It parses Java `.class` files, extracts dependency graphs, detects cyclic dependencies (SCC), and visualizes code architecture.

**Tech Stack**: Java 17+, JavaFX 21.0.1, ASM 9.6, JUnit 5, Maven

## Architecture

### Package Structure
```
de.weigend.s202/
├── analysis/           # Analyse-Logik
│   ├── domain/         # DomainModel, LevelCalculator
│   ├── input/          # InputAnalyzer (JAR → DependencyModel)
│   ├── scc/            # Tarjan SCC-Algorithmus
│   └── strategy/       # Level-Berechnungsstrategien
├── io/                 # JarLoader, BytecodeAnalyzer (ASM)
└── ui/                 # JavaFX UI
    ├── model/          # ArchitectureNode, ArchitectureNodeBuilder
    └── views/          # ArchitectureView, PackageTreeView
```

### Data Pipeline
```
JAR → InputAnalyzer → DependencyModel 
    → LevelCalculator → DomainModel 
    → ArchitectureNodeBuilder → ArchitectureNode (tree)
    → ArchitectureView.setArchitectureRoot()
```

### Key Classes
- **InputAnalyzer**: Converts JAR to DependencyModel (packages, classes, dependencies)
- **LevelCalculator**: Computes topological levels with SCC analysis
- **DomainModel**: Analysis result (levels, violations, cycles)
- **ArchitectureNode**: UI tree node (package/class with level + dependencies)
- **ArchitectureNodeBuilder**: Builds UI tree from DomainModel

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
