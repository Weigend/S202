# ADR: Architecture-Level vs. Local-Layer-Index — zwei Konzepte trennen

Status: **Diskussion** — Branch `feature/parent-child-deps-in-weights`
Datum: 2026-05-15

---

## 1 — Das Problem in einem Satz

Im Code heißt heute *zweimal das Gleiche* `level`, obwohl es **zwei
fundamental unterschiedliche Konzepte** sind. Dieselbe Int-Spalte auf
`CalculatedElementInfo` (und entsprechend auf `Element.level()`) bedient
beides — und alle Heuristiken (Pre-Filter, `childPkgUsedLevel`-Lift,
Containment-Edge-Sonderkategorie) sind Krücken, die versuchen, die zwei
Welten *im selben Wert* zu koordinieren.

Die zwei Konzepte:

| Konzept | Geltungsbereich | Wozu | Wie berechnet |
|---|---|---|---|
| **ArchitectureLevel** | global, über den ganzen Dep-Graphen | Schichten-Aussage, Violation-Erkennung, Tangle-Bewertung, *konzeptionell* „über/unter" | Längster Pfad im (gewichteten) Dep-Graphen |
| **LocalLayerIndex** | lokal, innerhalb einer einzelnen Eltern-Box | Position einer Box im Visual — Schichten *innerhalb eines Pakets* | Sortier-Rang der **Geschwister** zueinander |

Beide handeln von *Schichten*. Aber:

- **ArchitectureLevel** ist die globale Schicht in der Gesamt-Architektur.
- **LocalLayerIndex** ist die Schicht *innerhalb genau dieses Container-Pakets* — also „im Paket P sind diese Klassen/Sub-Pakete oben, diese unten".

---

## 2 — Warum das heute knallt

### 2.1 Konkretes Beispiel auf Paket-Ebene

```
                          ArchitectureLevel (heute)
de.weigend.s202.ui                L6     <-- Eltern-Paket
de.weigend.s202.ui.rendering      L5     <-- Sub-Paket
de.weigend.s202.ui.model          L4     <-- Sub-Paket
```

`ui` (L6) liegt korrekt über seinen Sub-Paketen — dank des neuen
„alle Deps zählen, SCC-Rank entscheidet"-Algorithmus.

**Aber:** der `HierarchicalLayeredArchitectureBuilder` sortiert die
Kinder von `ui` (die Klassen *und* die Sub-Pakete) nach derselben
Int-Spalte:

```java
sorted.sort(Comparator
        .comparingInt((CalculatedElementInfo c) -> c.level).reversed()
        .thenComparing(c -> c.fullName));
```

Damit landen `ui.rendering` (L5) und `ui.model` (L4) in *zwei
verschiedenen Reihen* innerhalb der `ui`-Box, weil ihre globalen
Architecture-Levels unterschiedlich sind. **Das ist Zufall.** Die
Frage, *welche Box innerhalb von `ui` über welcher sitzen soll*, hat
mit den globalen Levels nichts zu tun — sondern damit, *wie diese
Geschwister-Boxen zueinander stehen*.

In der konkreten Lage:
- `ui.rendering` ruft `ui.model.ArchitectureNode` auf (rendering → model)
- `ui.model` ruft `ui.rendering` nicht zurück

→ **lokal in `ui`** muss `ui.rendering` über `ui.model` stehen. Dass
das per Zufall durch verschieden hohe globale Levels rauskommt, ist
fragil — und im allgemeinen Fall falsch (s. 2.2).

### 2.2 Konkretes Beispiel auf Klassen-Ebene

```
                          ArchitectureLevel (heute)
de.weigend.s202.ui.ArchitectureView    L4 (Klasse)
de.weigend.s202.ui.rendering           L5 (Sub-Paket)
de.weigend.s202.ui.LevelClassBox       L1 (Klasse)
```

`ArchitectureView` ist eine Klasse *im Paket `ui`*. Sie ruft massiv
Klassen aus `ui.rendering` auf (`TangleEdgeRenderer`,
`DependencyRenderer`, `CircuitBoardRenderer`, …). Im Class-Dep-Graphen
hat sie das Level 4 (eine Kette über CircuitBoardRenderer → … → einer
Leaf-Klasse mit Tiefe 4).

`ui.rendering` als Paket-Knoten hat Architecture-Level 5 — Skala aus
dem Paket-Graphen, nicht aus dem Class-Graphen.

In der `ui`-Box werden Klasse und Sub-Paket gemischt sortiert:

```
ui-Box (innen, sortiert nach .level desc):
  ┌─ Row L5 ── ui.rendering ── ui.consistency ── ui.debug ── ui.layout
  ├─ Row L4 ── ArchitectureView (Klasse) ── ui.model
  └─ Row L1 ── LevelClassBox, LevelPackageBox
```

**ArchitectureView landet unter der `ui.rendering`-Box, obwohl sie alle
Renderer dort drinnen aufruft.** Klassen-Levels und Paket-Levels sind
auf verschiedenen Skalen, lassen sich aber per `Comparator.comparingInt`
direkt vergleichen — der Vergleich ist *bedeutungslos*.

Manuell drüberziehen behebt die Sicht und alle Deps laufen brav nach
unten. Aber der Algorithmus kann das nie automatisch hinkriegen, weil
er die beiden Skalen für *vergleichbar* hält.

### 2.3 Was die Krücken heute davon kaschieren

Der bestehende Algorithmus hat über die Jahre versucht, die Spannung
mit Sonderfällen aufzulösen:

- **Pre-Filter** in `buildWeightedPackageGraph`: Parent→Child-Class-Refs
  raus, damit ein Paket *nicht* fälschlicherweise unter sein Kind
  rutscht.
- **`childPkgUsedLevel`-Lift**: Sub-Paket-Level wird über die
  Klassen-Levels der Eltern-Klassen gehoben, die es benutzt.
- **`ContainmentEdge`** (jetzt neu): die Parent→Child-Refs, die der
  Pre-Filter rausgeworfen hat, als separate Kategorie sichtbar machen.

Jede dieser Sachen ist ein lokaler Patch gegen denselben strukturellen
Bug: *globale Skala wird für lokale Sortierung missbraucht*. Mit dem
sauberen Schnitt fällt alles drei weg.

---

## 3 — Vorschlag: zwei Felder, zwei Algorithmen

### 3.1 Datenmodell

`CalculatedElementInfo` (bzw. das künftige Domain-Modell) trägt **zwei**
Werte statt einem:

```java
public class CalculatedElementInfo {
    ...
    public final int architectureLevel;   // global, vom LevelCalculator
    public int localLayerIndex;           // lokal, vom Layout-Layer-Calculator
}
```

Entsprechend auf `Element`:

```java
public sealed interface Element {
    int architectureLevel();
    int localLayerIndex();
    ...
}
```

### 3.2 Wie ArchitectureLevel berechnet wird

Im Wesentlichen das, was heute der `LevelCalculator` macht — mit der
Änderung dieses Branches, dass *alle* Deps (inkl. Parent→Child) in den
gewichteten Graphen einfließen und der SCC-Rank-Breaker fair
entscheidet. Aber **nur** das. Keine `childPkgUsedLevel`-Lifts mehr,
keine Pre-Filter, keine Sonderfälle. Pure Dep-Chain-Tiefe.

Das ArchitectureLevel dient dann ausschließlich Analyse-Aussagen:

- „Klasse `A` (Level 4) hängt von Klasse `B` (Level 6) ab → UPWARD-Violation"
- „Paket `P` und `Q` sind in einem SCC → Tangle"
- „Wie viele Schichten tief ist diese Architektur insgesamt?"

Nichts vom ArchitectureLevel wird mehr im Rendering benutzt.

### 3.3 Wie LocalLayerIndex berechnet wird

Pro Eltern-Paket (oder Top-Level für die Root-Anordnung) wird ein
**eigener kleiner Layout-Algorithmus** gefahren:

1. Sammele alle direkten Kinder des Eltern-Pakets — Klassen *und*
   Sub-Pakete derselben Hierarchie-Tiefe.
2. Baue einen Graphen *nur über diese Geschwister*: eine Kante von
   Geschwister `X` zu Geschwister `Y` existiert, wenn irgendwo im
   Subtree von `X` eine Class-Ref auf irgendetwas im Subtree von `Y`
   liegt. Gewichtung wie heute (Call-Count).
3. Tarjan-SCC auf diesem lokalen Geschwister-Graphen → SCC-DAG.
4. SCC-Rank-Break wie heute, falls Zyklen auftreten.
5. Topologische Längst-Pfad-Berechnung auf dem lokalen DAG → ergibt
   `localLayerIndex ∈ {0, 1, …, n-1}` pro Geschwister.

Wichtig: **`localLayerIndex` ist immer nur innerhalb einer Eltern-Box
gültig.** Index 3 in `ui` und Index 3 in `ui.wfx` haben nichts
miteinander zu tun.

### 3.4 Was der Renderer macht

Der heutige `buildRowsForPackage` wird einfacher: pro Paket nimmt er
die direkten Kinder, sortiert *nach `localLayerIndex` desc*, gruppiert
zu Rows pro `localLayerIndex`-Wert. Fertig. Keine Skalen-Vermischung
mehr.

---

## 4 — Beispiele, die unter dem neuen Schema „sich richtig anfühlen"

### 4.1 Paket-Ebene: Geschwister im Paket `ui`

Eltern: `ui`. Direkte Kinder (Auswahl):

```
Geschwister im Paket 'ui':
  ArchitectureView      (Klasse)
  LevelClassBox         (Klasse)
  LevelPackageBox       (Klasse)
  ui.rendering          (Sub-Paket)
  ui.model              (Sub-Paket)
  ui.layout             (Sub-Paket)
  ui.consistency        (Sub-Paket)
```

Lokaler Sibling-Graph (Aufruf-Aggregat):

```
ArchitectureView  →  ui.rendering     (massiv)
ArchitectureView  →  ui.model         (mehrfach)
ArchitectureView  →  ui.layout        (mehrfach)
ArchitectureView  →  ui.consistency   (gelegentlich)
ui.rendering      →  ui.model         (rendering nutzt model)
ui.rendering      →  LevelClassBox    (Boxen rendern)
ui.rendering      →  LevelPackageBox  (Boxen rendern)
ui.consistency    →  ui.model         (consistency liest model)
```

Topologie:

```
Row 3 (oben):  ArchitectureView
Row 2:         ui.rendering ── ui.consistency ── ui.layout
Row 1:         ui.model
Row 0 (unten): LevelClassBox ── LevelPackageBox
```

`localLayerIndex` werden vergeben: ArchitectureView=3, ui.rendering=2,
ui.consistency=2, ui.layout=2, ui.model=1, LevelClassBox=0,
LevelPackageBox=0.

→ **ArchitectureView sitzt sauber über `ui.rendering` in der `ui`-Box.**
Genau das, was beim manuellen Drag rauskommt.

### 4.2 Klassen-Ebene: Geschwister im Paket `ui.rendering`

Eltern: `ui.rendering`. Kinder sind nur Klassen (das Paket
`ui.rendering.circuit` ist auch noch dabei):

```
Geschwister im Paket 'ui.rendering':
  CircuitBoardRenderer
  DependencyRenderer
  DependencyRendererStrategy
  SCCRenderer
  TangleEdgeRenderer
  WhatIfUpwardEdgeRenderer
  ui.rendering.circuit  (Sub-Paket)
```

Lokaler Graph:

```
CircuitBoardRenderer       →  DependencyRendererStrategy  (implements)
DependencyRenderer         →  DependencyRendererStrategy  (implements)
CircuitBoardRenderer       →  ui.rendering.circuit        (delegiert ans Routing)
```

Topologie:

```
Row 2: CircuitBoardRenderer ── DependencyRenderer
Row 1: ui.rendering.circuit ── SCCRenderer ── TangleEdgeRenderer ── WhatIfUpwardEdgeRenderer
Row 0: DependencyRendererStrategy
```

Innerhalb der `ui.rendering`-Box sind die zwei „großen" Renderer oben,
das Strategy-Interface unten — wie man's erwartet, ohne dass irgendein
globales Class-Level damit interagiert.

### 4.3 Avaje-Wiring-Pattern: `ui.wfx` mit Sub-Modulen

Eltern: `ui.wfx`. Kinder:

```
S202Module                   (Klasse)
ArchitectureWfxView          (Klasse)
S202MenuBar                  (Klasse)
ui.wfx.events                (Sub-Paket)
ui.wfx.outline               (Sub-Paket)
ui.wfx.quality               (Sub-Paket)
ui.wfx.tangles               (Sub-Paket)
ui.wfx.whatif                (Sub-Paket)
```

Sibling-Graph:

```
S202Module          →  ui.wfx.whatif        (Lookup, 1 Call)
S202Module          →  ui.wfx.tangles       (mehrere Calls auf TangleFilter)
S202Module          →  ui.wfx.events        (Events publishen)
ArchitectureWfxView →  ui.wfx.events        (Events handlen)
ui.wfx.outline      →  ArchitectureWfxView  (View-Referenz)
ui.wfx.quality      →  ArchitectureWfxView  (View-Referenz)
ui.wfx.tangles      →  ArchitectureWfxView  (View-Referenz)
ui.wfx.whatif       →  ArchitectureWfxView  (View-Referenz)
```

`ArchitectureWfxView` wird also von vier Sub-Paketen *eingehend*
referenziert, von außen kaum aufgerufen. Sie ist die *Kern-View*, an
die sich alles andichtet.

Topologie:

```
Row 2: S202Module
Row 1: ui.wfx.outline ── ui.wfx.quality ── ui.wfx.tangles ── ui.wfx.whatif
       (jedes nutzt ArchitectureWfxView, also alle eine Stufe drüber)
Row 0: ArchitectureWfxView ── S202MenuBar ── ui.wfx.events
```

Schön zu sehen: **`ArchitectureWfxView` wandert in der `ui.wfx`-Box
nach UNTEN**, weil sie der Service ist, an dem die Module andocken.
S202Module ist oben, weil sie der Orchestrator ist. Heute liegt das
genau verkehrt herum, weil `ArchitectureWfxView`'s Class-Level nicht
weiß, dass es ein „Service-Charakter" hat.

---

## 5 — Was sich technisch ändert

### 5.1 Im DomainModel

```java
public class CalculatedElementInfo {
    public final int architectureLevel;   // war: level
    public int localLayerIndex;           // neu
    ...
}
```

Und `getter` / `setter` entsprechend. Migrations-Detail: alle bisherigen
Aufrufer von `.level` umstellen — entweder auf `.architectureLevel`
(Analyse) oder auf `.localLayerIndex` (Layout). Beim Code-Lesen wird
sofort klar, welche Bedeutung gemeint ist.

### 5.2 Im LevelCalculator

- `calculatePackageLevels` füllt `architectureLevel` und sonst nichts.
- `childPkgUsedLevel`-Logik fliegt raus.
- Mit dem Branch-Change „alle Deps in Weights": Pre-Filter raus.

### 5.3 Neu: LocalLayerCalculator

Neue Klasse `LocalLayerCalculator` (oder Methode auf
`HierarchicalLayeredArchitectureBuilder`):

```java
void assignLocalLayerIndices(String parentPkg, DomainModel domain) {
    List<CalculatedElementInfo> siblings = directChildrenOf(parentPkg, domain);
    Map<String, Map<String, Integer>> graph = buildSiblingGraph(siblings, domain);
    // Tarjan -> SCC-rank-break -> Longest-path topologisch
    Map<String, Integer> ranks = computeLocalLayers(graph);
    for (CalculatedElementInfo s : siblings) {
        s.localLayerIndex = ranks.getOrDefault(s.fullName, 0);
    }
    // Rekursion in Sub-Pakete
    for (CalculatedElementInfo s : siblings) {
        if ("PACKAGE".equals(s.type)) {
            assignLocalLayerIndices(s.fullName, domain);
        }
    }
}
```

Aufgerufen vom Builder nach der Architecture-Level-Berechnung.

### 5.4 Im Builder

`buildRowsForPackage` sortiert künftig nach `localLayerIndex` statt
`level`. Sonst keine Änderung — die Row-Struktur entsteht aus der
Gruppierung gleicher Indizes.

### 5.5 Die ContainmentEdge-Kategorie

Wird mit dem un-gefilterten Graphen technisch obsolet — die Edges
existieren jetzt regulär. Frage: trotzdem behalten als visuelle
Hilfslinie (gestrichelt), weil Parent→Child-Refs für den Leser einen
**strukturellen** Charakter haben, unabhängig von der Frage „zählt
das im Layering"? Würde ich erstmal beibehalten und in einem
separaten Schritt entscheiden.

---

## 6 — Migration, Konsequenzen, Risiken

### 6.1 Tests

Massive Verschiebung erwartet. Test-Erwartungen über
`pkg.level == X` müssen unterteilt werden:

- Tests, die Analyse-Aussagen prüfen → `architectureLevel`
- Tests, die Layout-Position prüfen → `localLayerIndex`

Beide Tests sind danach *klarer*, weil offensichtlich ist, was
geprüft wird.

### 6.2 Violation-Erkennung

Die heutige Violation-Logik in
`HierarchicalLayeredArchitectureBuilder.detectViolations` benutzt
`computeVisualRank` — eine Liste aus Paket-Levels der Vorfahren plus
Class-Level. Das ist eine *visuelle* Aussage über UPWARD-Edges. Diese
Logik müsste auf `localLayerIndex` umgestellt werden — was eigentlich
auch sauberer ist, denn „Violation" ist *visuell* definiert
(„Pfeil zeigt nach oben"), nicht globalwegs.

### 6.3 Risiken

- **Lokale SCCs zwischen Geschwistern werden möglich**, wenn z.B.
  zwei Geschwister-Sub-Pakete sich gegenseitig referenzieren. Der
  Rank-Break muss das genauso fair tun wie heute auf globaler Ebene.
- **Unterschiedliche Anzahl Reihen pro Eltern-Box** ist heute schon
  der Fall, wird aber stärker variieren — das Visual muss damit
  umgehen (tut es vermutlich, weil heute auch schon Pakete mit nur
  einer Reihe vorkommen).

---

## 7 — Entscheidungen

### 7.1 Sub-Paket-Aggregation: nur innerhalb der Eltern-Box

Wenn ein Geschwister ein Sub-Paket ist, hängen seine Deps an seinen
Inhalts-Klassen. **Aggregat-Regel:** aus jeder Class-Ref im Subtree
von Geschwister `X` zu irgendetwas im Subtree von Geschwister `Y`
(beide Subtrees innerhalb desselben Eltern-Pakets!) wird eine Kante
`X → Y` mit Call-Count als Gewicht.

Refs, die **aus dem Eltern-Paket rauslaufen** (eine Klasse im Subtree
von `X` ruft etwas außerhalb des Eltern-Pakets auf), werden im lokalen
Sibling-Graphen **ignoriert**. Diese Out-of-Parent-Beziehungen
schlagen sich ausschließlich im globalen ArchitectureLevel nieder
und werden über die Paket-Hierarchie eine Ebene höher behandelt.

→ Folge: der Sibling-Graph in jedem Eltern-Paket ist geschlossen und
unabhängig vom Rest des Universums. Das ist Voraussetzung dafür, dass
LocalLayerIndex überhaupt lokal Sinn ergibt.

### 7.2 Tangles bleiben rein global

Tangles sind eine globale Aussage auf Basis des ArchitectureLevel
über den vollen Dep-Graphen — die jetzige Logik bleibt unverändert.

**Wir führen kein zweites Tangle-Konzept auf Layout-Ebene ein.**
Sollte ein Sibling-Graph lokal einen Zyklus enthalten (z.B. zwei
Geschwister-Sub-Pakete, die sich gegenseitig referenzieren), erledigt
das der Rank-basierte SCC-Break als reine Layout-Mechanik, ohne dass
der User davon erfährt. Es gibt also kein „Local Cycle" als
sichtbares Domain-Konzept — nur globale Tangles werden gemeldet.

### 7.3 Performance: erst korrekt, dann optimieren

Pro Eltern-Paket ein eigener Tarjan-Lauf. Bei der Größenordnung
realistischer Codebases (hunderte Pakete) ist das problemlos. Sollte
es bei sehr großen Projekten knapp werden, ist ein nachträgliches
Caching der Tarjan-Ergebnisse pro Subtree machbar — aber erst, wenn
die Korrektheit steht.

---

## 8 — Vorschlag fürs Vorgehen

1. Diese ADR diskutieren, Punkte 7 entscheiden.
2. `architectureLevel` einführen als Umbenennung von `level`. Compile-
   Failures abarbeiten. Pure Mechanik, kein Verhalten ändert sich.
3. `localLayerIndex` als zweites Feld einführen, zunächst auf 0
   gesetzt, kein Effekt.
4. `LocalLayerCalculator` implementieren und im Builder aufrufen.
5. `buildRowsForPackage` von `level` auf `localLayerIndex` umstellen.
   Hier kippt das Verhalten — Tests werden brennen.
6. Tests neu kalibrieren, gleichzeitig die `childPkgUsedLevel`-Lifts
   und Containment-Pre-Filter entfernen (gehören dann zur
   Aufräumarbeit).
7. Violation-Detection auf `localLayerIndex` umstellen.

Schritte 2–4 sind kostenlos / revertbar. Schritt 5 ist der Sprung.

---

## 9 — Begriffsklärung

- **Schicht** = jede Position in einem Layered-Diagramm.
- **ArchitectureLevel** = die Schicht in der globalen Architektur.
- **LocalLayerIndex** = die Schicht innerhalb einer Eltern-Box.
- **Tangle** = SCC im globalen Dep-Graphen. Bleibt das einzige
  user-sichtbare Zyklus-Konzept.
