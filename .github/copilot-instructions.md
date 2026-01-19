# S202 Code Analyzer - Copilot Instructions

## Project Overview
S202 is a **JavaFX-based bytecode analysis and architecture visualization tool**. It parses Java `.class` files, extracts dependency graphs, detects cyclic dependencies (strongly connected components), and visualizes code architecture with layered package hierarchies.

**Key capabilities**: Bytecode parsing (ASM 9.6) → Dependency extraction → SCC-based cycle detection → Topological layering → JavaFX visualization with parent package wrapping.

## Strict Architecture: Layered, UI-Free Core

The codebase enforces **strict separation of concerns** across 4 layers:

### Layer 0: Model (`de.weigend.s202.model.*`)
**Core data structures with ZERO external dependencies**. No UI, no ASM, no analysis logic.
- `JavaClass`: Represents a Java class (fqn, package, simple name, dependencies set)
- `JavaPackage`: Hierarchical package structure (subpackages, contained classes)
- `ClassDependency`: Represents a class-to-class reference (source, target, type)
- `CyclicDependency`: Represents cyclic component with member classes
- **Testing**: Comprehensive unit tests validate all model behavior. Any model change requires test updates.
- **Critical invariant**: Immutable package/class names; dependencies managed via `addDependency()` and `addDependencies()`

### Layer 1: IO/Analysis (Non-UI Core Logic)
**Two independent pipelines**:

**IO Package** (`de.weigend.s202.io.*`):
- `JarLoader`: Loads `.jar` files, extracts `.class` bytecode streams
- `BytecodeAnalyzer`: Parses bytecode with ASM, builds `JavaClass` models with dependencies

**Analysis Package** (`de.weigend.s202.analysis.*`):
- **Raw Analysis** (`raw/`): Converts `JavaClass` sets → `DependencyModel` (package hierarchy + dependencies)
  - `RawAnalyzer`: Builds hierarchy from classes, groups by package
  - `DependencyModel`: Stores package tree + class relationships
- **SCC Detection** (`scc/`): Strongly Connected Component analysis (Tarjan's algorithm O(V+E))
  - `TarjanSCCFinder`: Detects all cycles
  - `SCCDAGBuilder`: Builds DAG from SCC components, computes levels (longest path)
  - `StronglyConnectedComponent`: SCC representation with intra/inter-component edges
  - `EdgeClassification`: Classifies edges as NORMAL (downward), VIOLATION (upward), INTRA_SCC
- **Calculated Model** (`calculated/`): Applies layering/ordering logic
  - `LevelCalculator`: Assigns topological levels (replaces deprecated `LayerAssigner`)
  - `CalculatedModel`: Decorated model with levels, edge classifications, cycle info
- **Architecture Model** (`ArchitectureModelBuilder`): UI hierarchy generation with dependency-based ordering + parent package wrapping

### Layer 2: UI (`de.weigend.s202.ui.*`)
**JavaFX presentation layer** (consumes Layer 1 outputs):
- `AnalyzerApplication`: Main entry point, file loading, scene composition
- `ArchitectureView`: Controller managing toolbar, status bar, and tree view
- `PackageTreeView`: TreeView component with hierarchical expansion and canvas-based violation rendering
- `ArchitectureTreeCell`: Custom cell renderer with styling
- `ArchitectureTreeItem`: TreeItem implementation for hierarchical architecture nodes
- **Data flow**: JAR → RawAnalyzer → LevelCalculator → UIModelBuilder → ArchitectureNode tree → PackageTreeView
- **Important**: UI **only depends on UIModel**, not on JavaClass/JavaPackage

## Critical Data Flows

### 1. **Bytecode → Model Pipeline**
```
JAR file → JarLoader.loadJar() → BytecodeAnalyzer.analyze()
         → Set<JavaClass> with dependencies
         → RawAnalyzer.buildHierarchy()
         → JavaPackage tree (root package)
```

### 2. **Dependency Analysis → Layering**
```
JavaPackage (raw hierarchy)
  → DependencyGraphBuilder (aggregate package dependencies from classes)
  → TarjanSCCFinder (Tarjan's algorithm)
  → SCCDAGBuilder (build SCC DAG + compute levels)
  → EdgeClassification (classify violations)
  → LevelCalculator (assign topological levels)
  → CalculatedModel (decorated with layers, violations, cycles)
```

### 3. **UI Rendering (Modern Pipeline - Jan 2026)**
```
CalculatedModel
  → UIModelBuilder.build() (organize elements by level)
  → UIModel (List<List<UIElementInfo>> organized by level)
  → AnalyzerApplication.buildArchitectureNodeFromUIModel() (convert to tree)
  → ArchitectureNode tree (with dependencies embedded)
  → PackageTreeView (hierarchical display with violation overlay canvas)
```

**Important**: The UI **no longer depends on JavaClass/JavaPackage**. All UI operations use the modern UIModel pipeline.

## Key Conventions & Patterns

### **Null Safety & Validation**
- All public methods validate inputs with `Objects.requireNonNull()` + descriptive messages
- Never return null collections; return empty sets/lists instead
- Example: `public void addDependency(ClassDependency dependency) { Objects.requireNonNull(dependency, "dependency cannot be null"); dependencies.add(dependency); }`

### **Naming**
- **Full names** (fqn): `com.example.MyClass` (includes package)
- **Simple names**: `MyClass` (class name only)
- **Package names**: `com.example` (dot-separated)
- **Variables**: `fullName`, `simpleName`, `packageName` consistently

### **Testing Strategy**
- **Unit tests in `src/test/java/`** mirror package structure
- **Model tests** verify immutability, package hierarchy, dependency integrity
- **Analysis tests** (35+ tests) validate SCC, layering, ordering logic
- **Test example projects** in `test-example/` and `test-jar/` provide reproducible test cases
- Run tests: `mvn test` (Surefire runner)
- Run specific test: `mvn test -Dtest=ClassName`

### **Parent Package Wrapping**
- **Only applied when package depth ≥ 3** (e.g., `de.weigend.s202` gets wrapped with `de` → `de.weigend` → `de.weigend.s202`)
- `ArchitectureModelBuilder.wrapWithParentPackages()` creates synthetic nodes from outermost (`de`) inward
- Wrapping happens AFTER dependency aggregation, before sorting
- Purpose: Show full package hierarchy for deep structures

### **Dependency Stability & Ordering**
- All graph operations use **TreeSet** (sorted) for consistent iteration order
- `LayerAssigner.stabilizeDependencies()` sorts all neighbors before SCC computation
- Ensures deterministic results across runs
- **Topological sort** uses multi-key: (level, id, name) for stable tie-breaking

### **Cycle Detection: SCC Levels**
- **Level 0**: Leaf SCCs (no outgoing edges to other SCCs)
- **Level N**: SCCs whose longest path to leaves is N edges
- **Computed in `SCCDAGBuilder`** via longest-path algorithm
- Used for horizontal layer positioning in visualization

## Build & Test Commands

| Task | Command |
|------|---------|
| **Full build** | `mvn clean install` |
| **Run unit tests** | `mvn test` |
| **Run specific test** | `mvn test -Dtest=TarjanSCCFinderTest` |
| **Build JAR** | `mvn clean package -DskipTests` |
| **Run app (Maven)** | `mvn javafx:run` (from `analyzer/` dir) |
| **Run app (JAR)** | `java -jar target/s202-code-analyzer-1.0.0.jar` |

## External Dependencies
- **JavaFX 21.0.1**: UI framework (controls, fxml, graphics)
- **ASM 9.6**: Bytecode analysis (asm, asm-tree, asm-analysis)
- **JUnit 5.10.1**: Unit testing (API + engine)
- All managed via Maven in `pom.xml`

## When Contributing
1. **Model changes** → Update unit tests immediately (model tests are mandatory)
2. **Analysis algorithm changes** → Verify with `test-example/` projects + existing test suites
3. **UI changes** → Ensure Layer 1 logic remains UI-free
4. **Deprecations**: Mark with `@Deprecated` (e.g., `LayerAssigner` replaced by `LevelCalculator`)
5. **New classes**: Follow existing package structure; no circular dependencies between packages

## Debugging Tips
- **Set breakpoints** in `BytecodeAnalyzer` to inspect extracted dependencies
- **SCCs not detected?** → Check if graph has actual cycles (single nodes aren't SCCs with size > 1)
- **UI not showing data?** → Trace through `ArchitectureView` callbacks; verify `CalculatedModel` is populated
- **Layering incorrect?** → Inspect `LevelCalculator` output or enable debug output in `EdgeClassification`
