# SCC Analysis Package

Dieses Package implementiert eine vollständige Lösung zur Analyse von stark zusammenhängenden Komponenten (Strongly Connected Components - SCC) in Abhängigkeitsgraphen, um zyklische Abhängigkeiten zu identifizieren und zu visualisieren.

## Komponenten

### 1. **TarjanSCCFinder**
- Implementiert **Tarjan's Algorithmus** zur Berechnung aller SCCs
- Zeitkomplexität: O(V + E)
- Identifiziert alle Zyklen im Abhängigkeitsgraph
- Gruppiert Knoten in maximale zyklische Komponenten

### 2. **StronglyConnectedComponent**
- Repräsentiert eine SCC mit ihren Mitgliedern
- **Tangle**: SCC mit size > 1 (echte Zyklen)
- Verwaltet ein- und ausgehende Abhängigkeiten
- Speichert das berechnete Level für Schichtzuweisung

### 3. **SCCDAGBuilder**
- Konstruiert die **DAG (Directed Acyclic Graph)** der SCCs
- Berechnet **Levels** für jede SCC basierend auf dem längsten Pfad
  - Level 0: Blatt-SCCs (keine ausgehenden Abhängigkeiten)
  - Level N: SCCs mit längsten Pfaden zu Blättern
- Stellt Abhängigkeiten zwischen SCCs her

### 4. **EdgeClassification**
- Klassifiziert Kanten basierend auf ihrer Richtung im Layered-Graph
- **Edge Types**:
  - `NORMAL`: Abwärtskanten (vom höheren zum niedrigeren Level)
  - `VIOLATION`: Aufwärtskanten (architektonische Verletzungen)
  - `INTRA_SCC`: Kanten innerhalb derselben SCC

### 5. **SCCVisualizationHelper**
- Unterstützt UI-Rendering von SCCs und Kanten
- Generiert Architecture Summary (Statistiken)
- **Greedy Out-In Heuristic**: Sortiert Knoten in Tangles
  - Knoten mit mehr ausgehenden Kanten kommen zuerst

## Workflow in LayerAssigner

```
1. DEPENDENCY STABILIZATION
   ├─ Sortiere alle Nachbarn für konsistente Iteration
   └─ Verwende TreeSet für stabile Ordnung

2. SCC COMPUTATION (Tarjan)
   ├─ Finde alle stark zusammenhängenden Komponenten
   └─ Identifiziere Zyklen (Tangles)

3. SCC DAG CONSTRUCTION
   ├─ Baue DAG aus SCC-Abhängigkeiten
   └─ Entferne intra-SCC Kanten

4. TOPOLOGICAL SORT
   ├─ Stabile Tie-Break-Regel
   └─ Sortiere nach Level und ID

5. LEVELIZATION (Longest Path)
   ├─ Berechne Level für jede SCC
   ├─ Leaf-SCCs = Level 0
   └─ Propagiere Level nach oben

6. NODE MAPPING
   └─ Zuordnung: Knoten -> Level

7. EDGE CLASSIFICATION
   ├─ Klassifiziere Kanten (Normal/Violation/Intra)
   └─ Identifiziere Architektur-Verletzungen
```

## Integration in LayerAssigner

Die SCC-Analyse wird in `LayerAssigner.assignLayers()` durchgeführt:

```java
// 1. Stabilisiere Dependencies
Map<String, Set<String>> stableGraph = stabilizeDependencies(packageInfoMap);

// 2. Berechne Layers mit SCC
Map<String, Integer> layers = calculateLayersWithSCC(stableGraph);

// 3. Zugriff auf SCC-Ergebnisse
List<StronglyConnectedComponent> sccs = layerAssigner.getSCCs();
Map<String, Integer> nodeToLevel = layerAssigner.getNodeToLevel();
List<ClassifiedEdge> edges = layerAssigner.getClassifiedEdges();
```

## UI-Rendering

### Tangle-Boxen
```
┌─────────────────┐
│   Tangle #1     │ (cycle detected)
│  [A ↔ B ↔ C]    │
└─────────────────┘
```

### Edge Types
- **Normal Kanten**: Pfeile zwischen verschiedenen Layern (absteigende Richtung)
- **Violations**: Rote Pfeile (aufwärts) - architektonische Verletzungen
- **Intra-SCC**: Graue Pfeile (innerhalb Tangle)

## Test-Abdeckung

- `TarjanSCCFinderTest`: Grundlegende SCC-Berechnung
  - Einfache Zyklen
  - Multiple SCCs
  - Komplexe Graphen
  - Edge Cases (Self-Loop, leere Graphen)

- `SCCDAGBuilderTest`: DAG-Konstruktion und Level-Zuweisung
  - Lineare Abhängigkeiten
  - Diamond-Struktur
  - Stabile Sortierung

## Algorithmen-Details

### Tarjan's Algorithm
```
Komplexität: O(V + E)
Stack-basierte Tiefensuche
Nutzt Low-Link Werte zur SCC-Erkennung
```

### Längster Pfad für Levelization
```
Rekursive Berechnung mit Caching
Level(SCC) = 1 + max(Level(dependencies))
Leaf SCCs erhalten Level 0
```

## Beispiel: Analyse einer Architektur mit Zyklen

Input:
```
ui -> model
model -> persistence
persistence -> model  (CYCLE!)
```

Output:
```
SCCs gefunden: 2
- SCC #0: [ui] - Level 2
- SCC #1: [model, persistence] - Level 0 (TANGLE)

Violations: 1
- persistence -> model (VIOLATION - upward edge)
```

## Performance

- **Tarjan's Algorithm**: O(V + E)
- **DAG Construction**: O(E) 
- **Level Assignment**: O(V + E) mit Memoization
- **Total Complexity**: O(V + E)

Geeignet für große Codebases mit Tausenden von Klassen.
