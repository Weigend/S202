# ADR: Violations und Levels als First-Class-Konzepte im Domain-Model

Status: **Entwurf** — Branch `feature/violations-in-domain-model`
Datum: 2026-05-13
Betrifft: `de.weigend.s202.domain.*`, `de.weigend.s202.analysis.*`,
`de.weigend.s202.ui.*` (insbesondere `WhatIfUpwardEdgeRenderer` und
`WhatIfDependenciesView`)

---

## 1 — Bestand

### 1.1 Was schon im Domain-Model lebt

- `DomainModel.classes: Map<String, CalculatedElementInfo>` — pro Klasse:
  `level`, `dependencies`, `dependents`, `simpleName`, `type`,
  `interfaceType`.
- `DomainModel.packages: Map<String, CalculatedElementInfo>` — pro Paket
  dieselbe Struktur.
- `DomainModel.packageEdgeWeights: Map<String, Map<String, Integer>>` —
  gewichteter Paketgraph; Gewicht = Method-Call-Count.
- `DomainModel.packageBackEdgeKeys: Set<String>` — Back-Edges (Format
  `"from\0to"`), gesetzt vom `LevelCalculator`.

Levels sind also drin, **pro Klasse und pro Paket getrennt**. Class-Level
und Package-Level kommen aus zwei separaten Berechnungen, die der
`LevelCalculator` orchestriert.

### 1.2 Was im Domain-Model fehlt

- **Violation** als eigenständiger Datensatz. Nirgendwo existiert ein
  Typ `domain.Violation`, der "diese Kante ist eine
  Architekturverletzung" beschreibt.
- Es gibt drei verschiedene Stellen, die Violations *ad hoc* ableiten:
  1. `graph.EdgeClassification.classifyEdge(...)` →
     `ClassifiedEdge(from, to, EdgeType{NORMAL, VIOLATION, INTRA_SCC})`.
     Datennah, aber wird nur intern vom `LayoutInvariantChecker` und
     vom Renderer für Edge-Farben benutzt — **keine Liste über das
     ganze Modell**.
  2. `analysis.invariants.LayoutInvariantChecker` R1/R3 → produziert
     `InvariantFinding`-Liste. Das ist aber laut Doku ein
     *Algorithmus-Bug-Detektor* ("Findings flagged here are real
     algorithm bugs in the level pipeline, not architectural
     violations"), nicht der Architektur-Violation-Strom.
  3. `ui.rendering.WhatIfUpwardEdgeRenderer.findVisibleViolations(...)`
     → vergleicht **Scene-Y** der Boxen, nicht Levels. Output ist eine
     UI-Liste von `Violation(source: Node, target: Node, classEdges)`.

### 1.3 Wo der Level-Algorithmus heute liegt

`de.weigend.s202.domain.LevelCalculator` — formal schon in Domain.
Aber er importiert massiv `analysis.strategy.*` (Strategien für SCC-
Breaking und Level-Berechnung). M2 des Quality-Plans löst genau das:
Strategien werden über Interfaces im Domain konsumiert, Implementierungen
liegen daneben. **Voraussetzung** dafür, dass Domain wirklich
*self-contained* wird.

### 1.4 Der Knackpunkt: UI berechnet Violations unabhängig vom Modell

`WhatIfUpwardEdgeRenderer` macht **Scene-Y-Vergleich**:

```
sc[1] (source-Y in pane) > tc[1] (target-Y in pane)  →  Verletzung
```

Das ist *nicht* dasselbe wie `srcLevel < tgtLevel` aus dem Modell:

- Visuelle Y-Position hängt von **Box-Anordnung** ab (Pakete sind in
  einem `VBox`, Klassen in `levelRows` innerhalb des Pakets). Mit
  Priority.ALWAYS verteilen sich Rows gleichmäßig — die Y-Achse spiegelt
  die Level-Reihenfolge wider, aber nicht die exakten Level-Differenzen.
- Bei kollabierten Paketen rollt die UI auf das Container-Box hoch —
  der Endpunkt wandert auf eine völlig andere Y.
- Bei DnD-Moves (Phase 2-Code) bewegt sich die Box visuell. Die Levels
  im Domain-Modell bleiben aber unverändert; der Y-Vergleich gibt einen
  anderen Violations-Set als die Modell-Levels suggerieren würden.

**Konsequenz:** Was die UI als "27 Wrong Edges" zählt, wäre laut Modell
2 Class-Upward-Edges. Die beiden Wahrheiten driften, ohne dass jemand
sagen kann welche stimmt.

---

## 2 — Problem

1. **Definition unklar:** "Violation" hat in drei Codepfaden drei
   verschiedene Definitionen.
2. **Domain liefert keine Violations-API:** Der einzig korrekte
   Detektor (`EdgeClassification.classifyEdge`) wird pro Edge intern
   aufgerufen, das Domain-Modell hat aber kein Feld
   `getViolations(): List<Violation>`.
3. **UI deriviert aus Scene-Y:** Bypass des Modells, daher
   *strukturell* nicht synchronisierbar.
4. **What-If liegt komplett neben dem Modell:** Der `VirtualIdentity`-
   Override-Store wirkt nicht auf `LevelCalculator` zurück. Die
   What-If-Sicht zeigt visuelle Konsequenzen, die das Modell nicht
   kennt. Wenn der User Klassen verschiebt, müsste das Modell die
   neuen Levels und Violations gegen das überlagerte Modell rechnen —
   tut es aber nicht.

---

## 3 — Soll-Bild

```
                    DependencyModel (raw bytecode)
                            │
                            ▼
              ┌─────────────────────────────┐
              │       LevelCalculator        │
              │       (im domain-Paket)      │
              │                              │
              │  1. ClassLevels             │
              │  2. PackageEdgeGraph        │
              │  3. SCCs (graph-Paket)      │
              │  4. PackageLevels           │
              │  5. ViolationDetector       │  ← NEU
              └─────────────────────────────┘
                            │
                            ▼
             ┌─────────────────────────────────┐
             │           DomainModel           │
             │                                 │
             │  CalculatedElementInfo          │
             │   - level                       │
             │   - dependencies                │
             │  PackageEdgeWeights             │
             │  PackageBackEdgeKeys            │
             │  Violations         (NEU)       │  ← getViolations(): List<Violation>
             │  PackageSCCs        (NEU)       │  ← getPackageSCCs(): List<Set<String>>
             └─────────────────────────────────┘
                            │
                ┌───────────┼────────────┐
                ▼           ▼            ▼
              UI         Invariants   WhatIf
          (rendert nur,  (prüft       (überlagert
           keine eigene  Konsistenz)  DependencyModel,
           Violation-                  recomputed
           Berechnung)                 Levels+Violations)
```

**Drei Kernregeln:**

1. **Violations sind eine Output-Größe des Modells.** Die UI fragt
   sie ab, rechnet sie nicht selbst.
2. **Der Level-Algorithmus ist self-contained im Domain-Paket.**
   Strategien werden über Interfaces dort definiert,
   Implementierungen können daneben liegen.
3. **What-If = neues `DependencyModel` + erneuter `LevelCalculator`-
   Lauf.** Das ergibt automatisch konsistente neue Levels und neue
   Violations. UI rendert nur.

---

## 4 — Schritte

In strikter Reihenfolge, jede Stufe einzeln committable und
revertierbar.

### S1 — Strategien-Inversion fertig machen (entspricht M2 des Quality-Plans)

**Was:**
- `analysis.strategy.SCCBreakingStrategy`, `ClassLevelCalculationStrategy`,
  `ClassAggregationStrategy`, `LevelCalculationStrategyContext` →
  Interfaces nach `domain.strategy.*` verschieben.
- Konkrete Implementierungen (`HeuristicSCCBreakingStrategy`,
  `BasicClassLevelCalculationStrategy`, `SimpleMaxAggregationStrategy`)
  bleiben unter `analysis.strategy.impl.*` und implementieren die
  Domain-Interfaces.
- `LevelCalculator` bekommt die Strategie per Konstruktor injiziert
  (Avaje DI). Default-Binding produziert das aktuelle Verhalten.
- `LevelCalculationStrategyFactory` in domain entfällt — Wiring
  übernimmt Avaje.

**Wirkung:**
- `domain → analysis`-Imports von 8 auf 0.
- 2er-SCC zwischen domain und analysis ist gebrochen.
- Domain ist self-contained. Voraussetzung für alles Weitere.

**Risiko:** mittel. Tests müssen ggf. Strategien explizit binden.

### S2 — `Violation`-Typ und `ViolationDetector` im Domain anlegen

**Was:**

```java
// de/weigend/s202/domain/Violation.java
public record Violation(
        String sourceFqn,
        String targetFqn,
        ViolationKind kind,
        Granularity granularity,    // CLASS oder PACKAGE
        int sourceLevel,
        int targetLevel) {}

public enum ViolationKind {
    UPWARD,           // source.level < target.level, kein Backedge,
                      // klassischer Layer-Aufstieg
    PACKAGE_TANGLE    // source und target sind Mitglieder derselben
                      // SCC der Größe > 1 (Zyklus-Mitgliedschaft)
}
```

Optional ein `ClassEdgeBackEdge`-Marker — Klarheit, dass wir Class-Edge-
Back-Edges *bewusst* nicht als Violation zählen (sie sind algorithmisch
"akzeptierte" Schiefen, die nötig sind, um Levels überhaupt vergeben zu
können).

`de.weigend.s202.domain.ViolationDetector` ist eine pure Funktion auf
einem fertig berechneten DomainModel:

```java
public final class ViolationDetector {
    public List<Violation> detect(DomainModel model, DependencyModel raw,
                                  Set<String> classBackEdges) { ... }
}
```

**Wirkung:** Eine einzige Wahrheit für "was ist eine Violation".

**Risiko:** gering. Reine Addition.

### S3 — `LevelCalculator` schreibt Violations in `DomainModel`

**Was:**
- Am Ende von `LevelCalculator.calculate(...)` läuft `ViolationDetector`.
- Ergebnis landet in `DomainModel.setViolations(List<Violation>)`.
- `DomainModel.getViolations()` als Read-Only-Getter.
- `DomainModel.getPackageSCCs()` als Read-Only-Getter (heute schon
  intern bekannt durch `packageBackEdgeKeys` + Tarjan, aber nicht
  exponiert).

**Wirkung:** Modell-Konsumenten haben einen sauberen Single Point of
Truth.

**Risiko:** gering. DomainModel wächst um zwei Felder.

### S4 — `LayoutInvariantChecker` gegen die neue API laufen lassen

**Was:**
- R1/R3 prüfen heute selbst auf "level invertiert". Sie können stattdessen
  `DomainModel.getViolations()` lesen und gegen ihre Algorithmus-
  Erwartungen abgleichen. Ihre **Rolle bleibt:** "stimmen die Levels
  konsistent mit den Findings überein, oder hat der Algorithmus einen
  Bug gemacht?". R1-R5 sind weiterhin Bug-Detektoren, jetzt aber mit
  Bezug auf die kanonische Violation-Liste.

**Wirkung:** Invariant-Checker wird einfacher; Eingangsdaten sind
explizit.

**Risiko:** mittel. R1-Findings können sich numerisch verschieben, weil
sie heute Edge-Klassifikation aus `EdgeClassification` ziehen. Test-
Fixtures müssen geprüft werden.

### S5 — UI liest Violations aus `DomainModel`

**Was:**
- `ArchitectureView` hält die aktuell aktive `DomainModel`-Referenz
  (`setDomainModel` existiert schon).
- `WhatIfUpwardEdgeRenderer` ersetzt die Scene-Y-Heuristik durch:
  ```java
  for (Violation v : domainModel.getViolations()) {
      Node src = elementRegistry.get(v.sourceFqn());
      Node tgt = elementRegistry.get(v.targetFqn());
      drawCurve(src, tgt, ...);
  }
  ```
- Scene-Y wird **nur noch zur Berechnung der Linien-Endpunkte**
  verwendet, nicht für die Entscheidung *ob* gemalt wird.
- Rollup bei kollabierten Paketen bleibt UI-Logik (welche Box ist
  aktuell sichtbar?), aber der **Violations-Set ist fix**.

**Wirkung:**
- Die UI-Zählung stimmt 1:1 mit der Modell-Zählung überein. Diskrepanzen
  wie aktuell 27-vs-14-vs-2 sind ausgeschlossen.
- `WhatIfDependenciesView` zeigt dieselben Daten ohne eigene
  Berechnung.

**Risiko:** mittel. Aggregation auf Paket-Ebene muss aus Class-Level-
Violations abgeleitet werden, falls beide Endpunkte hochrollen.

### S6 — What-If = Modell-Re-Run statt Scene-Trick

**Was:**
- `WhatIfModel` (heute: VirtualIdentity + ClassEdges + lokale
  Aggregation) erzeugt ein **derived `DependencyModel`** mit
  umgeschriebenen Klassen-Paket-Zuordnungen (klar definiert über
  `VirtualIdentity.virtualFullName`).
- Auf dem derived DependencyModel läuft `LevelCalculator.calculate(...)`
  erneut.
- Das ergibt ein neues DomainModel mit neuen Levels und neuen
  Violations.
- UI rendert genau dieses neue Modell. Box-Positionen werden visuell
  per DnD verschoben — der **Datenstand bleibt konsistent**.

**Wirkung:**
- What-If liefert echte Soll-Architektur-Levels statt verschobener
  Visualisierung.
- Aufwärts-Edges entstehen oder verschwinden, weil sich der Dep-Graph
  ändert, nicht weil eine Box anders sitzt.
- Performance: ein DomainModel-Rerun pro Move. Per Algorithmus
  inkrementell machbar (kleiner Subgraph betroffen), aber zunächst
  brute-force ok für kleine bis mittelgroße JARs.

**Risiko:** hoch (semantisch). Hier kann sich das Verhalten des Tools
sichtbar ändern: User-Moves haben jetzt klare Konsequenzen im
Modell. Phase 3 des bisherigen What-If-Designs muss neu bewertet
werden — VirtualIdentity bleibt nützlich, PackageAggregator wird
überflüssig, VirtualPackageGraph wird zur derived-DomainModel-
Schicht.

### S7 — `EdgeClassification` und Co. an die neue Domäne anpassen

**Was:**
- `graph.EdgeClassification` bekommt Eingaben nur noch vom DomainModel
  (Level + Backedge-Status), liefert weiterhin
  `ClassifiedEdge(from, to, EdgeType)` für stylistische
  Render-Entscheidungen (Dimming intra-SCC, Hervorheben von
  Violation-Edges, etc.).
- `DependencyRenderer`/`SCCRenderer` ziehen Edge-Klassifikation aus
  einer Source-of-Truth, nicht aus eigenen Bedingungen.

**Risiko:** gering.

---

## 5 — Konsequenzen für die What-If-Architektur

- **VirtualIdentity** bleibt zentral — sie definiert das Override-Mapping.
- **WhatIfModel** wird zu einem dünnen Wrapper, der aus dem statischen
  `DependencyModel` + `VirtualIdentity` ein derived `DependencyModel`
  baut. Daraus baut `LevelCalculator` ein neues `DomainModel`.
- **PackageAggregator** und **VirtualPackageGraph** entfallen in ihrer
  jetzigen Form — die Funktionalität liegt jetzt im Re-Run des
  `LevelCalculator`. Der Aggregator könnte als interner Helfer im
  Override-Pfad überleben, ist aber kein eigenständiger
  Konzept-Knoten mehr.

---

## 6 — Risiken

1. **Perf:** Re-Run des `LevelCalculator` bei jedem DnD-Drop. Für
   kleine bis mittlere JARs (≤ einige Tausend Klassen) sub-Sekunde,
   für große problematisch. Wenn sich das bestätigt, brauchen wir
   einen inkrementellen Modus — separates ADR.
2. **Verhaltensänderung sichtbar:** User-Moves können jetzt
   verschiedene Auswirkungen haben als bisher (mehr/weniger
   Violations). Vor Roll-Out auf bekannten Test-JARs validieren.
3. **R1/R3-Findings:** Tests, die auf bestimmte Findings-Counts
   prüfen, müssen ggf. nachgezogen werden.
4. **Migration des What-If-Visualisierungs-Codes:** Die aktuelle
   `WhatIfUpwardEdgeRenderer`-Y-Logik ist seit ein paar Iterationen
   stable, der Wechsel auf Modell-Konsumption ist nicht ganz trivial
   weil Rollup-bei-kollabierten-Paketen visuelle Logik bleibt.

---

## 7 — Reihenfolge

1. **S1** (Strategien-Inversion) — Voraussetzung. Klein. Risiko mittel.
2. **S2** (Violation-Typ + Detector) — additiv. Klein. Risiko gering.
3. **S3** (LevelCalculator schreibt Violations) — additiv. Klein. Risiko gering.
4. **S5** (UI liest aus Modell) — mittlere Größe. Risiko mittel. **Hier
   verschwindet die UI-Modell-Diskrepanz.**
5. **S4** (LayoutInvariantChecker konsumiert) — Bereinigung. Klein.
6. **S6** (What-If = Re-Run) — groß. Risiko hoch. Erst angehen, wenn
   S1–S5 stehen und gemessen ist, dass Re-Run-Perf reicht.
7. **S7** (EdgeClassification-Konsolidierung) — Aufräumarbeit.

---

## 8 — Nicht-Ziele

- Kein Verhaltenswechsel an Level-Algorithmus-Details (back-edge-
  Heuristik, Strategy-Auswahl, etc.) ohne separates ADR.
- Kein Modell-Rebuild auf jedem UI-Event — Re-Run nur bei expliziten
  What-If-Mutationen oder neuer Analyse.
- Kein Persistenz-Format-Bruch (siehe M3 im Quality-Plan).
