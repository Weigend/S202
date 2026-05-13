# Refactorings ohne Code-Change

Dieses Dokument listet die What-If-Moves auf, mit denen sich die meisten
Architektur-Violations des analyzer-Projekts visuell auflösen lassen —
ohne den Source-Code anzufassen. Die Vorschläge sind aus dem Diagnose-
Test [`AnalyzerSelfViolationsTest`](../analyzer/src/test/java/de/weigend/s202/domain/architecture/AnalyzerSelfViolationsTest.java)
abgeleitet, der dieselbe Pipeline durchläuft wie die UI und exakt die
27 Klassen-Edges (+ 2 Tangles) zeigt, die das Tool im
"Wrong-direction edges"-Panel anzeigt.

Test ausführen:

```
cd analyzer && mvn test -Dskip.self.violations=false -Dtest=AnalyzerSelfViolationsTest
```

## Ausgangsbild

| # | Source pkg ↑ Target pkg | Klassen-Edges |
| --- | --- | --- |
| 1 | `ui` ↑ `ui.rendering` | 6 |
| 2 | `ui.rendering` ↑ `ui.rendering.circuit` | 4 |
| 3 | `domain` ↑ `analysis.strategy` | 3 |
| 4 | `domain` ↑ `analysis.strategy.impl` | 3 |
| 5 | `ui` ↑ `ui` | 2 |
| 6 | `ui.wfx` ↑ `ui.wfx.tangles` | 2 |
| 7 | `ui.wfx` ↑ `ui.wfx.whatif` | 2 |
| 8 | `domain` ↑ `analysis.strategy.aggregation` | 1 |
| 9 | `project` ↑ `ui.rendering` | 1 |
| 10 | `ui` ↑ `ui.tree` | 1 |
| 11 | `ui.wfx` ↑ `ui.wfx.outline` | 1 |
| 12 | `ui.wfx` ↑ `ui.wfx.quality` | 1 |

## Gruppe A — Sub-Paket-Aggregation (18 von 27)

**Beobachtung.** Eltern-Paket-Klassen hängen an Klassen in Sub-Paketen.
Der `LevelCalculator` setzt das Sub-Paket auf das *Maximum* der Level
seiner Klassen — dadurch rückt das Sub-Paket optisch über genau die
Klasse im Eltern-Paket, die es benutzt. Visuell sieht das wie ein
Aufwärtspfeil aus, obwohl die Code-Abhängigkeit selbst sauber ist.

**Move.** Innerhalb jeder Eltern-Box das Sub-Paket per Stack-Drop in
eine Zeile **unter** seine Konsumenten ziehen.

| Move | erspart |
| --- | --- |
| Innerhalb `ui`: `ui.rendering`-Box unter `ArchitectureView` | 6 |
| Innerhalb `ui.rendering`: `ui.rendering.circuit` unter `CircuitBoardRenderer` | 4 |
| Innerhalb `ui`: `ui.tree` unter `ArchitectureView` | 1 |
| Innerhalb `ui.wfx`: `ui.wfx.tangles` unter `S202Module`/`WfxModule` | 2 |
| Innerhalb `ui.wfx`: `ui.wfx.whatif` unter `S202Module`/`WfxModule` | 2 |
| Innerhalb `ui.wfx`: `ui.wfx.outline` unter `WfxModule` | 1 |
| Innerhalb `ui.wfx`: `ui.wfx.quality` unter `WfxModule` | 1 |
| **Summe Gruppe A** | **17** |

## Gruppe B — Top-Level-Layering brüchig (7 von 27)

**Beobachtung.** `domain.LevelCalculationStrategyFactory` und
`domain.LevelCalculator` hängen an Klassen in `analysis.strategy.*`.
Strukturell ist das ein echter Layering-Verstoß (Domäne hängt an der
Analyse-Schicht), aber visuell sehr klar auflösbar, indem das
`strategy`-Sub-Tree komplett unter `domain` einsortiert wird. Bestätigt
durch User-Test: das Tool zeigt das gut, sobald `strategy` aus `domain`
nach unten gezogen wird.

| Move | erspart |
| --- | --- |
| Top-Level: `analysis.strategy` (samt `.impl`, `.aggregation`) unter `domain` | 7 |

Für einen späteren Code-Change wäre die saubere Auflösung: das
Strategy-Interface zur Domäne migrieren (Inversion-of-Control), die
Impls bleiben in `analysis`.

## Gruppe C — echte Hot-Spots (2 von 27 bleiben)

Drei Klassen-Edges, die sich nicht durch reine Moves auflösen lassen
und auf echte Anti-Patterns im Code hindeuten:

- **`ui ↑ ui`** (2 Edges) — `LevelClassBox` und `LevelPackageBox`
  rufen in `ArchitectureView` zurück. Intra-Package-Aufwärtspfeil:
  visuell nicht entkoppelbar, weil Source und Target im selben Paket
  liegen. Saubere Auflösung wäre ein Callback-Interface oder Event-Bus
  in `ui`, an das die Boxes hängen statt direkt an `ArchitectureView`.

- **`project ↑ ui.rendering`** (1 Edge) — `S202ProjectMapper` hängt an
  `TangleEdgeRenderer`. Der Projekt-Persistenz-Layer sollte nicht an
  einer UI-Renderer-Klasse hängen. Visuell ließe sich `project` zwar
  unter `ui.rendering` schieben — semantisch ist das aber Unsinn. Der
  ehrliche Fix: den genutzten `TangleEdgeRenderer.Edge`-Record als
  Domain-Typ rausziehen (z.B. nach `de.weigend.s202.domain.architecture`
  oder einer eigenen `project.api`), die UI nutzt dann diesen Domain-Typ.

## Zusammenfassung

- **24 von 27 Violations (89%)** verschwinden durch reine What-If-Moves.
- 3 Edges (Gruppe C) sind echte Code-Anti-Patterns und brauchen
  kleinere Refactorings im Source.
- Die 2 Tangles bleiben unverändert (statische Eigenschaft des
  Klassen-Graphen, von visuellen Moves nicht beeinflusst).
