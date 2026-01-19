# Quick Reference: Violations in der UI

## 🎯 Was wurde gemacht?

Violations (architektonische Verstöße) werden jetzt in der JavaFX-UI als **rote gestrichelte Linien** angezeigt.

## 📌 Violations = Upward Dependencies

```
BAD (Violation):           GOOD (Normal):
  b ╌╌╌→ c                   a ──→ b
  (upward, wrong!)           (downward, OK)
```

## 🚀 Code-Integration

### 1. Klassifizierte Kanten sammeln (`ArchitectureView.java`)
```java
LayerAssigner layerAssigner = new LayerAssigner();
layerAssigner.assignLayers(rootNode);
List<ClassifiedEdge> classifiedEdges = layerAssigner.getClassifiedEdges();
treeView.setArchitectureRoot(rootNode, classifiedEdges);  // ← Pass classified edges!
```

### 2. Violations weitergeben (`PackageTreeView.java`)
```java
public void setArchitectureRoot(ArchitectureNode rootNode, 
                               List<ClassifiedEdge> classifiedEdges) {
    this.classifiedEdges = classifiedEdges;
    // ... weitergabe an ArchitectureGraphView
}
```

### 3. Violations zeichnen (`ArchitectureGraphView.java`)
```java
private void redraw() {
    // ... zeichne normale Linien ...
    drawDependencyLines(gc);
    
    // ✓ ZEICHNE VIOLATIONS (rote gestrichelte Linien)!
    drawViolationLines(gc);
    
    // ... zeichne Boxen oben ...
}

private void drawViolationLines(GraphicsContext gc) {
    for (ClassifiedEdge edge : classifiedEdges) {
        if (edge.type == EdgeType.VIOLATION) {
            drawViolationArrow(gc, sourceBox, targetBox);  // Red dashed!
        }
    }
}
```

## 🎨 Styling

| Property | Value | Notes |
|----------|-------|-------|
| Color | `#FF0000` | Rot |
| Line Style | Dashed (5,5) | Gestrichelt |
| Width | 2px | Sichtbar |
| Arrow | Filled polygon | Rote Pfeilspitze |
| Shape | Bezier curve | Um rechts herum |

## 📋 ClassifiedEdge Struktur

```java
public static class ClassifiedEdge {
    public final String from;              // Source package
    public final String to;                // Target package  
    public final EdgeType type;            // NORMAL, VIOLATION, INTRA_SCC
}

public enum EdgeType {
    NORMAL,      // ✓ Downward (acceptable)
    VIOLATION,   // ✗ Upward (ERROR!) - gezeigt als rote gestrichelte Linie
    INTRA_SCC    // ⚠️  Internal cycle
}
```

## 🧪 Test-JAR mit Violations

```bash
cd test-jar
./RUN_UI_WITH_TEST_JAR.sh
```

Zeigt:
- **VIOLATION**: `b → c` (rote gestrichelte Linie!)
- **TANGLE 1**: `a ↔ b` (blauer Container mit Zyklen innen)
- **TANGLE 2**: `c → d → e → c` (blauer Container mit 3-Knoten Zyklus)

## 📊 Workflow im LayerAssigner

```
assignLayers(root)
    ↓
collectPackageInfo()
    ↓
stabilizeDependencies()
    ↓
TarjanSCCFinder.findSCCs()  ← Findet Tangles
    ↓
SCCDAGBuilder.buildDAG()     ← Erstellt SCC-DAG
    ↓
SCCDAGBuilder.assignLevels() ← Longest-Path Levels
    ↓
EdgeClassification.classifyAllEdges()  ← NORMAL / VIOLATION / INTRA_SCC
    ↓
getClassifiedEdges()  ← Zur UI
    ↓
ArchitectureGraphView.drawViolationLines()  ← Rote gestrichelte Linien!
```

## 🔍 Debugging Tipps

### Violations werden nicht angezeigt?

1. Check `getClassifiedEdges()` Output:
```bash
mvn test -Dtest=*LayerAssigner* -v
# Look for: "VIOLATION: x → y"
```

2. Debug-Print in `drawViolationLines()`:
```java
System.out.println("Drawing " + classifiedEdges.size() + " edges");
for (ClassifiedEdge edge : classifiedEdges) {
    System.out.println("  " + edge.from + " → " + edge.to + " [" + edge.type + "]");
}
```

3. Stelle sicher, dass `redraw()` aufgerufen wird:
```java
private void redraw() {
    System.out.println("REDRAW called");
    drawDependencyLines(gc);      // Normal edges
    drawViolationLines(gc);        // RED DASHED! ← Should see output here
}
```

## 🎓 Algorithm: Edge Classification

```java
for each Edge(u → v):
    if SCC(u) == SCC(v):
        type = INTRA_SCC        // Internal to cycle
    else if Level(u) < Level(v):
        type = NORMAL           // Downward, OK
    else:
        type = VIOLATION        // ✗ Upward, WRONG! ← Red dashed
```

## 📈 Performance

- Classifying edges: O(E) - linear in number of edges
- Drawing violations: O(violations) - only draws the bad ones
- Total overhead: Negligible (<1ms)

## 🚦 Best Practices für Refactoring

Wenn Sie rote gestrichelte Linien sehen:

1. **Finde die Quelle**: Welche Klasse in `b` nutzt `c`?
2. **Nutze Interfaces**: Abstraktion statt direkter Dependency
3. **Dependency Injection**: `new C()` → Parameter
4. **Reorganisiere Packages**: Überdenke Struktur

### Beispiel Refactoring
```java
// ❌ Violation: b imports c
// B.java
import com.example.c.C;
public B() { C c = new C(); }  // ← Violation!

// ✅ Refactored
// com.example.api/IService.java  (in Level 0)
public interface IService { ... }

// B.java (in Level 1)
public B(IService service) { ... }  // ← Dependency Injection!
```

## 📚 Weitere Dateien

- [VIOLATION_VISUALIZATION.md](VIOLATION_VISUALIZATION.md) - Detailliert
- [SCC_IMPLEMENTATION_SUMMARY.md](SCC_IMPLEMENTATION_SUMMARY.md) - Übersicht
- [QUICKSTART.md](QUICKSTART.md) - Einstieg
