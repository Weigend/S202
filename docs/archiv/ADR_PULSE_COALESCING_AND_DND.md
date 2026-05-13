# ADR: Pulse-Coalescing, HBox/VBox-DnD und What-If-Architektur-Refactoring

Status: **Entschieden** — §4 am 2026-05-08, **erweitert** am 2026-05-12 (What-If-Scope mit virtueller Umbenennung)
Datum: 2026-05-08, erweitert 2026-05-12
Betrifft: `de.weigend.s202.ui.*` (LevelPackageBox-Callback, Renderer-Refresh, neue DnD-Schicht, virtuelle Identitäts-Schicht, Violation- und Dependencies-Anzeige)

> **Hinweis zur Historie:**
> 1. Eine erste Fassung schlug eine eigene Layout-Engine vor — verworfen.
> 2. Erweiterung 2026-05-12, erste Form: Cross-Parent-DnD mit stabilem `fullName` (nur Slot-Override). **Verworfen am selben Tag** — der Nutzer braucht eine echte What-If-Analyse: wenn eine Klasse verschoben wird, **ändert sich virtuell ihr Paketname**, und damit ändern sich die Paket-zu-Paket-Dependencies, die Paket-SCCs und die Paket-Level. Nur so wird sichtbar, was ein echtes Refactoring im Modell bewirken würde.
> 3. §4.2 ist gegenüber dem 2026-05-08-Stand zweimal überarbeitet; finale Form siehe unten.

---

## 1 — Kontext

Die Architektur-Ansicht baut die Hierarchie aus geschachtelten `HBox`/`VBox`-Containern in [LevelPackageBox.java](../analyzer/src/main/java/de/weigend/s202/ui/LevelPackageBox.java) und [LevelClassBox.java](../analyzer/src/main/java/de/weigend/s202/ui/LevelClassBox.java). Renderer ([DependencyRenderer.java](../analyzer/src/main/java/de/weigend/s202/ui/rendering/DependencyRenderer.java), [TangleEdgeRenderer.java](../analyzer/src/main/java/de/weigend/s202/ui/rendering/TangleEdgeRenderer.java), [SCCRenderer.java](../analyzer/src/main/java/de/weigend/s202/ui/rendering/SCCRenderer.java)) lesen Knoten-Bounds über `Node.getBoundsInParent()` / `localToScene(...)`.

Beobachtete Probleme und Bedarfe:

1. **Pulse-Race bei Bounds-Konsumenten.** Der Expand/Collapse-Callback in [LevelPackageBox.java:184-200](../analyzer/src/main/java/de/weigend/s202/ui/LevelPackageBox.java#L184-L200) feuert **synchron** im selben Pulse, in dem `setManaged(false)`/`setVisible(false)` die Layout-Invalidierung erst einreiht. Renderer lesen Bounds, bevor JavaFX neu layoutet hat → falsche/stale Pfeil-Endpunkte, Pfeile auf Null-Bounds, Mis-Routing nach Resize.

2. **Keine architekturelle DnD-Funktion.** Es gibt heute keine Möglichkeit, eine Klasse oder ein Paket manuell an eine andere Architektur-Position zu ziehen.

3. **Kein What-If-Refactoring-Workflow.** Ohne die Möglichkeit, Klassen und Pakete versuchsweise umzuhängen und sofort zu sehen, wie sich Paket-Dependencies, -Levels und -SCCs ändern, bleibt das Tool reine Diagnose. Refactoring-Vorbereitung — *"wenn ich diese Klasse zu Paket B verschiebe, welche neuen Architektur-Probleme entstehen, welche lösen sich auf, ändern sich Tangles?"* — ist heute nicht möglich.

Alle drei Themen werden gemeinsam adressiert: What-If braucht DnD, DnD prüft Pulse-Koordination scharf, und beides verlangt einen sauberen inkrementellen Recompute des Paket-Graphen (sonst nicht interaktiv).

## 2 — Entscheidung

### 2.1 — Layout bleibt JavaFX-nativ

Die bestehende `HBox`/`VBox`-Struktur in `LevelPackageBox`/`LevelClassBox` und `ArchitectureTreeBuilder` bleibt unverändert. Keine eigene Layout-Engine.

### 2.2 — Pulse-Coalescing als zentrales Refresh-Pattern

Renderer-Refresh wird über genau einen Mechanismus ausgelöst:

```
trigger (expand/collapse, DnD-Drop, Resize, Modell-Reload)
       ↓ markiert "arrows dirty"
       ↓ Platform.runLater(this::flushArrowsOnce)   — coalesced
       ↓ in genau einem späteren Pulse, nach abgeschlossenem Layout
Renderer liest aktuelle Bounds und zeichnet
```

Konkrete Hooks:
- `LevelPackageBox.toggleExpanded()`: Callback per `Platform.runLater` deferieren statt synchron auszulösen.
- `ArchitectureView`: `boundsInParentProperty`-Listener auf dem Wurzel-Container des `zoomableContent`. Jede Layout-Änderung markiert Pfeile als dirty; ein einziger `runLater`-Slot pro Pulse zeichnet sie neu (Coalescing).
- DnD-Drop ruft denselben Dirty-Mechanismus auf — keine Sonderlogik.

Cross-Parent-DnD und Hierarchie-Move erhöhen den Pulse-Druck. Phase 1 ist deshalb Voraussetzung für Phase 2.

### 2.3 — DnD-Mechanik: Slot-basiert, Cross-Parent, Hierarchie-fähig

**Drag-Quellen:** Klassen- und Paket-Boxen sind beide draggable. Ein gedragtes Paket bewegt seinen gesamten Subtree als logische Einheit mit (§4.5).

**Drop-Targets sind Slots zwischen Children einer Layout-Row, nicht die Container selbst.** Eine Row hat die Struktur:

```
| _  C1  _  P1  _  C2  _  C3  _ |
   ↑       ↑       ↑       ↑   ↑
   Drop-Slot 0..4 (Insert-Marker-Position)
```

Die `_` Slots sind die einzig gültigen Drop-Zonen. Beim Hover wird der adressierte Slot **blau gehighlightet** (eingefügter Spacer-Node mit Hover-Background). Drop:
- **Innerhalb derselben Row:** `parentHBox.getChildren().remove(node); parentHBox.getChildren().add(targetIndex, node);`
- **Vertikal in andere Row desselben Pakets:** Ziel-`HBox` ist `LevelPackageBox.levelRows.get(newLevel)`.
- **Cross-Parent (in eine Row eines anderen Pakets):** `oldParent.contentContainer` entfernt, Ziel-Row des neuen Parents fügt ein. Konsequenzen für das Modell siehe §2.4 und §4.2.

**Hit-Test:** Slots zwischen Children, gefiltert nach gültigen Zielen (kein Drop in eigenen Subtree, kein Selbst-Drop). Ungültige Slots werden visuell nicht hervorgehoben; Drop dort verwirft den Drag.

### 2.4 — Virtuelle Identitäts-Schicht und inkrementeller Recompute

Damit DnD eine echte What-If-Analyse liefert und gleichzeitig interaktiv bleibt, läuft **kein** voller Modell-Neubau pro Drop. Stattdessen:

**Schichten:**

| Schicht | Inhalt | Mutiert durch DnD? |
|---|---|---|
| **Statisch (Code-Realität)** | Class-IDs, Class-to-Class-Edges, Methodenaufruf-Details | **Nein** — kommt aus der Code-Analyse, ändert sich nur bei Neu-Analyse |
| **Virtuelle Identität (`OverrideStore`)** | Pro bewegtem Knoten: `virtualPackagePath` (neuer Paket-Pfad als virtueller `fullName`) + `virtualSlot` (parent, level, rowIndex, colIndex) | **Ja** (in-memory) |
| **Abgeleitet** | Paket-Membership-Resolution (Klasse → virtuelles Paket), Paket-zu-Paket-Edges, Paket-Level, Paket-SCCs, Violation-Klassifikation | **Ja, lokal inkrementell** |

**Echte virtuelle Umbenennung:** Wenn `com.foo.a.X` virtuell nach Paket `b` verschoben wird, gilt für alle Architektur-Auswertungen: Die Klasse heißt jetzt `com.foo.b.X`. Eine Class-Edge `c.Y → a.X` wird zur Paket-Edge `c → b` aggregiert, nicht mehr `c → a`. Das ist die What-If-Aussage in echter Form: "Was wäre, wenn diese Klasse tatsächlich in `b` läge?" — inklusive aller Folgen für Paket-SCCs und -Level.

**Lokales Recompute pro Drop:**

1. **Override aktualisieren** für den bewegten Wurzel-Knoten (bei Hierarchie-Move nur den Wrapper, §4.5). Override enthält neuen virtuellen Paket-Pfad + Slot.
2. **Betroffene Class-Edges** = alle, deren Quelle oder Ziel im bewegten Subtree liegt. Für diese:
   - Alte Paket-Resolution: `(oldPkgSrc → oldPkgTgt)` Aggregations-Counter dekrementieren.
   - Neue Paket-Resolution: `(newPkgSrc → newPkgTgt)` Aggregations-Counter inkrementieren.
3. **Paket-Graph-Recompute** auf dem aktualisierten Paket-Edge-Set:
   - **Paket-SCCs** (Tarjan auf Paket-Graph) — voller Recompute akzeptabel, weil der Paket-Graph klein ist (typisch <100 Knoten, <1000 Edges). Pro Drop deutlich unter Pulse-Budget.
   - **Paket-Level** aus dem Kondensations-DAG — ebenso.
4. **Nicht angefasst:** Class-Edge-Existenz selbst, Methodenaufruf-Details, alle Class-Edges außerhalb der bewegten Subtree-Inzidenz.

**Komplexität pro Drop:** O(deg(bewegter Subtree)) für die Edge-Reklassifikation + O(|V_pkg| + |E_pkg|) für SCC/Level auf dem Paket-Graph. Beide klein.

### 2.5 — Upward-Edges sichtbar machen

Nach jedem Drop zeigt der Renderer die nach oben laufenden Dependencies gegen die **neuen** virtuellen Paket-Level:

- **Klassen-Edges:** einzelne Pfeile in Warn-Farbe (Class A → Class B, virtueller Source-Level höher als virtueller Target-Level).
- **Paket-Edges (collapsed):** **aggregierte Linie** zwischen Paket-Boxen mit Count-Badge (z.B. `↑ 7`), Anzahl der enthaltenen aufwärts laufenden Class-Edges. Beim Expandieren löst sich das Aggregat in einzelne Klassen-Edges auf.
- **Neu entstandene Paket-SCCs** werden vom `SCCRenderer` sofort als Tangle markiert — direktes What-If-Feedback: "Dieser Move erzeugt einen neuen Zyklus".
- Edge-Auswahl hängt am bestehenden Mechanismus aus [GraphSelection.java](../analyzer/src/main/java/de/weigend/s202/ui/GraphSelection.java).

### 2.6 — Dependencies-View (neues Side-Panel links)

Ein neues, dockbares Panel links vom `zoomableContent`. Inhalt:

- **Liste der aktuellen aufwärts laufenden Edges**, gruppiert nach (virtueller) Quelle.
- **Liste der Paket-SCCs**, inkl. neu durch den Move entstandener Zyklen (Diff-Markierung).
- Klick auf einen Eintrag selektiert die zugehörige Edge / das SCC im Renderer.
- **Drilldown auf aggregierte Paket-Edges:** Klick auf eine `↑ 7`-Linie öffnet die Liste der dahinterliegenden Class-Edges; weiterer Klick auf eine Class-Edge zeigt die einzelnen **Methodenaufrufe**, die diese Dependency stiften (aus den statischen Methodenaufruf-Details — die ändern sich nie).
- Phase 5 startet mit einfacher Liste + Suche + Paging, kein Graph.

### 2.7 — Violations weiterhin über existierenden `LayoutInvariantChecker`

`LayoutInvariantChecker` läuft beim Drop gegen das durch die virtuelle Identität überlagerte Modell. Da die virtuelle Identität echte Paket-Pfade liefert, kann der Checker unverändert verwendet werden — er sieht das Modell so, als sei das Refactoring tatsächlich passiert. Kein Kopier-Aufwand der Regel-Logik.

## 3 — Konsequenzen

**Positiv:**
- Pulse-Bugs strukturell behoben.
- DnD ist additive UI- + Identitäts-Override-Schicht, kein Refactor der Layout-Wege.
- What-If ist echtes Power-Feature: Nutzer sieht **alle** Modell-Konsequenzen einer geplanten Klassenverschiebung — geänderte Paket-Deps, neue/aufgelöste Paket-SCCs, verschobene Levels, neue Upward-Edges, neu erfüllte/verletzte Invarianten.
- Statische Daten (Class-Edges, Methodenaufruf-Details) werden nie neu berechnet → Interaktivität gehalten.
- Aus dem Override-Store lassen sich später echte Refactoring-Schritte ableiten (Move-Class-Operation pro Override-Eintrag).

**Negativ / bewusst aufgegeben:**
- **Layout-Tests rein auf Datenstrukturebene** entfallen — TestFX bleibt der Weg.
- **Renderer-Kopplung an `getBoundsInParent`** bleibt.
- **Zwei Wahrheiten:** Statische `fullName` (Code) ≠ virtueller `fullName` (Architektur-Hypothese). UX braucht klare Kennzeichnung verschobener Boxen ("modified") und einen sichtbaren Reset-Pfad. Bewusst akzeptiert, weil es die What-If-Natur des Features korrekt abbildet.

**Bleibt offen für später, ohne Designhindernis:**
- **Lazy-Materialisierung der Node-Kinder.** Additive Erweiterung von `ArchitectureTreeBuilder`.
- **Echtes Refactoring-Codegen** aus dem Override-Store (jeder Override → Move-Class-Operation).
- **Persistenz** der Overrides (siehe §4.1).

**Nicht-Ziele:**
- Keine Änderungen an Class-Edge-Discovery oder Methodenaufruf-Analyse.
- Keine eigene Layout-Engine.
- Kein automatischer Refactoring-Apply-Schritt in dieser Iteration.

## 4 — Architektur-Entscheidungen

Beschlossen am 2026-05-08, erweitert am 2026-05-12.

### 4.1 — Persistenz manueller DnD-Verschiebungen `[ENTSCHIEDEN: nur In-Memory]`

Overrides leben nur in der laufenden Session. Keine Serialisierung. Beim Schließen oder bei neuer Analyse weg. Persistenz und Apply-Workflow in späterer Iteration (eigenes ADR).

### 4.2 — Drop-Scope und virtuelle Identität `[ÜBERARBEITET 2026-05-12: Cross-Parent erlaubt, echte virtuelle Umbenennung]`

DnD darf ein Element in jeden Slot jedes anderen Containers verschieben — Cross-Parent eingeschlossen. Vertikales Verschieben und Hierarchie-Move sind erlaubt.

**Semantik: Echte virtuelle Umbenennung.** Beim Drop in ein anderes Paket bekommt der bewegte Knoten einen `virtualPackagePath` im `OverrideStore`. Für **alle** Architektur-Auswertungen — Paket-Aggregation, Paket-Edges, Paket-SCCs, Paket-Level, Violation-Checker — verhält sich die Klasse, als hieße sie tatsächlich `<neues Paket>.<KlassenName>`. Die statische Code-Quelle bleibt unverändert.

**Folge:** Die What-If-Analyse ist vollständig. Ein Move von `a.X` nach `b` zeigt:
- Welche Class-Edges jetzt zu `b` aggregieren statt zu `a`.
- Welche Paket-Edges neu entstehen oder verschwinden.
- Welche Paket-SCCs sich auflösen oder neu bilden.
- Welche Upward-Edges entstehen oder weggehen.

**Folge für späteres Refactoring-Codegen:** Jeder Override-Eintrag entspricht einer "Move class X to package Y"-Operation. Saubere Quelle.

**Ungültige Drops** (Selbst-Drop, Drop in eigenen Subtree bei Hierarchie-Move) werden nicht hervorgehoben und verworfen.

**Verworfene Vorgängerentscheidungen:**
- 2026-05-08: "Drops nur innerhalb desselben Parents." Begründung damals `fullName`-Stabilität — mit virtueller Identität ohnehin gegeben für Code-Realität, und der Cross-Parent-Bedarf ist Kern des What-If-Workflows.
- 2026-05-12 (Zwischenstand): "Cross-Parent erlaubt, aber `fullName` stabil — nur visueller Slot ändert sich." Verworfen, weil das die What-If-Analyse nicht vollständig macht: Paket-Aggregation und Paket-SCCs würden gegen den alten Paket-Pfad rechnen und der Nutzer sähe nicht, was ein echtes Refactoring bewirken würde.

### 4.3 — Violation-Anzeige: Live-Preview vs. Post-Drop `[ENTSCHIEDEN: erst nach Drop, lokal inkrementell]`

Edge-Reklassifikation + Paket-Graph-Recompute + `LayoutInvariantChecker` laufen genau einmal nach erfolgreichem Drop, lokal beschränkt auf den betroffenen Subtree für die Edge-Phase und voll-aber-klein für den Paket-Graph (§2.4). Findings + Upward-Edges + neue SCCs anschließend angezeigt.

**Begründung:** Deterministisch, performant durch Lokalität, simpler Code-Pfad. Live-Preview bleibt additiv möglich.

### 4.4 — Konfliktauflösung Override vs. Neuanalyse `[ENTSCHIEDEN: Edits werden verworfen]`

Folgt aus §4.1. Neue JARs / neue Analyse → Override-Store verworfen. UX: Bestätigungsdialog vor Re-Analyse, wenn Overrides existieren.

### 4.5 — Hierarchie-Move: nur Wrapper-Knoten bekommt Override `[ENTSCHIEDEN 2026-05-12]`

Beim Verschieben eines Pakets `P` samt Inhalt erhält **nur** `P` einen Eintrag im Override-Store. Die enthaltenen Kinder bewegen sich logisch über die Parent-Beziehung mit — die virtuelle Paket-Resolution einer Kind-Klasse `X` läuft: hat `X` eigenen Override? Nein → folge Parent-Kette zum nächsten Override-tragenden Ahn → konstruiere virtuellen `fullName` aus dessen `virtualPackagePath` + dem Suffix unterhalb dieses Ahnen.

**Begründung:**
- O(1) Override-Einträge pro Hierarchie-Move statt O(|Inhalt|).
- Bei späterer Persistenz wird die Override-Tabelle nicht aufgebläht.
- Bei späterem Refactoring-Codegen wird ein Hierarchie-Move zu einer einzelnen "Move package P to Q"-Operation, nicht zu N "Move class"-Operationen.

**Caching:** Die Paket-Resolution-Funktion (Class-ID → virtueller Paket-Pfad) wird gecacht und beim Override-Update gezielt invalidiert.

### 4.6 — Paket-Edge-Aggregation: Counter inkrementell, Paket-SCCs voll `[ENTSCHIEDEN 2026-05-12]`

Aggregierte Paket-zu-Paket-Edge-Counts werden als `Map<(PkgA, PkgB) → AggregateEdge { totalCount, upwardCount }>` geführt und pro Drop **inkrementell aktualisiert**: Differenz der alten → neuen Paket-Resolution für die betroffenen Class-Edges. Paket-SCCs und Paket-Level werden auf dem resultierenden Paket-Graph **voll neu berechnet** (Tarjan + Topo-Sort) — der Graph ist klein genug.

**Begründung:** Class-Edge-Voll-Recompute (Tausende) wäre nicht interaktiv; Paket-Graph-Voll-Recompute (Hunderte Edges) ist sub-Pulse.

**Risiko:** Counter-Drift bei Bug → Test-Mode vergleicht periodisch gegen Voll-Recompute der Aggregat-Counter (siehe §6).

## 5 — Phasenplan

| Phase | Aufwand | Inhalt |
|---|---|---|
| 1 | 2–3 T | **Pulse-Fix.** `Platform.runLater` für Expand-Callback, `boundsInParentProperty`-Listener mit Coalescing, Regressionstests für bekannte Renderer-Bugs. |
| 2 | 1.5 W | **DnD-Mechanik.** `ArchitectureDragController`, Slot-Highlighting (blaue Spacer), Cross-Parent-Hit-Test, Hierarchie-Move (Wrapper-Drag, §4.5). Reine UI, ohne Modellmutation. |
| 3 | 1.5 W | **Virtuelle Identität + inkrementeller Recompute.** `OverrideStore` mit `virtualPackagePath`, Paket-Resolution-Funktion mit Caching, inkrementelle Aggregat-Counter (§4.6), Paket-Graph-Recompute (SCC + Level) auf Drop, Re-Trigger des Layouts. |
| 4 | 1 W | **Upward-Edge-Renderer + Violations + neue SCCs.** Einzel- vs. aggregierte Paket-Edges mit Count-Badges, Warn-Farben, `SCCRenderer` gegen neuen Paket-SCC-Set, `LayoutInvariantChecker`-Lauf gegen Override-Modell, Box-Highlighting. |
| 5 | 3–5 T | **Dependencies-View.** Side-Panel links: Liste Upward-Edges, Liste Paket-SCCs (mit Diff vs. statischem Modell), Drilldown Paket-Edge → Class-Edges → Methodenaufrufe (§2.6). |
| Später | — | **Persistenz** + **Refactoring-Codegen** (jeder Override → Move-Operation). Eigenes ADR. |

**Gesamt: ~5–5.5 Wochen** für Phasen 1–5. Phase 1 ist eigenständig wertvoll. Phase 3 wuchs gegenüber dem 2026-05-08-Plan um ~0.5 Woche wegen Paket-Graph-Recompute und Caching.

## 6 — Risiken

1. **Coalescing-Bug subtil falsch.** Dirty-Flag nicht atomar oder mehrere `runLater` parallel → doppeltes Rendering (harmlos) oder verpasste Updates (gravierend). Tests prüfen genau das.
2. **Bounds-Listener-Storm.** Viele Boxen + Listener teuer. Lösung: Listener nur auf wenigen Wurzelknoten.
3. **Verlust manueller Edits beim Reload.** Folgt aus §4.1/§4.4. Akzeptiert für v1, UX kommuniziert es.
4. **DnD und Animationen.** v1: harte Snaps.
5. **Cross-Parent erhöht Pulse-Druck.** Hierarchie-Move löst viele Layout-Pässe aus. Coalescing aus Phase 1 muss das tragen.
6. **Aggregations-Counter-Drift.** Bei falscher Inkrementierung divergieren Anzeige und tatsächlicher Edge-State. Tests: regelmäßiger Vergleich gegen Voll-Recompute auf synthetischen Modellen; in Debug-Build optional pro Drop.
7. **Paket-Resolution-Cache-Invalidierung.** Bei Hierarchie-Move muss der Cache für **alle** Nachfahren des bewegten Wurzelknotens invalidiert werden. Bug-anfällig — Test, dass Resolution nach Move für jedes Kind den korrekten neuen Pfad liefert.
8. **Drilldown-UI-Komplexität.** Methodenaufruf-Liste pro Aggregat groß → Phase 5 startet mit Liste + Suche + Paging, kein Graph.
9. **Zwei Wahrheiten verwirren Nutzer.** Statische `fullName` ≠ virtuelle. UX braucht visuelles Signal an verschobenen Boxen, Reset-Knöpfe pro Override und global.

## 7 — Referenzen

- Pulse-kritischer Callback: [LevelPackageBox.java:184-200](../analyzer/src/main/java/de/weigend/s202/ui/LevelPackageBox.java#L184-L200)
- Bestehender Tree-Aufbau: [ArchitectureTreeBuilder.java](../analyzer/src/main/java/de/weigend/s202/ui/tree/ArchitectureTreeBuilder.java)
- Renderer mit Bounds-Lookup: [DependencyRenderer.java](../analyzer/src/main/java/de/weigend/s202/ui/rendering/DependencyRenderer.java), [TangleEdgeRenderer.java](../analyzer/src/main/java/de/weigend/s202/ui/rendering/TangleEdgeRenderer.java), [SCCRenderer.java](../analyzer/src/main/java/de/weigend/s202/ui/rendering/SCCRenderer.java)
- Wiederverwendete Regel-Engine: [LayoutInvariantChecker.java](../analyzer/src/main/java/de/weigend/s202/analysis/invariants/LayoutInvariantChecker.java)
- Selektionsmechanismus für Highlighting: [GraphSelection.java](../analyzer/src/main/java/de/weigend/s202/ui/GraphSelection.java)
