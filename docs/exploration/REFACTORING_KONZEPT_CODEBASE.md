# Refactoring-Konzept: Zyklen auflösen & Riesenklassen zerlegen

*Stand: Juli 2026 · Befund toolverifiziert (S202-Analyse auf `s202-code-analyzer-1.0.0.jar`)*

Dieses Dokument ist das interne Umbau-Konzept für die eigene Codebase.
(Nicht zu verwechseln mit [S202_REFACTORING.md](../S202_REFACTORING.md), das den
Nutzer-Workflow beschreibt.)

---

## 1. Befund (mit dem eigenen Tool erhoben)

Analysepipeline `InputAnalyzer → LevelCalculator → TarjanSCCFinder` auf das
eigene Jar angewandt. Ergebnis:

### 1.1 Zwei Paket-Tangles (je 4 Pakete)

**Tangle 1 — die 2D-View-Schicht:**
`ui ↔ ui.component ↔ ui.rendering ↔ ui.tree`

Verursachende Kanten (Klassenebene):

| von | nach | Grund |
|---|---|---|
| `ui.rendering.DependencyRenderer` | `ui.GraphSelection`, `ui.LevelClassBox`, `ui.LevelPackageBox` | Typ-Checks/`getFullName()` |
| `ui.rendering.TangleEdgeRenderer` | `ui.PulseCoalescer` | Redraw-Koaleszenz |
| `ui.rendering.WhatIfUpwardEdgeRenderer` | `ui.LevelPackageBox` | Typ-Check |
| `ui.tree.*TreeBuilder` (alle drei) | `ui.LevelClassBox`, `ui.LevelPackageBox`, `ui.ArchitectureDragController` | Box-Erzeugung, Row-Marker |
| `ui.component.ComponentBox` | `ui.ArchitectureDragController`, `ui.GraphSelection` | DnD, Selektion |
| `ui.ArchitectureView` | `ui.component.ComponentBox`, `ui.rendering.*`, `ui.tree.*` | Orchestrierung (legitim, abwärts) |

**Tangle 2 — die wfx-Modulschicht:**
`ui.wfx ↔ ui.wfx.tangles ↔ ui.wfx.whatif ↔ ui.wfx.report.impl`

Genau fünf Kanten, alle lokalisierbar:

| von | nach | Stelle |
|---|---|---|
| `tangles.TopTanglesModule` | `wfx.ArchitectureWfxView` | Fokus-Tracking |
| `whatif.WhatIfDependenciesModule` | `wfx.ArchitectureWfxView` | Fokus-Tracking |
| `wfx.S202Module` | `tangles.TangleFilter` | `openTangleView` (Z. 2037) |
| `wfx.S202Module` | `whatif.WhatIfDependenciesModule` | `registerArchitectureView` (Z. 523) |
| `wfx.S202Module` | `report.impl.JavaFxQualityReportImageRenderer` | `exportQualityReport` (Z. 1691) |

### 1.2 Ein Klassenzyklus mit 8 Mitgliedern

`ArchitectureView ↔ {LevelClassBox, LevelPackageBox} ← {DependencyRenderer,
WhatIfUpwardEdgeRenderer, ArchitectureTreeBuilder, ComponentArchitectureTreeBuilder,
HexagonalArchitectureTreeBuilder}`

**Die einzigen Rückkanten in den Zyklus sind:**
`LevelClassBox → ArchitectureView` und `LevelPackageBox → ArchitectureView` —
und beide bestehen ausschließlich aus dem Zugriff auf die **statischen
Properties** `ArchitectureView.showIconsProperty()` /
`showArchitectureLevelProperty()` (LevelClassBox Z. 82–94, LevelPackageBox
Z. 116–361). Alles andere im Zyklus sind Abwärtskanten. Ein einziger Move
bricht den kompletten 8er-Zyklus.

Klassen-Back-Edges gesamt: **10** — alle innerhalb dieser Strukturen; mit den
Zyklen verschwinden sie.

### 1.3 Klassen > 500 LoC (18 Stück)

| LoC | Klasse | | LoC | Klasse |
|---|---|---|---|---|
| 2360 | `ui.wfx.S202Module` | | 708 | `ui.rendering.DependencyRenderer` |
| 2343 | `ui.ArchitectureView` | | 707 | `reader.impl.java.InputAnalyzer` |
| 1350 | `ui.rendering.TangleEdgeRenderer` | | 668 | `ui.wfx.view3d.SceneBuilder3D` |
| 995 | `report.quality.impl.QualityReportModelBuilder` | | 626 | `ui.wfx.view3d.ArchitectureView3D` |
| 896 | `reader.impl.c.CSourceAnalyzer` | | 617 | `ui.wfx.tangles.TopTanglesModule` |
| 880 | `ui.tree.HexagonalArchitectureTreeBuilder` | | 589 | `ui.tree.ArchitectureTreeBuilder` |
| 811 | `ui.tree.ComponentArchitectureTreeBuilder` | | 528 | `analysis.invariants.LayoutInvariantChecker` |
| 799 | `domain.impl.HexagonalArchitectureBuilder` | | 505 | `domain.impl.LevelCalculator` |
| 783 | `report.quality.impl.QualityReportHtmlWriter` | | 777 | `reader.impl.python.PythonSourceAnalyzer` |

---

## 2. Ziele und Leitplanken

1. **0 Paket-Tangles, 0 Klassen-SCCs > 1, 0 Klassen-Back-Edges** — gemessen
   mit der eigenen Pipeline (siehe §7 Selbsttest).
2. **Keine Klasse > 500 LoC**, jede mit einem Satz benennbarer Zuständigkeit.
3. **Rein verhaltenserhaltend.** Kein Feature-Umbau, keine API-Semantik-Änderung.
4. Externe Verträge bleiben stabil:
   - ServiceLoader-Entrypoint `de.weigend.s202.ui.wfx.S202Module`
     (`META-INF/services/…platform.api.Module`) — Klassenname und
     `preload/start/stop` bleiben.
   - Extern gebundene JavaFX-Properties behalten ihre **Objektidentität**
     (Bindings der Toolbar/Panels dürfen nicht abreißen).
   - Von Tests genutzte Logik (`S202ModuleTest` →
     `firstTargetMethodCalledByDependency`) bleibt erreichbar.
5. Fehlt eine wfx-Plattform-Fähigkeit, wird sie **in wfx** ergänzt, nicht in
   S202 umgangen.

---

## 3. Teil A — Zyklen brechen (kleine Moves, große Wirkung)

### A1: `ArchitectureViewSettings` extrahieren *(bricht den 8er-Klassenzyklus)*

Die zwei statischen Properties `SHOW_ICONS` / `SHOW_ARCHITECTURE_LEVEL`
(ArchitectureView Z. 185–189, Accessors Z. 2116–2141) wandern in eine neue
Klasse **`ui.graph.ArchitectureViewSettings`** (Name s. A2).

- Anzupassen: `LevelClassBox`, `LevelPackageBox` (Bindings),
  `S202Module`-Toolbar-Checkboxen, `Architecture3DModule`.
- `ArchitectureView` behält keine Delegate — die vier Aufrufstellen werden
  direkt umgestellt (überschaubar, ein PR).
- Ergebnis: Boxen kennen `ArchitectureView` nicht mehr → der 8er-SCC zerfällt
  vollständig, 7 der 10 Back-Edges verschwinden.

### A2: Neues Basis-Paket `ui.graph` *(löst Tangle 1)*

Die geteilten visuellen Grundbausteine liegen heute im Elternpaket `ui`,
werden aber von allen Unterpaketen benutzt — deshalb zeigen `rendering`,
`tree` und `component` „aufwärts“. Fix: die Bausteine in ein **eigenes
Unterpaket** verschieben, auf das alle zeigen dürfen:

```
ui.graph  (neu — geteilte View-Primitive, keine Abhängigkeit nach oben)
  LevelClassBox, LevelPackageBox, GraphSelection,
  PulseCoalescer, ArchitectureDragController, ArchitectureViewSettings
```

Ziel-Schichtung danach (nur Abwärtskanten):

```
ui (ArchitectureView + Controller)          ← Orchestrierung
   → ui.component / ui.rendering / ui.tree  ← Feature-Schichten
        → ui.graph                          ← Primitive
             → ui.model                     ← Datenmodell
```

Hinweis: `LevelPackageBox.setOnExpandChangeCallback(...)` (statischer
globaler Callback, Z. 79–81) bleibt funktional erhalten, sollte aber bei
dieser Gelegenheit zum Instanz-Callback werden — statischer globaler Zustand
ist bei mehreren offenen Views ein latenter Bug.

### A3: `ArchitectureWfxView` absenken *(löst Tangle 2)*

`ArchitectureWfxView` (95 LoC, Wrapper) liegt in `ui.wfx`, wird aber von fünf
Untermodulen (view3d, outline, whatif, quality, tangles) referenziert. Move
nach **`ui.wfx.view`** (neues Paket, analog zum bestehenden `ui.wfx.events`).

- Damit verschwinden die beiden Aufwärtskanten
  `TopTanglesModule → wfx` und `WhatIfDependenciesModule → wfx`.
- Die drei Abwärtskanten aus `S202Module` (TangleFilter,
  WhatIfDependenciesModule, JavaFxQualityReportImageRenderer) sind dann
  zyklenfrei — eine Composition-Root darf nach unten zeigen. Sie wandern in
  Teil B ohnehin in die jeweiligen Feature-Controller.

**Abnahme Teil A:** Selbstanalyse zeigt 0 Tangles, 0 SCCs > 1, 0 Back-Edges.
Teil A ist unabhängig von B–E und sollte **zuerst** landen (3 kleine PRs).

---

## 4. Teil B — `S202Module` zerlegen (2360 → Fassade + 7 Controller)

`S202Module` hat **keine eingehenden Compile-Kanten** (Verdrahtung rein über
ServiceLoader) — es lässt sich frei zerlegen. Muster: schlanke
Modul-Fassade + Feature-Controller, verdrahtet in `start()`; Feature-Logik
zieht in ihr Feature-Paket.

| Neue Einheit | Inhalt (heutige Zeilen) | ~LoC | Paket |
|---|---|---|---|
| `S202Module` (Fassade) | `preload/start/stop`, EventBus-Subscriptions, Wiring | ~200 | `ui.wfx` |
| `ArchitectureViewManager` | View-Factory/-Registry, open/close/forget, Fokus, Annotations-Propagation (227–290, 429–679) | ~300 | `ui.wfx` |
| `ToolbarController` | Toolbar-Aufbau + Fokus-Bindung (681–887) | ~210 | `ui.wfx` |
| `SourceOpenController` | FileChooser/FileLoader-Adapter (889–1161) | ~280 | `ui.wfx` |
| `AnalysisPipeline` | Scan + Analyse + Apply (1133–1472) inkl. Records | ~330 | `ui.wfx` |
| `ProjectPersistenceController` | save/load/close Projekt (1474–1509, 1876–2000) | ~160 | `ui.wfx` |
| `QualityReportController` | Report-Export (1636–1874) — trägt die `report.impl`-Kante | ~240 | `ui.wfx.report` |
| `City3DController` | City3D-Export/Server/Sync (1511–1634) | ~130 | `ui.wfx` |
| `TangleTabController` | Tangle-Tabs öffnen/reuse/Preview-Cuts (2015–2334) — trägt die `tangles`-Kante | ~220 | `ui.wfx.tangles` |
| `TangleEdgeMethodResolver` | zustandslose Methoden-Auflösung (2172–2273) + `TargetMethod` — direkt getestet | ~100 | `ui.wfx.tangles` |

Geteilter Zustand wird explizit statt implizit:

- **`ViewRegistry`** — `viewSources`, `viewInvariantReports`, `tangleViews`
  (heute von 5 Blöcken beschrieben) gehören in den `ArchitectureViewManager`;
  andere Controller referenzieren ihn.
- **`RefactoringPreviewState`** — `refactoringPreviewCuts` wird von Tangle-,
  View- und Report-Code gelesen → kleiner eigener Halter.
- **`ProgressPublisher`** (publishStatus/Progress), **`RecentDirectories`**
  (die vier `last*Directory`), **`BackgroundTasks`** (das fünffach kopierte
  `Task`+`Thread`-Muster) — drei kleine Helfer, die jeden Controller spürbar
  verschlanken.

---

## 5. Teil C — `ArchitectureView` zerlegen (2343 → Fassade + 8 Einheiten)

`ArchitectureView` bleibt als `BorderPane`-Fassade mit den extern gebundenen
Properties (Settings, Domain-Handles, Sinks) und delegiert an Controller.
Geteilter Kern (`elementRegistry`, `arrowsCoalescer`, `zoomController`,
Panes) wird den Controllern per Konstruktor gereicht — Property-Instanzen
bleiben in der Fassade (Identität!).

| Neue Einheit | Zuständigkeit (heutige Blöcke/Zeilen) | ~LoC |
|---|---|---|
| `ArchitectureView` (Fassade) | Properties, Sinks, Delegation | ~300 |
| `RootLifecycleController` | setupUI, buildTree(Async), Renderer-Verdrahtung, Builder-Wahl nach ViewStyle (222–291, 366–496, 679–801) | ~400 |
| `OverlayRenderCoordinator` | applyShow*, redrawVisibleArrows, Snapshot, Zoom-Passthrough (293–364, 1603–1714, 1827–1864) | ~300 |
| `ArchitectureProjectionModel` | domainModel/architecture/whatIf/raw + rebuildArchitectureProjection — **UI-frei, gut testbar** (1119–1299) | ~200 |
| `StyleProjectionStateKeeper` | Expansion-/Viewport-Erhalt bei Stilwechsel (498–677) | ~200 |
| `WhatIfEditController` | DnD-Drop, Undo/Redo, applyMoveToScene (1301–1417, 1716–1825) | ~300 |
| `TangleOverlayController` | komplette Tangle-API + State + Sinks (1940–2101) | ~250 |
| `ScopeAndReportController` | Scope-Dialoge, Planned Packages, Report-JSON (803–1117) | ~320 |
| `SelectionController` | selectByFullName, scrollToNode, Selektions-Property (643–660, 1473–1601) | ~150 |
| `ElementBoundsExporter` | Bounds/Footprint-Export für 3D & Snapshot (2220–2342) | ~130 |

Empfohlene Extraktionsreihenfolge (Kopplung aufsteigend):
`ArchitectureProjectionModel` → `ElementBoundsExporter` →
`TangleOverlayController` → `SelectionController` → `WhatIfEditController` →
`ScopeAndReportController` → `StyleProjectionStateKeeper` →
`OverlayRenderCoordinator` → `RootLifecycleController`.

Alle Einheiten bleiben im Paket `ui` — sie bilden gemeinsam die View; neue
Pakete braucht nur Teil A.

---

## 6. Teil D & E — Renderer und Tree-Builder

### D: `TangleEdgeRenderer` (1350 → 6 Einheiten)

Die Klasse ist entlang der Pipeline **Lanes → Routing → Painting** bereits
geschichtet; ein Großteil ist `static` und risikofrei extrahierbar:

| Einheit | Inhalt (Zeilen) | ~LoC |
|---|---|---|
| `TangleGeometry` | nested Wertetypen + statische Geometrie (834–1037 u. a.) | ~250 |
| `TangleLaneLayout` | Lane-Grid-Aufbau (237–422) | ~185 |
| `TangleRouter` | Routing + Fallback-Pfade (424–554, 694–770) | ~300 |
| `TangleEdgePainter` | Painting, Interaktion, Styling (556–692, 1039–1162) | ~350 |
| `TangleCycleModel` | Tarjan + Edge-Prädikate (1164–1252) | ~90 |
| `TangleEdgeRenderer` (Kern) | API, redraw(), Retry, Bounds | ~300 |

Achtung: `routeEdges` ruft heute bei Fehlschlag direkt ins Painting
(`renderFallbackEdge`) — beim Schnitt über einen Ergebnistyp
(vorhandenes `RoutingResult`-Muster) entkoppeln.

### D2: `DependencyRenderer` (708)

Kohäsiv; kein Zerlegungszwang. Aber `createDependencyLine` und
`createCurvedDependencyLine` (416–580) sind zu ~75 % identisch (~60 LoC) —
**zu einer Methode mit optionalem Badge vereinigen**. Reicht das nicht unter
500, ist die saubere Naht: Traversierung/Aggregation (195–382) ↔
Zeichnen/Auswahl (416–670).

### E: Tree-Builder-Trio (589 + 811 + 880)

Keine gemeinsame Vererbung erzwingen (HATB ist strukturell radial, die
anderen verschachtelt) — stattdessen Komposition:

1. **`TreeBuilderSupport`** (statisch): `effectiveRoot`,
   `shouldChildrenBeTransparent`, `createTopLevelContainer/Row`
   (parametrisiert), `parentOf`, `simpleName` — beseitigt die
   ATB↔CATB-Duplikation (~45 LoC) **und** die Selbst-Duplikation in ATB
   (sync-Pfad hat die Logik inline, async-Pfad als Methoden).
2. **`ProgressSink` hochziehen** (eigene Datei statt nested in ATB) +
   gemeinsames `runOnFxThread`-Muster — beseitigt die ~90 % identischen
   `buildTreeAsync`-Wrapper in CATB/HATB.
3. **`LeafBoxFactory`** — Box-Erzeugung inkl. `setSelectionChangeSink`
   (heute dreifach).
4. **CATB**: Projektionslogik (Z. 380–798: cloneApi/cloneImplementation,
   API-Erkennung) in eigene Klasse `ComponentProjection` (~420 LoC) →
   Builder selbst ~390.
5. **HATB**: innere Widgets `HexGroupBox` + `HexagonalSegmentHeader`
   (~185 LoC) als eigene Top-Level-Klassen → HATB ~695, danach
   Ring-/Sektor-Geometrie (`bandRadius`, `angleFor*`, `placeNode`) als
   `HexLayoutGeometry` (~150) → unter 500.

---

## 7. Teil F — übrige Klassen > 500 LoC

| Klasse | LoC | Empfehlung | Priorität |
|---|---|---|---|
| `QualityReportModelBuilder` | 995 | je Report-Sektion ein Sektions-Builder (Metrics/Tangles/Violations/…), Snapshot-Assembler bleibt | mittel |
| `CSourceAnalyzer` | 896 | Scanner/Präprozessor ↔ Einheiten-Mapping ↔ Call-Kanten trennen (52 Methoden!) | mittel |
| `HexagonalArchitectureBuilder` | 799 | Ring-Zuordnung (Annotation/Level-Regeln) von Modell-Aufbau trennen | mittel |
| `QualityReportHtmlWriter` | 783 | HTML-Sektionen ↔ SVG/CSS-Assets trennen | mittel |
| `PythonSourceAnalyzer` | 777 | wie C: Parsing ↔ Modell-Mapping | mittel |
| `InputAnalyzer` | 707 | ASM-Visitor-Teil ↔ Modell-Aufbau/Exclude-Logik | mittel |
| `SceneBuilder3D` | 668 | Geometrie-Fabriken (Boxen/Plattformen/Kanten) herauslösen | niedrig |
| `ArchitectureView3D` | 626 | Kamera/Input ↔ Szenen-Sync trennen | niedrig |
| `TopTanglesModule` | 617 | Modul-Wiring ↔ Tabellen-View-Logik | niedrig |
| `LayoutInvariantChecker` | 528 | eine Check-Klasse pro Invariantengruppe; 1:1-Port-Charakter dokumentieren | niedrig |
| `LevelCalculator` | 505 | Kern-Algorithmus, hohes Risiko: nur den Paket-Level-Schritt extrahieren, sonst nicht anfassen | niedrig |

Diese Klassen sind **kohäsiver** als die Top 3 — hier gilt: beim nächsten
Feature-Touch mitschneiden (Boy-Scout), kein eigener Big-Bang.

---

## 8. Selbsttest: Zyklenfreiheit dauerhaft absichern

Der Befund aus §1 wurde mit einem Wegwerf-Audit erhoben. Daraus wird ein
**dauerhafter Selbsttest** (`analyzer/src/test`, Integrationstest):

```
SelfArchitectureTest
  1. InputAnalyzer auf das eigene gebaute Jar (bzw. target/classes)
  2. LevelCalculator → DomainModel
  3. assert: getPackageTangles().isEmpty()
  4. assert: TarjanSCCFinder auf Klassengraph → keine SCC > 1
  5. assert: getClassBackEdgeCount() <= <Budget, startet bei 10, sinkt auf 0>
```

Das Budget-Muster erlaubt, den Test **sofort** zu aktivieren (schützt vor
Verschlechterung) und mit jedem Teil-A-PR abzusenken. Zusätzlich als
Abnahmekriterium pro PR: `mvn test` grün + UI-Smoke (App startet,
test-hexagon laden, Ansicht wechseln).

---

## 9. Etappenplan

| Phase | Inhalt | PRs | Abnahme |
|---|---|---|---|
| 0 | `SelfArchitectureTest` mit Ist-Budgets (2 Tangles, 1 SCC, 10 Back-Edges) | 1 | Test grün, schlägt bei neuen Zyklen an |
| 1 | **A1–A3**: Settings-Extraktion, `ui.graph`, `ArchitectureWfxView`-Move | 3 | Budgets auf 0/0/0 |
| 2 | **B**: S202Module → Fassade + Controller (erst Helfer, dann Controller einzeln) | 4–6 | S202Module ≤ 500; ServiceLoader-Smoke-Test grün |
| 3 | **C**: ArchitectureView → Fassade + Controller (Reihenfolge s. §5) | 5–8 | ArchitectureView ≤ 500; UI-Smoke pro PR |
| 4 | **D+E**: TangleEdgeRenderer-Split, DependencyRenderer-Dedup, TreeBuilderSupport | 3–4 | alle ui.rendering/ui.tree-Klassen ≤ 500 |
| 5 | **F**: übrige Klassen opportunistisch | laufend | keine Klasse > 500 (CI-Check denkbar) |

Grundregeln für jeden Schritt:

- **Ein Move oder eine Extraktion pro PR**, rein mechanisch, keine
  Verhaltensänderung im selben PR.
- Extraktion = Konstruktor-Injektion des geteilten Kerns; keine neuen
  statischen Singletons (die zwei bestehenden statischen Mechanismen —
  Settings-Properties und Expand-Callback — werden dabei bewusst behandelt,
  s. A1/A2).
- Nach jedem PR: `mvn test`, Selbsttest-Budgets, UI-Smoke.
- Alles, was wie eine fehlende wfx-Fähigkeit aussieht, wird als
  wfx-Feature-Request gelöst, nicht lokal umgangen.

## 10. Erwartetes Endbild

- `ui` ist eine echte Schichtung: Orchestrierung → Features → Primitive →
  Modell; `ui.wfx` ist Composition-Root über seinen Feature-Paketen.
- Die drei größten Klassen sind Fassaden ≤ 500 LoC mit je einem Satz
  Zuständigkeit; die Logik liegt in benannten, einzeln testbaren Einheiten.
- Der Selbsttest hält die Codebase dauerhaft zyklenfrei — das eigene Tool
  prüft ab dann jede Änderung an sich selbst.

---

## 11. Umsetzungsstand (Juli 2026, Branch `refactor/cycles-and-god-classes`)

| Phase | Status | Ergebnis |
|---|---|---|
| 0 | ✅ | `SelfArchitectureTest` aktiv, Budgets auf **0 / 0 / 0** |
| 1 (A1–A3) | ✅ | 0 Paket-Tangles, 0 Klassen-Zyklen, 0 Back-Edges; neue Basis-Pakete `ui.graph` und `ui.wfx.view` |
| 2 | ✅ | S202Module **2361 → 374** Zeilen; 8 Controller + Basis-Paket `ui.wfx.shell` (der Selbsttest fing dabei einen neuen Tangle im ersten Wurf ab — Infrastruktur musste unter die Feature-Pakete) |
| 3 | ✅* | ArchitectureView **2343 → 868** Zeilen; 12 Einheiten (ProjectionModel, BoundsExporter, TangleOverlay-, WhatIfEdit-, ScopeAndReport-, Selection-, StateKeeper-, OverlayRenderCoordinator, TreeBuilderFactory, AbstractArchitectureView) |
| 4 | ✅ | TangleEdgeRenderer **1350 → 224** (+5 Pipeline-Einheiten ≤ 386); Tree-Builder-Trio **589/811/880 → 496/392/483** (+ TreeBuilderSupport, ComponentProjection, 5 Hex-Einheiten); DependencyRenderer **708 → 380** (+ DependencyArrowPainter 313, Zeichen-Duplikat vereinigt) |
| 5 | ⏳ | 11 Mittelklasse-Kandidaten aus Teil F (Reader/Report/3D/Domain) — wie geplant opportunistisch beim nächsten Feature-Touch |

**\*Dokumentierte Abweichung (Phase 3):** ArchitectureView liegt bei 868
statt ≤500 Zeilen. Der Rest besteht aus (a) dem Szenen-Aufbau
(`finishArchitectureRootBuild` — der Kern einer JavaFX-View), (b) der
Controller-Verdrahtung im Konstruktor und (c) ~400 Zeilen
Einzeiler-Delegation der breiten öffentlichen API (~70 Methoden, von allen
wfx-Modulen konsumiert). Alle *Logik*-Einheiten liegen unter 500; weitere
Zerlegung würde nur API-Fläche zwischen Dateien umschichten. Falls die
500er-Grenze auch für die Fassade gelten soll, ist der designierte nächste
Schritt ein `RootBuildCoordinator` (Szenen-Aufbau, ~230 Zeilen) — bewusst
zurückgestellt, weil er die riskanteste Umverdrahtung bei geringstem
Klarheitsgewinn ist.
