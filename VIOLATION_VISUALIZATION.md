# Violation Visualization in UI

## Overview

Die UI zeigt nun architektonische Violations (Verletzungen) visuell an:

- **Rote gestrichelte Linien** = Violations (Upward dependencies)
- **Blaue Linien** = Normale Dependencies (Downward)
- **Blaue Kästen** = Packages

## Features

### 1. Violations markieren
Violations sind Abhängigkeiten, die AUFWÄRTS gehen - sie zeigen auf ein Paket, das höher in der Hierarchie liegt. Dies verstößt gegen Best Practices und sollte refaktoriert werden.

### 2. Visuelle Unterscheidung
```
Normale Dependencies:
  A  ──→  B  (solid line, downward)

Violations:
  B  ╌╌╌→  C  (dashed red line, upward)
```

### 3. Klassifizierung durch SCC-Algorithmus

Der Algorithmus klassifiziert ALLE Kanten in drei Kategorien:

1. **NORMAL** (Solid blue lines)
   - Edge geht von höherem zu niedrigerem Level
   - Akzeptable Abhängigkeit

2. **VIOLATION** (Red dashed lines) 
   - Edge geht von niedrigerem zu höherem Level
   - Verstößt gegen Architektur
   - SOLLTE refaktoriert werden

3. **INTRA_SCC** (Internal edges)
   - Edges innerhalb eines Tangles (SCC)
   - Teil eines zirkulären Abhängigkeitszyklus

## Verwendung

### Test JAR mit Violations anschauen

```bash
cd test-jar
./RUN_UI_WITH_TEST_JAR.sh
```

Das öffnet die UI mit einem Test-JAR, das folgende Violations enthält:
- **TANGLE 1**: a ↔ b (2-node cycle)
- **TANGLE 2**: c → d → e → c (3-node cycle)
- **VIOLATION**: b → c (upward dependency)

### Eigenes JAR analysieren

1. UI starten: `mvn javafx:run` (from parent directory)
2. "Load JAR" Button klicken
3. JAR-Datei auswählen
4. **Rote gestrichelte Linien** zeigen die Violations

## Implementierungsdetails

### ArchitectureView.java
- Erstellt LayerAssigner
- Ruft `getClassifiedEdges()` auf
- Übergibt klassifizierte Kanten an PackageTreeView

### PackageTreeView.java
- Empfängt ClassifiedEdge-Liste
- Leitet Violations an ArchitectureGraphView weiter

### ArchitectureGraphView.java
- Zeichnet alle Violations mit `drawViolationLines()`
- Verwendet rote Farbe (#FF0000)
- Gestrichelte Linien (dash pattern: 5,5)
- Pfeilspitze am Ende der Linie

### LayerAssigner.java
- SCC-Analyse findet Tangles
- Level-Zuweiseung: längster Pfad in DAG
- `EdgeClassification` klassifiziert alle Kanten
- `getClassifiedEdges()` gibt klassifizierte Liste zurück

## Beispiel: Test JAR Struktur

```
com.example.a              com.example.b
       ↓                         ↓
   imports B          ────┐  imports A
       ↓                  │      ↓
com.example.b          (cycle)   ↓
       ├── imports A           com.example.c
       └── imports C              ↓ (VIOLATION!)
            (upward!)          imports D
                               Level 0
                                  
com.example.d
       ↓
   imports E
       ↓
com.example.e
       ↓
   imports C ────┐
       ↓         │
    (cycle)  ────┘
     Level 0
```

## Farben und Symbole

| Element | Farbe | Bedeutung |
|---------|-------|-----------|
| Package Box | Blau (#0066CC) | Package-Container |
| Normal Edge | Grau (solid) | Akzeptable Abhängigkeit |
| Violation Edge | Rot (gestrichelt) | Architektur-Verstöß |
| Arrow Head | Passend zu Linie | Abhängigkeitsrichtung |

## Interaktion

- **Klick auf Package Header**: Expand/Collapse
- **Rote Linien schweben**: Zeigen Violations an
- **Layers-Farben**: Verschiedene Ebenen unterscheiden

## Refactoring-Empfehlungen

Violations sollten PRIORITÄT bei Refactoring haben:

1. **Ursache finden**: Welche Klasse in B nutzt C?
2. **Abstraktionen nutzen**: Interface in Level-0 Package erstellen
3. **Dependency Injection**: C nicht direkt instanziieren
4. **Re-organization**: Paketstruktur überdenken

### Beispiel Refactoring (Test JAR)

Violation: `b → c`

**Lösung**: 
```java
// Vorher (falsch):
// B.java
import com.example.c.C;
C c = new C();  // ← VIOLATION

// Nachher (richtig):
// In com.example.d Package ein Interface erstellen:
// IDependency.java
public interface IDependency { ... }

// B.java: Dependency Injection nutzen
public B(IDependency dep) { ... }
```

## Zeichen-Reihenfolge

1. Dependency Lines (solid gray)
2. **Violation Lines (red dashed) ← SICHTBAR!**
3. Package Boxes (overlaid on top)

Dies stellt sicher, dass Violations deutlich sichtbar sind aber nicht die Struktur verbergen.
