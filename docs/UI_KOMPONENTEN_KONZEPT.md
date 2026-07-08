# UI-Komponenten-Konzept: Vom technischen Monolith zu fachlichen Teilkomponenten

*Stand: Juli 2026 · Folgekonzept zu [REFACTORING_KONZEPT_CODEBASE.md](REFACTORING_KONZEPT_CODEBASE.md)*

## 0. Anlass und Selbstkritik

Das Zyklen-/Größen-Refactoring hat entlang **technischer** Nähte geschnitten
(Builder, Renderer, Controller) und dabei die Metriken saniert — aber den
**fachlichen** Schnitt nicht hergestellt, sondern zementiert. Die UI ist
zyklenfrei geschichtet und trotzdem ein Monolith: Die Pakete beantworten die
Frage „*was für eine Art* Klasse ist das?“ (tree, rendering, shell) statt
„*zu welchem Thema* gehört das?“ (Hexagonal-Ansicht, Tangle-Ansicht, 3D).

Messbar (Verteilung der Fachthemen über UI-Verzeichnisse, per grep/Analyse):

| Fachthema | verteilt über | Beispiele |
|---|---|---|
| Hexagonal-Ansicht | **8 Verzeichnisse** | `ui.tree.Hex*` (6 Dateien), `ui` (Style-Switch), `ui.wfx` (Menü/Öffnen), `domain.impl` |
| Tangle-Ansicht | **10+ Verzeichnisse** | `ui.rendering.Tangle*` (5), `ui.TangleOverlayController`, `ui.wfx.tangles` (5), `ui.wfx.events` (4) |
| Component-Ansicht | **7 Verzeichnisse** | `ui.component.ComponentBox`, `ui.tree.ComponentProjection`, API-Drop in `ui.WhatIfEditController` |
| 3D-Ansicht | 2 | `ui.wfx.view3d` (immerhin schon gebündelt) |

Die Prüffrage eines Architekten — *„Wo ist der Hexagonal-View? Kann ich die
3D-Ansicht rausnehmen?“* — hat heute keine gute Antwort. Der Kern des
Problems ist eine einzige Entwurfsentscheidung:

> **`ArchitectureView` ist mit `ArchitectureViewStyle {LAYERED, COMPONENT,
> HEXAGONAL}` ein Modus-Monolith: eine Klasse, die drei Ansichten *ist*,
> statt drei Ansichten, die einen Kern *nutzen*.** Alle Switches
> (TreeBuilderFactory, ViolationOverlayKinds, Kontextmenüs, API-Drop)
> sind Folgeschäden dieser Entscheidung.

Dabei existiert das richtige Muster schon im eigenen Haus: Im Domain-Layer
ist die Stil-Achse **polymorph** (`domain.architecture.ArchitectureStyle`,
SPI mit `kind()` + `Lookup.findAll`). Nur die UI hat stattdessen Modus-Felder.
Das Konzept hebt dieses Muster eine Ebene hoch.

---

## 1. Zielbild: Fachkomponenten mit gemeinsamem Kern

```
de.weigend.s202.ui
│
├── core/                      GEMEINSAME KONZEPTE — stabil, ansichtsfrei
│   ├── model/                 ArchitectureNode & Co. (heute ui.model, bleibt)
│   ├── graph/                 View-Primitive: Boxen, Selektion, DnD, Coalescer
│   ├── canvas/                Canvas-Mechanik: Scroll/Zoom/Overlay-Stack,
│   │                          Element-Registry, Bounds-Export, Snapshot,
│   │                          Viewport-/Expansion-Erhalt
│   ├── arrows/                generische Kanten-Overlays: Dependency-/SCC-Pfeile
│   └── spi/                   DIE Verträge (s. §2): StyleView, ViewFeature,
│                              ViewServices
│
├── views/                     EINE FACHKOMPONENTE PRO ANSICHT — geschlossen
│   ├── layered/               Schichten-Ansicht (Referenz-StyleView) + Scope
│   ├── component/             Component-Ansicht: ComponentBox, Projektion,
│   │                          API-Drag&Drop, Planned Packages, Refactoring-Report
│   ├── hexagonal/             alle Hex*-Klassen, Rollen-Menüs, Backdrop
│   ├── tangle/                Tangle-Renderer-Pipeline, Overlay-Controller,
│   │                          TangleFilter, MethodResolver, Tangle-Tabs
│   ├── threed/                heute ui.wfx.view3d
│   └── city3d/                Exporter, Loopback-Server, Browser-Sync
│
├── features/                  ANDOCKBARE QUERSCHNITTS-FEATURES (optional einzeln)
│   ├── whatif/                What-If-Editing (DnD, Undo/Redo, moved-Deko)
│   └── ...                    (Kandidaten: Outline, Quality-Panel)
│
└── app/                       SHELL (heute ui.wfx): Composition-Root, Toolbar,
                               Menü, Analyse-Pipeline, Persistenz, Statusbar
```

**Die drei Fragen des Reviews bekommen einfache Antworten:**
*Wo ist der Hexagonal-View?* → `ui.views.hexagonal`, ein Verzeichnis.
*Was ist gemeinsam?* → alles in `core` (und nur das).
*Kann ich 3D rausnehmen?* → `ui.views.threed` löschen, Rest baut (per Test garantiert, §4).

---

## 2. Die Verträge (core/spi) — das Herzstück

### 2.1 `StyleView` — Ansichten als Plugins statt Modi

Ersetzt `ArchitectureViewStyle` + alle Switches. Analog zum Domain-SPI
`ArchitectureStyle`, gleiche Registrierung (ServiceLoader/Lookup):

```java
public interface StyleView {
    ArchitectureKind kind();                       // LAYERED / COMPONENT / HEXAGONAL / …
    String title();                                // "Hexagonal View"
    StyleContent build(ArchitectureNode root, ViewServices services);
    void buildAsync(ArchitectureNode root, ViewServices services,
                    ProgressSink progress, Consumer<StyleContent> done);
    Set<ViolationKind> violationOverlayKinds();    // heute der Switch im Coordinator
    ExpansionMemento captureExpansion(Node content);       // heute StateKeeper-Sonderfälle
    void restoreExpansion(Node content, ExpansionMemento m);
}
```

`ViewServices` ist das Parameterobjekt, das heute als Supplier-Geflecht durch
die Konstruktoren läuft: Element-Registry, Selektions-Sink, Annotations
(lesen/schreiben), Raw-/Domain-Modell, Coalescer, Status. **Eine** Struktur
statt 13 Konstruktor-Parameter.

Der heutige `ArchitectureView` wird zum **`ArchitectureCanvas`** in
`core/canvas`: Er besitzt Scroll/Zoom/Overlays/Registry/Properties und
rendert eine ihm übergebene `StyleView`. Er kennt keine konkrete Ansicht
mehr — kein `viewStyle`-Feld, keine Stil-Imports. Die Layered-Ansicht ist
*eine Implementierung unter dreien*, nicht der eingebaute Normalfall.

### 2.2 `ViewFeature` — Querschnitt als Andockpunkt

What-If-Editing ist heute in den Kern eingebacken, obwohl es (a) nur für
Row-basierte Ansichten gilt und (b) der Component-API-Drop eine
Component-Spezialität ist. Vertrag:

```java
public interface ViewFeature {
    boolean supports(StyleView view);
    void attach(ArchitectureCanvas canvas, ViewServices services);
    void detach();
}
```

`features/whatif` implementiert das; `views/component` steuert seinen
API-Drop-Handler selbst bei (heute: `if (viewStyle == COMPONENT)` im
WhatIfEditController — genau solche Fremd-Kenntnisse verschwinden).

### 2.3 App-Anbindung: ein wfx-Modul pro Komponente

Die ursprüngliche `XYModule`-Idee wird wieder tragend: Jede Fachkomponente
bringt **ihr eigenes wfx-Modul** als Eintrittspunkt mit
(`HexagonalViewModule`, `TangleViewModule`, `ThreeDViewModule`,
`City3DModule`), selbstregistrierend über ServiceLoader — exakt wie
`S202Module` heute. Öffnen einer Ansicht läuft über ein Event
(`OpenStyleViewEvent(kind)`), das die jeweilige Komponente beantwortet.
Damit verliert die Shell ihr Stil-Wissen: `ArchitectureViewManager.
openComponentView/openHexagonalView` entfällt; das Menü publiziert nur.

Menü-/Toolbar-Beiträge pro Modul: falls wfx dafür keinen Erweiterungspunkt
hat, ist das ein **wfx-Feature-Request** (Modul-beigesteuerte Menü-Items),
kein lokaler Umbau — wfx-Regel des Projekts.

---

## 3. Was gehört wohin — die Sortierliste

| heute | Ziel | Begründung |
|---|---|---|
| `ui.tree.Hex*` (6 Klassen) | `views/hexagonal` | reine Hex-Fachlichkeit |
| `ui.rendering.Tangle*` (5), `ui.TangleOverlayController`, `ui.wfx.tangles.*` | `views/tangle` | ein Thema, ein Ort; inkl. Tangle-Events |
| `ui.component.ComponentBox`, `ui.tree.ComponentProjection`, `ui.tree.ComponentArchitectureTreeBuilder`, API-Drop, Planned Packages, `ScopeAndReportController`-Reportteil | `views/component` | Component-Fachlichkeit inkl. ihrer Dialoge |
| `ui.tree.ArchitectureTreeBuilder`, Scope-Erweiterung | `views/layered` | die Referenz-StyleView |
| `ui.wfx.view3d.*` | `views/threed` | schon gebündelt, nur Ort + Modul-Vertrag |
| `ui.city3d.*` + `ui.wfx.City3DController` | `views/city3d` | dito |
| `ui.WhatIfEditController`, `WhatIfUndoManager`, `ui.whatif.ClassEdge` | `features/whatif` | Querschnitt, andockbar |
| `ui.graph.*`, `ui.model.*`, `ui.zoom` | `core/graph`, `core/model`, `core/canvas` | echte Gemeinsamkeiten |
| `ArchitectureView`-Rest (Canvas, Registry, Properties), `OverlayRenderCoordinator` (ohne ViolationKind-Switch), `SelectionController`, `StateKeeper` (ohne ComponentBox-Wissen!), `BoundsExporter`, `TreeBuilderSupport` | `core/canvas` / `core/arrows` | Mechanik, ansichtsfrei |
| `DependencyRenderer`/`ArrowPainter`/`SCCRenderer` | `core/arrows` | von allen Level-Views genutzt |
| `ui.rendering.circuit` (9 Klassen, keine Nutzer) | **löschen** (oder `docs/exploration`-Archiv) | toter Prototyp |
| `ui.wfx.*` Rest | `app` | Shell bleibt Shell |

Lackmustest je Klasse: *„Ändert sich diese Klasse, wenn ich Ansicht X
ändere — und nur dann?“* → Komponente X. *„Ändert sie sich bei jeder neuen
Ansicht?“* → core/spi-Kandidat (und dann so entwerfen, dass sie es
gerade **nicht** mehr tut).

Aufzulösende Kern-Kenntnisse konkreter Ansichten (heute versteckte Kopplung):
`StyleProjectionStateKeeper` kennt `ComponentBox` → Expansion-Memento wird
StyleView-Sache (§2.1). `ScopeAndReportController` mischt Scope (layered)
und Report (component) → trennen. `ProgressPublisher` (app-Schicht!) kennt
`ArchitectureView.BuildProgress` → BuildProgress in core/spi.

---

## 4. Regeln, die den Schnitt halten (durchsetzbar mit dem eigenen Tool)

Der `SelfArchitectureTest` wird um **Komponenten-Regeln** erweitert
(gleiche Pipeline, neue Assertions):

1. `views.X → views.Y` ist **verboten** (für alle X ≠ Y).
2. `views.* → app` ist verboten (Komponenten kennen die Shell nicht;
   Kommunikation via `core/spi` + Events).
3. `core → views.*` und `core → app` sind verboten (der Kern kennt
   niemanden).
4. `features.* → views.*` ist verboten (Features sprechen nur den
   SPI-Vertrag).

Dazu ein **Removability-Test** als Abnahmekriterium pro Komponente:
Verzeichnis `views/X` temporär aus dem Source-Set nehmen → `mvn compile`
des Rests ist grün (im Endausbau erzwingt das ein Maven-Multi-Module-Schnitt
physisch: `s202-ui-core`, `s202-view-hexagonal`, … — Stufe 2, optional,
erst wenn die Paketregeln stabil grün sind).

---

## 5. Migrationspfad (jede Etappe grün, verhaltenserhaltend)

| Etappe | Inhalt | Effekt |
|---|---|---|
| M0 | Zielpakete anlegen; Komponenten-Regeln in SelfArchitectureTest (zunächst mit Ist-Budgets, wie beim Zyklen-Budget bewährt) | Verschlechterungs-Stopp |
| M1 | **Reine Moves** (keine Logik): Hex* → `views/hexagonal`, Tangle-Klassen → `views/tangle`, view3d → `views/threed`, city3d → `views/city3d`, Component-Klassen → `views/component`, circuit löschen | „Wo ist X?“ ist beantwortet; Budgets sinken deutlich |
| M2 | `StyleView`-SPI + `ViewServices`; TreeBuilderFactory-, ViolationKinds- und Expansion-Switches auflösen; `ArchitectureView` → `ArchitectureCanvas` (layered als erste StyleView) | Der Modus-Monolith ist weg |
| M3 | `ViewFeature`-SPI; What-If nach `features/whatif`; Component-API-Drop nach `views/component`; Scope↔Report trennen | Querschnitt entkoppelt |
| M4 | Ein wfx-Modul pro Komponente; Öffnen via Event; ViewManager verliert Stil-Wissen; ggf. wfx-Feature-Request für Menü-Beiträge | Shell = reine Plattform |
| M5 (optional) | Maven-Multi-Module | Grenzen physisch erzwungen |

M1 ist bewusst zuerst: billigste Etappe, größter Verständlichkeitsgewinn,
und sie erzeugt die Paketstruktur, gegen die die Regeln aus §4 messen.
M2 ist die eigentliche Architekturänderung und braucht die meiste Sorgfalt
(UI-Smoke je Ansicht nach jedem Schritt).

## 6. Erwartetes Endbild

- Jede Ansicht ist **ein** Verzeichnis mit eigenem wfx-Modul — begreifbar,
  einzeln testbar, einzeln entfernbar (und perspektivisch: einzeln
  ausliefer- oder zukaufbar).
- `core` enthält ausschließlich Dinge, die *jede* Ansicht braucht, und
  kennt keine davon. Neue Ansichten entstehen additiv (SPI implementieren,
  Modul registrieren) — ohne Änderung an Kern oder Shell.
- Die Komponenten-Regeln laufen in derselben Selbstanalyse wie die
  Zyklen-Budgets: Das Tool bewacht nicht nur *ob* die UI geschichtet ist,
  sondern *entlang welcher fachlichen Linien*.
