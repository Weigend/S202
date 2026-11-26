# VS Code Setup

## Prerequisites

- **JDK 25 with JavaFX.** The file `.sdkmanrc` names the JDK S202 is developed with
  (Liberica Full JDK 25); with SDKMAN: `sdk env install` once, then `sdk env`.
- **WFX built once.** WFX, the UI platform, is not on Maven Central. Run
  `./build-all.sh` (Windows: `build-all.bat`) once before the first build; it clones
  and builds WFX and then builds S202.

## Install Extensions

- **Extension Pack for Java** (Microsoft): `vscjava.vscode-java-pack`
- **Maven for Java** (Microsoft): `vscjava.vscode-maven`

## Open the Project

```bash
cd <your clone of S202>
code .
```

The checked-in `.vscode/settings.json` turns off the automatic update of the Java
build configuration (`java.configuration.updateBuildConfiguration: disabled`); after
changing a `pom.xml`, update the project by hand (*Java: Update Project
Configuration*).

## Start the Application

### Option 1: Terminal
```bash
./run-ui.sh                    # Windows: run-ui.bat
./run-ui.sh path/to/your.jar   # with files to open
```
or, with Maven directly:
```bash
mvn javafx:run -pl analyzer
```

### Option 2: Run Configuration

Create `.vscode/launch.json`:
```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "S202 Analyzer",
      "type": "java",
      "request": "launch",
      "mainClass": "de.weigend.s202.ui.shell.S202Main",
      "projectName": "s202-code-analyzer"
    }
  ]
}
```
Then press `F5` to start. Files to open can be passed as `args`.

## Run Tests

```bash
mvn test
```

Or through VS Code: Test Explorer (beaker icon in the sidebar).
