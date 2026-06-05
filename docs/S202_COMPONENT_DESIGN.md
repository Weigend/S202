# S202 Component Design

Sammlung von Entkopplungs- und Komponentendesign-Entscheidungen für S202.
Dieses Dokument wächst mit neuen Erkenntnissen.

---

## 1. `reader` als Komponente mit versteckter Implementierung

### Problem

`S202Module` kennt alle Interna des `reader`-Pakets:

```
de.weigend.s202.reader.java.InputAnalyzer
de.weigend.s202.reader.java.MavenProjectScanner
de.weigend.s202.reader.java.GradleProjectScanner
de.weigend.s202.reader.python.PythonSourceAnalyzer
de.weigend.s202.reader.c.CSourceAnalyzer
```

Eine neue Sprache erfordert heute eine Änderung in `S202Module`.

### Ziel

`reader` wird eine Komponente. Die UI sieht nur noch die Komponenten-API:

```
de.weigend.s202.reader.LanguageAnalyzer    ← Interface (API)
de.weigend.s202.reader.AnalyzerRegistry   ← Einstiegspunkt (API)
```

Alles unter `reader.java.*`, `reader.python.*`, `reader.c.*` ist
Implementierungsdetail der Komponente. Die Registrierung der konkreten
Analyzer passiert intern — per SPI oder statischer Registry, das ist
eine Implementierungsentscheidung innerhalb des `reader`-Moduls.

### Interface

```java
public interface LanguageAnalyzer {
    String displayName();
    DependencyModel analyze(List<Path> inputs) throws IOException;
}
```

`InputAnalyzer` konvertiert intern `path.toString()`. Python und C nehmen
`Path` ohnehin schon. Der Progress-Callback bleibt als optionaler Overload
auf `InputAnalyzer` — er ist ein UI-Concern, kein Teil des Analyzer-Contracts.

### UI-seitiger FileLoader

Die UI weiß, wie sie Input für bekannte Analyzer beschafft:

```java
interface FileLoader {
    String menuLabel();
    List<Path> chooseInput(Window owner);
}
```

Bekannte Analyzer (`JarFileLoader`, `MavenProjectLoader`,
`GradleProjectLoader`) bekommen dedizierte `FileLoader`-Implementierungen.
Neue Sprachen, die per SPI reinkommen, bekommen automatisch einen
`GenericDirectoryLoader`. Wer einen spezialisierten Chooser will, kann
optional einen passenden `FileLoader` dazuregistrieren.

### Ergebnis

- `reader` hat eine klare API-Grenze — keine direkten Zugriffe der UI auf
  Implementierungsklassen.
- Eine neue Sprache = neuer `LanguageAnalyzer` + SPI-Eintrag, kein
  Anfassen von `S202Module`.
- Der `FileLoader`-Gedanke bleibt in der UI und arbeitet gegen
  `LanguageAnalyzer.displayName()`.

---

## 2. `SCCVisualizationHelper` — Altlast, kann gelöscht werden

`de.weigend.s202.graph.SCCVisualizationHelper` hat keinen einzigen Aufrufer
außerhalb seiner eigenen Klassendefinition. Die Klasse ist toter Code.

Inhaltlich ist sie durch neuere Mechanismen vollständig ersetzt:

| Methode | Ersatz |
|---|---|
| `getTangles()` | `DomainModel.getPackageTangles()`, `SCCRenderer` |
| `generateSummary()` / `ArchitectureSummary` | kein Abnehmer — nie integriert |
| `sortTangleMembers()` | früher Layoutversuch; ersetzt durch `rank(P)` in `LevelCalculator` |

Zusätzlich ist der Name strukturell falsch: eine Klasse mit "Visualization"
im Namen gehört nicht ins `graph`-Paket (Domain-Graph-Infrastruktur).

**Aktion:** Datei löschen.

---

## 3. `SCCDAGBuilder` — Altlast, kann gelöscht werden

`de.weigend.s202.graph.SCCDAGBuilder` wird im Produktionscode nirgendwo
aufgerufen. Einziger Aufrufer ist `SCCDAGBuilderTest`, der die Klasse
künstlich am Leben hält.

Die Funktionalität ist vollständig im `LevelCalculator` enthalten: dieser
baut ebenfalls einen SCC-DAG und berechnet Levels per Longest-Path — aber
mit `rank(P)`-Mechanismus und Paket-Hypothese. `SCCDAGBuilder` kennt
beides nicht und würde allein keine korrekten Architekturlevels liefern.

**Aktion:** Klasse und begleitenden `SCCDAGBuilderTest` löschen.

---

## 4. `EdgeClassification` — verschieben, nicht löschen

`de.weigend.s202.graph.EdgeClassification` hat genau einen Aufrufer im
Produktionscode: `LayoutInvariantChecker`. Kein anderer Code braucht sie.

Als eigenständige public Klasse im `graph`-Paket suggeriert sie eine
allgemeine Nutzbarkeit, die nicht existiert. Die Klasse ist ausschließlich
ein Implementierungsdetail der Invariantenprüfung.

**Aktion:** Als `private static` Hilfsklasse in `LayoutInvariantChecker`
verschieben (oder package-private im `analysis.invariants`-Paket, falls
der Checker zu groß wird). Aus dem `graph`-Paket entfernen.

---
