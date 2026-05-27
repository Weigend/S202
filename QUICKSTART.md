# S202 Code Analyzer - Quickstart

## 🚀 Start in 3 Steps

### 1. Open a terminal
```bash
cd /home/johannes/Programieren/Structure202
```

### 2. Build and start the application
```bash
mvn javafx:run
```

### 3. Load code
There are three entry points in the **File** menu:

- **Open JAR…** - one or more JARs (multi-selection opens a staging dialog)
- **Open Maven Project…** - select the root `pom.xml`; all module JARs from `target/` are collected
- **Open Gradle Project…** - select `settings.gradle(.kts)` or `build.gradle(.kts)`; all module JARs from `build/libs/` are collected

Prerequisite for Maven/Gradle projects: `mvn package` or `gradle build` must already have been run.

## Example: Analyze S202 Itself

```bash
mvn clean package -DskipTests
mvn javafx:run
# In the UI: File -> Open JAR... -> analyzer/target/s202-code-analyzer-1.0.0.jar
```

## Main Features

| Feature | Description |
|---------|--------------|
| **Open JAR / Maven / Gradle** | Load individual JARs or complete multi-module projects |
| **Package Tree** | Hierarchical package view |
| **Level Layout** | Packages sorted by dependency depth |
| **Violations** | Bold dashed arrows show architectural problems (package aggregates use a filled circle for the call count) |
| **Invariant Check** | Five invariants as plausibility alerts; four report algorithm bugs in the level pipeline, while R1-visual shows real architectural violations |

## Level Meaning

- **Level 0** = Base packages (no dependencies)
- **Level 1** = Depend on Level 0
- **Level 2+** = Depend on lower layers

## Useful Commands

```bash
mvn clean install    # Build project
mvn test             # Run tests
mvn javafx:run       # Start UI
```

## Additional Documentation

- [README.md](README.md) - Project overview
- [docs/VS_CODE_SETUP.md](docs/VS_CODE_SETUP.md) - VS Code Integration

**Q: Can I export a specific package structure?**
- Not yet - an export feature is on the TODO list (PlantUML, SVG, etc.)
