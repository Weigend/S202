# VS Code Setup

## Extensions installieren

- **Extension Pack for Java** (Microsoft): `vscjava.vscode-java-pack`
- **Maven for Java** (Microsoft): `vscjava.vscode-maven`

## Projekt öffnen

```bash
cd /home/johannes/Programieren/Structure202
code .
```

## Anwendung starten

### Option 1: Maven Task
1. `Ctrl+Shift+P` → `Maven: Run from Terminal`
2. Wähle `javafx:run`

### Option 2: Terminal
```bash
mvn javafx:run
```

### Option 3: Run Configuration

Erstelle `.vscode/launch.json`:
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
Dann `F5` zum Starten.

## Tests ausführen

```bash
mvn test
```

Oder über VS Code: Test Explorer (Beaker-Icon in Sidebar).
