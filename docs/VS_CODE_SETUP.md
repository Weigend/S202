# VS Code Setup

## Install Extensions

- **Extension Pack for Java** (Microsoft): `vscjava.vscode-java-pack`
- **Maven for Java** (Microsoft): `vscjava.vscode-maven`

## Open the Project

```bash
cd /home/johannes/Programieren/Structure202
code .
```

## Start the Application

### Option 1: Maven Task
1. `Ctrl+Shift+P` -> `Maven: Run from Terminal`
2. Select `javafx:run`

### Option 2: Terminal
```bash
mvn javafx:run
```

### Option 3: Run Configuration

Create `.vscode/launch.json`:
```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "S202 Analyzer",
      "type": "java",
      "request": "launch",
      "mainClass": "de.weigend.s202.ui.AnalyzerApplication",
      "projectName": "s202-analyzer"
    }
  ]
}
```
Then press `F5` to start.

## Run Tests

```bash
mvn test
```

Or through VS Code: Test Explorer (beaker icon in the sidebar).
