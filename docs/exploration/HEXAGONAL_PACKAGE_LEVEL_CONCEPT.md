# Explorationspapier: Hexagonal View auf Paketebene mit Overlay-Expand

Stand: 2026-06-11

Dieses Papier baut auf `HEXAGONAL_ARCHITECTURE_EXPLORATION.md` (2026-06-03)
auf. Es bewertet die inzwischen umgesetzte erste Version der Hexagonal View
und beschreibt das ueberarbeitete Konzept:

1. Die Ring-Zuordnung erfolgt auf **Paketebene**, nicht auf Klassenebene.
2. Pakete sind **auf- und zuklappbar** wie in der Architecture View,
   allerdings als **Overlay** ueber der Radialgeometrie, ohne Reflow.
3. Jeder Ring ist eine **Schale, die ihre API an der Aussenkante**
   praesentiert: Die Level-0-Interfaces des Anwendungskerns sind die Ports
   und sitzen auf der Ringgrenze zum naechsten Ring -- voellig analog zur
   API von Komponenten, nur dass bei Outbound Ports der aeussere Ring die
   API implementiert (Dependency Inversion).

## Kurzfassung

```text
Ring pro PAKET aus dem Paket-architectureLevel (nicht pro Klasse).
Klassen erben den Ring ihres Pakets.
Level-0-Interfaces des Kerns = Ports = Aussenkante des Core-Rings.
Aufklappen = Detail-Karte als Overlay in der Radial-Pane, kein Reflow.
Zuklappen  = Pfeile rollen ueber den vorhandenen Rollup-Mechanismus auf.
```

## Bewertung des Ist-Zustands

Die erste Version (`HexagonalArchitectureBuilder`,
`HexagonalArchitectureTreeBuilder`) projiziert jede **Klasse** einzeln in
die Ringe. Die Ring-Zuordnung kommt aus dem Klassen-`architectureLevel`
relativ zum Maximum (Drittel-Regel in `classifyRing`):

```text
ratio <= 0.34  -> CORE
ratio <= 0.67  -> APPLICATION
sonst          -> ADAPTER
```

Das ist fachlich falsch -- nicht wegen der Level-Metrik, sondern wegen der
**Granularitaet**:

1. **Klassen-Level sind Rauschen fuer die Rollenfrage.** Ein DTO oder
   Mapper im REST-Adapter hat keine ausgehenden Abhaengigkeiten, also ein
   niedriges Level, und landet im Core. Eine Fassade des Domaenenkerns
   orchestriert viel, hat ein hohes Level, und landet bei den Adaptern.
   Das lokale Level einer Klasse sagt nichts ueber ihre architektonische
   Rolle.

2. **Kohaesive Pakete werden ueber die Ringe verschmiert.** Ein Paket wie
   `adapter.rest` (Controller hoch, DTOs niedrig) verteilt sich auf alle
   drei Ringe. In Ports and Adapters ist aber das Paket/Modul die Einheit
   der Ring-Zuordnung.

3. **Auf Paketebene ist die Level-Ordnung dagegen genau richtig.** Sie
   zeigt, wer wen benutzt. Wer einen Anwendungskern hat, haelt ihn in
   Paketen geordnet. Niedrige Paket-Level = wird von allen benutzt =
   innen; hohe Paket-Level = benutzt andere = aussen. Das ist exakt die
   Abhaengigkeitsrichtung von Ports and Adapters (alles zeigt nach innen).
   Basisabhaengigkeiten, die das Bild stoeren wuerden, sind ueber die
   Filter (excluded-prefixes) ohnehin draussen.

Folgerung: Die Drittel-Regel bleibt, aber sie wird auf
**Paket-architectureLevel** angewendet. Klassen werden nicht mehr einzeln
klassifiziert.

## Konzept

### 1. Domain: Ring-Zuordnung pro Paket

`HexagonalArchitectureBuilder` emittiert pro Segment zusaetzlich
**Paket-Elemente**. Das Datenmodell ist dafuer bereits vorbereitet:
`HexElement.classElement` existiert, ist heute aber immer `true`.
Paket-Elemente bekommen `classElement = false`.

Ring-Zuordnung pro Paket:

```text
1. Explizite ElementRole-Annotation auf dem Paket-FQN gewinnt.
   (Das Segment-Kontextmenue schreibt solche Rollen heute schon.)
2. Sonst: architectureLevel des PAKETS relativ zum maximalen
   Paket-Level, Drittel-Regel wie bisher.
3. Klassen ERBEN den Ring ihres Pakets. Keine eigene
   Klassen-Klassifikation mehr.
```

Damit verschwindet das Verschmieren kohaesiver Pakete ersatzlos. Ein
"gemischtes Paket" gibt es per Definition nicht mehr: Ein Paket ist eine
Einheit. Beim Aufklappen sieht man die Klassen mit ihren lokalen Levels
innerhalb der Detail-Karte.

Welche Pakete: alle Pakete unterhalb des Segment-Roots, die direkt Klassen
enthalten (Blatt-Pakete). Tiefere Hierarchien koennen spaeter verfeinert
werden.

Die Paket-Level existieren bereits im DomainModel -- die Layered View
ordnet Pakete heute schon danach. Der `LevelCalculator` bleibt unberuehrt;
alles ist weiterhin reine Projektion.

### 2. Schalenmodell: Ports sind die Level-0-Interfaces des Kerns

Jeder Ring ist eine Schale, deren **Aussenkante die Interfaces traegt**,
ueber die der naechstaeussere Ring zugreift:

```text
Zentrum:     Kern-Implementierung (hoeheres Level, benutzt die Interfaces)
Core-Rand:   Level-0-Interfaces des Kerns = die PORTS, als Sockel
             AUF der Ringgrenze
weiter aussen: Application-Pakete, dann Adapter-Pakete
```

Die radiale Position innerhalb eines Ring-Bandes folgt dem Paket-Level --
als Schalenmodell, nicht als monotone Achse: API-nahe Pakete an der
Aussenkante des Bandes, Implementierungspakete weiter innen.

Das ist voellig analog zur API von Komponenten, und die Maschinerie
existiert schon: `HexElement.componentApi` wird ueber den
`ComponentApiClassifier` befuellt -- dieselbe Logik, die in der Component
View API-Klassen an die Komponentengrenze stellt. Der Builder erfindet
nichts neu, er wendet sie richtig an:

```text
Port-Kandidaten = die Level-0-Interfaces der Core-Pakete eines Segments.
Automatisch erkannt. Annotationen BESTAETIGEN (und geben die Richtung),
sie erschaffen die Ports nicht erst.
```

Der entscheidende Unterschied zur Komponenten-API ist die
Realisierungsrichtung:

```text
INBOUND  - der aeussere Ring RUFT das Interface, der Kern implementiert
           es. Wie eine klassische Komponenten-API.
OUTBOUND - der Kern definiert und ruft das Interface, der aeussere Ring
           IMPLEMENTIERT es (Dependency Inversion).
```

In der Level-Ordnung sehen beide Faelle identisch aus (Interface auf
Level 0, alle Beteiligten darueber) -- genau deshalb funktioniert die
Paket-Level-Projektion fuer beide. Unterscheidbar sind sie an der
Kantenart: implements-Beziehung von aussen = Outbound, reine Nutzung von
aussen = Inbound. Die IN/OUT-Beschriftung der Sockel kann damit
hergeleitet statt nur aus der Annotation gelesen werden.

Explizite Ports bleiben klassenbasiert -- das ist die Semantik des
Patterns und wird nicht aggregiert.

### 3. View: HexPackageBox statt Klassen-Spalten

In `HexagonalArchitectureTreeBuilder` ersetzt eine kompakte
**HexPackageBox** (Toggle "+/-", Paketname, Klassenzahl) die heutigen
Ring-Klassenspalten:

- Platzierung pro Segment und Ring wie heute (`placeNode` mit Radius und
  Segmentwinkel), innerhalb des Ring-Bandes radial nach Paket-Level
  gestaffelt (siehe Schalenmodell).
- Port-Sockel sitzen direkt auf der Grenzlinie (wie heute bei
  `APPLICATION_RADIUS + 12`, kuenftig auch an der Core-Grenze).
- Das "+N more"-Muster kann uebernommen werden, wird durch die
  Aggregation aber deutlich seltener noetig.
- Properties auf der Box: `s202.aggregateEndpoint = true`,
  `s202.collapsed` je nach Zustand -- damit greift die bestehende
  Pfeil-Aggregation inklusive Count-Badge im `DependencyRenderer`.

### 4. Aufklappen als Overlay: Detail-Layer in der Radial-Pane

Die Radialgeometrie ist absolut positioniert; ein Reflow wie im Layered
View (VBox/HBox mit `setManaged(false)`) ist nicht moeglich und auch
nicht wuenschenswert. Deshalb Overlay:

Die Radial-Pane bekommt als **letztes Kind eine `Group detailLayer`**.
Beim Aufklappen einer HexPackageBox wird dort eine **Detail-Karte**
gerendert: ein VBox-Panel mit den `LevelClassBox`-Eintraegen des Pakets
(Wiederverwendung von `classBox()` samt Kontextmenue fuer Port- und
Rollen-Markierung), verankert an der Boxposition, leicht radial nach
aussen versetzt, mit kurzer Verbindungslinie zur Box. Die Karte schwebt
ueber Ringen und Nachbarsegmenten, ohne dass die Geometrie umbrechen
muss.

Mechanik beim Toggle (alles vorhandene Muster):

```text
- Karte visible/managed schalten, s202.collapsed auf der Box setzen.
- Klassen der Karte sind in der elementRegistry registriert; die Karte
  traegt s202.rollupEndpointFqn = <Paket-FQN>. Zugeklappt rollen alle
  Pfeile automatisch auf die HexPackageBox (DependencyRenderer.
  findVisibleTarget laeuft den Parent-Chain hoch).
- Danach layoutChangeCallback.run() -- bereits in den Builder
  verdrahtet, triggert den PulseCoalescer (Platform.runLater-
  Entkopplung gegen Pulse-Races, siehe ADR_PULSE_COALESCING_AND_DND).
```

Mehrere Karten duerfen gleichzeitig offen sein. MVP ohne
Kollisionsvermeidung; falls stoerend, spaeter einfache radiale
Staffelung der Kartenanker.

Verglichen mit der Alternative (eigene Pane in der `overlayPane` der
ArchitectureView) hat der Detail-Layer in der Radial-Pane drei Vorteile:
identische Koordinaten (kein Transform-Mapping), garantierte Z-Order
(letztes Kind = immer oben), kein Eingriff in den Pane-Stack der
ArchitectureView. Es ist keine wfx-Aenderung noetig; alles ist
projektlokales JavaFX.

### 5. Expand-Zustand ueberlebt Rebuilds

Anders als im Layered View (Zustand lebt in den UI-Nodes) wird die
Hexagonal View bei jeder Annotation-Aenderung komplett neu gebaut.
Deshalb:

```text
Map<String, Boolean> packageExpansionState   (Key = Paket-FQN)
```

gehalten in der `ArchitectureView`, dem `HexagonalArchitectureTreeBuilder`
per Konstruktor mitgegeben -- analog zur bestehenden Restaurierung des
ComponentBox-Zustands.

## Violations in der Paket-Projektion

Da Paket-Level topologisch aus den Abhaengigkeiten kommen, sind regulaere
Abhaengigkeiten automatisch ring-konform. Sichtbar werden genau die
interessanten Befunde:

```text
HEXAGON_OUTWARD_DEPENDENCY - Wrong-Direction-Kanten aus Paket-Zyklen/
                             Tangles, die Ringe falsch herum kreuzen.
HEXAGON_PORT_BYPASS        - Aussen-Pakete, die an den Port-Sockeln
                             vorbei in die Interna eines Innen-Pakets
                             greifen. Geometrisch intuitiv: eine Kante,
                             die an den Sockeln vorbei ins Innere der
                             Schale sticht.
```

Die Violation-Erkennung bleibt auf Klassenebene; das Rollup auf
Paketpfeile macht der Renderer.

## Umsetzungsreihenfolge

```text
Schritt 1: Builder + Tests
  - Paket-Elemente (classElement = false), Ring aus Paket-Level.
  - Klassen erben den Ring des Pakets; classifyRing fuer Klassen
    entfaellt (nur Annotation/Port bleibt als Override).
  - Port-Kandidaten = Level-0-Interfaces der Core-Pakete
    (ComponentApiClassifier wiederverwenden).
  - HexagonalArchitectureBuilderTest erweitern.

Schritt 2: View
  - HexPackageBox, Staffelung im Ring-Band nach Paket-Level.
  - detailLayer + Detail-Karten, Toggle-Mechanik, Rollup-Properties.
  - Port-Sockel auf den Grenzlinien, IN/OUT aus Kantenart herleitbar.

Schritt 3: Zustand + Feinschliff
  - packageExpansionState in ArchitectureView.
  - Legende, Badges, optionale Kartenstaffelung.
```

Geschaetzter Aufwand: Builder + Tests ~1 Tag, View ~1-2 Tage,
Zustand/Feinschliff ~0,5 Tag.

## Risiken

| Risiko | Gegenmassnahme |
|---|---|
| Platz im Core-Ring bei vielen Paketen | Aggregation reduziert Elementzahl stark; notfalls "+N more" |
| Ueberlappende Detail-Karten | MVP akzeptiert; spaeter radiale Staffelung der Anker |
| Pulse-Races beim Toggle | layoutChangeCallback -> PulseCoalescer, wie in der Layered View |
| Falsche Port-Kandidaten | Annotation bestaetigt/ueberschreibt; Kandidaten nur visuell hervorheben |

Bewusst nicht verfolgt: die Hexagonal View auf das VBox/HBox-Reflow-Modell
der Architecture View umzustellen (echtes In-Place-Expandieren). Das
wuerde die gesamte Polargeometrie dynamisch machen -- hoher Aufwand,
instabile Optik. Das Overlay-Modell liefert das gewuenschte Verhalten
ohne diesen Preis.

## Iteration 2 (2026-06-11): Fachliche Themen als Segmente

Befund aus dem Paper-Whale-Test: Top-Level-Pakete als Segmente ergeben
Schicht-Wedges (domain, application, persistence, ...) -- das ist falsch.
Die Segmente muessen die FACHLICHEN THEMEN sein. Im Beispiel: das domain-
Paket enthaelt vier Themen (book, publisher, inventory, logistics); jedes
Thema nimmt einen Sektor ueber ALLE Ringe ein.

```text
Segmente  = die GROESSTE GESCHWISTER-GRUPPE von Core-Blatt-Paketen unter
            einem gemeinsamen Eltern-Paket ("im domain package gibt es
            vier Themen"). Die Gruppierung nach Parent verhindert, dass
            Vertragspakete wie api/spi faelschlich Themen werden -- deren
            Interfaces liegen per Design auf Level 0 (Interfaces tragen
            im Bytecode-Modell keine Abhaengigkeiten; alles ruht auf
            ihnen). Fallback auf Top-Level-Segmentierung, wenn keine
            zwei Themen erkennbar sind.
Zuordnung = Klassen in einem Themen-Paket gehoeren dem Thema. Der Rest
            in Phasen, alle Runden synchron und deterministisch:
            Phase A: Abhaengigkeits-Voting, Stimme gewichtet mit
                     1/Popularitaet des Ziels -- ein ueberall genutztes
                     Value Object (Isbn) zieht so keine Adapter in sein
                     Thema.
            Phase B: fuer Klassen ohne Dependency-Pfad (Interfaces!):
                     Nutzer/Implementierer stimmen, gewichtet mit
                     1/Fan-Out des Waehlers. Eine fokussierte
                     Implementierung (2 Deps) stimmt laut, der
                     Bootstrap (20 Deps) fluestert -- ein Proxy fuer
                     die Implements-Beziehung.
            Phase C: Rest ins groesste Thema.
            Die Einheit der Themen-Zuordnung ist die KLASSE -- das
            api-Paket etwa verteilt seine Use-Cases auf mehrere
            Sektoren.
```

Die Ring-Zuordnung bleibt paketbasiert (Iteration 1); neu ist nur die
Winkel-Zuordnung (Segment) auf Klassenebene.

Darstellung pro Sektor:

```text
CORE        - eine aufklappbare Box pro Core-Paket des Themas. Expand
              blendet die Schichtendarstellung der Klassen als Overlay
              ein: eine Zeile pro ARCHITECTURE LEVEL (globale
              semantische Tiefe, NICHT der lokale Layout-Level im
              Parent-Container), hoechste oben.
APPLICATION - eine aufklappbare Box fuer die Service-Klassen des Themas.
              Die API- und SPI-Interfaces des Themas sitzen als ZWEI
              GETRENNT aufklappbare Sockel auf der Aussengrenze des
              Application-Rings (API = inbound, SPI = outbound;
              Erkennung: PortDirection-Annotation, sonst Paketname
              spi/api; KEINE Ring-Vorbedingung, da Vertragspakete auf
              Level 0 liegen).
ADAPTER     - eine aufklappbare Box pro (Adapter-Paket x Thema), radial
              nach Paket-Level gestaffelt wie bisher.

Jede Overlay-Karte traegt ein Schliessen-Symbol (x) im Kopf -- sonst
bleiben aufgeklappte Karten haengen, wenn die zugehoerige Box verdeckt
ist.
```

Violations: Die crossSegment-Klausel der PORT_BYPASS-Regel entfaellt --
bei Themen-Segmenten sind Querbezuege zwischen Themen im Kern normal
(book -> publisher). Bypass bleibt: Adapter-Klasse greift an den Ports
vorbei auf Application/Core-Implementierung zu.

### Vertragssignal: Service vs. Adapter

Die Level allein koennen die Service-Schicht nicht von den Adaptern
trennen -- beide liegen genau eine Stufe ueber den Ports. Die Edge-Kinds
des Bytecode-Readers (IMPLEMENTS/EXTENDS vs. Nutzung) koennen es:

```text
Paket implementiert einen SPI-Vertrag -> ADAPTER (driven: persistence,
                                         carrier, notifier)
Paket implementiert einen API-Vertrag -> APPLICATION (die Use-Case-
                                         Implementierung)
Paket NUTZT API-Vertraege nur        -> ADAPTER (driving: rest, ui,
                                         oder Adapter-Glue)
```

Vertraege = explizite Ports plus Klassen in api/spi-benannten Paketen.
Praezedenz der Ring-Zuordnung: explizite Paket-Annotation > Vertrags-
signal > Level-Drittel. Ohne rawModel (Edge-Kinds) faellt die Heuristik
stumm auf das Level-Drittel zurueck. Damit landen persistence, rest und
ui ohne jede Annotation im Adapter-Ring aussen.
