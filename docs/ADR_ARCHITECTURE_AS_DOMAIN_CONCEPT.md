# ADR: Architecture als First-Class Domain-Konzept mit Verifikations-Scaffold

Status: **Entschieden** — Branch `feature/violations-in-domain-model`
Datum: 2026-05-13
Ersetzt teilweise: [ADR_VIOLATIONS_IN_DOMAIN_MODEL](ADR_VIOLATIONS_IN_DOMAIN_MODEL.md)
(S2/S3 dort werden durch diesen Plan abgelöst; S1/S4–S7 bleiben gültig
als Folge-Arbeiten)

---

## 1 — Idee

Die Anwendung braucht ein **Architekturmodell** als eigenständiges
Domain-Konzept. Bisher liegen Levels und SCCs im `DomainModel`, aber
die *Struktur* der Visualisierung — Reihen, Spalten, verschachtelte
Pakete — und die *Definition von "Violation"* werden in der UI on the
fly rekonstruiert. Genau diese Aufspaltung ist die Ursache der
27-vs-14-vs-2-Diskrepanz.

**Lösung:** ein polymorphes `Architecture`-Konzept im Domain, das die
gesamte zu zeigende Struktur trägt — inklusive der Violations, die
*für diesen Architekturstil* gelten. Verschiedene Architekturstile
sind erstklassige Geschwister:

- `HierarchicalLayeredArchitecture` — der heutige Fall: ineinander
  geschachtelte Layer (Rows), Klassen/Pakete pro Row, Violation =
  Edge gegen Layer-Richtung.
- `InterfaceArchitecture` (Beispiel-Ausblick) — Interfaces sitzen
  oberhalb ihrer Implementierungen, dadurch werden Aufrufe von der
  Impl Richtung Interface *erwartet* statt als Violation gezählt.
- Weitere Stile sind ohne globalen Refactor anschließbar.

---

## 2 — Datenmodell

Geplante Typen unter `de.weigend.s202.domain.architecture`:

```java
public sealed interface Architecture permits HierarchicalLayeredArchitecture {
    /** Die für diese Architektur geltenden Violations. */
    List<Violation> violations();
}

public record HierarchicalLayeredArchitecture(
        List<List<Element>> rows,    // top-level: rows of layers, von hoch nach tief
        List<Violation> violations
) implements Architecture {}

public sealed interface Element permits Element.ClassElement, Element.PackageElement {
    String fqn();
    int level();

    record ClassElement(String fqn, int level) implements Element {}

    record PackageElement(
            String fqn,
            int level,
            List<List<Element>> rows  // rekursiv — ein Paket trägt eine eigene Layered-Struktur
    ) implements Element {}
}

public record Violation(
        String sourceFqn,
        String targetFqn,
        ViolationKind kind,
        int sourceLevel,
        int targetLevel) {}

public enum ViolationKind { UPWARD, PACKAGE_TANGLE }
```

`rows` ist genau die Layered-Sicht, die die UI heute aus mehreren
Quellen zusammenstrickt: `List<List<Element>>` — *Rows-of-Cols*. Pakete
sind rekursiv: ein `PackageElement` trägt seine eigene innere
Schichtenfolge.

---

## 3 — Verifikations-Scaffold

Bevor irgendein UI-Code verändert wird, lassen wir den neuen
Architekturpfad parallel zum alten laufen und prüfen 1:1-Äquivalenz:

```
DependencyModel ──► LevelCalculator ──► DomainModel
                                            │
            ┌───────────────────────────────┴────────────────────────────┐
            ▼                                                             ▼
HierarchicalLayeredArchitectureBuilder              ArchitectureNodeBuilder
            │                                                             │
            ▼                                                             ▼
HierarchicalLayeredArchitecture       ◄── ArchitectureConsistencyChecker ─── ArchitectureNode (UI-Tree)

       (assertEquivalent: gleiche Rows, gleiche Elemente pro Row,
        gleiche Verschachtelung, gleiche Levels, gleiche Violations)
```

Der Checker läuft:

1. **als JUnit-Test** über die bundled Test-JARs / synthetischen
   `DomainModel`-Fixtures.
2. **als Runtime-Assertion** in Debug-Builds, gehakt nach
   `LevelCalculator.calculate(...)` oder `setArchitectureRoot(...)`.
   Über ein Flag (System-Property o.ä.) abschaltbar für Release.

Solange beide Pfade dieselbe Information liefern, ist klar: das neue
Modell sagt den UI-Tree exakt voraus. Erst dann reißen wir den alten
Pfad raus (entsprechende Schritte aus dem vorherigen ADR — UI liest
Modell statt selbst zu rechnen).

---

## 4 — Schritte

Vier Commits, jeder additiv und commit-and-revert-bar.

### C1 — Domain-Architecture-Typen anlegen

`de.weigend.s202.domain.architecture` mit den oben gezeigten Typen.
Reine Datenstrukturen, keine Logik. Funktionalitätsrisiko: **null**.

### C2 — `HierarchicalLayeredArchitectureBuilder`

Pure Funktion `build(DomainModel) → HierarchicalLayeredArchitecture`.
Liest Class- und Package-Levels aus dem `DomainModel`, baut die
Rows-of-Cols-Struktur identisch zu dem, was
`ArchitectureNodeBuilder` + `ArchitectureTreeBuilder` heute
visualisieren. Violations werden hier mit-erzeugt — Definition für
diesen Architekturstil: `srcLevel < tgtLevel` als `UPWARD`, plus
Paket-SCC-Mitgliedschaften als `PACKAGE_TANGLE`. Funktionalitätsrisiko:
**null** (keine bestehende Stelle wird angefasst).

### C3 — `ArchitectureConsistencyChecker` + Test

Vergleicht die neu gebaute `HierarchicalLayeredArchitecture` mit dem
heutigen `ArchitectureNode`-Baum (bzw. dem darunterliegenden
`DomainModel`-Output): Anzahl Rows, Elemente je Row, Levels,
Verschachtelung, Violation-Set. Schlägt laut fehl bei Abweichung.

Testfixtures: mindestens ein bundled Test-JAR aus dem Repo (z.B.
`test-example`). Funktionalitätsrisiko: **null** (additiver Test).

### C4 — Runtime-Hook im Dev-Build

Optional. Nach `LevelCalculator.calculate(...)` oder
`setArchitectureRoot(...)` läuft der Checker, sofern ein Flag
(z.B. `s202.dev.architectureCheck=true`) gesetzt ist. Bei Diskrepanz
loggt er einen `WARN` und/oder wirft (je Konfiguration). In Release
default off. Funktionalitätsrisiko: **gering** (nur bei aktiviertem
Flag aktiv).

---

## 5 — Was kommt danach (außerhalb dieses ADRs)

Wenn C1–C4 stabil grün sind:

- UI konsumiert `Architecture` statt eigener Logik (entspricht S5 im
  alten ADR).
- `WhatIfUpwardEdgeRenderer`s Scene-Y-Heuristik wird ersetzt: er fragt
  `architecture.violations()`, Scene-Y bleibt nur als Detail wo er
  die Kurve zeichnet.
- What-If = derived `DependencyModel` + erneuter
  `LevelCalculator`-Lauf + neue `Architecture` (S6 im alten ADR).
- `PackageAggregator`, `VirtualPackageGraph`, ad-hoc Violation-
  Berechnungen entfallen.

Diese Schritte sind **nicht** Teil dieses ADRs — erst nach grünem
Verifikations-Scaffold angehen.

---

## 6 — Risiken

1. **Rows-of-Cols-Bauplan-Drift:** Die UI hat über Iterationen
   Layout-Tricks aufgenommen (transparente Wrapper-Pakete übersprungen,
   Horizontal-Layout-Ordering, etc.). Der Builder muss dieselben
   Regeln treffen, sonst gibt es Pseudo-Diskrepanzen. Lösung: nicht
   den Renderer einzeln spiegeln, sondern dieselben Quellen lesen
   (`HorizontalLayoutOrdering`, `shouldChildrenBeTransparent` etc.
   wiederverwenden oder die Logik dorthin verschieben, wo sie
   semantisch hingehört — ins Domain).
2. **Violations-Definition-Drift:** Wenn der Builder eine andere
   Violation-Liste produziert als der heutige UI-Pfad, muss
   *entschieden* werden welche stimmt. Der Checker macht das sichtbar;
   die ADR-Entscheidung ist explizit: **Violations werden vom
   Architecture-Builder definiert**. Der heutige Y-Vergleich gilt
   als bug-fix-relevant.
3. **Speicher-/Perf-Aufwand:** Ein zusätzliches Modell pro Analyse
   verdoppelt nicht den Speicher (die Rekord-Hierarchie ist flach),
   aber die Erstellung kostet O(|Klassen| + |Pakete|) — vernachlässigbar.

---

## 7 — Nicht-Ziele

- Kein UI-Rewrite in diesem ADR.
- Kein What-If-Rewrite in diesem ADR.
- Keine zweite Architecture-Klasse (`InterfaceArchitecture`) in diesem
  ADR — sie ist erlaubt, aber wird erst dann gebaut, wenn der
  Hierarchical-Layered-Pfad steht.
- Keine API-Änderung am bestehenden `DomainModel` außer ggf. dem
  Hinzufügen eines optionalen `getArchitecture()`-Getters (frühestens
  in C4).
