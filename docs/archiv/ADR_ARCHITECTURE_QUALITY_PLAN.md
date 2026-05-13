# ADR: Maßnahmenkatalog Architekturqualität

Status: **Entwurf** — auf Branch `feature/architecture-quality`
Datum: 2026-05-13
Betrifft: gesamtes `analyzer`-Modul

---

## 1 — Bestandsaufnahme

Quelle: statische Auswertung der `import`-Anweisungen unter
`analyzer/src/main/java/de/weigend/s202`, plus die UI-Live-Anzeige des
S202-Analyzers, der auf sich selbst angewandt wurde (Nutzerbeobachtung:
ca. 30 falsche Dependencies, mehrere Paketzyklen, einige SCC-Cluster).

**Soll-Layering:** `ui → project → analysis → domain → reader` (Pfeil =
„depends on"). Eine höhere Schicht darf nur tiefere Schichten kennen,
und idealerweise nur die unmittelbar nächst-tiefere.

**Top-Level-Importmatrix** (Anzahl Imports von Spalte aus Zeile):

| Quelle ↓ | analysis | domain | reader | project | ui |
|---|---|---|---|---|---|
| ui | 16 | 9 | 15 | 3 | (intern) |
| project | 2 | 1 | 3 | — | **1** |
| analysis | (intern) | 4 | 1 | — | — |
| domain | **13** | (intern) | 7 | — | — |
| reader | — | — | (intern) | — | — |

Hervorgehobene Zellen sind Verletzungen.

**Kerndefekte:**

| # | Defekt | Beleg | Wirkung |
|---|---|---|---|
| D1 | **domain → analysis (13)** — Inversion | `LevelCalculator`, `LevelCalculationStrategyFactory`, `DebugPackageLevels` ziehen `TarjanSCCFinder`, `SCCBreaker`, `StronglyConnectedComponent`, `HeuristicSCCBreakingStrategy`, `LevelCalculationStrategyContext` aus `analysis` | Bildet zusammen mit `analysis → domain` (4) eine 2er-SCC; verhindert sauberen Layer-Schnitt. |
| D2 | **project → ui (1)** | `S202ProjectMapper` importiert `TangleEdgeRenderer.Edge` als Serialisierungstyp | UI-Renderer wird Persistenz-Vokabular; Mapper kann nicht ohne UI getestet werden. |
| D3 | **project → analysis (2)** | `S202ProjectMapper` serialisiert `InvariantFinding`/`LayoutInvariantReport` direkt | Persistenzlayer hängt an internen Analyse-Datenstrukturen statt an einem DTO. |
| D4 | **ui skip-level: 16+9+15 = 40 Direktzugriffe** auf `analysis`/`domain`/`reader` | Vielzahl von Stellen in `ui.wfx.*` und `ui.rendering.*` | UI baut Logik gegen Analyse-Interna; jede Modelländerung trifft die UI direkt. |
| D5 | God-Klassen: `S202Module` 1535 Z., `ArchitectureView` 1133 Z., `TopTanglesModule` 594 Z., `LayoutInvariantChecker` 459 Z. | siehe Audit | Hohe Änderungs-Hot-Spots; verteilen die Verletzungen auf wenige zentrale Stellen. |
| D6 | Feingranulare Verletzungen, die die Live-Anzeige zeigt, aber im Top-Level-Audit verschwinden | innerhalb `ui.*`, `analysis.*` | Konkrete Klassen-zu-Klassen-Schiefen, die wir mit dem Tool selbst aufspüren. |

---

## 2 — Maßnahmen

Sortiert nach Hebel × Risiko. Jede Maßnahme nennt Diagnose, Eingriff,
betroffene Dateien, das verwendete Entkopplungs-Werkzeug
(Event / Interface / Paketstruktur) und den Funktionalitätsrisiko-Faktor.

### M1 — Graph-Primitive in `de.weigend.s202.graph` extrahieren

**Diagnose (löst D1, Hauptanteil):** `TarjanSCCFinder`,
`StronglyConnectedComponent`, `SCCBreaker`, `EdgeClassification`,
`SCCDAGBuilder` sind generische Graph-Algorithmen ohne fachlichen
Bezug. Aktuell liegen sie unter `analysis.scc`, werden aber genauso
von `domain.LevelCalculator` benutzt.

**Eingriff:**
1. Neues Paket `de.weigend.s202.graph` anlegen.
2. Klassen aus `analysis.scc.*` dort hinein verschieben — die liegen
   fachlich unter beiden Schichten.
3. `analysis` und `domain` referenzieren `graph.*` (beide nach unten).
4. `analysis.scc`-Paket bleibt für `SCCBreaker.findBackEdges()`-Heuristiken
   (analyse-spezifisch) bestehen, falls anwendbar.

**Werkzeug:** Paketstruktur.

**Betrifft:** `analysis/scc/TarjanSCCFinder.java`,
`analysis/scc/StronglyConnectedComponent.java`,
`analysis/scc/EdgeClassification.java`,
`analysis/scc/SCCDAGBuilder.java`,
`domain/LevelCalculator.java` (Import-Update),
`domain/LevelCalculationStrategyFactory.java` (Import-Update),
`ui/whatif/VirtualPackageGraph.java` (Import-Update).

**Funktionalitätsrisiko:** sehr gering — reines `package`-Refactor.

**Folge:** ca. 10 von 13 `domain → analysis`-Imports verschwinden, ohne
dass eine Zeile Logik geändert wird.

---

### M2 — Strategie-Interfaces in `domain`, Implementierungen in `analysis.strategy`

**Diagnose (löst D1-Rest):** Nach M1 bleiben drei Imports übrig:
`HeuristicSCCBreakingStrategy`, `LevelCalculationStrategyContext`,
`LevelCalculationStrategyFactory` referenziert konkrete Strategien
aus `analysis.strategy.impl.*`. Klassische Inversion.

**Eingriff:**
1. Interface `LevelCalculationStrategy` in `domain` (neu oder existent
   prüfen).
2. Strategien in `analysis.strategy.impl.*` implementieren das Interface.
3. Auswahl der Strategie verschiebt sich vom hartkodierten Factory zur
   DI-Konfiguration in Avaje (`@Singleton` + `@Named` oder
   `@Primary`/`@Secondary`).
4. `LevelCalculator` bekommt eine `LevelCalculationStrategy` per
   Konstruktor injiziert.

**Werkzeug:** Interface + DI (Avaje).

**Betrifft:** `domain/LevelCalculator.java`,
`domain/LevelCalculationStrategyFactory.java` (entfällt),
`analysis/strategy/**/*.java` (annotieren).

**Funktionalitätsrisiko:** mittel — Strategiewahl wandert vom Code in
die DI-Konfig. Tests müssen ggf. eine Strategie binden.

**Folge:** `domain → analysis` auf 0 (DebugPackageLevels nach
`analysis.debug` verschieben, siehe M5).

---

### M3 — Persistenz-DTOs vom Analyse- und UI-Modell trennen

**Diagnose (löst D2 + D3):** `S202ProjectMapper` serialisiert
`InvariantFinding`, `LayoutInvariantReport` und
`TangleEdgeRenderer.Edge` direkt. Persistenzformat ist damit an
Implementierungstypen aus `analysis` und `ui` gekoppelt.

**Eingriff:**
1. `project.dto.*` für jede serialisierbare Form (z.B. `InvariantFindingDto`,
   `TangleEdgeDto`).
2. Mapping zwischen Domain- und DTO-Typen rein in `project` (Mapper-Klassen).
3. `TangleEdgeRenderer.Edge` als reines Datum (record) bleibt in
   `ui.rendering`, wird beim Persistieren auf `TangleEdgeDto` gemapped.
4. Alternativ (sauberer): `TangleEdge` als Domain-Record nach
   `domain/refactoring/` ziehen — Render-Klasse importiert dann von
   `domain`, nicht umgekehrt.

**Werkzeug:** Interface (DTOs) + Paketstruktur.

**Betrifft:** `project/S202ProjectMapper.java`,
`project/dto/*.java` (neu),
`ui/rendering/TangleEdgeRenderer.java` (Edge-Record evtl. nach
`domain/refactoring/TangleEdge.java`).

**Funktionalitätsrisiko:** mittel — Serialisierungsformat darf nicht
brechen. Beim DTO-Move alte Class-Names per `@JsonAlias` o.ä. tolerieren.

**Folge:** `project → ui` = 0; `project → analysis` reduziert auf den
Pfad „lese Analyseergebnis → mappe DTO → schreibe Datei", was bewusst
und dokumentiert ist.

---

### M4 — UI-Skip-Level entkoppeln über Event-Bus und Analyse-Fassaden

**Diagnose (löst D4):** UI hat 40 direkte Importpfade nach
`analysis`/`domain`/`reader`. Manche sind legitim (z.B.
`DomainModel` für die Anzeige), aber viele führen zu Boilerplate-Glue
in den Modulen, der eigentlich Application-Service-Code wäre.

**Eingriff:**
1. **Analyse-Fassade in `project`:** ein dünner Service
   `AnalysisFacade` mit Methoden wie `analyzeJars(List<Path>) →
   AnalysisResult`, `loadProject(Path)`, `runInvariants(DomainModel,
   DependencyModel) → LayoutInvariantReport`. Die Fassade bündelt die
   Aufrufe, die heute in `S202Module` über mehrere Pakete verteilt sind.
2. **Read-Only-Datenzugriff in UI nur über das Fassaden-Result**,
   nicht über `analysis.*` direkt.
3. **Reaktiver Zustand über Event-Bus**: Statt das Modul die Modelle
   pro Architekturfenster manuell durchzuschieben (siehe
   `loadJarFiles` mit `view.setDomainModel(...)` &
   `view.setRawDependencyModel(...)`), Events veröffentlichen:
   `AnalysisCompletedEvent`, `ProjectLoadedEvent`,
   `InvariantReportReadyEvent`. UI-Views subscriben und aktualisieren
   sich selbst.

**Werkzeug:** Event + Interface (Fassade) + Paketstruktur.

**Betrifft:** Neue Klassen `project/AnalysisFacade.java`,
`ui/wfx/events/AnalysisCompletedEvent.java`,
`ui/wfx/events/InvariantReportReadyEvent.java`;
`S202Module.java` (entfernt Direktaufrufe nach `analysis.*`),
`ui/ArchitectureView.java` (subscribed Events statt setX-Methoden).

**Funktionalitätsrisiko:** hoch — viele Aufrufstellen. **Iterativ
durchführen**, eine Datenklasse nach der anderen (`DomainModel` zuerst,
`DependencyModel` als Nächstes, `LayoutInvariantReport` zuletzt).

**Folge:** UI-Imports auf `analysis`/`domain`/`reader` reduzieren sich
deutlich; Architekturfenster werden austauschbar weil sie nur Events
konsumieren.

---

### M5 — God-Klassen zerlegen

**Diagnose (löst D5):**
- `S202Module.java` (1535 Z.) bündelt Menü, Toolbar, JAR-Loading,
  Projekt-IO, Tangle-Spawning, Stylesheet-Setup, Architecture-View-
  Lifecycle in einer Klasse.
- `ArchitectureView.java` (1133 Z.) trägt Layout, Renderer-Wiring,
  Zoom-State, Tangle-Edges, What-If-State, alle Properties.
- `TopTanglesModule.java` (594 Z.) und `LayoutInvariantChecker.java`
  (459 Z.) sind Sammelplätze.

**Eingriff (S202Module als Beispiel):**

| Sub-Komponente | Verantwortung | neue Datei |
|---|---|---|
| Toolbar-Wiring | Checkboxes, Buttons, Zoom-Label | `ui/wfx/toolbar/ArchitectureToolbar.java` |
| JAR-Loading | `loadJarFiles`, `analyzeMultiple`, Progress | `project/JarLoader.java` (verwendet `AnalysisFacade` aus M4) |
| Project-IO | `applyLoadedProject`, `saveProject`, `closeProject` | `project/ProjectIO.java` |
| Tangle-View-Spawning | Tangle-Satellite-Views erzeugen | `ui/wfx/tangles/TangleViewSpawner.java` |
| Architecture-Window-Lifecycle | `newArchitectureWindow`, `registerArchitectureView` | bleibt in `S202Module` als „Application Controller" |

Jede neue Klasse wird Avaje-Bean (`@Singleton`), `S202Module` ist nur
noch Composition Root.

**Werkzeug:** Paketstruktur + DI.

**Funktionalitätsrisiko:** mittel-hoch — viele kleine Move-Refactorings,
jeder Test sollte grün bleiben.

**Folge:** Sichtbare Schichten innerhalb des UI-Pakets, Hot-Spot
verschwindet, jede Komponente einzeln testbar.

---

### M6 — Eigenes Tooling als Build-Gate

**Diagnose (löst D6):** Der Live-Analyzer zeigt Verletzungen, die der
statische Top-Level-Audit nicht sieht. Damit jede künftige Änderung
die Architekturqualität nicht wieder schmälert, bauen wir das Tooling
selbst als Test ein.

**Eingriff:**
1. JUnit-Test `ArchitectureSelfCheckTest` im `analyzer`-Modul.
2. Test ruft `InputAnalyzer` mit dem eigenen `target/classes` auf,
   baut `DomainModel`, lässt `LayoutInvariantChecker` laufen.
3. Test schlägt fehl, wenn:
   - Anzahl `InvariantFinding`s ein konfiguriertes Budget überschreitet
     (z.B. max 5 R1-Findings, max 0 R2-Findings),
   - neue Pakete-SCCs entstehen, die nicht in einer Allowlist stehen.
4. Budget startet konservativ am aktuellen Stand, wird mit jedem
   M1–M5-Schritt verringert.

**Werkzeug:** Test als Architektur-Sperrklinke.

**Betrifft:** `analyzer/src/test/java/de/weigend/s202/architecture/
ArchitectureSelfCheckTest.java` (neu),
`analyzer/src/test/resources/architecture-budget.json` (neu).

**Funktionalitätsrisiko:** sehr gering — additiver Test.

**Folge:** Regressionsschutz nach jedem Schritt; Dogfood-Verifikation.

---

## 3 — Reihenfolge

Eine Iteration nach der anderen, jede committable und revertierbar.

| Schritt | Maßnahme | Erwartete Wirkung | Vorgehen |
|---|---|---|---|
| 1 | **M1** Graph-Paket | 10 Imports weg, SCC analysis↔domain bricht | reines Move-Refactor; Tests danach grün |
| 2 | **M6** Self-Check-Test | Baseline-Budget festschreiben | additiv |
| 3 | **M2** Strategie-Interfaces | Rest von `domain → analysis` auf 0 | Avaje-Bindings prüfen |
| 4 | **M3** Persistenz-DTOs | `project → ui` = 0, `project → analysis` über Mapper | JSON-Format-Kompatibilität |
| 5 | **M4** UI-Fassade + Events (iterativ) | UI skip-level halbieren | je Datenklasse einzeln |
| 6 | **M5** God-Klassen splitten (zuerst `S202Module`) | Hot-Spots zerlegen | je Sub-Komponente einzeln |

---

## 4 — Offene Fragen

1. **Reader unter Domain?** Aktuell `domain → reader` (7 Imports) für
   `DependencyModel`, `ClassInfo`, etc. Falls `DependencyModel` als
   reines Bytecode-Output verstanden wird, das Domain konsumiert —
   ok. Falls Domain ein eigenes Quell-Modell haben sollte, wäre ein
   `domain.input.*`-Port angebracht.
2. **Wofür wird `analysis → reader` (1 Import) genutzt?** Prüfen und
   ggf. in der Self-Check-Allowlist explizit machen.
3. **Per-Paket-Budget oder globales Findings-Budget** im Self-Check
   Test (M6)? Per-Regel + globale Obergrenze ist robuster, braucht
   aber eine kleine Config-Struktur.

---

## 5 — Nicht-Ziele

- Kein Verhaltenswechsel an Renderern, Layout-Algorithmus,
  Level-Berechnung selbst.
- Keine API-Breaks für Persistenz-Format ohne Migrationspfad.
- Keine UI-Reorganisation ohne sichtbaren Mehrwert (es reicht, die
  Imports zu entkoppeln; das UI-Ergebnis darf identisch bleiben).
