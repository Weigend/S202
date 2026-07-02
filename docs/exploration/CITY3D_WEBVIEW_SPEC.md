# Spezifikation: City3JS (Three.js) als 3D-Stadtansicht in Structure202

**Ziel:** Die heutige, sehr einfache JavaFX-3D-Ansicht (`ArchitectureView3D` /
`SceneBuilder3D`) durch den schickeren **Manhattan-Renderer aus `../City3JS`** (Three.js /
WebGL) ersetzen bzw. ergänzen — ohne die bestehende, getestete Analyse-Pipeline anzufassen.

**Grundlage:** `../City3JS/docs/integration-structure202-webview.md` (Konzept) und
`integration-softwarecity2026.md` (Schwesterdokument, .NET-Weg).

**Status:** Spezifikations-Entwurf · 2026-07-02 · **noch keine Code-Änderung** · offene
Punkte in §9 sind vor der Umsetzung zu klären.

> ⚠️ **Wichtigstes Ergebnis vorab (Spike bereits durchgeführt, siehe §2):** Die naive
> BrowserView-Idee (JavaFX-`WebView` + Three.js in-process, Konzept-„V1") **funktioniert
> nicht** — die WebView in JavaFX 21.0.5 hat **kein WebGL**. Damit ist die Frage aus dem
> Auftrag („funktioniert der Lösungsweg BrowserView überhaupt?") empirisch beantwortet:
> **nein, nicht mit der Standard-WebView.** Der Weg führt über **JCEF (eingebettetes
> Chromium)** oder einen lokalen Server + externen Browser.

---

## 1. Was gegen den echten Code verifiziert wurde

Das Konzept macht mehrere Annahmen über Structure202. Diese wurden hier gegen den Code geprüft:

| Konzept-Annahme | Verifiziert? | Befund im Code |
|---|---|---|
| WebView wird bereits verwendet | ✅ ja | [`QualityReportView`](../../analyzer/src/main/java/de/weigend/s202/ui/wfx/report/QualityReportView.java) nutzt `javafx.scene.web.WebView` (read-only, ohne JS-Bridge) |
| Berechnung/Darstellung getrennt | ✅ ja | `Layout3D` (Layout, reines Java) → `SceneBuilder3D` (JavaFX-Boxen) |
| 3D liest interne 2D-Strukturen | ⚠️ **teils überholt** | `Architecture3DModule` liest Footprints bereits über **öffentliche** API `ArchitectureView.getElementFootprintBoundsInLayout()` + `getVisibleElementParentFqns()` — das im Konzept §5 geforderte Refactoring ist **teilweise schon erledigt** |
| JavaFX-3D-Mapping ist „dünn" | ✅ ja | `SceneBuilder3D`: Höhe = Level-Elevation, Breite = `UNIT·(1+log10(fanIn))`, Farbe = **nur Paket-Verschachtelungstiefe** (`#fffacd`→`#d6b85a`); keine Coverage/SCC-Farbe |
| Analyse identisch zu SoftwareCity2026 | ✅ plausibel | `InputAnalyzer` (ASM), `TarjanSCCFinder`, `LevelCalculator` vorhanden |

**Konsequenz:** Die Ausgangslage ist sogar etwas besser als im Konzept beschrieben — die
saubere Footprint-API existiert schon. Der Renderer-Tausch ist dadurch entkoppelbarer.

**Umgebung (gemessen):**
- JavaFX **21.0.5** (`javafx-web-21.0.5`, WebKit-basiert), JDK 21.
- City3JS: Three.js `^0.169`, Vite-Build. Renderer `src/city.js` (~1.283 LoC, Fassaden-Shader),
  `src/controls.js` (Navigation), `src/main.js`.

---

## 2. Der Spike ist gelaufen — Ergebnis: **NO_WEBGL**

Das Konzept (§3) nennt den WebGL-Spike als „1-Tages-Entscheidung, an der alles hängt". Er
wurde **bereits ausgeführt** — mit exakt den JavaFX-21.0.5-WebView-Binaries, die auch die App
lädt, gegen ein echtes Display:

```
PROBE_RESULT: NO_WEBGL (getContext returned null)
```

- `canvas.getContext('webgl' | 'experimental-webgl' | 'webgl2')` liefert **`null`**.
- DOM/JS selbst laufen (das JS hat den `document.title` gesetzt) — es fehlt **nur** WebGL.
- Deckt sich mit OpenJDK [JDK-8090547](https://bugs.openjdk.org/browse/JDK-8090547) („Support
  WebGL in WebView", seit 2013 offen).

### Fehlt uns einfach ein Modul/Flag? — Nein (tiefer nachgeprüft)

Die naheliegende Hoffnung „vielleicht müssen wir WebGL nur einschalten" wurde gezielt untersucht:

- **Prism-Pipeline ausgeschlossen:** Probe erneut mit erzwungener Hardware-Pipeline
  (`-Dprism.order=es2`) — Prism initialisiert `ES2Pipeline`, „Graphics Vendor: NVIDIA
  Corporation". Trotzdem `NO_WEBGL`. Es ist **kein** Software-Rendering-Artefakt.
- **Nativer Code hat WebGL sogar:** In `libjfxwebkit.so` finden sich
  `WebGL2RenderingContext`, `WebGLBuffer`, `setWebGLEnabled`, `webgl`/`webgl2` — die
  WebKit-C++-Ebene **kann** WebGL, ist aber im JavaFX-Build **default-aus**.
- **Aber die Java-Bindung reicht keinen Schalter durch:** Über **alle 380** WebKit-Java-Klassen
  (`com.sun.webkit.*`, `com.sun.javafx.webkit.*`) hinweg gibt es **null** WebGL-Referenzen.
  `WebPage` hat `twkSet…`-Natives für JavaScript, Developer-Extras, Page-Cache, Zoom — aber
  **kein `twkSetWebGLEnabled`**.

**Fazit:** Es gibt **kein Classpath-Modul, keine System-Property, keine Config**, die WebGL in
der JavaFX-WebView aktiviert. Der einzige theoretische Weg wäre, **OpenJFX' WebKit selbst zu
patchen und neu zu bauen** (natives Binding ergänzen + WebKit-Nativbuild pro Plattform) — ein
Plattform-Fork, kein Feature-Flag. **Für dieses Projekt unrealistisch → V1 bleibt ausgeschlossen.**

Der Spike-Code liegt als `WebGLProbe.java` im Scratchpad; er ist wiederholbar, falls jemand die
JavaFX-Version wechselt oder eine WebGL-fähige WebView-Variante testen will.

---

## 3. Architektur-Entscheidung

Da der Spike rot ist, bleiben zwei Wege. Empfehlung folgt der Konzept-Logik:

> ✅ **V2-Spike gelaufen (2026-07-02): grün.** JCEF (`me.friwi:jcefmaven:146.0.10`, Chromium
> 146.0.7680.179) initialisiert auf dieser Linux-Maschine und **WebGL läuft hardwarebeschleunigt**:
> `WEBGL_OK | WebGL 1.0 (OpenGL ES 2.0 Chromium) | ANGLE (NVIDIA GeForce RTX 2080 SUPER, OpenGL 4.5.0)`,
> `onLoadEnd http=200`. Three.js würde hier rendern. **Reproduzierbar mit und ohne
> Chromium-Sandbox.**
>
> **Beobachtungen aus dem Spike (ehrlich eingeordnet):**
> 1. **Content-Laden muss über absolute URLs erfolgen.** Der allererste Wegwerf-Lauf zeigte im
>    Fenster „Datei nicht gefunden" — Ursache war eine **relative `file://`-URL** der Probe-HTML,
>    **nicht** JCEF/Sandbox. Mit absoluter `file:///…`-URL (bzw. später: gebündelte Ressource /
>    `data:`-URL / lokaler Loopback) lädt der Content sauber.
> 2. **Sandbox ist unkritisch.** Die Meldung `Failed global descriptor lookup: 7` erscheint auch
>    im erfolgreichen Lauf — sie ist **harmlose Log-Noise, kein Crash-Grund**. (Frühere Annahme
>    „`--no-sandbox` ist Pflicht" hat sich als falsch erwiesen; das Flag ist nicht nötig.)
> 3. **Getestet wurde JCEF in einem Swing-`JFrame`, noch NICHT eingebettet in eine echte
>    JavaFX-Scene.** Das ist der eigentlich noch offene Integrationspunkt → §3a.

### V2 — JCEF (eingebettetes Chromium) *(empfohlen)*

```
[Structure202 JVM]
   InputAnalyzer → LevelCalculator → DomainModel/ArchitectureNode
        │  CityModelJson (Java-Serialisierer)
        ▼
   JCEF (Chromium, WebGL ✓) ──► City3JS / Three.js
        ▲
        │  Upcall: Klick auf Gebäude → CefMessageRouter → EventBus(NodeSelectionEvent)
```

- **WebGL garantiert** (echtes Chromium). Three.js-Renderer 1:1 wiederverwendbar.
- Bridge über `CefMessageRouter`/JS-Query statt `JSObject` (WebView-Bridge geht hier **nicht**).
- Einbettung in JavaFX via `JFXPanel`/Swing-Interop oder als eigenes Fenster.
- **Preis:** +150–250 MB native Chromium-Binaries **pro Plattform**; Packaging je OS aufwendiger.

### V3 — Lokaler Server + externer/eingebetteter Browser *(Fallback)*

Structure202 startet einen winzigen lokalen HTTP-/WebSocket-Endpoint; die Three.js-View läuft
im System-Browser; Selektionen kommen per WebSocket zurück.

- Kein Embedding-Problem, jede Engine geht, gut zu debuggen.
- Bricht aber die „eine integrierte App"-Erfahrung (Fenster außerhalb der WFX-Oberfläche) und
  bringt Server-Lifecycle + Port-Handling.

> **Reihenfolge:** V2 bevorzugen (hält die In-App-Erfahrung). V3 nur, wenn das
> JCEF-Packaging (Bundle-Größe / jpackage / WFX-Distribution) inakzeptabel ist. → §9.

### 3a. Der noch offene Integrationspunkt: JCEF **in einer JavaFX-Scene**

Der Spike hat bewiesen: JCEF + WebGL läuft in-process. **Nicht** bewiesen ist die Einbettung in
eine **JavaFX**-Scene (der Spike lief in einem Swing-`JFrame`). Das ist die eigentliche
Restunsicherheit für die WFX-Integration, weil zwei Wege existieren:

| Weg | Prinzip | Risiko |
|---|---|---|
| **Heavyweight über `SwingNode`** | CEFs `getUIComponent()` (heavyweight AWT-Canvas) in einen `SwingNode` | ⚠️ Heavyweight-AWT in `SwingNode` ist klassisch problematisch (separate native Fenster, „grauer Kasten", Overlap, Fokus). Meist **nicht** sauber. |
| **OSR (Off-Screen-Rendering)** | JCEF rendert offscreen in einen Pixel-Buffer (`isOffscreen=true` + `CefRenderHandler`); Buffer wird in eine JavaFX-`ImageView`/`Canvas` komponiert | ✅ Sauberer JavaFX-Weg, von den meisten JavaFX+JCEF-Projekten genutzt. Etwas mehr Code (Buffer-Bridge, Maus/Tastatur-Weiterleitung); WebGL bleibt GPU-beschleunigt. |

**Empfehlung:** OSR-Weg.

> ✅ **OSR-Spike gelaufen (2026-07-02): grün auf allen drei Achsen.** Ein OSR-JCEF-Browser
> rendert eine WebGL-Seite (rotierendes Dreieck) off-screen; jeder Frame kommt per
> `CefClient.addOnPaintListener` als BGRA-`ByteBuffer` und wird über eine JavaFX
> `PixelBuffer`/`WritableImage` in eine **`ImageView`** geschoben. Ergebnis:
> 1. **Pixel kommen in JavaFX an** — die `ImageView` zeigt die Animation flüssig (Frame-Zähler
>    lief kontinuierlich hoch).
> 2. **WebGL bleibt in OSR GPU-beschleunigt** — Renderer-String
>    `ANGLE (NVIDIA GeForce RTX 2080 SUPER, OpenGL 4.5.0)`, **kein** SwiftShader-Software-Fallback.
>    (Das war das eigentliche OSR-Risiko — entkräftet.)
> 3. **Maus + Tastatur funktionieren** — sowohl synthetisch injizierte als auch echte
>    Interaktion des Nutzers im JavaFX-Fenster kamen in der Seite an
>    (`mouse=x,y | clicks=N | key=a`).
>
> ⚠️ **Eine reale Integrationsreibung (nicht JCEF, sondern Toolkit-Koexistenz):** Beim Start
> gab es 3 nicht-fatale AWT-Exceptions ohne Stacktrace plus eine `Gdk-WARNING:
> XSetErrorHandler()` — der bekannte Konflikt, wenn **JavaFX (GTK) und AWT/Swing (GTK) im
> selben JVM** laufen (mein Spike hält die CEF-Komponente in einem off-screen Swing-`JFrame`).
> Rendering und Input liefen davon unbeeinträchtigt. Für die echte Integration ist zu klären,
> wie die AWT-Fläche minimal gehalten wird (der OSR-Weg braucht sie eigentlich nur als
> CEF-Input/Size-Plumbing) und ob die WFX-Plattform hier schon etwas vorgibt. → §9.
>
> **Bewertung: V2 ist damit end-to-end validiert** — WebGL-Renderer (Chromium), OSR→JavaFX-
> Kompositing und Input funktionieren nachweislich auf dieser Maschine. Offen bleiben nur noch
> die *Produkt-*Fragen (Packaging, Toolkit-Koexistenz sauber lösen), nicht mehr die *Machbarkeit*.

---

## 4. Host-unabhängige Arbeitspakete (sofort nützlich, ohne V2/V3-Entscheidung)

Diese drei Bausteine sind **unabhängig vom Embedding-Weg** und auch für das heutige JavaFX-3D
schon sinnvoll — sie können vor der V2/V3-Entscheidung gebaut werden:

1. **`CityModelJson`-Serialisierer (Java).** Wandelt `DomainModel.CalculatedElementInfo` +
   `ArchitectureNode`-Baum + die 2D-`elementBounds` (die `SceneBuilder3D` heute liest) in das
   JSON aus §5. Ersetzt das, was `SceneBuilder3D` an Geometrie tut, durch reine Datenausgabe.
2. **Footprint-/Modell-API der 2D-View** — **großenteils schon vorhanden**
   (`getElementFootprintBoundsInLayout()`, `getVisibleElementParentFqns()`). Zu prüfen: ob sie
   für den Renderer ausreicht oder ob noch Metriken (fanIn/fanOut, LoC, Coverage, SCC-Flag)
   ergänzt werden müssen.
3. **Reicheres Mapping** (als JS-Schicht im Renderer, siehe §6) — kann gegen das
   `CityModelJson` entwickelt werden, bevor der Host steht.

---

## 5. Datenvertrag: `CityModelJson`

Ein kompaktes, stabiles JSON — im Kern dasselbe wie im .NET-Dokument, Quelle hier aber Java:

```jsonc
{
  "maxLevel": 7,
  "districts": [
    {
      "fullName": "com.example.app.ui", "simpleName": "ui",
      "parentFullName": "com.example.app",
      "architectureLevel": 2, "nestingDepth": 3,
      "footprint": { "x": 120, "y": 40, "w": 300, "h": 180 },  // aus 2D-Layout
      "childDistrictNames": ["…"], "buildingNames": ["…"]
    }
  ],
  "buildings": [
    {
      "fullName": "com.example.app.ui.MainWindow", "simpleName": "MainWindow",
      "districtFullName": "com.example.app.ui",
      "architectureLevel": 2,
      "footprint": { "x": 130, "y": 50, "w": 40, "h": 30 },
      "linesOfCode": 340,        // ► Höhe / Etagen
      "fanIn": 12, "fanOut": 5,  // ► Breite / Dependencies
      "testCoverage": 0.75,      // ► beleuchtete Fenster (falls verfügbar)
      "isInScc": false           // ► rote Fassade
    }
  ],
  "dependencies": [ { "from": "…MainWindow", "to": "…UserService", "kind": "USES" } ],
  "violations": [ /* What-If / Level-Verletzungen, entspricht CurvedArrow3D heute */ ]
}
```

> **Realistisch bzgl. Metriken:** `testCoverage` und ggf. `linesOfCode` sind in Structure202
> heute nicht überall gefüllt. Der Serialisierer muss **Fallbacks** definieren (z. B. Höhe aus
> `architectureLevel` wie heute, wenn keine LoC vorliegen). Welche Metriken real verfügbar
> sind → §9.

**Bridge (JCEF/V2):**
- **Java → JS:** nach Load `window.loadCity(<json>)` aufrufen (JS-Query/`executeJavaScript`).
- **JS → Java:** Picking im Raycaster → `cefQuery({request:'select:'+fqn})` →
  `Platform.runLater(() -> eventBus.publish(new NodeSelectionEvent(fqn, view)))`.
- Selektions-**Rückkanal** existiert im heutigen Modul bereits
  (`Architecture3DModule` ↔ `NodeSelectionEvent`) und wird 1:1 wiederverwendet.

---

## 6. Was zu portieren ist: (fast) nur das Mapping

Die teure Analyse (Reader, SCC, Level, 2D-Layout) **bleibt komplett in Java**. Three.js bekommt
fertige Modell- + Footprint-Daten. Kein Graph-Algorithmus muss nach JS. Zu tun:

| Aufgabe | Heute (JavaFX `SceneBuilder3D`) | Neu (City3JS-Mapping) |
|---|---|---|
| Höhe | Level-Elevation | `f(linesOfCode)` bzw. Fallback Level |
| Breite | `log10(fanIn)` | Dependencies (fanIn/fanOut) |
| Farbe | **nur** Verschachtelungstiefe | Level **+ Coverage + SCC/Risiko** |
| Fassade | – | vorhandener City3JS-Shader (Fenster, Stile) |
| Coverage | – | Anteil **beleuchteter Fenster** |
| SCC | eigenes Overlay | **rote Fassade** |
| Picking/Tooltip | JavaFX-Pick | Three.js-Raycaster → Bridge |
| Dependency/SCC/Violation-Overlays | `CurvedArrow3D` | Three.js-Linien (Phase 2) |
| Kamera/Navigation | `FlyCamera` | `controls.js` (bringt City3JS mit) |

City3JS erzeugt heute eine **bedeutungsfreie** Skyline aus RNG (`city.js`). Der Umbau ersetzt
`heightField()`/RNG durch die `CityModelJson`-Daten + Footprint-Koordinaten. Die
Render-Infrastruktur (InstancedMesh, Fassaden-Shader, Bloom, Navigation) ist wiederverwendbar.

---

## 7. Roadmap (nach geklärten offenen Punkten)

- **Phase 0 — Durchstich:** ✅ **erledigt (2026-07-02, Branch `feat/city3d-prototype`).**
  Headless Java-Exporter (`de.weigend.s202.ui.city3d.CityModelExporter`, nutzt
  `InputAnalyzer → LevelCalculator → ArchitectureNodeBuilder`, kein JavaFX) schreibt
  `city3d/city.json`; eigener Three.js-Viewer (`city3d/`) rendert daraus echte Gebäude im
  Browser. End-to-end in echtem Chromium verifiziert (test-example: 23 Klassen, 14 Pakete,
  31 Deps, Level 0–4; keine JS-Fehler). Metriken heute: Level, fanIn/fanOut, Interface-Flag;
  Footprints headless `null` (Client-Layout). Offen für Phase 1: LoC/Coverage/SCC, Footprint-Reuse.
- **Phase 1 — Render datengetrieben:** Metrik-Mapping (§6), Picking + Tooltip, Distrikt-Gruppierung.
- **Phase 2 — Embedding:** JCEF (V2) einbetten, `Architecture3DModule`-Analogon baut eine
  JCEF-basierte `View` statt `ArchitectureView3D`; Bridge nach §5. (Oder V3, falls so entschieden.)
- **Phase 3 — Overlays:** Dependency-Linien, SCC-/Violation-Highlighting (Ersatz für `CurvedArrow3D`).

> **Entscheidung (2026-07-02): eigene City3JS-Kopie für Structure202.** Kein geteilter Renderer
> mit SoftwareCity2026 — Structure202 bekommt eine entkoppelte Variante. Das vermeidet
> Cross-Repo-Kopplung an den .NET-Weg; das `CityModelJson`-Format kann sich unabhängig
> entwickeln. (Preis: Renderer-Verbesserungen driften ggf. auseinander.)

---

## 8. Aufwand / Risiko

| | Aufwand | Risiko |
|---|---|---|
| `CityModelJson` + Mapping (Phase 0/1) | mittel | gering — reine Java-Serialisierung + JS |
| **JCEF-Embedding (V2)** | **hoch** | **mittel–hoch** — Packaging je OS, +150–250 MB, WFX-Lifecycle |
| Server + Browser (V3) | mittel | gering technisch, hoch fürs „eine App"-Gefühl |
| Overlays (Phase 3) | mittel | gering |

Das Hauptrisiko ist **nicht mehr WebGL** (geklärt), sondern das **JCEF-Packaging/Bundle** in
der WFX-Distribution.

---

## 9. Entscheidungen & offene Punkte

**Bereits entschieden (2026-07-02):**

- ✅ **V1 (WebView + Three.js) ausgeschlossen** — WebGL fehlt, kein Modul/Flag hilft (§2).
- ✅ **Altes JavaFX-3D bleibt parallel** — Umschalter alt/neu im View-Menü, kein harter Ersatz.
- ✅ **Eigene City3JS-Kopie für Structure202** — kein geteilter Renderer mit SoftwareCity2026.

**Machbarkeit von V2 gilt als bewiesen** (WebGL in Chromium ✅, OSR→JavaFX ✅, Input ✅, §3/§3a).
Offen sind nur noch *Produkt-* und *Sauberkeits*-Fragen:

1. **V2 (JCEF) oder V3 (Server+Browser)?** *(Kernentscheidung.)* V2 ist technisch validiert und
   hält die In-App-Erfahrung, kostet aber +150–250 MB pro Plattform und aufwendigeres Packaging;
   V3 ist pragmatischer, bricht aber das „eine App"-Gefühl. Empfehlung: erst der host-unabhängige
   Durchstich (Phase 0), Entscheidung mit Daten in der Hand.
2. **Toolkit-Koexistenz JavaFX ↔ AWT/GTK auf Linux** *(neu, aus OSR-Spike).* Der OSR-Weg braucht
   eine minimale AWT-Fläche als CEF-Input/Size-Plumbing; JavaFX+AWT im selben JVM warf beim Start
   benigne GTK-Exceptions. Zu klären: sauber gelöst (AWT-Fläche minimieren / unsichtbar halten)
   und ob die WFX-Plattform dafür schon etwas vorsieht.
3. **Welche Metriken sind real verfügbar?** LoC, fanIn/fanOut, `testCoverage`, SCC-Flag pro
   `CalculatedElementInfo` — was ist gefüllt, was braucht Fallbacks (§5)? *(Kann ich im Code
   nachsehen, bevor der Serialisierer entsteht.)*
4. **WFX-Integration:** Kann eine JCEF-OSR-Komponente sauber als `io.softwareecg.wfx…View`
   registriert werden (Fokus, Lifecycle, Resize)? Ggf. wfx-seitig (siehe
   [[feedback_wfx_upstream_fixes]] — Plattform-Themen gehören in wfx, nicht als Workaround hier).
5. **Reihenfolge:** Zuerst der host-unabhängige Durchstich (Phase 0, §4) — einverstanden?

---

### Referenzen

- Konzept: `../City3JS/docs/integration-structure202-webview.md`, `integration-softwarecity2026.md`
- Structure202 3D: [`SceneBuilder3D.java`](../../analyzer/src/main/java/de/weigend/s202/ui/wfx/view3d/SceneBuilder3D.java),
  [`ArchitectureView3D.java`](../../analyzer/src/main/java/de/weigend/s202/ui/wfx/view3d/ArchitectureView3D.java),
  [`Layout3D.java`](../../analyzer/src/main/java/de/weigend/s202/ui/wfx/view3d/Layout3D.java),
  [`Architecture3DModule.java`](../../analyzer/src/main/java/de/weigend/s202/ui/wfx/view3d/Architecture3DModule.java)
- WebView-Präzedenz: [`QualityReportView.java`](../../analyzer/src/main/java/de/weigend/s202/ui/wfx/report/QualityReportView.java)
- City3JS-Renderer: `../City3JS/src/city.js`, `controls.js`, `main.js`
- WebGL-Spike: `WebGLProbe.java` (Scratchpad) · Ergebnis `NO_WEBGL`
- JCEF: <https://github.com/jcefmaven/jcefmaven> · JDK-Ticket: <https://bugs.openjdk.org/browse/JDK-8090547>
