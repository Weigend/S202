# S202 Code Analyzer

Ein JavaFX-basiertes Tool zur Analyse von Java-Bytecode und Visualisierung der Architektur mit Abhängigkeitserkennung.

## Features

- **Bytecode-Analyse**: Parst Java .class-Dateien mit ASM-Bibliothek
- **Abhängigkeitserkennung**: Extrahiert Klassen- und Paket-Abhängigkeiten
- **Zyklische Abhängigkeiten**: Automatische Erkennung und Aggregation von Zyklen
- **Hierarchische Visualisierung**: Aufklappbare Pakete und Klassen mit Tree-View
- **Auto-Expand**: Konfigurierbare Tiefe für automatisches Aufklappen (typisch 3 Ebenen)
- **Top-Down Layout**: Abhängige Pakete über ihren Abhängigkeiten dargestellt

## Projektstruktur

```
src/
├── main/
│   ├── java/de/weigend/s202/
│   │   ├── model/               # Datenmodelle
│   │   │   ├── ClassDependency.java
│   │   │   ├── JavaClass.java
│   │   │   ├── JavaPackage.java
│   │   │   └── CyclicDependency.java
│   │   ├── analysis/            # Analyse-Logik
│   │   │   ├── BytecodeAnalyzer.java
│   │   │   ├── DependencyGraphBuilder.java
│   │   │   └── ArchitectureModelBuilder.java
│   │   └── ui/                  # JavaFX UI
│   │       ├── ArchitectureView.java
│   │       ├── ArchitectureTreeCell.java
│   │       ├── ArchitectureTreeItem.java
│   │       └── AnalyzerApplication.java
│   └── resources/
└── test/
    └── java/de/weigend/s202/
        ├── model/               # Unit Tests für Modelle
        └── analysis/            # Unit Tests für Analyse
```

## Dependencies

- **JavaFX 21.0.1**: UI-Framework
- **ASM 9.6**: Bytecode-Analyse
- **JUnit 5**: Unit Testing
- **Java 17+**: Minimum JDK Version

## Build & Run

### Build
```bash
mvn clean install
```

### Tests ausführen
```bash
mvn test
```

### Anwendung starten (Variante 1: Mit Maven)
```bash
mvn javafx:run
```

### Anwendung starten (Variante 2: JAR-Datei)
```bash
java -jar target/s202-code-analyzer-1.0.0.jar
```

### In VS Code starten
1. Öffne die Kommandopalette: `Ctrl+Shift+P`
2. Wähle "Maven: Run from Terminal" oder
3. Drücke `F5` (mit launch.json Konfiguration)
4. Weitere Details siehe [VS_CODE_SETUP.md](VS_CODE_SETUP.md)

## Architektur

### Trennung der Schichten

#### 1. **Model Layer** (`de.weigend.s202.model`)
- Reine Datenklassen
- Keine Abhängigkeiten zu UI oder ASM
- Vollständig mit Unit Tests abgesichert

#### 2. **Analysis Layer** (`de.weigend.s202.analysis`)
- `BytecodeAnalyzer`: Konvertiert .class → JavaClass Modelle
- `DependencyGraphBuilder`: Konstruiert Abhängigkeitsgraph
- `ArchitectureModelBuilder`: Erstellt UI-Modell mit Sortierung und Filterung

#### 3. **UI Layer** (`de.weigend.s202.ui`)
- `ArchitectureView`: Hauptkomponente mit TreeView
- `ArchitectureTreeCell`: Custom TreeCell für Styling
- `AnalyzerApplication`: Entry Point

### Datenfluss

```
.class Dateien
     ↓
BytecodeAnalyzer (ASM)
     ↓
JavaClass + ClassDependency (Modelle)
     ↓
DependencyGraphBuilder
     ↓
JavaPackage Hierarchie + Cycles
     ↓
ArchitectureModelBuilder
     ↓
ArchitectureNode (für UI)
     ↓
ArchitectureView (JavaFX)
```

## Verwendung

### Basis-Beispiel

```java
// 1. Bytecode analysieren
BytecodeAnalyzer analyzer = new BytecodeAnalyzer();
JavaClass myClass = analyzer.analyzeClass(
    "com.example.MyClass",
    new FileInputStream("MyClass.class")
);

// 2. Graph konstruieren
DependencyGraphBuilder builder = new DependencyGraphBuilder();
builder.addClass(myClass);
JavaPackage root = builder.buildPackageHierarchy("com");

// 3. UI-Modell erstellen
ArchitectureModelBuilder uiBuilder = new ArchitectureModelBuilder();
ArchitectureNode model = uiBuilder.buildModel(root, 3); // 3 Ebenen auto-expand

// 4. In UI anzeigen
architectureView.setArchitectureRoot(model);
```

## Verwendung

### Basis-Beispiel

```java
// 1. Bytecode analysieren
BytecodeAnalyzer analyzer = new BytecodeAnalyzer();
JavaClass myClass = analyzer.analyzeClass(
    "com.example.MyClass",
    new FileInputStream("MyClass.class")
);

// 2. Graph konstruieren
DependencyGraphBuilder builder = new DependencyGraphBuilder();
builder.addClass(myClass);
JavaPackage root = builder.buildPackageHierarchy("com");

// 3. UI-Modell erstellen
ArchitectureModelBuilder uiBuilder = new ArchitectureModelBuilder();
ArchitectureNode model = uiBuilder.buildModel(root, 3); // 3 Ebenen auto-expand

// 4. In UI anzeigen
architectureView.setArchitectureRoot(model);
```

## UI Features

### File Loader
- **📂 Load JAR Button**: Öffnet File-Dialog zur Auswahl von JAR-Dateien
- **Automatische Analyse**: Extrahiert alle .class-Dateien und analysiert Abhängigkeiten
- **Fehlerbehandlung**: Informiert Benutzer über Analysen-Fehler

### Auto-Expand Controls
- **Spinner**: Einstellbar von 1-10 Ebenen (Default: 3)
- **Hierarchisches Laden**: Automatisches Expandieren basierend auf Tiefe-Einstellung
- **Status Bar**: Zeigt Anzahl Klassen, Pakete und erkannte Zyklen

### Baum-Ansicht
- **📦 Pakete**: Fett, blau, mit Abhängigkeitscount
- **📄 Klassen**: Regulär, schwarz
- **Aufklapp-Symbole**: Nur für Knoten mit Kindern
- **On-Demand Loading**: Effizienter für große JAR-Dateien

## Testing

Das Projekt hat umfassende Unit Tests für alle Kernkomponenten:

- **JavaClassTest**: 9 Tests
- **JavaPackageTest**: 10 Tests
- **DependencyGraphBuilderTest**: 7 Tests
- **ArchitectureModelBuilderTest**: 9 Tests

**Gesamt: 35 Tests mit 100% Erfolgsquote**

Alle Tests verwenden JUnit 5 mit Assertions für:
- Korrekte Objekt-Erstellung
- Validierung von Eingaben
- Gleichheit und Hash-Codes
- Hierarchie-Konstruktion

## Performance

- **ASM**: ~1ms pro Klasse für Bytecode-Analyse
- **GraphBuilder**: O(n) für n Klassen
- **UI Rendering**: Optimiert für 1000+ Pakete mit lazy loading

## VS Code Integration

Für vollständige VS Code Setup-Anleitung siehe [VS_CODE_SETUP.md](VS_CODE_SETUP.md)

Quick Start:
```bash
cd /home/johannes/Programieren/Structure202
code .
# Drücke Ctrl+Shift+P und wähle "Maven: Run from Terminal"
# Oder: mvn javafx:run
```

## Erweiterungsmöglichkeiten (TODO)

1. **Cycle Visualization**: Grafische Pfeile für zyklische Abhängigkeiten
2. **Filtering**: Filter nach Package-Namen, Abhängigkeitstyp
3. **Export**: Export als Graphviz DOT oder PlantUML
4. **Statistics**: Abhängigkeitsmetriken und Komplexitätsanalyse
5. **Reflection Support**: Erkennung von dynamischen Abhängigkeiten
6. **Dependency Graph**: Visuelle Pfeile zwischen Paketen
7. **Context Menu**: Rechtsklick-Optionen für Copy, Expand All, etc.
