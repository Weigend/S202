# VS Code Quick Start Guide

## Installation und Setup

### 1. **VS Code Extensions**
Installiere folgende Extensions:

- **Extension Pack for Java** (Microsoft)
  - ID: `vscjava.vscode-java-pack`
  - Includes: Language Support, Debugger, Test Runner

- **Maven for Java** (Microsoft)
  - ID: `vscjava.vscode-maven`

Öffne VS Code und gehe zu Extensions (`Ctrl+Shift+X`) und suche nach den Extensions.

### 2. **Projekt öffnen**
```bash
cd /home/johannes/Programieren/Structure202
code .
```

## Anwendung starten

### **Variante 1: Mit Maven-Task (Empfohlen)**
1. Öffne Kommandopalette: `Ctrl+Shift+P`
2. Tippe: `Maven: Run from Terminal`
3. Wähle: `de.weigend:s202-code-analyzer [jarrun]`
4. Oder direkt im Terminal:
```bash
mvn javafx:run
```

### **Variante 2: Mit Run-Konfiguration**
1. Öffne `.vscode/launch.json` (oder erstelle sie)
2. Füge folgende Konfiguration ein:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "S202 Analyzer",
      "type": "java",
      "name": "Launch AnalyzerApplication",
      "request": "launch",
      "mainClass": "de.weigend.s202.ui.AnalyzerApplication",
      "projectName": "s202-code-analyzer",
      "console": "integratedTerminal",
      "cwd": "${workspaceFolder}"
    }
  ]
}
```

3. Drücke `F5` oder gehe zu Run → Run Without Debugging (`Ctrl+F5`)

### **Variante 3: Direct Run (Terminal)**
Öffne integriertes Terminal (`Ctrl+Backtick`) und führe aus:
```bash
mvn javafx:run
```

## Verwendung im Tool

### Hauptfenster
```
┌─────────────────────────────────────┐
│ S202 Code Analyzer - Architecture   │
│────────────────────────────────────│
│ [📂 Load JAR] [3 ↓] [🔄 Refresh]   │ ← Toolbar
├─────────────────────────────────────┤
│                                     │
│  📦 Root Package                    │
│    📦 com.example.service           │
│      📄 UserService [3 deps]        │  ← TreeView
│      📄 AuthService [2 deps]        │
│    📦 com.example.repository        │
│      📄 UserRepository              │
│                                     │
├─────────────────────────────────────┤
│ Loaded 24 classes | 5 packages      │ ← Status Bar
└─────────────────────────────────────┘
```

### Schritte zur Analyse

**1. JAR-Datei laden**
- Klicke auf den `📂 Load JAR` Button
- Wähle eine `.jar`-Datei aus
- Die Anwendung analysiert die Klassen

**2. Struktur erkunden**
- Klicke auf **Pfeile** (▶) um Pakete auf/zuzuklappen
- Siehe Abhängigkeitscount in Klammern `[5 deps]`

**3. Tiefe anpassen**
- Ändere "Auto-Expand Depth" von 1-10
- Standard: 3 Ebenen (wie Structure101)
- Speichert für nächste Analyse

**4. Detailansicht**
- Wähle ein Paket/Klasse aus → Abhängigkeiten im Status

## Debugging

### Breakpoints setzen
1. Klicke auf die Zeilennummer (vor dem Code)
2. Rot-Punkt erscheint
3. Starte Debug-Session (`F5`)

### Watch-Ausdrücke
1. Öffne Debug-Panel: `Ctrl+Shift+D`
2. Gib Variable ein: z.B. `node.getFullName()`

## Nützliche Shortcuts

| Aktion | Shortcut |
|--------|----------|
| Kommandopalette | `Ctrl+Shift+P` |
| Integriertes Terminal | `` Ctrl+` `` |
| Quick Open | `Ctrl+P` |
| Go to Symbol | `Ctrl+Shift+O` |
| Format Document | `Shift+Alt+F` |
| Run/Debug | `F5` |
| Stop Debug | `Shift+F5` |

## Troubleshooting

### "Java Runtime not found"
```bash
# Check Java Version
java -version

# Set JAVA_HOME (falls nötig)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
```

### Maven Fehler
```bash
# Cache löschen und neubuild
mvn clean install -U

# Mit Offline-Mode deaktivieren
mvn -o clean install
```

### JavaFX Display Fehler (Linux)
```bash
# Fallback auf Mesa oder X11
export DISPLAY=:0
# Oder mit software rendering
mvn javafx:run -Dglass.platform=monocle -Dmonocle.platform=x11
```

## Performance-Tipps

1. **Große JAR-Dateien**: Nutze `Auto-Expand Depth: 2` für schnelleres Laden
2. **Memory**: Starte mit mehr RAM:
   ```bash
   export MAVEN_OPTS="-Xmx2g"
   mvn javafx:run
   ```

3. **Incremental Build**: Nutze Maven Daemon
   ```bash
   mvnd javafx:run  # Falls installiert
   ```

## Build und Deployment

### JAR erstellen (ausführbar)
```bash
mvn clean package
java -jar target/s202-code-analyzer-1.0.0.jar
```

### Nur kompilieren (ohne Tests)
```bash
mvn clean compile -DskipTests
```

### Tests einzeln ausführen
```bash
# Alle Tests
mvn test

# Nur spezifische Test-Klasse
mvn test -Dtest=JavaClassTest

# Nur spezifische Test-Methode
mvn test -Dtest=JavaClassTest#testConstructor
```
