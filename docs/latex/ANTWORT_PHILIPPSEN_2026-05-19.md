# Antwort an Prof. Philippsen zu Mail 2 vom 19.05.2026

Stand: 2026-05-19
Basis: `docs/latex/ICSOFT_2026_ABSTRACT_EN.tex` nach Umsetzung aller heutigen Anmerkungen
Vorgänger: `ANTWORT_PHILLIPSEN_2026-05-16.md`, `FEEDBACK_PHILIPPSEN_2026-05-19.md`

## Teil A -- Mailentwurf

Lieber Michael,

die Idee kommt aus einem alten Buch (*Nie wieder Bugs*) von Microsoft Press.
Microsoft hatte bei WinWord 2.0 Probleme mit der Druckvorschau -- man wusste
nicht, ob die Vorschau korrekt ist. Der Chefentwickler hat dann eine redundante
Implementierung gebaut, die während der Entwicklung parallel lief und die
Ergebnisse verglichen hat: wenn OK, alles gut; wenn NOK, Alert mit Debug-Dialog.

Der Punkt hier ist aber, dass ich keine alternative Layout-Engine gebaut habe,
sondern Bedingungen prüfe, die unsere Darstellung erfüllen muss, wenn sie
korrekt arbeitet. Streng genommen prüfe ich Post-Conditions. Der Witz ist nur:
diese Bedingungen gelten gleichzeitig für den Klassenbaum, den Paketbaum und
für das visuelle Layout -- und sie gelten auch dann, wenn nur einzelne Klassen
in den Paketbaum eingefügt werden. Deshalb spreche ich von Invarianten, die
allerdings nicht permanent geprüft werden, sondern nur einmal als Post-Condition.

Deinen Vorschlag mit den "implausibility alerts" finde ich aber trotzdem gut
und habe ihn übernommen.

Die überarbeitete Fassung liegt als Draft V4 anbei (bzw. im Repository).
Umgesetzte Änderungen:

- **Pfeile-Mechanik erklärt**, bevor Invarianten ins Spiel kommen --
  Bird's-eye, neuer Absatz *Where the arrows come from*.
- **Vor-Bird's-eye-Block eingedampft** auf einen Satz --
  Beginn von Appendix A, vor dem Bird's-eye.
- **R-Nummern und R4 erklärt** beim ersten Auftauchen --
  Bird's-eye, Absatz *Invariants act as implausibility alerts*.
- **Implausibility-Alert-Sprache** zweistufig im Vordergrund (vier
  Pipeline-Defekt-Alerts, R1-visual als Architektur-Finding) --
  Abstract; Bird's-eye; Section *Layout Invariants for Level Correctness*.
- **Demo-Figur mit eingespeistem Pipeline-Defekt** unter Figure 1, plus
  Report-Listing zum Mitlesen --
  Bird's-eye, Figure 2 + Listing direkt darunter.
- **Dashuber 2021 und 2022 zitiert** und gegen die jetzige Arbeit abgegrenzt --
  Section *Relation to Structure101 and Levelized Structure Maps*.
- **Figure *Invariant Finding R2* vergrößert** von 0.7 auf 0.95\linewidth --
  Section *Layout Invariants for Level Correctness*.

Herzliche Grüße
Johannes

## Teil B -- Umgesetzter Maßnahmenkatalog (Stand 2026-05-19, 17:00)

Internes Protokoll der heutigen Überarbeitung. Bezugnahmen auf
`docs/latex/ICSOFT_2026_ABSTRACT_EN.tex`.

### M1 -- Pfeile-Mechanik erklärt

Status: umgesetzt.

- Neuer `\paragraph{Where the arrows come from.}` direkt nach der Phasen-Liste
  ([Z. 179--202](ICSOFT_2026_ABSTRACT_EN.tex)).
- Beschreibt: Pfeile = Klassen-Abhängigkeiten aus dem Bytecode (vor den Phasen
  schon vorhanden), Phasen 1--3 ordnen die Boxen, Renderer zeichnet die Pfeile
  zwischen den Boxen, Klassifikation in vier Kategorien
  (`NORMAL`/`VIOLATION`/`INTRA_SCC`/`HEURISTIC_CUT`).
- Schließt ab mit der Aussage: Invarianten lesen das Ergebnis, ändern nichts;
  R1-algo/R1-visual prüfen Pfeilrichtung vs. Levels, R2/R3 prüfen
  Package-Placement, R5 prüft die Klassifikation.

### M2 -- Vor-Bird's-eye-Block eingedampft

Status: umgesetzt.

- Alte 12-Zeilen-Einleitung zum Appendix mit "drei Ordnungszahlen auf drei
  Graphen" gestrichen.
- Übrig bleibt ein einzelner Satz "This supplementary material expands the
  one-page abstract." ([Z. 141](ICSOFT_2026_ABSTRACT_EN.tex)).
- Die Aussage "keine Phase überschreibt früheren Output" steht jetzt nur noch
  in der Phase-3-Beschreibung; Redundanz entfernt.

### M3 -- R-Nummern und R4 beim ersten Auftauchen erklärt

Status: umgesetzt.

- Klammerzusatz im Bird's-eye-Absatz: *"R is short for rule; the catalogue
  contains R1--R5, with R4 retired and absorbed into R2, see Section R4: A
  Retired Rule"*.

### M4 -- Demo-Figur mit injiziertem Pipeline-Defekt

Status: umgesetzt.

- Neue Figure `Finding-R1-Wrong-Direction.png` direkt unter Figure 1
  ([Z. 238--255](ICSOFT_2026_ABSTRACT_EN.tex)).
- Caption beschreibt: bewusst eingespeister Defekt im `LevelCalculator`,
  Vergleich mit Figure 1 (korrekt) erlaubt direkten A/B-Abgleich, R1-Finding
  öffnet den Dialog automatisch, Statusbar zeigt vier wrong-direction edges.
- Implementierung: `s202.demo.injectAlert=true` System-Property
  ([LevelCalculator.java Block](../../analyzer/src/main/java/de/weigend/s202/domain/architecture/LevelCalculator.java))
  + Export in `run-ui.sh`.

### M5 -- Report-Listing unter der Demo-Figur

Status: umgesetzt.

- `lstlisting`-Block in `\scriptsize\ttfamily` mit dem vollständigen
  Layout-Invariant-Report unmittelbar unter der Demo-Figur
  ([Z. 257--292](ICSOFT_2026_ABSTRACT_EN.tex)).
- Macht den im Screenshot kleingedruckten Dialog im Druck lesbar.

### M6 -- Dashuber-Zitate eingebaut

Status: umgesetzt.

- Zwei neue Sätze im Abschnitt *Relation to Structure101 and Levelized
  Structure Maps* ([Z. 340--348](ICSOFT_2026_ABSTRACT_EN.tex)).
- Zitiert `Dashuber2021` (Layered Software City for Dependency Visualization)
  und `Dashuber2022` (Static and Dynamic Dependency Visualization in a Layered
  Software City).
- Abgrenzung: visuelle Metapher + Infrastruktur dort, aber Level-Berechnung
  als einzige Koordinate ohne Invarianten. Vorliegende Arbeit schließt die
  algorithmische Lücke für die 2D-Sicht.

### M7 -- Figure 2 (Invariant Finding R2) vergrößert

Status: umgesetzt.

- `\includegraphics[width=0.7\linewidth]` → `[width=0.95\linewidth]`
  ([Z. 734](ICSOFT_2026_ABSTRACT_EN.tex)).

### M8 -- Zweistufige Invarianten/Alert-Erzählung im Detail-Opener

Status: umgesetzt (heute Vormittag, siehe `FEEDBACK_PHILIPPSEN_2026-05-19.md`).

- Bridge-Satz: *"five machine-checkable invariants that act as the
  implausibility alerts introduced in the abstract"*.
- Builder/Checker-Asymmetrie für R1-visual auf einen Halbsatz reduziert
  ("a historical split with no semantic consequence").

### Restpunkte / Nicht-Abdeckbares

- **Hochauflösender Screenshot für die Demo-Figur**: aktuell auf
  `0.95\textwidth`; weiteres Vergrößern bringt Marginprobleme. Falls die Schrift
  im Dialog im Druck weiterhin grenzwertig wirkt, hilft nur ein neuer
  Screenshot mit größerem Dialog-Fenster -- das Listing darunter trägt
  inzwischen die Lesbarkeit.
- **Demo-Patch im Code**: bleibt bis nach Philippsens nächstem Durchgang als
  einzeiliger Block in `LevelCalculator.java` (Property-gegated, Tests grün).
  Wird vor dem finalen Einreichen entfernt.

### LaTeX-Kompilierung

- `pdflatex` + `bibtex` + zwei weitere Passes laufen ohne fatale Fehler durch.
- Nur die üblichen SCITEPRESS-Layout-Warnungen (Float-Placement, twocolumn-Höhe).
- PDF aktuell 30 Seiten, alle Zitate aufgelöst.
