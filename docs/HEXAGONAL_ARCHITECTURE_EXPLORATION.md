# Explorationspapier: Hexagonal Architecture View

Stand: 2026-06-03

## Kurzfassung

Die Hexagonal Architecture View sollte keine neue Schichtenberechnung
einfuehren. Sie sollte dieselbe berechnete Architekturhypothese radial
projizieren:

```text
innen  = niedrige architectureLevel, Core/Application
aussen = hohe architectureLevel, Adapter/Technik/Frameworks
Regel  = Abhaengigkeiten laufen im Normalfall nach innen
```

Der beste erste Ansatz ist eine "Radial Ports Projection": ein Kreis oder
Vieleck mit Segmenten, konzentrischen Ringen und Ports auf den Ringgrenzen.
Die Sicht ist fachlich nahe an der Component View, aber nicht identisch:

- Component View: API ist die freigegebene Flaeche einer Komponente.
- Hexagonal View: Port ist eine freigegebene Flaeche mit Richtung und
  Position auf einer Architekturgrenze.

Eine API-Markierung ist deshalb ein sehr guter Port-Kandidat, aber ein Port
braucht zusaetzlich mindestens eine Richtung:

```text
INBOUND  - Adapter ruft Anwendung/Core ueber den Port auf.
OUTBOUND - Anwendung/Core definiert Port, Adapter implementiert ihn.
GENERIC  - noch nicht klassifiziert, aber als Vertragsflaeche sichtbar.
```

Empfehlung: Die Hexagonal View bekommt spaeter ein eigenes WFX-View-Modul,
aber die fachliche Projektion, Port-Policy und Violations liegen im
zentralen Architekturmodell. Damit bleibt der bestehende Level-Algorithmus
unberuehrt.

## Ziel der Exploration

Dieses Papier untersucht, wie eine Hexagonal Architecture View in S202
aussehen und modelliert werden sollte. Es beantwortet insbesondere:

- Wie kann eine ansprechende Kreis-/Vieleckdarstellung aussehen?
- Wie funktionieren auf- und zuklappbare Segmente?
- Wie werden API-Markierungen aus der Component View zu Ports?
- Welche Abhaengigkeitsregeln entstehen?
- Welche Teile gehoeren in Domain/Architecture-Modell, welche in die WFX-UI?
- Welche Risiken gibt es fuer die bestehende Schichten- und Component View?

Nicht Ziel dieses Papiers:

- keine Aenderung am `LevelCalculator`;
- keine neue Semantik fuer bestehende Paketlevel;
- kein Umbau der Component View;
- keine Festlegung auf konkrete JavaFX-Pixelkoordinaten.

## Anschluss an den aktuellen Stand

Der aktuelle Code hat bereits wichtige Grundlagen:

- `ArchitectureKind.HEXAGONAL` existiert als stabiler Style-Identifier.
- `ArchitectureAnnotations.PortSpec` existiert mit `PortDirection`.
- `ArchitectureAnnotations.ElementRole` kennt `INBOUND_PORT`,
  `OUTBOUND_PORT`, `ADAPTER` und `CORE`.
- `ViolationKind` enthaelt bereits reservierte Hexagonal-Kinds:
  `HEXAGON_OUTWARD_DEPENDENCY` und `HEXAGON_PORT_BYPASS`.
- Die Component View hat Persistenz fuer API-Markierungen und zeigt, dass
  style-spezifische Projektionen moeglich sind, ohne die Levelberechnung zu
  veraendern.

Daraus folgt: Hexagonal sollte nicht als Sonderfall im bestehenden
Rows-of-Columns-Renderer entstehen. Die Geometrie ist anders genug, um eine
eigene View zu rechtfertigen. Die fachliche Grundlage sollte aber dieselbe
bleiben:

```text
DependencyModel
  -> DomainModel mit architectureLevel/localLevel
  -> ArchitectureAnnotations
  -> HexagonalArchitectureBuilder
  -> HexagonalArchitecture
  -> HexagonalArchitectureView
```

## Visuelles Leitbild

Die Sicht sollte auf den ersten Blick wie eine Architekturkarte wirken:

```text
                 [ adapter ring ]
            +-----------------------+
        web |     inbound ports     | messaging
            |   +---------------+   |
            |   | application   |   |
            |   |   +-------+   |   |
            |   |   | core  |   |   |
            |   |   +-------+   |   |
            |   +---------------+   |
       db   |    outbound ports    | external
            +-----------------------+
```

Das konkrete Rendering sollte nicht auf ein starres "sechs Ecken fuer alles"
festgelegt werden. Hexagonal Architecture ist ein Architekturstil, kein
Zwang zu exakt sechs Segmenten. Eine gute UI sollte deshalb zwei Modi kennen:

| Situation | Darstellung |
|---|---|
| bis 6 Segmente | Vieleck, bevorzugt Hexagon-artig |
| mehr als 6 Segmente | Kreis mit Segmentbogen |
| sehr viele Segmente | Kreis mit gruppierten Segmenten und Such-/Fokusmodus |

Der View-Name kann trotzdem "Hexagonal Architecture" bleiben. Die
Darstellung ist dann ein Kreis oder Vieleck, das die hexagonale Semantik
transportiert.

## Grundstruktur der Darstellung

Die Projektion besteht aus drei visuellen Achsen:

1. Radiale Tiefe: innen/aussen
2. Segment: fachlicher oder technischer Bereich
3. Port-Grenze: Vertragsflaeche zwischen Ringen

### Radiale Ringe

Die Ringe entstehen aus `architectureLevel` und optionalen Rollen:

```text
Ring 0: Core
Ring 1: Application / Domain Services / Ports
Ring 2: Adapters
Ring 3: Framework / IO / External Glue
```

Der Builder sollte die Rohlevel nicht zwingend eins zu eins als Ringe
rendern. Bei echten Projekten koennen 20, 50 oder mehr Level entstehen.
Visuell braucht die Hexagonal View eine verdichtete Ringstruktur:

```text
architectureLevel 0..1      -> core
architectureLevel 2..n-mid  -> application
architectureLevel mid..max  -> adapters
explizite Rolle CORE        -> core, auch wenn Level abweicht
explizite Rolle ADAPTER     -> adapter, auch wenn Level abweicht
PortSpec                    -> Port-Grenze
```

Wichtig: Diese Ringzuordnung ist eine Projektion. Sie aendert keine
`architectureLevel` im `DomainModel`.

### Segmente

Segmente teilen den Kreis winkelmaessig auf. Ein Segment ist ein sichtbarer
Kontext, z.B.:

- ein Top-Level-Paket;
- eine Komponente aus der Component View;
- ein explizit gepflegtes Hexagonal-Segment;
- spaeter eine fachliche Gruppe aus Projektannotation.

Im ersten Implementierungsschritt sollte die Segmentbildung konservativ
sein:

```text
Wenn ComponentArchitecture vorhanden:
  Komponenten werden Segmente.

Sonst:
  Top-Level-Pakete werden Segmente.
```

Segmente werden nicht geschachtelt. Pakete innerhalb eines Segments koennen
aber aufgeklappt werden, analog zur Component View.

### Ports

Ports sind kleine, stabile Andockpunkte auf der Grenze zwischen zwei Ringen.
Sie sollten nicht als normale Klassenbox irgendwo im Segment verschwinden.
Die Port-Klasse bleibt selektierbar, aber ihre Hauptfunktion ist sichtbar:

```text
outer ring
   |
   v
[ inbound port ]  -> application/core

application/core
   |
   v
[ outbound port ] <- adapter implementation depends on it
```

Optisch:

- Inbound Ports sitzen auf der aeusseren Kante des Application-Rings.
- Outbound Ports sitzen auf der Grenze zwischen Application und Adapter.
- Generic Ports sitzen auf derselben Port-Spur, aber ohne Richtungsglyph.
- Ports bekommen kurze Labels und koennen bei Platzmangel zu Port-Badges
  aggregiert werden.

Damit ist die Beziehung zur Component View klar:

```text
Component API = Vertrag ist oeffentlich.
Hexagonal Port = Vertrag ist oeffentlich und hat eine Richtung an einer Grenze.
```

Die UI sollte deshalb API-Klassen als "Port candidates" anzeigen, aber erst
eine explizite Port-Markierung macht daraus einen echten Port.

## Vorgeschlagene Visualisierung

### Collapsed Segment

Ein zugeklapptes Segment zeigt nur eine kompakte Zusammenfassung:

```text
+--------------------------------+
| payments                       |
| Core 12  App 34  Adapter 18    |
| Ports 5  Violations 2          |
+--------------------------------+
```

Im Kreis ist das ein Segmentbogen mit:

- Segmentname;
- Port-Anzahl;
- Violation-Badge;
- optional Mini-Histogramm der Ringverteilung;
- ein Collapse/Expand-Icon.

Kanten zu einem zugeklappten Segment werden auf Segment-Endpunkte gerollt.
Das entspricht der Paketaggregation in der normalen Ansicht.

### Expanded Segment

Ein aufgeklapptes Segment wird zu einer radialen Lane. Innerhalb dieser Lane
werden Pakete und Klassen entlang der Ringe angeordnet:

```text
Segment: payments

outer
  [payments.adapter.rest] [payments.adapter.jpa]
        |                    |
        v                    v
  (CreateOrderPort)    (OrderRepositoryPort)
        |                    |
        v                    v
  [payments.application]
        |
        v
  [payments.domain]
inner
```

Die lokale Schachtelung kann weiter die vorhandene Paket-/Klassenhierarchie
nutzen:

- Packages bleiben collapsible.
- Klassen bleiben selektierbar.
- Package-Children nutzen lokal weiterhin `localLevel`.
- Ports sind zusaetzlich auf der Port-Spur sichtbar.

Damit verliert der Benutzer nicht die bekannte Bedienung aus Architecture
View und Component View.

### Focused Segment

Bei vielen Segmenten sollte es einen Fokusmodus geben:

- ein Segment wird gross geoeffnet;
- alle anderen Segmente bleiben als Randkontext sichtbar;
- Kanten aus/in den Fokus bleiben sichtbar;
- uninteressante Kanten werden ausgeblendet oder stark abgeschwaecht.

Das ist wichtig, weil Kreisdiagramme bei vielen Kanten schnell unlesbar
werden. Der Fokusmodus ist kein Detail-Luxus, sondern eine Voraussetzung fuer
brauchbare Analyse.

## Kantenrouting

Die Abhaengigkeitsrichtung wird fachlich aus den Ringen bestimmt, nicht aus
Pixelpositionen.

```text
sourceRing > targetRing  => nach innen, normal
sourceRing = targetRing  => seitlich, pruefe Segment-/Portregel
sourceRing < targetRing  => nach aussen, potenzielle Violation
```

Visuell:

- normale Inward-Kanten sind duenn und laufen radial nach innen;
- Outward-Violations sind deutlich, z.B. rot/dashed/bold;
- Port-Bypass-Kanten docken bewusst nicht am Port an, sondern an der
  Implementation, damit die Umgehung sichtbar bleibt;
- Kanten zu collapsiblen Segmenten werden aggregiert;
- Hover auf einer aggregierten Kante zeigt Klassenpaare und Call-Count.

Die Richtung der Pfeile sollte immer aus Source/Target kommen:

```text
source class -> target class
```

Die Frage "ist das in Hexagonal erlaubt?" ist davon getrennt und wird ueber
Ring, Rolle und Port entschieden.

## Port-Semantik

### Inbound Port

Ein Inbound Port ist ein Vertrag, ueber den ein aeusserer Adapter die
Anwendung erreicht.

Beispiele:

- REST Controller -> UseCase Interface
- CLI Command -> Application Service Interface
- Message Handler -> Command Port

Erlaubt:

```text
adapter -> inbound port
inbound port -> application/core, falls der Port selbst delegiert
application/core intern nach innen
```

Problematisch:

```text
adapter -> application implementation direkt
adapter -> domain implementation direkt, wenn Port existiert
```

### Outbound Port

Ein Outbound Port ist ein Vertrag, den der innere Bereich definiert, damit
ein Adapter eine technische Abhaengigkeit kapselt.

Beispiele:

- Application Service -> Repository Port
- Domain Service -> Clock Port
- Core -> External Payment Gateway Port

Erlaubt:

```text
adapter implementation -> outbound port
application/core -> outbound port
```

Verboten:

```text
application/core -> adapter implementation
port -> adapter implementation
core -> framework class, wenn ein Port existiert
```

Wichtig: In vielen Java-Projekten sieht man bei Outbound Ports eine
Abhaengigkeit vom Adapter zur Port-Schnittstelle, weil der Adapter das
Interface implementiert. Das ist korrekt und laeuft im Dependency Graph
weiterhin nach innen.

### Generic Port

Ein Generic Port ist sinnvoll als Uebergangszustand:

- API-Klasse aus Component View wurde als Port-Kandidat erkannt;
- Richtung ist noch nicht gepflegt;
- View zeigt ihn als Port, aber Policy bleibt vorsichtig.

Fuer Generic Ports sollte die Sicht keine aggressiven Ausnahmen machen.
Sie koennen Warnungen reduzieren, aber nicht blind alle Beziehungen erlauben.

## API und Port optisch zusammenbringen

Die wichtigste Designentscheidung:

```text
API bleibt eine Eigenschaft.
Port wird eine sichtbare Rolle.
```

Eine Klasse kann API sein, ohne Port zu sein:

```text
Component API:
  public interface BillingApi

Hexagonal:
  noch kein Port, solange keine Richtung oder Grenze gepflegt ist
```

Eine Klasse kann Port sein und damit automatisch API-Kandidat sein:

```text
Port:
  CreateOrderUseCase, INBOUND

Component View:
  sollte als API angezeigt werden oder vorgeschlagen werden
```

Optisch bedeutet das:

- In der Component View bleibt die API-Box oben.
- In der Hexagonal View erscheinen dieselben Klassen als Port-Badges auf
  einer Ringgrenze.
- Der Tooltip oder Inspector zeigt beide Rollen:

```text
CreateOrderUseCase
Role: INBOUND_PORT
Component API: yes
Segment: order
Ring: application boundary
```

Damit wird die Verbindung zwischen beiden Sichten erkennbar, ohne die
Metaphern zu vermischen.

## Policy und Violations

Die Hexagonal View sollte eigene Violations berechnen, aber nicht die
Layered Violations ersetzen.

### Grundregel

```text
Eine Kante ist hexagonal sauber, wenn sie nach innen laeuft
oder ueber einen passenden Port eine Segment-/Ringgrenze passiert.
```

### Violation-Arten

| Kind | Bedeutung |
|---|---|
| `HEXAGON_OUTWARD_DEPENDENCY` | Ein inneres Element haengt von einem aeusseren Adapter oder Framework ab. |
| `HEXAGON_PORT_BYPASS` | Eine Kante passiert eine Segment- oder Ringgrenze, ohne den passenden Port zu nutzen. |
| `STYLE_UNCLASSIFIED` | Uebergangsfall, bis eine stabile Hexagonal-Klassifikation existiert. |

Optional spaeter:

```text
HEXAGON_SEGMENT_COUPLING
HEXAGON_GENERIC_PORT_DIRECTION_UNKNOWN
HEXAGON_PORT_IMPLEMENTATION_LEAK
```

Diese sollten erst eingefuehrt werden, wenn die UI und Tests zeigen, dass die
Unterscheidung fuer Benutzer einen echten Mehrwert hat.

### Bewertungsmatrix

| Beziehung | Bewertung |
|---|---|
| Adapter -> Inbound Port | erlaubt |
| Adapter -> Application Implementation | `HEXAGON_PORT_BYPASS`, wenn Port existiert |
| Adapter -> Core Implementation | `HEXAGON_PORT_BYPASS` oder `HEXAGON_OUTWARD_DEPENDENCY` je nach Richtung/Ring |
| Application/Core -> Outbound Port | erlaubt |
| Adapter Implementation -> Outbound Port | erlaubt |
| Application/Core -> Adapter Implementation | `HEXAGON_OUTWARD_DEPENDENCY` |
| Port -> Adapter Implementation | `HEXAGON_OUTWARD_DEPENDENCY` |
| Segment A -> Segment B Implementation | `HEXAGON_PORT_BYPASS`, wenn nicht ueber Port |
| Same Segment inward | erlaubt |
| Same Ring cross-segment | pruefen: Port oder explizite erlaubte Beziehung erforderlich |

Die bestehende Wrong-Direction-Logik bleibt parallel erhalten. Eine Kante
kann also gleichzeitig eine Layered Violation und eine Hexagonal Violation
sein. Das ist kein Widerspruch, sondern ein staerkerer Befund.

## Datenmodell-Vorschlag

Die Domain-Projektion sollte nicht auf JavaFX-Knoten basieren.

```java
public record HexagonalArchitecture(
        List<HexRing> rings,
        List<HexSegment> segments,
        List<HexPort> ports,
        List<HexElement> elements,
        List<Violation> violations,
        List<Tangle> tangles) implements Architecture {}

public record HexRing(
        String id,
        String label,
        int innerLevelInclusive,
        int outerLevelInclusive,
        HexRingRole role) {}

public enum HexRingRole {
    CORE,
    APPLICATION,
    ADAPTER,
    EXTERNAL
}

public record HexSegment(
        String id,
        String label,
        String rootFqn,
        boolean explicit) {}

public record HexPort(
        String id,
        String classFqn,
        String segmentId,
        String ringBoundaryId,
        ArchitectureAnnotations.PortDirection direction) {}

public record HexElement(
        String fqn,
        String segmentId,
        String ringId,
        ArchitectureAnnotations.ElementRole role,
        boolean componentApi) {}
```

Die wichtigsten Eigenschaften:

- Ring und Segment sind berechnete Projektion.
- Port kommt aus Annotation oder Vorschlagslogik.
- `componentApi` ist eine Eigenschaft, nicht automatisch Port-Wahrheit.
- `violations()` bleibt die fachliche Quelle fuer Renderer und Dependencies
  View.

## Segment- und Ring-Ableitung

### Segment-Ableitung

Erste Version:

```text
1. Explizite SegmentAnnotation, falls spaeter vorhanden.
2. Sonst ComponentSpec/rootPackageFqn.
3. Sonst Top-Level-Paket.
```

Konflikte:

- Eine Klasse darf im ersten Schritt nur einem Segment gehoeren.
- Ueberlappende Segmentroots werden abgelehnt oder der spezifischste Root
  gewinnt mit Warnung.
- Externe Bibliotheksklassen, falls sichtbar, bekommen ein eigenes Segment
  `external`.

### Ring-Ableitung

Erste Version:

```text
1. Explizite ElementRole CORE/ADAPTER gewinnt.
2. PortDirection platziert Port auf Boundary.
3. architectureLevel wird in wenige Ring-Buckets verdichtet.
4. Nicht klassifizierbare Elemente landen in Application.
```

Die Verdichtung sollte deterministisch sein und getestet werden:

```text
minLevel = 0
maxLevel = domain max architectureLevel

core        = unteres Quartil oder explizit CORE
application = mittlere Level
adapter     = oberes Quartil oder explizit ADAPTER
external    = technische/externe Gruppen, falls bekannt
```

Ob "niedrige Level innen" exakt der heutigen Schichteninterpretation
entspricht, muss mit echten Projekten validiert werden. Das Papier nimmt es
als Startannahme, weil die bisherige Semantik "Aufrufer stehen ueber
Aufgerufenen" radial bedeutet: Aufrufer weiter aussen, Aufgerufene weiter
innen.

## Bedienkonzept

### Context Menu

Auf Klassen, Paketen, Ports und Segmenten sollte es Hexagonal-spezifische
Aktionen geben:

```text
Mark as Core
Mark as Adapter
Mark as Inbound Port
Mark as Outbound Port
Mark as Generic Port
Remove Port
Clear Hexagonal Role
Assign to Segment...
Create Segment from Package
```

Diese Aktionen aendern nur `ArchitectureAnnotations`. Sie aendern keine
Dependency Edge und kein Level.

### Drag and Drop

Drag and Drop sollte analog zur Component View funktionieren, aber mit
radialer Bedeutung:

| Drag | Drop-Ziel | Wirkung |
|---|---|---|
| Klasse | Port-Spur | markiert Klasse als Port |
| Klasse | Core-Ring | setzt Rolle `CORE` |
| Klasse/Paket | Adapter-Ring | setzt Rolle `ADAPTER` |
| Paket | Segmentkopf | weist Paket einem Segment zu |
| Port | andere Port-Spur | aendert Richtung/Boundary |

Alle Drag-Aktionen muessen bestaetigungsarm, aber rueckgaengig machbar sein.
Mindestens sollte nach jeder Aktion der Projektzustand dirty werden und die
Persistenz dieselben Markierungen wiederherstellen.

### Inspector

Eine Seitenleiste ist fuer Hexagonal wichtiger als fuer die normale
Schichtenansicht, weil Rollen erklaerungsbeduerftig sind.

Der Inspector fuer ein Element sollte zeigen:

```text
FQN
Segment
Ring
architectureLevel
localLevel
Component API yes/no/source
Port yes/no/direction
Role source: explicit/heuristic
Incoming edges
Outgoing edges
Hexagonal violations
Layered violations
```

## Dependencies View

Wenn die Hexagonal View aktiv ist, sollte der Dependencies View einen neuen
ersten Abschnitt zeigen:

```text
Hexagonal Violations
  Outward dependencies
  Port bypasses

Wrong Direction Edges
Package Tangles
```

Reihenfolge wie bei Component View:

1. aktive Style-Violations ganz oben;
2. allgemeine Layered Wrong Direction Edges;
3. Package Tangles.

Klick auf eine Hexagonal Violation:

- waehlt Source und Target;
- oeffnet betroffene Segmente, falls kollabiert;
- hebt den genutzten oder fehlenden Port hervor;
- zeigt bei Port-Bypass den vorgeschlagenen Port-Kandidaten, falls vorhanden.

## Renderer-Prinzipien

### Keine Pixel-Semantik

Der Renderer darf nicht entscheiden, ob eine Kante nach innen oder aussen
laeuft. Er bekommt diese Information aus der Projektion:

```java
edge.directionInStyle()
edge.violationKind()
edge.visibleSourceEndpoint()
edge.visibleTargetEndpoint()
```

Pixelkoordinaten dienen nur dem Zeichnen.

### Stabile Andockpunkte

Ports, Klassen und Segmentaggregate brauchen stabile Anchor-Typen:

```text
RADIAL_INNER
RADIAL_OUTER
SEGMENT_LEFT
SEGMENT_RIGHT
PORT_SOCKET
SEGMENT_AGGREGATE
```

Eine Inward-Kante dockt typischerweise am aeusseren Rand des Targets an.
Eine Outward-Violation dockt bewusst gegen die normale Richtung an und
bekommt einen auffaelligen Stil.

### Kantenreduktion

Ohne Reduktion wird die Hexagonal View unlesbar. Erste Version:

- standardmaessig aggregierte Kanten zwischen Segmenten;
- Detailkanten erst bei Hover, Auswahl oder Fokussegment;
- Violations immer sichtbar, aber zusammenfassbar;
- Call-Count als Badge auf aggregierter Kante;
- Port-Bundling: mehrere Adapterkanten zu demselben Port werden gebuendelt.

## Optischer Vorschlag

Farben sollten Rollen unterscheiden, nicht nur einen Hue heller/dunkler
machen:

| Rolle | Visuelle Idee |
|---|---|
| Core | ruhiger heller Hintergrund, starke Mitte |
| Application | gruene oder tealartige Ringflaeche |
| Ports | warme Akzentfarbe, kleine Socket-/Badge-Form |
| Adapters | neutraler aeusserer Ring |
| Violations | klare Warnfarbe, dashed/bold |
| Selection | kontrastierende Kontur, keine Flaeche uebermalen |

Formen:

- Segmentkopf: kurzer Labelbogen oder kleines Header-Panel am Rand.
- Port: kleine "Socket"-Form auf der Ringgrenze, optional mit Richtungspfeil.
- Inbound Port: Pfeilspitze nach innen.
- Outbound Port: geteiltes Symbol, innen Vertrag, aussen Adapter-Andockpunkt.
- Collapsed Segment: kompakter Keil mit Badges.
- Expanded Segment: radialer Lane-Ausschnitt mit Paketboxen.

Die Sicht sollte nicht versuchen, jede Klasse permanent zu zeigen. Sie sollte
zuerst Architekturrollen sichtbar machen und Details bei Bedarf oeffnen.

## Minimaler Prototyp

Ein sinnvoller erster Prototyp sollte klein bleiben:

1. `HexagonalArchitectureBuilder` als reine Domain-Projektion.
2. Segmente aus Component Roots oder Top-Level-Paketen.
3. Drei Ringe: Core, Application, Adapter.
4. API-Klassen als Port-Kandidaten anzeigen.
5. Manuelle Port-Markierung per Context Menu.
6. Violations:
   - inner -> outer = `HEXAGON_OUTWARD_DEPENDENCY`;
   - cross-segment implementation target = `HEXAGON_PORT_BYPASS`.
7. JavaFX-View mit kollabierbaren Segmenten.
8. Dependencies View Abschnitt `Hexagonal Violations`.

Was der erste Prototyp bewusst nicht leisten muss:

- perfektes automatisches Erkennen von Adaptertypen;
- vollstaendige DDD-/Bounded-Context-Modellierung;
- automatische Interface-Implementation-Mapping fuer alle Faelle;
- alle Kanten gleichzeitig lesbar darstellen;
- beliebig viele Segmentebenen.

## Tests und Invarianten

Domain-nahe Tests zuerst:

- PortSpec zeigt auf existierende Klasse.
- Port-Klasse liegt in genau einem Segment.
- Explizite Rolle gewinnt vor Level-Bucket.
- API-Markierung erzeugt Port-Kandidat, aber nicht automatisch Port-Policy.
- Inner -> outer erzeugt `HEXAGON_OUTWARD_DEPENDENCY`.
- Adapter -> fremde Implementation erzeugt `HEXAGON_PORT_BYPASS`.
- Adapter -> Port ist erlaubt.
- Core/Application -> Outbound Port ist erlaubt.
- Core/Application -> Adapter Implementation ist verboten.
- `LevelCalculator` Output bleibt unveraendert.

UI-Tests spaeter:

- Segment collapse/expand aendert nur Sichtbarkeit, nicht Violations.
- Auswahl synchronisiert mit Outline/Quality/Dependencies.
- Klick auf Violation oeffnet relevante Segmente.
- Port bleibt selektierbar.
- Aggregierte Kante zeigt korrekte Count-Summe.

## Risiken

| Risiko | Auswirkung | Gegenmassnahme |
|---|---|---|
| API und Port werden gleichgesetzt | falsche erlaubte Beziehungen | API nur als Kandidat, Port braucht Richtung/Boundary |
| Renderer entscheidet Richtung aus Koordinaten | inkonsistente Befunde bei Layoutaenderungen | Richtung im Domain-Modell berechnen |
| Kreis wird bei vielen Kanten unlesbar | schoene, aber nutzlose Sicht | Aggregation, Fokusmodus, Violations priorisieren |
| Ring-Buckets wirken willkuerlich | Benutzer misstraut Sicht | Rollen sichtbar machen, Bucket-Regel im Inspector zeigen |
| Hexagonal View veraendert Level | Regression in Layered/Component View | Builder nur als Projektion auf DomainModel |
| zu viele neue Rollen im ersten Schritt | UI wird unbedienbar | Start mit Core, Adapter, Inbound Port, Outbound Port |
| Ports werden nicht persistent | Reload verliert Architekturwissen | `ArchitectureAnnotations.PortSpec` nutzen |

## Offene Fragen

1. Soll ein Segment primaer eine Komponente oder ein Paket sein?

   Empfehlung: Wenn Komponenten vorhanden sind, Komponenten verwenden.
   Sonst Top-Level-Pakete. Das passt zur Component View und verhindert, dass
   zwei Konzepte parallel konkurrieren.

2. Soll jede Component API automatisch ein Port sein?

   Empfehlung: Nein. Component APIs werden als Port-Kandidaten sichtbar.
   Erst eine Richtung macht daraus einen Port. Ausnahme: Eine UI-Option kann
   "Create Ports from Component APIs" anbieten.

3. Wie werden Outbound Port Implementierungen erkannt?

   Empfehlung: Im ersten Schritt nicht vollautomatisch erzwingen.
   Dependency/Inheritance-Informationen koennen Kandidaten liefern, aber die
   fachliche Wahrheit sollte ueber Annotationen bestaetigt werden.

4. Wie viele Ringe sind sinnvoll?

   Empfehlung: Drei Ringe fuer den Prototyp. Mehr Ringe erst, wenn echte
   Beispiele zeigen, dass sie lesbarer sind.

5. Soll die Sicht ein echtes Hexagon bleiben?

   Empfehlung: Nein. Die Semantik ist hexagonal, die Geometrie darf Kreis
   oder N-Gon sein. Exakt sechs Ecken waeren bei echten Projekten zu starr.

## Umsetzungsvorschlag

### Phase 1: Domain-Projektion

- `HexagonalArchitecture` einfuehren.
- `HexagonalArchitectureBuilder` implementieren.
- Segmentableitung aus Komponenten oder Top-Level-Paketen.
- Ringableitung aus Rollen und verdichteten `architectureLevel`.
- Port-Kandidaten aus Component API und Naming-Heuristik ableiten.
- Violations domain-nah testen.

### Phase 2: Annotation Commands

- Context Menu Commands fuer Port/Rollen.
- Persistenz ueber vorhandene `ArchitectureAnnotations`.
- Projekt dirty setzen und View neu bauen.
- Keine UI-Geometrie-Spezialfaelle in der Persistenz speichern.

### Phase 3: Erste Hexagonal View

- Eigenes WFX-Modul oder eigene WFX-View-Klasse.
- Kreis-/Vieleckdarstellung mit Segmenten.
- Collapsed/expanded Segmente.
- Port-Badges auf Ringgrenzen.
- Auswahl- und Hover-Integration.

### Phase 4: Edges und Dependencies

- Aggregierte Kanten zwischen Segmenten.
- Detailkanten bei Fokus/Selektion.
- `Hexagonal Violations` im Dependencies View.
- Klick auf Befund oeffnet Segmente und hebt Port/Bypass hervor.

### Phase 5: Usability-Ausbau

- Fokusmodus fuer ein Segment.
- Filter: only violations, only ports, only selected segment.
- Inspector fuer Rollen, Port-Richtung und Quellen.
- Optionaler Assistent "Create Ports from Component APIs".

## Empfehlung

Die beste Richtung ist eine eigenstaendige Hexagonal Architecture View als
radiale Port-Projektion:

```text
S202-Level bleiben die Wahrheit fuer innen/aussen.
Segmente geben fachlichen Kontext.
Ports sind API-Flaechen mit Richtung.
Violations entstehen im HexagonalArchitectureBuilder.
Renderer zeichnet nur die Projektion.
```

So entsteht eine attraktive Visualisierung, ohne die bestehende
Schichtenlogik oder Component View zu gefaehrden. Die API-Logik aus der
Component View wird nicht weggeworfen, sondern aufgewertet: APIs werden zu
Port-Kandidaten, und Ports bringen die Richtung und die hexagonale Grenze in
die Darstellung.

Der Prototyp sollte nicht versuchen, eine perfekte Hexagonal-Theorie fuer
alle Java-Projekte zu bauen. Er sollte zuerst drei Dinge sehr gut koennen:

1. Innen/aussen ueber die bestehende Level-Semantik sichtbar machen.
2. Ports als klare Andockpunkte auf Segmentgrenzen zeigen.
3. Outward Dependencies und Port Bypasses verlaesslich markieren.
