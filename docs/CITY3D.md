# City3D — Die Software-Stadt von Structure202

*Stand: Juli 2026 · Branch `feat/city3d-prototype` · Code unter [`city3d/`](../city3d/)*

City3D rendert die analysierte Architektur eines Java-Systems als begehbare
3D-Stadt im Browser (three.js/WebGL): eine Insel im Ozean, auf der Pakete zu
terrassierten Plattformen, Klassen zu Gebäuden und **Abhängigkeiten zu echtem
Verkehr** werden. Die Ansicht wird aus der JavaFX-App heraus geöffnet
(*File → Show City3D View*), läuft aber auch standalone gegen ein exportiertes
`city.json`.

---

## 1. Konzeption: Das Layout ist die Aussage

Die zentrale Entwurfsregel der gesamten Entwicklung:

> **Das X/Z-Layout kodiert die Abhängigkeitsstruktur und ist unantastbar.**
> Alle visuellen Ebenen (Licht, Verkehr, Bögen, Atmosphäre) sind additiv und
> dürfen die Anordnung nie verändern — höchstens die Abstände.

Die Stadt ist die 3D-Projektion der S202-Schichtendarstellung
(siehe [`docs/concept`](concept/)):

- **Paket-Baum = verschachtelte Plattformen.** Jedes Paket ist ein Rechteck,
  das seine Kinder umschließt; jede Verschachtelungsebene hebt die Plattform
  eine Terrassenstufe an. Die Stadt-Silhouette *ist* der Paketbaum.
- **Level-Reihen.** Innerhalb eines Pakets stehen Kinder zeilenweise nach
  lokalem Level (das „L:x" der 2D-Ansicht): unten die Basis, oben die Nutzer —
  exakt die Anordnung der 2D-Architektursicht, nur als Stadtgrundriss.
- **Abhängigkeiten fließen deshalb sichtbar „bergab".** Regelkonforme Kanten
  laufen von hinteren zu vorderen Reihen; alles, was gegen die Schichtung
  verstößt, fällt als Gegenverkehr auf.

Damit gilt: Wer die Stadt lesen kann, liest die Architektur — Distanz,
Richtung und Höhe sind nie Dekoration, sondern immer Struktur.

## 2. Was die Stadt kodiert

| Stadtelement | Bedeutung |
|---|---|
| Plattform (Terrasse) | Paket; Stapelhöhe = Verschachtelungstiefe, Reihen = lokale Levels |
| Rote Plattform | Paket ist Teil eines Paket-Zyklus (Tangle) |
| Gebäude | Klasse; ein Gebäude pro Klasse, auf der Plattform seines Pakets |
| Gebäudehöhe | Methodenanzahl (Codemenge) |
| Grundfläche | Fan-In (Breite) × Fan-Out (Tiefe) |
| Gebäudetyp Glas → Beton | globales Architektur-Level (oben → unten); Interfaces sind immer Glas |
| Rot blinkendes Dach-Beacon | Klasse steckt in einem Abhängigkeits-Zyklus |
| Fensterlicht (Metrik-Modus) | Dichte/Helligkeit folgt wählbarer Metrik (Fan-In / Fan-Out / Methoden) — Hotspots leuchten nachts, tote Klassen bleiben dunkel |
| Paket-Schilder | Distrikt-Namen an den Plattformkanten, distanzabhängig eingeblendet |

## 3. Abhängigkeiten: zwei Visualisierungen, eine Semantik

**Farbcode überall gleich:** cyan/blau = regelkonform (Level fällt),
**rot = Verstoß** (Kante aufwärts oder gleiches Level = Zyklus).

### 3.1 Datenströme (Bögen)

Leuchtende Bézier-Bögen von Dach zu Dach mit GPU-animierten Pulsen
(„Datenpakete" fliegen vom Nutzer zum Genutzten). Modi: *Aus / Auswahl /
Alle / nur Verstöße*; im Auswahl-Modus eingehend türkis, ausgehend amber.
Das Panel zeigt Kantenzahl und Verstoß-Zähler.

### 3.2 Verkehr (das Herzstück)

Jede (gesampelte) Abhängigkeit **fährt**: ein Fahrzeug nimmt die echte
Straßenroute von der nutzenden zur genutzten Klasse.

- **Gelbe NY-Cabs** = paketübergreifende Beziehungen. Karosserie mit dunkler
  Glaskabine, weiße Scheinwerfer, rote Rücklichter; die Semantik sitzt in der
  **Taxi-Dachleuchte** (cyan/rot).
- **Fußgänger** (einfarbige Spielfiguren) = paketlokale Beziehungen; sie
  laufen auf dem Gehsteig statt auf der Fahrbahn.
- **Tempo = Metrik:** Die Geschwindigkeit skaliert mit dem globalen
  Level-Sprung der Abhängigkeit — weite Fahrten quer durch den Schichtenstapel
  rasen, Ein-Level-Hops schlendern. (Netter Nebeneffekt: Zyklus-Fahrten sind
  level-nah und damit auffällig langsame rote Cabs.)
- **Tag/Nacht-Rhythmus:** Verkehrsdichte = Regler × Tageslicht-Faktor.
- **Labels:** In Kameranähe erscheint über den nächsten Pods der Fahrttext
  „Quelle → Ziel" (reiner Text, positionsgenau pro Frame nachgeführt).
- **Hotspots** (zuschaltbar): sanft pulsierende Glow-Flächen auf den
  meistbefahrenen Kreuzungen — die Routenlast pro Graphknoten macht die
  „Arterien" der Architektur sichtbar.

## 4. Das Straßennetz: ein routbarer Graph

Straßen sind keine Dekoration in den Lücken, sondern ein **Graph**, auf dem
Dijkstra Routen berechnet ([`roads.js`](../city3d/src/roads.js)):

- **Ringstraße** innen an jeder Plattformkante (garantiert Konnektivität),
  **Querstraßen** zwischen den Level-Reihen, **Seitenstraßen** zwischen
  Nachbarn, **Stichstraßen** (Zufahrten) an *beiden* Gebäudeseiten — das
  Routing startet/endet per Multi-Source-Dijkstra an der günstigeren
  Ausfahrt, statt ums Paket zu fahren —, **Rampen** von jedem Kind-Deck zu
  den Eltern-Korridoren (durch die Hierarchie immer genau eine
  Terrassenstufe).
- **Kantengewichte erzeugen Bündelung:** Autos zahlen Aufschlag auf schmale
  Straßen (Verkehr sammelt sich auf den breiten Boulevards der Wurzel) und
  einen Rampen-Malus (kein Abkürzen über fremde Paket-Decks); Fußgänger
  bevorzugen invers die ruhigen Seitenstraßen.
- **Fahrbar gemacht:** Rechtsverkehr per Spurversatz, Ecken als
  Quadratic-Bézier-Bögen, Gehsteige mit Bordstein und Freischnitten an
  Kreuzungen/Zufahrten, runde Kreuzungsteller (Asphalt- + Gehweg-Disc) gegen
  die 90°-Ecklücken, Mittellinien, Laternen.
- Boulevards werden zur Hierarchie-Wurzel hin breiter — die Straßenbreite
  selbst kodiert die Ebene.

## 5. Atmosphäre & Landschaft

- **Eigener Himmel-Dome** (Shader): sattes Zenit-Blau → heller Horizont,
  Sonnenscheibe mit Glow, **Mondscheibe** gegenüber der Sonne. Nachts ist der
  Mond ein echtes Key-Light (kühles Blau, Schattenwurf) — die Mondlichtphase
  hält Boden und Straßen lesbar. Driftende prozedurale Wolkenschicht
  (FBM-Canvas) mit Horizont-Fade; **Zeitraffer**-Knopf rotiert Sonne und Mond
  durch den vollen Tagesbogen.
- **Insel im Ozean:** Die Stadt steht auf einem Betonsockel; außenrum eine
  animierte, reflektierende Wasserfläche (Szenen-Spiegelung, Wellen-Wobble,
  Fresnel, Distanz-Blend in den Horizontdunst).
- **Grünflächen:** Parks (Rasenfelder + Baum-Cluster, zwei Baumtypen,
  Büsche) und Beton-Freiflächen werden kollisionsgeprüft in die freien
  Plaza-Bereiche gesampelt; ein Grüngürtel säumt die Insel.
- Wetter (Nässe/Regen), Nebel, Bloom, SSAO, Farb-Grading — alles regelbar.

## 6. Interaktion

| Aktion | Wirkung |
|---|---|
| Klick auf Gebäude/Plattform | Info-Panel (Metriken, klickbare Uses/Used-by-Chips zum Navigieren), Glow-Highlight; erneuter Klick deselektiert (Toggle) |
| Klick auf Pod | Fahrt-Info (Quelle → Ziel, Typ, Routenlänge), Route leuchtet, beide Endgebäude glühen; **📍 Verfolgen** startet die Chase-Cam |
| **🎥 Rundfahrt** | Kamera heftet sich an ein zufälliges Fahrzeug — endlose Stadttour entlang echter Abhängigkeiten (Esc/Klick beendet) |
| Suche (`/`) | Autocomplete über Klassen + Pakete, Auswahl mit Kamera-Anflug |
| Doppelklick | Anflug auf das Objekt |
| 2D-Sync | Selektion läuft bidirektional zwischen Browser und JavaFX-App (Loopback-Server) |

## 7. Implementierung

```
city3d/src/
  adapter.js   CityModel-JSON -> Layout (Layout3D-Port) + Straßennetz als Graph-Daten + Anker
  roads.js     Graph-Builder (Stationen/Kreuzungen), gewichteter Dijkstra, fahrbare Routen
  vehicles.js  Fahrzeug-Pools (Cabs/Fußgänger, Multi-Layer-Instancing), Labels, Picking, Follow
  deps.js      Abhängigkeits-Bögen + GPU-Pulse
  city.js      Rendering: Gebäude (Fassaden-Shader), Plattformen, Straßen, Rampen, Insel, Wasser
  sky.js       Himmel-Dome, Sonne/Mond, Beleuchtungsmodell, Wolken, Env-Map
  greenery.js  Parks, Bäume, Büsche, Freiflächen (Sampling + Kollisionstest)
  labels.js    Paket-Namensschilder
  i18n.js      UI-Übersetzungen (DE/EN/PT), Sprachwahl + data-i18n-Anwendung
  main.js      Orchestrierung, UI, Selektion, Kamera (Anflug/Follow), Post-FX
```

**Mehrsprachigkeit:** Die gesamte Oberfläche (Panels, Legende, Hilfe, Suche,
Info-Overlays, Tooltips) ist in Deutsch, Englisch und Portugiesisch verfügbar.
Statische Texte hängen über `data-i18n`-Attribute in `index.html` am
Wörterbuch in `i18n.js`; dynamische Texte (Info-Panel, Tageszeit-Label,
FPS-Zeile) rufen `t()` direkt. Sprachwahl: `?lang=de|en|pt` in der URL schlägt
die gespeicherte Wahl (`localStorage`) schlägt die Browsersprache; Umschalter
im Einstellungs-Panel, Wechsel wirkt live ohne Reload. Klassen-, Paket- und
Fahrzeug-Beschriftungen in der Szene sind Bezeichner aus dem Modell und
bleiben sprachneutral.

Datenfluss: `CityModelExporter` (Java) serialisiert die Analyse zu
`city.json` (Pakete, Klassen, Metriken, **Abhängigkeitskanten**);
`CityView3DServer` serviert das Vite-Bundle und den Selektions-Sync.

Performance-Prinzipien: alles Wiederholte ist instanziert (Gebäude in einem
InstancedMesh mit prozeduralem Fassaden-Shader, Fahrzeuge als geteilte
Matrizen über Layer-Meshes, Bögen/Pulse als ein Draw-Call), Routen werden
einmalig berechnet, Animation läuft auf der GPU oder über billige
Matrix-Updates. Getestet bis in den Bereich zehntausender Kanten (Caps und
LOD-Schwellen fangen Ausreißer).

## 8. Erarbeitete Design-Lektionen

- **Semantik in die Dachleuchte:** Ikonografie (gelbes Cab) und Bedeutung
  (Farbe) lassen sich trennen — das Objekt bleibt lesbar, die Aussage auch.
- **Zielen braucht eine stehende Welt:** Mausbewegung pausiert die
  Kino-Rotation, gepickt wird bei *pointerdown* — sonst schießt jede
  Selektion am driftenden Ziel vorbei.
- **Kleine bewegliche Ziele klickt man im Screen-Space**, nicht per Raycast
  (der verliert gegen die Plattform darunter).
- **Overlays gehören nicht in den SSAO-Tiefenpass** (sonst werfen unsichtbare
  Label-Quads „AO-Schatten" — die berüchtigten schwarzen Balken).
- **Ein Verfolger-Lerp verträgt kein konkurrierendes Orbit-Damping** — beim
  Verfolgen wird die Orbit-Steuerung komplett stummgeschaltet.

## 9. Offene Ideen

- Paket-Aggregation der Bögen bei großer Distanz (weniger Spaghetti bei
  Systemen mit tausenden Klassen).
- Ampeln/Stau-Inszenierung an Hotspot-Kreuzungen; Kreuzungslast als Export.
- Kino-Tour entlang der schlimmsten Verstöße („Architektur-Review-Rundflug").
- Aggregierte Metrik-Zeitreihen (Stadt im Wandel über Commits).
