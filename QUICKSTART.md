# S202 Code Analyzer - Quickstart

## 🚀 Start in 3 Steps

### 1. Build once
WFX, the UI platform, is not on Maven Central. The bootstrap script clones and
builds it once, then builds S202:
```bash
cd <your clone of S202>
chmod +x build-all.sh && ./build-all.sh     # Windows: build-all.bat
```

### 2. Start the application
```bash
./run-ui.sh                    # Windows: run-ui.bat
# or with Maven: mvn javafx:run -pl analyzer
```

### 3. Load code
There are four entry points in the **File** menu:

- **Open JAR…** - one or more JARs (multi-selection opens a staging dialog)
- **Open Maven Project…** - select the root `pom.xml`; all module JARs from `target/` are collected
- **Open Gradle Project…** - select `settings.gradle(.kts)` or `build.gradle(.kts)`; all module JARs from `build/libs/` are collected
- **Open Source Code** - JavaScript, Go, Python or C sources

Prerequisite for Maven/Gradle projects: `mvn package` or `gradle build` must already have been run.

## Example: Analyze S202 Itself

```bash
mvn clean package -DskipTests
./run-ui.sh analyzer/target/s202-code-analyzer-1.9.0.jar
```

## Main Features

| Feature | Description |
|---------|--------------|
| **Open JAR / Maven / Gradle** | Load individual JARs or complete multi-module projects |
| **Package Tree** | Hierarchical package view |
| **Level Layout** | Packages sorted by dependency depth |
| **Violations** | Red arrows show architectural problems (package aggregates use a filled circle for the call count) |
| **Invariant Check** | Five invariants as plausibility alerts; four report algorithm bugs in the level pipeline, while R1-visual shows real architectural violations |

## Level Meaning

- **Level 0** = Base packages (no dependencies)
- **Level 1** = Depend on Level 0
- **Level 2+** = Depend on lower layers

## Useful Commands

```bash
mvn clean install             # Build project (after build-all once)
mvn test                      # Run tests
mvn javafx:run -pl analyzer   # Start UI
```

## Additional Documentation

- [README.md](README.md) - Project overview
- [docs/VS_CODE_SETUP.md](docs/VS_CODE_SETUP.md) - VS Code Integration

**Q: Can I export the current architecture graphic?**
- Yes. Choose **File → Export Image...** (Ctrl+E, or the camera in the toolbar) to save the active view as a PNG. The export includes content outside the visible scroll viewport.
- Structured PlantUML and SVG exports are still future options.
