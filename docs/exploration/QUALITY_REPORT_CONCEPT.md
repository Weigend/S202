# Konzeptpapier: QualityReport

Stand: 2026-06-07

## Kurzfassung

S202 braucht einen automatisch erzeugten HTML-Report, der ohne laufendes Tool
lesbar ist und die wichtigsten Qualitaetseinschaetzungen einer Codebase
managementtauglich zusammenfasst. Der Report soll keine neue
Architekturanalyse erfinden, sondern vorhandene Domain- und
Architecture-Projektionen verdichten:

- statistischer Ueberblick ueber Groesse, Groessenverteilung, Art und
  Technologie der Codebase;
- Qualitaetsuebersicht aus der bestehenden Quality View, erweitert um
  Verletzungen, Paketzyklen und Klassenzyklen;
- alle relevanten Bilder, die strukturelle Befunde sichtbar machen;
- automatisch generierte Scope-Bilder fuer konkrete Problemstellen:
  Pakethierarchie-Verletzungen, Paketzyklen, Klassenzyklen;
- Komponentenverletzungen nur dann, wenn eine wahrscheinliche
  Komponentenarchitektur vorliegt;
- keine hexagonale Sicht im Report.

Ganz am Anfang des HTML-Reports steht eine Management-Bewertung als
`Quality Score x/100`. Dieser Score verdichtet Projektgroesse,
Groessenverteilung und strukturelle Anomalien in eine Zahl, ein Label und
kurze generische Textbausteine fuer Treiber und empfohlene Fokusthemen.

Der Report ist damit kein Screenshot-Dump. Er ist eine kuratierte
Export-Projektion: Kennzahlen, Befunde, Bilder und kurze Interpretation
werden aus einem stabilen `QualityReportModel` erzeugt und anschliessend als
statisches HTML mit Assets exportiert.

## Ziel

`QualityReport` soll die Frage beantworten:

> Was sind die wichtigsten strukturellen Qualitaetsbefunde dieser Codebase,
> und wo sieht man sie?

Die Primaerzielgruppe sind technische Entscheider, Architekturverantwortliche
und Management-nahe Stakeholder. Der Report muss deshalb:

- schnell erfassbar sein;
- belastbare Zahlen vor Einzelbeispiele stellen;
- Befunde priorisieren;
- visuelle Evidenz enthalten;
- ohne S202-Installation lesbar bleiben;
- genug Details enthalten, damit ein Entwicklungsteam die Problemstellen
  wiederfindet.

Die Detailnavigation im Tool bleibt wertvoll, ist aber nicht Voraussetzung zum
Verstehen des Reports.

## Nicht-Ziele

- Kein Ersatz fuer die interaktive S202-Analyse.
- Kein Refactoring-Plan mit konkreten Code-Aenderungen.
- Kein PDF-Renderer als erster Schritt. HTML ist das kanonische Format; PDF
  kann spaeter ueber Browser-Print oder eine optionale Exportstufe entstehen.
- Keine Integration der hexagonalen Sicht.
- Keine Komponentenverletzungen, wenn die Codebase keine belastbare
  Komponentenstruktur erkennen laesst.
- Keine UI-spezifische Neuberechnung von Violations. Die fachliche Quelle
  bleibt das Domain-/Architecture-Modell.

## Einordnung in S202

Empfohlen wird eine eigene Komponente:

```text
de.weigend.s202.report.quality
```

mit einer Komponentenmarkierung:

```java
@S202Component(displayName = "Quality Report")
```

Die Komponente konsumiert Analyseergebnisse, erzeugt aber keine neue
Architektursemantik:

```text
reader.DependencyModel
domain.DomainModel
domain.architecture.LayeredArchitecture
domain.architecture.ComponentArchitecture    optional
analysis.quality.QualityMetrics
analysis.invariants.LayoutInvariantReport
ui export renderers / snapshot renderers       nur fuer Bilder

        -> QualityReportModel
        -> QualityReportHtmlWriter
        -> report.html + assets/
```

Wichtig ist die Richtung: `QualityReport` darf `reader`, `domain` und
`analysis` lesen. Bildexport kann eine schmale UI-nahe Adapter-Schicht
verwenden. Die Report-Kennzahlen duerfen nicht aus Pixeln, Scene-Koordinaten
oder sichtbaren JavaFX-Nodes rekonstruiert werden.

## Report-Aufbau

Der HTML-Report sollte in dieser Reihenfolge aufgebaut sein:

1. Overall Quality Score
2. Executive Summary
3. Codebase Profile
4. Quality Overview
5. Architecture Findings
6. Cycle Findings
7. Component Findings, nur wenn belastbar
8. Evidence Gallery
9. Appendix mit vollstaendigen Tabellen

### 1. Overall Quality Score

Der erste Inhaltsblock zeigt eine Zahl `x/100`, ein generisches Label und
kurze Textbausteine:

- Groessen-Druck: Klassenanzahl und Dependency-Dichte;
- Verteilungs-Druck: dominierende Packages, Paket-Imbalance,
  uebergrosse Packages und Architekturlevel-Tiefe;
- Anomalie-Druck: Fat/Tangled, Layering-Verletzungen, Paketzyklen,
  Klassenzyklen und, falls belastbar, Komponentenverletzungen.

Die Bewertung ist ein Management-Indikator, keine mathematische Wahrheit. Sie
priorisiert Aufmerksamkeit und sollte ueber mehrere Exporte hinweg als Trend
beobachtet werden.

### 2. Executive Summary

Die erste Seite beantwortet in maximal einer Bildschirmhoehe:

- Name oder Quelle der Analyse;
- Analysezeitpunkt;
- Groesse der Codebase;
- erkannte Technologie;
- Gesamtqualitaet als Ampel oder Score;
- Anzahl kritischer Befunde;
- Top 3 Risiken;
- Top 3 Hotspots;
- Links zu den wichtigsten Bildern.

Beispielhafte Karten:

| Karte | Inhalt |
|---|---|
| Size | Klassen, Packages, Module, Dependencies |
| Shape | max. Architekturlevel, groesste Packages, Verteilung |
| Quality | Fat, Tangled, zyklische Dependency-Anteile |
| Violations | Layering, Package-Hierarchy, Component API Bypass |
| Cycles | Paketzyklen, Klassenzyklen, groesste SCC |

Die Formulierung bleibt vorsichtig. Der Report sagt nicht "schlechter Code",
sondern "hohe Kopplung", "zyklische Abhaengigkeiten", "verletzte
Architekturhypothese" oder "Komponentengrenzen werden umgangen".

### 2. Codebase Profile

Dieser Abschnitt beschreibt Art und Technologie der Codebase:

- Eingabetyp: JAR, Maven-Projekt, Gradle-Projekt, Python-Quellen,
  C-Quellen, gemischte Source-Sets;
- erkannte Sprachen und Reader;
- Build-/Modulhinweise: Maven, Gradle, JPMS-Module;
- Annotationen: `@S202Component`, `@S202Api`, `@S202Package`;
- Anzahl Klassen/Typen, Packages, Module;
- Anzahl Dependencies nach `EdgeKind`: `EXTENDS`, `IMPLEMENTS`, `IMPORTS`,
  `INSTANTIATES`, `CALLS`, `USES`;
- Anteil Interfaces zu Klassen;
- Anteil API-markierter oder exportierter Packages.

Technologieerkennung sollte als eigene Report-Datenklasse modelliert werden,
nicht als Textheuristik im HTML-Template:

```java
public record TechnologyProfile(
        List<String> languages,
        List<String> readers,
        List<String> buildSystems,
        int moduleCount,
        int exportedPackageCount,
        int componentAnnotatedPackageCount,
        int apiAnnotatedPackageCount) {}
```

Die erste Implementierung kann konservativ starten:

- `Source.kind` und Reader-Aufruf liefern den primaeren Input-Typ;
- Dateiendungen und Projektdateien liefern Build-System-Hinweise;
- `DependencyModel.getAllModules()` liefert JPMS;
- `DependencyModel.getComponentAnnotatedPackages()` und
  `getApiAnnotatedPackages()` liefern S202-spezifische Architekturhinweise.

### 3. Groessenverteilung

Die Groessenverteilung soll zeigen, ob die Codebase wenige dominante Bereiche
oder viele vergleichbare Teile hat:

- Klassen pro Package;
- Dependencies pro Package;
- eingehende und ausgehende Package-Dependencies;
- groesste Packages;
- groesste Klassen nach ausgehenden Dependencies;
- Packages nach Architekturlevel;
- Packages nach Tangle-/Violation-Anteil.

Geeignete Visualisierungen im HTML:

- horizontales Balkendiagramm "Top 10 Packages by Classes";
- horizontales Balkendiagramm "Top 10 Packages by Dependencies";
- Histogramm der Klassen pro Package;
- Histogramm der Architekturlevel;
- kleine Tabelle "Largest hotspots".

SVG ist fuer diese einfachen Diagramme ideal, weil es im HTML selbst
eingebettet werden kann. JavaFX-Snapshots werden nur fuer Architektur- und
Scope-Bilder benoetigt.

### 4. Quality Overview

Die bestehende Quality View liefert `fat` und `tangled`. Der Report sollte
diese Sicht aufnehmen, aber ergaenzen:

| Metrik | Bedeutung |
|---|---|
| Fat | durchschnittliche interne Dependencies pro Klasse, normalisiert |
| Tangled | Anteil der Dependencies, die in Klassenzyklen liegen |
| Class count | Anzahl analysierter Klassen |
| Dependency count | interne Klassen-Dependencies |
| Intra-SCC dependency count | Dependencies innerhalb zyklischer SCCs |
| Package tangle count | Anzahl Paketzyklen |
| Class tangle count | Anzahl Klassen-SCCs mit mehr als einem Element |
| Layered violation count | Verletzungen der Schichtenhypothese |
| Package hierarchy violation count | aggregierte UPWARD-Verletzungen auf Package-Ebene |

Die Report-Version der Quality View sollte als statisches Bild oder als
SVG/Canvas-nahes HTML erzeugt werden. Fuer Management-Lesbarkeit reicht eine
2D-Grafik mit Marker plus Kurztext:

```text
Die Codebase liegt im Bereich hoher zyklischer Kopplung:
fat=0.42, tangled=0.67. 1.240 von 1.850 internen Dependencies liegen in
Klassenzyklen.
```

Diese Texte muessen aus Schwellenwerten kommen, nicht frei im Template
formuliert werden. Beispiel:

```java
public enum QualityBand {
    HEALTHY,
    WATCH,
    RISK,
    CRITICAL
}
```

## Architecture Findings

Dieser Abschnitt behandelt die Schichten- und Pakethierarchie, weil sie fuer
jede analysierte Codebase vorhanden ist.

### Layered Violations

Quelle ist `LayeredArchitecture.violations()` bzw. allgemein
`Architecture.violations()` mit `ViolationKind.UPWARD`.

Der Report zeigt:

- Anzahl UPWARD-Verletzungen;
- Anzahl betroffener Source-Packages;
- Anzahl betroffener Target-Packages;
- Top-Verletzungen nach Kantenanzahl;
- Top-Verletzungen nach beteiligten Klassen;
- Bild der Gesamtsicht mit Violation-Overlay;
- Scope-Bilder der wichtigsten Verletzungscluster.

Die Aggregation sollte stabil und reproduzierbar sein:

```text
source package -> target package
source component/root package -> target component/root package
source architecture level -> target architecture level
```

### Pakethierarchie-Verletzungen

"Pakethierarchie-Verletzung" bezeichnet im Report eine aggregierte
Schichtenverletzung, bei der eine Klasse aus einem tieferen Packagebereich
auf eine Klasse in einem hoeheren oder fremden Packagebereich zugreift und
damit die aus der Paketstruktur abgeleitete Architekturhypothese verletzt.

Der Report sollte nicht jede Klassenkante prominent zeigen. Er zeigt zuerst
Cluster:

| Rang | Source Scope | Target Scope | Violations | Back edges | Beispiel |
|---|---|---:|---:|---|
| 1 | `ui.wfx` | `domain.impl` | 42 | 3 | `S202Module -> LevelCalculator` |

Zu jedem Top-Cluster wird ein Scope-Bild erzeugt:

```text
assets/scopes/package-violation-001.png
assets/scopes/package-violation-002.png
...
```

Das Bild zeigt nur:

- kleinsten gemeinsamen sichtbaren Kontext;
- Source- und Target-Package;
- direkte Nachbarschaft;
- verletzende Kanten farblich hervorgehoben;
- optional graue Kontextkanten.

Damit entstehen Report-Bilder, die ein Management-Leser verstehen kann, ohne
die komplette Codebase zu inspizieren.

## Cycle Findings

Zyklen sind ein eigener Report-Schwerpunkt, getrennt von Schichtenviolations.

### Paketzyklen

Quelle:

- `DomainModel.getPackageTangles()`;
- `Architecture.tangles()`;
- `DomainModel.getPackageEdgeWeights()`;
- `DomainModel.isPackageBackEdge(from, to)`.

Der Report zeigt:

- Anzahl Paketzyklen;
- groesster Paketzyklus nach Member-Anzahl;
- groesster Paketzyklus nach internen Kanten/Weight;
- Top 5 Paketzyklen als Tabelle;
- je Top-Zyklus ein Scope-Bild.

Scope-Bild fuer Paketzyklen:

```text
assets/scopes/package-cycle-001.png
```

Das Bild zeigt:

- alle Pakete der SCC;
- gewichtete Kanten innerhalb der SCC;
- Back-Edges oder Cut-Kandidaten gesondert markiert;
- optional angrenzende ein- und ausgehende Packages als Kontext.

### Klassenzyklen

Quelle:

- `SCCFinder.findSCCs(classGraph)`;
- `QualityMetrics` fuer globale Zyklusanteile;
- `TopTanglesModule.computeTopTangles(...)` als inhaltlicher Vorlaeufer,
  aber die Berechnung sollte fuer den Report in eine analysis-nahe Klasse
  extrahiert werden.

Der Report zeigt:

- Anzahl Klassen-SCCs mit Groesse > 1;
- groesste Klassenzyklen;
- Klassenzyklen mit den meisten Method-Call-Kanten;
- Hotspot-Klassen, die in mehreren oder besonders grossen Zyklen liegen;
- je Top-Klassenzyklus ein Scope-Bild.

Scope-Bild fuer Klassenzyklen:

```text
assets/scopes/class-cycle-001.png
```

Das Bild zeigt:

- Klassen der SCC;
- Kanten innerhalb der SCC;
- Kantentypen, wenn vorhanden (`CALLS`, `USES`, `INSTANTIATES`, ...);
- Cut-/Break-Kandidaten aus dem bestehenden Tangle-Workflow, falls vorhanden.

Wichtig: Klassenzyklen koennen sehr gross sein. Bilder muessen deshalb
automatisch begrenzen:

- bis 25 Klassen: vollstaendig zeigen;
- 26 bis 80 Klassen: Top-Knoten nach interner Degree/Call-Weight zeigen,
  Rest als zusammengefassten Kontextknoten;
- ueber 80 Klassen: kein unlesbares Vollbild, sondern Hotspot-Teilbilder plus
  Tabelle.

## Component Findings

Komponentenverletzungen werden nur gezeigt, wenn eine wahrscheinliche
Komponentenarchitektur vorliegt.

### Wann ist eine Komponentenarchitektur wahrscheinlich?

Eine `ComponentArchitecture` gilt fuer den Report als belastbar, wenn mehrere
Signale zusammenkommen. Empfohlen ist ein expliziter Confidence-Wert:

```java
public record ComponentArchitectureConfidence(
        boolean reportable,
        double score,
        List<String> evidence,
        List<String> warnings) {}
```

Positive Signale:

- mindestens zwei Component Roots;
- explizite `@S202Component`-Pakete;
- JPMS exports;
- API-/impl-Paketmuster (`api`, `spi`, `impl`, `internal`);
- Interfaces oder API-Klassen am Komponentenrand;
- relevante externe Zugriffe auf diese API;
- nicht nur ein einzelnes triviales Root-Package.

Negative Signale:

- nur eine erkannte Komponente;
- alle Komponenten ohne API-Surface;
- Komponenten entstehen nur aus sehr flachen Top-Level-Packages ohne weitere
  Hinweise;
- fast alle Klassen liegen ausserhalb erkannter Komponenten;
- erkannte Komponenten sind nur technische Sammelbereiche wie `util`,
  `common`, `shared`.

Vorschlag fuer die erste Regel:

```text
reportable =
  componentCount >= 2
  and apiClassCount >= 2
  and coveredClassRatio >= 0.35
  and (
       explicitComponentAnnotations > 0
    or exportedPackageCount > 0
    or apiImplPatternCount >= 2
  )
```

Diese Regel ist absichtlich konservativ. Ein ausgelassener Komponentenabschnitt
ist besser als ein Report, der eine zufaellige Top-Level-Paketstruktur als
Architektur verkauft.

### Inhalt des Komponentenabschnitts

Wenn reportable:

- erkannte Komponenten mit API-/Implementation-Anteil;
- Abdeckung: Klassen innerhalb erkannter Komponenten vs. ausserhalb;
- Anzahl `COMPONENT_API_BYPASS`;
- Anzahl `COMPONENT_API_LEAKS_IMPLEMENTATION`;
- Top-Komponentenverletzungen als Tabelle;
- Komponenten-Uebersichtsbild;
- Scope-Bilder der wichtigsten API-Bypass-Befunde.

Wenn nicht reportable:

- kein Findings-Abschnitt;
- optional ein kurzer Appendix-Hinweis:
  "Keine belastbare Komponentenarchitektur erkannt; Komponentenbefunde wurden
  nicht bewertet."

## Evidence Gallery

Die Evidence Gallery sammelt alle Bilder mit klaren Captions. Jedes Bild muss
eine Frage beantworten:

| Bildtyp | Frage |
|---|---|
| Overview architecture | Wie sieht die Codebase als Schichtenmodell aus? |
| Quality plot | Wo liegt die Codebase auf Fat/Tangled? |
| Violation overview | Wo brechen Dependencies die Schichtenhypothese? |
| Package hierarchy scope | Welche Packagebereiche verletzen sich konkret? |
| Package cycle scope | Welche Packages bilden einen Zyklus? |
| Class cycle scope | Welche Klassen halten einen Zyklus zusammen? |
| Component overview | Welche Komponenten/API-Grenzen wurden erkannt? |
| Component violation scope | Wo wird eine Komponenten-API umgangen? |

Jede Caption sollte maschinenlesbare Fakten enthalten:

```text
Package cycle #2: 6 packages, 18 internal package edges, 143 class dependencies.
Back-edge candidate: domain.impl -> ui.model.
```

## Bildgenerierung

Es gibt zwei Arten von Bildern:

1. einfache Report-Diagramme;
2. Architektur-/Scope-Bilder.

### Einfache Report-Diagramme

Diese sollten ohne JavaFX erzeugt werden:

- Balkendiagramme;
- Histogramme;
- Donuts fuer Dependency-Kinds;
- Quality Plot, wenn nicht aus der bestehenden View per Snapshot exportiert.

Technisch reicht ein kleiner SVG-Writer:

```text
QualityReportCharts
  -> SvgBarChart
  -> SvgHistogram
  -> SvgQualityPlot
```

Das Ergebnis kann inline im HTML stehen. Dadurch bleibt der Report portabel.

### Architektur- und Scope-Bilder

Architekturbilder koennen auf bestehenden JavaFX-Views aufbauen, sollten aber
ueber eine explizite Export-Schnittstelle laufen:

```java
public interface ReportImageRenderer {
    ReportImage render(ReportImageRequest request);
}

public record ReportImageRequest(
        ReportImageKind kind,
        Set<String> includedElements,
        Set<DependencyEdge> highlightedEdges,
        ArchitectureKind architectureKind,
        String caption,
        int width,
        int height) {}
```

Der Renderer muss deterministisch arbeiten:

- feste Groesse;
- feste Zoom-/Fit-Strategie;
- keine Abhaengigkeit vom aktuell selektierten UI-Zustand;
- keine offenen Animationen;
- Layout-Pulse abwarten;
- PNG-Datei schreiben;
- Metadaten an das Report-Modell zurueckgeben.

Scope-Bilder sollten nicht als manuelles "open view and screenshot" entstehen.
Sie sind generierte Views:

```text
QualityReportScopeSelector
  -> waehlt relevante Elemente und Kanten
QualityReportScopeRenderer
  -> baut temporaere ArchitectureView/ScopeView
  -> rendert nur diesen Ausschnitt
  -> exportiert PNG
```

## Report-Datenmodell

Das zentrale Modell sollte serialisierbar und testbar sein:

```java
public record QualityReportModel(
        ReportMetadata metadata,
        CodebaseProfile codebase,
        SizeDistribution sizeDistribution,
        QualitySummary quality,
        List<ArchitectureFinding> architectureFindings,
        List<CycleFinding> packageCycles,
        List<CycleFinding> classCycles,
        Optional<ComponentFindings> componentFindings,
        List<ReportImage> images,
        List<AppendixTable> appendix) {}
```

Beispielhafte Detailtypen:

```java
public record ReportMetadata(
        String title,
        String analyzedAt,
        List<String> sourcePaths,
        String s202Version) {}

public record QualitySummary(
        double fat,
        double tangled,
        QualityBand band,
        int classCount,
        int dependencyCount,
        int intraSccDependencyCount,
        int layeredViolationCount,
        int packageCycleCount,
        int classCycleCount) {}

public record ReportImage(
        String id,
        ReportImageKind kind,
        String path,
        String caption,
        List<String> relatedFindingIds) {}
```

Der HTML-Writer bekommt nur dieses Modell. Er darf nicht direkt auf
`DomainModel`, `ArchitectureView` oder `DependencyModel` zugreifen.

## Findings und Priorisierung

Der Report braucht eine stabile Priorisierung, damit die wichtigsten
Problemstellen automatisch oben stehen.

### Violation-Prioritaet

```text
score =
  violationCount * 10
  + distinctSourceClasses * 3
  + distinctTargetClasses * 3
  + backEdgeCount * 5
  + callEdgeWeight
```

Die genaue Formel ist weniger wichtig als die Stabilitaet:

- gleiche Eingabe erzeugt gleiche Reihenfolge;
- grosse Cluster stehen vor Einzelkanten;
- Method-Call-Informationen erhoehen Relevanz;
- Back-Edges und zyklische Kanten erhoehen Relevanz.

### Cycle-Prioritaet

```text
score =
  memberCount * 10
  + internalEdgeCount * 5
  + methodCallWeight
  + externalFanIn
  + externalFanOut
```

Paketzyklen und Klassenzyklen bekommen getrennte Rankings, weil sie
unterschiedliche Management-Aussagen tragen.

## HTML-Struktur

Der Export sollte ein einzelnes `index.html` plus `assets/` erzeugen:

```text
quality-report/
  index.html
  assets/
    report.css
    overview-layered.png
    quality.svg
    scopes/
      package-violation-001.png
      package-cycle-001.png
      class-cycle-001.png
      component-violation-001.png
```

Alternativ kann CSS inline eingebettet werden. Bilder sollten als Dateien
liegen, damit der Report nicht zu einem schwer handhabbaren HTML-Monolithen
wird.

Das Layout sollte ruhig und dicht sein:

- keine Marketing-Hero-Seite;
- klare Tabellen;
- wenige Farben;
- Ampelfarben nur fuer Bewertung;
- Captions direkt unter Bildern;
- Appendix einklappbar oder am Ende;
- druckbar mit `@media print`.

## Bedienung

Zwei Einstiege sind sinnvoll:

1. UI-Menue: `File -> Export Quality Report...`
2. CLI oder Headless-Command fuer Automatisierung

Beispiel CLI:

```text
s202 report --input path/to/project --output build/reports/s202-quality
```

Die UI-Variante kann auf der aktuellen Analyse arbeiten. Die CLI-Variante
muss die Analyse laden oder neu ausfuehren koennen.

## Fehlerfaelle

Der Report muss unvollstaendige Daten sichtbar behandeln:

| Fall | Verhalten |
|---|---|
| keine Klassen | Report mit Fehlerstatus und Quelle |
| keine Dependencies | Quality zeigt "keine internen Dependencies" |
| keine Packagezyklen | Abschnitt kurz als "keine Paketzyklen gefunden" |
| keine Klassenzyklen | Abschnitt kurz als "keine Klassenzyklen gefunden" |
| Bildrendering fehlgeschlagen | Finding bleibt, Bildplatz zeigt Fehlercaption |
| keine Komponentenarchitektur | Komponentenabschnitt wird ausgelassen |
| sehr grosse SCC | Teilbilder + Tabelle statt unlesbarer Gesamtgrafik |

## Tests

Die Report-Komponente braucht vor allem Modell- und Snapshot-Tests:

- `QualityReportModelBuilderTest`
  - Klassen, Packages, Dependencies, Module korrekt gezaehlt;
  - Dependency-Kinds korrekt aggregiert;
  - Fat/Tangled aus `QualityMetrics` uebernommen;
  - Violations aus `Architecture.violations()` uebernommen.

- `ComponentArchitectureConfidenceTest`
  - explizite Komponenten werden reportable;
  - reine Top-Level-Package-Struktur ohne API wird nicht reportable;
  - eine einzelne Komponente wird nicht reportable;
  - API-/impl-Muster plus mehrere Roots wird reportable.

- `CycleFindingRankerTest`
  - groessere und dichtere Zyklen werden hoeher priorisiert;
  - Sortierung ist stabil.

- `QualityReportHtmlWriterTest`
  - HTML enthaelt alle Kernabschnitte;
  - keine rohen FQNs ungeescaped;
  - fehlende Bilder erzeugen keinen kaputten Report.

- `ReportImageRendererTest`
  - PNG-Dateien werden erzeugt;
  - leere Scope-Anfragen werden abgelehnt;
  - grosse SCCs werden begrenzt.

## Umsetzungsvorschlag

### Phase 1: Report-Modell ohne Bilder

- Paket `de.weigend.s202.report.quality` anlegen.
- `QualityReportModel` und Builder implementieren.
- Codebase Profile, Size Distribution und Quality Summary berechnen.
- UPWARD-Violations und Tangles als Tabellen exportieren.
- HTML-Writer mit statischem CSS erzeugen.
- UI-Menuepunkt oder einfacher interner Command zum Export.

Ergebnis: ein lesbarer HTML-Report ohne Architekturbilder.

### Phase 2: Diagramme und Quality Plot

- SVG-Writer fuer Balkendiagramme, Histogramme und Quality Plot.
- Dependency-Kind-Verteilung aufnehmen.
- Groessenverteilung visualisieren.
- Executive Summary um Ampel/Band ergaenzen.

Ergebnis: managementtauglicher Report mit statistischer Uebersicht.

### Phase 3: Scope-Bilder fuer Violations und Zyklen

- Scope-Auswahl fuer Top-Pakethierarchie-Verletzungen.
- Scope-Auswahl fuer Top-Paketzyklen.
- Scope-Auswahl fuer Top-Klassenzyklen.
- deterministischen JavaFX/PNG-Renderer kapseln.
- Bilder in `assets/scopes/` integrieren.

Ergebnis: Report zeigt konkrete Problemstellen visuell.

### Phase 4: Komponentenbefunde mit Confidence Gate

- `ComponentArchitectureConfidence` implementieren.
- `ComponentArchitecture` nur bei reportable Confidence in den Report aufnehmen.
- Komponenten-Uebersicht und API-Bypass-Findings aufnehmen.
- Scope-Bilder fuer Top-Komponentenverletzungen erzeugen.

Ergebnis: Komponentenverletzungen erscheinen nur bei belastbarer
Komponentenhypothese.

### Phase 5: Automatisierung und Regression

- CLI-Export.
- Golden-HTML-Test fuer `test-example`.
- optionaler Report-Export in CI.
- Dokumentation im User Guide.

## Offene Entscheidungen

- Soll `QualityReport` im `analyzer`-Modul bleiben oder langfristig ein
  eigenes Maven-Modul werden?
- Soll der HTML-Report CSS/Bilder extern halten oder als Single-File-HTML
  exportierbar sein?
- Welche Schwellenwerte definieren `QualityBand`?
- Soll der Report vorhandene What-if-Cuts beruecksichtigen oder immer den
  Ist-Zustand der Codebase bewerten?
- Welche maximale Bildanzahl ist sinnvoll? Vorschlag: 1 Overview,
  1 Quality Plot, bis 5 Violation-Scopes, bis 5 Package-Cycle-Scopes,
  bis 5 Class-Cycle-Scopes, bis 5 Component-Scopes.
- Soll ein Report auch aus gespeicherten `.s202`-Projektdateien erzeugt
  werden koennen?

## Empfehlung

`QualityReport` sollte als neues, eigenstaendiges Report-Modell beginnen und
nicht als Erweiterung der bestehenden Views. Die Views koennen Bilder liefern,
aber die Report-Aussagen muessen aus Domain-, Analysis- und
Architecture-Daten entstehen.

Die wichtigste Designregel lautet:

> Der Report zeigt nur das, was das Modell fachlich belegen kann.

Darum gehoeren Komponentenbefunde hinter ein Confidence Gate, die hexagonale
Sicht bleibt draussen, und Scope-Bilder werden gezielt aus priorisierten
Findings erzeugt statt als zufaellige Screenshots der aktuellen UI.
