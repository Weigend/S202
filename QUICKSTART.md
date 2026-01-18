# S202 Code Analyzer - VS Code Quickstart

## 🚀 In 5 Schritten zum Start

### 1. Terminal öffnen
```bash
cd /home/johannes/Programieren/Structure202
code .
```

### 2. Integriertes Terminal in VS Code
Drücke `` Ctrl+` `` um das integrierte Terminal zu öffnen

### 3. Anwendung starten
```bash
mvn javafx:run
```

**Oder Alternative:** 
- Öffne Kommandopalette (`Ctrl+Shift+P`)
- Suche: `Maven: Run from Terminal`
- Wähle: `s202-code-analyzer [jarrun]`

### 4. JavaFX-Fenster erscheint
```
┌─────────────────────────────────────────────┐
│  S202 Code Analyzer - Architecture Viewer   │
├─────────────────────────────────────────────┤
│ [📂 Load JAR]   [3] [🔄 Refresh]           │
├─────────────────────────────────────────────┤
│                                             │
│  (Warte auf JAR-Datei...)                  │
│                                             │
└─────────────────────────────────────────────┘
```

### 5. JAR-Datei analysieren
- Klicke **📂 Load JAR**
- Wähle eine `.jar`-Datei (z.B. aus `target/` oder System JARs)
- Das Tool analysiert Bytecode und zeigt Struktur

## 📝 Beispiel: Analyse der eigenen S202 JAR

```bash
# Zuerst bauen (optional)
mvn clean package -DskipTests

# Dann im Tool: "Load JAR" → target/s202-code-analyzer-1.0.0.jar
```

Du siehst dann die S202-eigene Struktur:
```
📦 de
  📦 weigend
    📦 s202
      📦 analysis
        📄 ArchitectureModelBuilder
        📄 BytecodeAnalyzer
        📄 DependencyGraphBuilder
      📦 io
        📄 JarLoader
      📦 model
        📄 ClassDependency
        📄 CyclicDependency
        📄 JavaClass
        📄 JavaPackage
      📦 ui
        📄 AnalyzerApplication
        📄 ArchitectureTreeCell
        📄 ArchitectureTreeItem
        📄 ArchitectureView
      📦 example
        📄 AnalyzerExample
```

## 🎯 Funktionen

| Feature | Hotkey | Beschreibung |
|---------|--------|-------------|
| **Load JAR** | Klick | Datei-Dialog für JAR-Auswahl |
| **Auto-Expand** | Spinner | Tiefe 1-10 (Default: 3) |
| **Refresh** | 🔄 Klick | UI aktualisieren |
| **Tree Expand** | ▶ Klick | Pakete auf/zuklappen |

## 🔧 Debug-Mode

Für Debugging mit Breakpoints:

1. Öffne [AnalyzerApplication.java](src/main/java/de/weigend/s202/ui/AnalyzerApplication.java)
2. Klicke auf eine Zeilennummer → Roter Punkt
3. Drücke `F5` → Debug-Session startet
4. Nutze Debug-Panel (`Ctrl+Shift+D`) für Watches

## 💡 Tipps

- **Große JAR-Dateien?** → Tiefe auf 2 setzen für schnelleres Laden
- **Viel RAM nötig?**
  ```bash
  export MAVEN_OPTS="-Xmx2g"
  mvn javafx:run
  ```

- **Projekt neu bauen?**
  ```bash
  mvn clean install
  ```

- **Tests ausführen?**
  ```bash
  mvn test
  ```

## 📚 Weitere Ressourcen

- [README.md](README.md) - Vollständige Dokumentation
- [VS_CODE_SETUP.md](VS_CODE_SETUP.md) - Detailliertes Setup-Guide
- [src/main/java/](src/main/java/) - Quellcode mit JavaDoc

## ❓ FAQ

**F: Wo finde ich JAR-Dateien zum Testen?**
A: In `target/` (nach Maven Build), oder System JARs:
- Linux: `/usr/lib/jvm/java-17-openjdk/`
- Java's `rt.jar` (falls Java 8)

**F: Tool lädt JAR nicht?**
A: Schau in den Status-Bar für Fehler-Messages und Terminal-Output

**F: Struktur wird nicht angezeigt?**
A: Stelle sicher, dass die JAR valide .class-Dateien enthält

**F: Performance-Probleme?**
A: 
- Reduziere Auto-Expand Depth
- Nutze größeres Heap: `-Xmx2g`
- Oder nutze kleinere JARs zum Testen
