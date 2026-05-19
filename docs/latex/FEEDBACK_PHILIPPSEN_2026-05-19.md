# Diskussion mit Prof. Philippsen: "Implausibility Alerts" als einheitliches Post-Check-Bild

Stand: 2026-05-19
Basis: `docs/latex/ICSOFT_2026_ABSTRACT_EN.tex`, Draft Version 3
Vorgänger: `ANTWORT_PHILLIPSEN_2026-05-16.md`

## Teil A -- Der Auslöser: Mail-Wechsel vom 19.05.2026

Philippsen hatte beim Lesen des überarbeiteten Abstracts den Eindruck, das
4-Phasen-Layout sei jetzt klar, aber die Rolle der fünf Invarianten unklar.
Nach einer kurzen Antwort von Johannes ("Invariant Checks laufen, wenn alles
berechnet ist, als redundante Absicherung der Konsistenz") kam von Philippsen
folgende Reaktion:

> "Aha, das war's, was mir beim Verständnis gefehlt hat. Wie wäre es, wenn Du
> vielleicht von *Implausibility Alert Checks* sprichst, die den Entwickler
> warnen? Und wenn keine solchen Alerts auftreten, dann sichert S202 zu, dass
> alles ok ist? Das muss in den Abstract und ins BirdsEye. Vielleicht wäre es
> dann auch hübsch, wenn das BirdEye-Beispiel einen solchen Alert zeigen würde?
> (Vielleicht kann man dann das pfeilfreie Beispiel links weglassen, um Platz
> zu schaffen.) Kommen beim Evaluationsbeispiel am Ende des Abstracts solche
> Alerts vor? Haben andere Tools solche Alerts?"

## Teil B -- Was die Diskussion ergeben hat

### B1 -- Reframing als "Alerts" ist gut, aber zweistufig

Philippsens Umrahmung ("Alerts an den Entwickler statt Invarianten verifizieren
Konsistenz") ist erzählerisch deutlich stärker, weil sie sofort sagt *warum es
den Leser interessiert*. Das ursprüngliche Versprechen ("keine Alerts ⇒ S202
sichert zu, dass alles ok ist") gilt aber nicht für alle fünf Checks
einheitlich. Es gibt zwei semantische Kategorien:

| Sorte | Feuert wenn... | Bedeutung für den Entwickler |
|---|---|---|
| **Pipeline-Defekt-Alerts:** R1-algo, R2, R3, R5 | nie auf einer korrekten Pipeline | Wenn doch: Algorithmus-Bug |
| **Architektur-Alerts:** R1-visual | nur in zyklischen Systemen, an Restkanten | Echtes Architektur-Finding zum Fixen |
| (separat) Heuristic Cut Marker | bei Zyklusbruch | Explizit toleriert, sichtbar dokumentiert (keine Alerts im strikten Sinn) |

Das Versprechen lässt sich damit zweistufig formulieren:

> *Auf einem azyklischen Codebase erzeugt S202 ein Layout ohne irgendwelche
> Alerts -- alle Kanten zeigen nach unten. Auf einem zyklischen Codebase sind
> alle Restkanten nach oben entweder explizit als heuristischer Cut markiert
> oder lösen einen R1-visual-Alert aus, der den Entwickler auf eine konkrete
> Architektur-Violation hinweist.*

### B2 -- R1-visual ist strukturell ein reiner Post-Check

Eine schöne Beobachtung aus der Diskussion: R1-visual kann nur dann feuern,
wenn ein Zyklus aufgelöst wurde und eine Restkante übrig bleibt, die nicht als
heuristic back-edge markiert ist. Konsequenz:

- In einem komplett azyklischen Klassen- und Paketgraph hat R1-visual schlicht
  keine Kandidaten, die scheitern könnten. Phase 1 liefert SCC-Trivialzerlegung
  mit konsistentem `archLvl`, Phase 3 baut sibling-azyklische Graphen mit
  konsistentem `localLevel`, alle Kanten zeigen visuell nach unten.
- R1-visual-Alerts sind also strukturell **Restkanten aufgebrochener Zyklen**.

Damit ist R1-visual **genauso ein reiner Post-Check** wie R1-algo, R2, R3, R5:
nach Pipeline-Ende einmal über das fertige Modell + das gerenderte Layout
laufen, lexikografische Vergleichung der Local-Level-Tuples gegen die
Klassenkanten. Keine Iteration, kein Feedback ins Layout.

Im aktuellen Text wird diese Symmetrie verwässert:

> "Five invariants run *after* the pipeline as consistency checks."
> (Bird's-eye, korrekt)
>
> "The `LayoutInvariantChecker` checks R1-algo, R2, R3, and R5 as algorithm
> consistency checks after every analysis run; the architecture builder checks
> R1-visual through violation detection on the visual-rank tuple."
> (Detail-Abschnitt, suggeriert Sonderrolle für R1-visual)

Diese Implementierungs-Asymmetrie (Builder vs. Checker) ist historisch
entstanden -- der Builder hat R1-visual zuerst gebraucht, um die roten Pfeile
zu zeichnen -- gehört aber konzeptuell nicht in die Hauptdarstellung.

### B3 -- Antworten auf Philippsens zwei Schlussfragen

**Kommen beim Evaluationsbeispiel solche Alerts vor?** -- Ja, beide Sorten:

- *software-ekg-7* zeigt einen R2-Pipeline-Defekt-Alert (167 Violations →
  0 nach Fix). Klassisches Beispiel für Kategorie "Pipeline-Defekt".
- *Minecraft Forge 1.19.2* zeigt 4 157 R1-visual-Architektur-Alerts auf der
  korrekt arbeitenden Pipeline. Klassisches Beispiel für Kategorie
  "Architektur-Finding".

**Haben andere Tools solche Alerts?** -- Nicht in dieser Form. Structure101
zeigt "feedback dependencies" als visuelle Marker, aber keinen
invariant-basierten Algorithmen-Selbsttest (also nichts, was unseren
Pipeline-Defekt-Alerts entspräche). Das ist der eigentliche
Differenzierungspunkt, der bisher im Abstract zu schwach formuliert ist.

## Teil C -- Konkrete Textänderungen *(umgesetzt 2026-05-19)*

### C1 -- Abstract

Aktueller Satz (Zeile 100--106):
> "Five machine-checkable invariants verify that each run is internally
> consistent: ... The checks distinguish pipeline defects from deliberately
> tolerated heuristic cuts and from architecture violations that the
> visualization is meant to expose."

Neuer Vorschlag (zweistufige Formulierung):
> "Five machine-checkable alerts run after every analysis and warn the
> developer when the level computation, the package architecture, or the
> rendered layout disagree with each other. Four of them (R1-algo, R2, R3, R5)
> are pipeline-defect alerts that never fire on a correct run; if they do,
> the algorithm has a bug. The fifth (R1-visual) fires only on remaining
> upward edges from broken cycles and points to a concrete architecture
> violation in the code. On an acyclic codebase, S202 therefore guarantees an
> alert-free layout in which every edge points downward."

Vorteil: Sofort klar, *warum* der Leser sich für die Checks interessieren soll,
und die Differenzierung zu Structure101 et al. ist textuell verankert.

### C2 -- Bird's-eye View (Appendix, Zeile 188--194)

Aktueller Absatz:
> "Five invariants run *after* the pipeline as consistency checks; they are
> not a feedback signal that drives further iteration. Every visible upward
> edge in the resulting drawing is therefore one of three things: an
> architecture violation, an explicit heuristic cut, or a peer-level cycle
> that could not be broken without contradicting the package architecture
> --- never an accidental artifact of the layout algorithm."

Erweitern um die Alert-Sprache und die zwei Kategorien.

### C3 -- Detail-Abschnitt "Layout Invariants for Level Correctness"
(Zeile 627--634)

Die Builder/Checker-Asymmetrie textuell auflösen:
- Konzeptuell sind alle fünf Post-Checks im selben Sinne.
- Implementierungsnotiz, dass R1-visual aktuell im Architecture-Builder
  realisiert ist, in eine Fußnote oder einen Klammer-Hinweis schieben.

### C4 -- Figure `S202-Dependencies.png` (Zeile 196--204)

Philippsens Vorschlag: linkes (azyklisches) Panel raus, dafür eine
Annotation/Markierung auf einer roten Upward-Kante im rechten Panel, die sie
explizit als R1-visual-Alert kennzeichnet ("This red edge fires R1-visual:
class X depends on class Y but is rendered below it -- architecture violation").

Damit zeigt das Bird's-eye-Beispiel direkt, *was ein Alert ist und wie er
aussieht*, statt nur die zwei Layout-Regime ohne Alert-Bezug zu illustrieren.

## Teil D -- Restpunkt: R1-visual physisch in den Checker ziehen

Separater Implementierungspunkt, nicht Voraussetzung für die Texterweiterung:
R1-visual sollte zur Klärung der konzeptuellen Symmetrie auch im Code in den
`LayoutInvariantChecker` umziehen (aktuell im Architecture-Builder). Das ist
eine eigene Aufgabe und hat keinen Einfluss auf das Verhalten -- nur auf die
Code-Struktur und die Lesbarkeit der zugehörigen Tests.

## Teil E -- Vorschlag zur Mail-Antwort an Philippsen

Kurze Antwort, die Philippsens Punkt aufgreift und die Zweistufigkeit erwähnt,
damit er die Nuance bei seinem nächsten Lesedurchgang nicht selbst entdecken
muss:

> Hallo Michael,
>
> Dein "Implausibility Alert"-Bild trifft den Punkt -- ich baue das in Abstract
> und Bird's-eye ein. Eine Nuance, die ich gleich mitnehmen will, damit es
> sauber wird: Die fünf Checks sind nicht alle gleich. Vier davon (R1-algo,
> R2, R3, R5) sind Pipeline-Defekt-Alerts und sollten auf einer korrekten
> Implementierung *nie* feuern; das ist Dein Versprechen "keine Alerts ⇒ ok".
> Der fünfte (R1-visual) feuert *nur*, wenn ein Zyklus aufgelöst wurde und
> eine Restkante übrig blieb, die nicht als heuristischer Schnitt markiert ist;
> das ist das eigentliche Architektur-Finding für den Entwickler. Auf einem
> azyklischen Codebase feuert also gar kein Alert.
>
> Beide Sorten kommen im Evaluationsteil vor: software-ekg-7 zeigt einen
> R2-Pipeline-Defekt-Alert, Minecraft Forge 1.19.2 zeigt 4 157 R1-visual-
> Architektur-Alerts auf der korrekt arbeitenden Pipeline. Structure101 und
> verwandte Tools haben zwar visuelle Feedback-Marker, aber meines Wissens
> keinen Invariant-basierten Algorithmen-Selbsttest -- das ist der eigentliche
> Differenzierungspunkt, den ich textlich klarer herausarbeiten werde.
>
> Die Figur im Bird's-eye drehe ich entsprechend um: linkes (pfeilfreies)
> Panel weg, im rechten Panel eine annotierte R1-visual-Alert-Kante.
>
> Schicke Dir die neue Fassung in den nächsten Tagen.
>
> Herzliche Grüße
> Johannes
