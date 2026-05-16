# Antwort an Prof. Philippsen zu den Abstract-Anmerkungen

Stand: 2026-05-16  
Basis: `docs/latex/ICSOFT_2026_ABSTRACT_EN.tex`, Draft Version 3 nach Überarbeitung

## Teil A -- Mailentwurf

Lieber Michael,

vielen Dank für Deine sehr klaren Anmerkungen zum Abstract V2. Ich habe
versucht, Deine Punkte in Abstract und Appendix einzuarbeiten. Parallel habe
ich im Code einen wesentlichen Aspekt weiterentwickelt: Die bisherige
Lifting-Heuristik für Package- und Darstellungspositionen ist durch einen
klareren Algorithmus ersetzt, der Architektur-Level und Darstellungs-/Layout-
Level explizit trennt. Darauf aufbauend habe ich die Fassung V3 überarbeitet.

In Fassung V3 steht das Ziel jetzt gleich am Anfang:
Es geht nicht um eine weitere Darstellungsmetapher, sondern um eine
Level-Berechnung, bei der eine sichtbare upward edge nicht durch ein
Layout-Artefakt entsteht. Sie soll entweder eine echte Architekturverletzung
oder einen bewusst offengelegten heuristischen Schnitt darstellen.

Den Related-Work-Teil habe ich ebenfalls umgestellt. Er stellt nicht mehr
unsere früheren Software-City-Arbeiten in den Vordergrund, sondern beginnt
allgemeiner bei levelisierten Dependency Maps: Software Cities und
Structure101/Levelized Structure Maps. Die Abgrenzung ist nun: Die visuelle
Idee ist nicht neu; auch unser früheres Paper beschreibt bereits einen
Level-Algorithmus. Die Lücke liegt eher in der Detailtiefe: Die bisherige
Beschreibung trennt die Architektur-Level und die Darstellungs-Level noch
nicht explizit genug und macht die Konsistenzbedingungen nicht so
reproduzierbar, dass die hier beschriebenen Problemfälle sauber gelöst werden.

Der technische Kern ist jetzt hoffentlich einfacher lesbar. Statt mehrere
Constraint-Systeme, Achsen, Postconditions und R-Nummern direkt im Abstract zu
listen, führt der Text nun diese zentrale Trennungsidee ein: S202 berechnet
Architektur-Level und Darstellungs-Level nicht mehr als denselben Wert, sondern
drei verschiedene Werte auf drei verschiedenen Graphen.

1. Das Class Architecture Level ist der längste Pfad im SCC-kollabierten
   Klassengraphen.
2. Das Package Architecture Level wird davon unabhängig auf einem gewichteten
   Inter-Package-Graphen berechnet.
3. Der Local Layer Index ist eine lokale Layout-Position pro Parent-Container
   und entscheidet die sichtbare Ordnung innerhalb eines Package-Boxes.

Die Invarianten werden nun nicht mehr als zweite Lösung oder als eigenes
Constraint-System verkauft, sondern als maschinenprüfbare Evidenz und
Regression-Schutz für diese Trennung. Im Appendix habe ich die Terminologie
entsprechend vereinheitlicht: aus "two coordinate concepts, four constraints"
und "five postconditions" wurde "three computed values, five consistency
checks". R2 ist jetzt auch sauber als Package-SCC-Konsistenzprüfung beschrieben,
passend zum aktuellen Code.

Den Punkt zu "verifiable" habe ich ebenfalls entschärft. Der Titel verwendet
den Begriff nicht mehr, und der Text behauptet keine formale Verifikation des
Visualisierungskonzepts. Der Anspruch ist enger: Die berechneten Levels und
Edge-Klassifikationen werden auf interne Konsistenz geprüft.

Zum Nachweis: Ich formuliere jetzt expliziter, dass die Evidenz technischer Art
ist, nicht eine Nutzerstudie. Genannt werden zwei Fälle: In `software-ekg-7`
hat ein Invariant Finding einen echten Package-SCC-Equalization-Bug gefunden
und einen Pipeline-Fix ausgelöst. Bei Minecraft Forge 1.19.2 reduziert die
Trennung von globaler Architekturtiefe und lokaler Layoutposition die visuellen
upward edges von ca. 6000 auf ca. 3600, weil frühere Layout-Artefakte
entfallen; die verbleibenden upward edges werden als Architekturverletzungen
oder explizite heuristische Schnitte klassifiziert.

Kurz gesagt: Ich habe versucht, Deine Rückmeldung strukturell umzusetzen:
Ziel -> Lücke im Stand der Technik -> eine zentrale Trennungsidee -> warum sie
hilft -> welche technische Evidenz es dafür gibt. Wenn der Abstract für Dich
jetzt in die richtige Richtung geht, hoffe ich, dass daraus ein sinnvoller
Beitrag für die Informatik-Community werden kann. Ich freue mich, wenn Du Dir auch die
weiteren Seiten anschaust. Unabhängig davon möchte ich noch ein paar Beispiele
ergänzen und die Grafiken überarbeiten, damit der Ansatz im Hauptteil weniger
abstrakt bleibt.

Veronika hat sich bisher nicht gemeldet; ich gehe deshalb davon aus, dass sie
bei diesem Paper nicht mit dabei ist. Vielleicht hast Du ja einen Mitarbeiter
oder eine Mitarbeiterin, der oder die Lust hätte, beim Finalisieren des
Papiers mitzuhelfen.

Herzliche Grüße
Johannes

## Teil B -- Umgesetzter Maßnahmenkatalog

Nicht mitschicken, falls die Mail kurz bleiben soll; gedacht als internes
Protokoll der Überarbeitung.

### M1 -- Ziel im ersten Absatz schärfen

Status: umgesetzt.

- Der Abstract formuliert jetzt früh das Ziel: upward edges sollen echte
  Architekturhinweise oder bewusst sichtbare heuristische Schnitte sein, keine
  Layout-Artefakte.
- Der Beitrag wird als reproduzierbare Level-Berechnung gerahmt, nicht als
  neue Rendering-Metapher.

### M2 -- Related Work allgemeiner rahmen

Status: umgesetzt.

- Der Related-Work-Abschnitt beginnt nun allgemeiner bei levelisierten
  Dependency Maps.
- Software Cities und Structure101/LSM werden als Stand der Technik genannt.
- Die eigene Arbeit wird über die offene, reproduzierbare Berechnung und
  Konsistenzprüfung abgegrenzt.

### M3 -- Drei Werte statt drei Constraint-Systeme als Einstieg

Status: umgesetzt.

- Der Abstract nennt als Kernidee drei Werte:
  class architecture level, package architecture level, local layer index.
- Der Appendix-Einstieg wurde auf "Three Computed Values, Five Consistency
  Checks" umgestellt.
- Die frühere Reibung zwischen "two coordinate concepts", "four constraints"
  und "five postconditions" ist entfernt.

### M4 -- "Postconditions" und Regeln entschlacken

Status: umgesetzt.

- Im Abstract werden keine R-Nummern eingeführt.
- "Postconditions" wurde durch "consistency checks" ersetzt.
- R-Nummern bleiben im Appendix/Tabelle.
- R2 wurde an die aktuelle Implementierung angepasst: Package-SCC-Konsistenz,
  nicht diffus Klassen- und Package-SCC zugleich.

### M5 -- "Verifiable" sauber ersetzen

Status: umgesetzt.

- "Verifiable" steht nicht mehr im Titel.
- Der Text spricht nur noch von maschinenprüfbarer Konsistenz der berechneten
  Levels und Edge-Klassifikationen.
- Es wird keine formale Verifikation der Architekturvisualisierung behauptet.

### M6 -- Nachweis quantitativer fassen

Status: umgesetzt.

- Der Abstract nennt die Evidenz explizit als technische Evidenz.
- `software-ekg-7`: Invariant Finding fand einen realen Package-SCC-Bug und
  löste einen Pipeline-Fix aus.
- Minecraft Forge 1.19.2: visuelle upward edges sinken von ca. 6000 auf ca.
  3600; entfernte Kanten waren Artefakte, verbleibende Kanten sind klassifiziert.

### M7 -- Abstract strukturell neu schreiben

Status: umgesetzt.

Neue Struktur:

1. Problem/Ziel: plausible Layouts können falsche Architekturinformation
   transportieren.
2. Lücke: Stand der Technik zeigt levelisierte Maps, spezifiziert aber die
   Level-Berechnung und Konsistenzprüfung nicht hinreichend.
3. Kernidee: drei semantisch verschiedene Werte getrennt berechnen.
4. Mechanismus: SCC-Kollaps, gewichtete Package-Graphen, lokale sibling-only
   Graphen; Heuristik macht Schnittkanten sichtbar.
5. Evidenz: Invariant-Checker + zwei reale Systeme + quantitative Reduktion
   von Artefakten.

### Restpunkte

- LaTeX kompiliert mit `pdflatex -interaction=nonstopmode -halt-on-error`.
- Es bleiben nur Layout-Warnungen zu `twocolumn`, Float-Platzierung und
  Underfull hboxes; keine undefined citations oder fatal errors.
- Die PDF wurde neu erzeugt.
