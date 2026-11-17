# SCC-Konsolidierung: TarjanSCCFinder einmalig im DomainModel

## Problem

Der `TarjanSCCFinder` wird an **14 Stellen** im Code aufgerufen. Davon sind mehrere
**redundante Neuberechnungen** auf demselben Graphen — obwohl der `LevelCalculator`
die SCCs bereits während seiner Berechnung ermittelt und danach wegwirft.

Das kritische Problem: `HierarchicalLayeredArchitectureBuilder.detectTangles()`
ist eine **eigenständige Alternativimplementierung** der Package-Tangle-Erkennung.
Sie baut unabhängig einen Package-Graphen aus rohen Klassen-Dependencies auf und
läuft Tarjan darauf — vollständig losgelöst von der Logik im `LevelCalculator`,
an der wir wochenlang gearbeitet haben. Wenn sich der Level-Algorithmus ändert
oder die Graphkonstruktion leicht abweicht, produziert diese Parallelimplementierung
stillschweigend andere Ergebnisse.

### Konkrete Risiken

1. **Semantische Drift**: `HierarchicalLayeredArchitectureBuilder` baut den
   Package-Graphen aus `CalculatedElementInfo.dependencies` (gefilterte
   Klassen-Deps im DomainModel). `LevelCalculator` arbeitet auf einem anderen,
   iterativ mutierten Graphen. Beide könnten verschiedene Package-SCCs liefern —
   und niemand merkt es.

2. **Keine gemeinsame Quelle der Wahrheit**: Was ist ein "Package-Tangle"?
   Die Antwort hängt davon ab, wo im Code man nachschaut.

3. **Wartungsaufwand**: Jede Änderung am Level-Algorithmus muss mental auf
   alle parallelen Implementierungen geprüft werden.

---

## Ist-Zustand der 14 Nutzungen

| # | Klasse | Verwendung | Kategorie |
|---|--------|-----------|-----------|
| 1–5 | `LevelCalculator` | Iterative SCC-Berechnung in `while`-Schleifen zur Kantenmutation | **Muss dynamisch bleiben** — Graph ändert sich |
| 6 | `LocalLevelCalculator` | Gleiche Iterationslogik | **Muss dynamisch bleiben** |
| 7 | `TopTanglesModule` | SCCs mit angewendeten Cuts gefiltert | **Muss dynamisch bleiben** — gefilteter Graph |
| 8 | `HierarchicalLayeredArchitectureBuilder` | Package-Tangles aus rohem Klassen-Graphen | **Redundant** — LevelCalculator hat das schon |
| 9 | `SCCRenderer` | Klassen-SCCs für rote Overlay-Linien | **Redundant** — könnte aus DomainModel lesen |
| 10 | `ArchitectureView3D` | Klassen-SCCs für 3D-Kanten | **Redundant** — könnte aus DomainModel lesen |
| 11 | `QualityMetrics` | Klassen-SCCs pro Scope | Grenzfall — scope-gefiltert, akzeptabel |
| 12–13 | `LayoutInvariantChecker` | Klassen- und Package-SCCs zur Fehlerprüfung | **Bewusst unabhängig** — das ist der Sinn |
| 14 | `TangleEdgeRenderer` | Inline-Tarjan-Duplikat | **Technische Schuld** — sollte TarjanSCCFinder nutzen |

---

## Lösung

### Schritt 1: DomainModel um SCC-Ergebnisse erweitern

`LevelCalculator.calculate()` berechnet Package-SCCs und Klassen-SCCs bereits
intern. Diese Ergebnisse werden in `DomainModel` gespeichert — analog zu den
bereits vorhandenen `packageBackEdgeKeys` und `classBackEdgeKeys`.

```java
// DomainModel — neue Felder
private List<StronglyConnectedComponent> packageTangles = List.of();
private List<StronglyConnectedComponent> classTangles   = List.of();

public List<StronglyConnectedComponent> getPackageTangles() { return packageTangles; }
public List<StronglyConnectedComponent> getClassTangles()   { return classTangles;   }
```

`LevelCalculator` ruft nach seiner fertigen Berechnung einmalig:
```java
model.setPackageTangles(sccs);  // aus dem originalen Package-Graphen
model.setClassTangles(sccs);    // aus dem originalen Klassen-Graphen
```

### Schritt 2: HierarchicalLayeredArchitectureBuilder vereinfachen

`detectTangles()` wird durch einen simplen Zugriff aufs Model ersetzt:

```java
private List<Tangle> detectTangles(DomainModel domain) {
    return domain.getPackageTangles().stream()
            .filter(StronglyConnectedComponent::isTangle)
            .map(scc -> new Tangle(scc.getMembers()))
            .toList();
}
```

Damit gibt es **eine gemeinsame Quelle der Wahrheit** für Package-Tangles.

### Schritt 3: SCCRenderer und ArchitectureView3D vereinfachen

Beide können `domain.getClassTangles()` lesen statt selbst Tarjan zu starten.

### Schritt 4: TangleEdgeRenderer bereinigen

Die inline-Implementierung `stronglyConnectedComponents()` + `strongConnect()`
(~50 Zeilen) durch `new TarjanSCCFinder(graph).findSCCs()` ersetzen.

---

## Was bleibt dynamisch

- `LevelCalculator` intern: Die `while`-Schleifen mit Graph-Mutation bleiben
  unberührt — das ist der Algorithmus selbst.
- `LocalLevelCalculator`: Gleiche Begründung.
- `TopTanglesModule`: Arbeitet auf einem durch applied-Cuts gefilterten Graphen —
  muss weiterhin selbst rechnen.
- `LayoutInvariantChecker`: Bewusst unabhängige Implementierung zur Fehlererkennung.
- `QualityMetrics`: Scope-abhängige Filterung, akzeptable Neuberechnung.

---

## Ergebnis

Von 14 Tarjan-Aufrufen sind nach dem Refactoring:
- **6–7** weiterhin dynamisch (korrekt so)
- **3–4** lesen aus `DomainModel` (einmalig berechnet)
- **1** inline-Duplikat entfernt

Die wichtigste Wirkung: `HierarchicalLayeredArchitectureBuilder` kann nicht mehr
von der Logik des `LevelCalculator` abweichen — es gibt nur noch eine Definition
von "Package-Tangle".
