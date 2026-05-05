# ICSOFT 2026 — Abstract (Deutsch, Arbeitsversion)

**Track:** Abstracts Track
**Deadline:** 22. Mai 2026
**Status:** Entwurf v1
**Sprache:** Deutsch (zur Übersetzung ins Englische)

---

## Titel

**Korrekte Layer-Berechnung für hierarchische Architekturvisualisierungen — Constraints, Invarianten und Pitfalls**

*Englische Alternative:* "Correct Layer Computation for Hierarchical Architecture Visualizations — Constraints, Invariants and Pitfalls"

## Autoren

Johannes Weigend (Technische Hochschule Rosenheim / QAware GmbH)

*Optional: Veronika Schwarz, Michael Philippsen (Co-Autoren 2021, dort publiziert als Dashuber, Philippsen) — noch anzufragen.*

---

## Abstract

Geschichtete Architekturvisualisierungen drücken Abhängigkeitsstruktur durch vertikale Anordnung aus: wer von anderen abhängt, steht höher. Ein Package auf einer niedrigeren Schicht als seine eigenen Klassen, zwei zyklisch abhängige Pakete auf unterschiedlichen Ebenen, eine reguläre Abhängigkeit die entgegen der Schichtrichtung zeigt — solche Fehler in der Layer-Berechnung sehen plausibel aus, täuschen aber den Betrachter. Das Prinzip klingt selbstverständlich: die Korrektheit eines Architekturlayouts sollte das Werkzeug garantieren, nicht der Nutzer. Wie schwer das tatsächlich ist, zeigt erst die Implementierung.

Unser 2021 bei IVAPP ausgezeichnetes Paper ("A Layered Software City for Dependency Visualization", Dashuber, Philippsen, Weigend) führte ein Schichtlayout ein, dessen Grundprinzip einfach ist: jede Klasse erhält ein höheres Level als die Klassen von denen sie abhängt. Die X/Y-Position von Gebäuden auf dem Stadtgrundriss folgt dieser Ordnung direkt — sie wird aus einer Abhängigkeits-Level-Berechnung abgeleitet — foundationale Klassen (niedriges Level, viele andere hängen von ihnen ab) stehen vorne/unten in der Stadt; hochrangige Module (hohes Level, hängen selbst von vielen anderen ab) stehen hinten/oben. Gebäudehöhen kodieren orthogonal dazu Metriken wie Komplexität oder Zeilenzahl. Das Layout minimiert explizite Abhängigkeitspfeile, da die räumliche Position bereits die Abhängigkeitsrichtung kommuniziert. Dieser Beitrag stellt eine quelloffene Referenzimplementierung (Java, Apache 2.0) vor, die dieses Konzept vollständig realisiert, sowie einen zugehörigen **Layout-Invarianten-Checker** der vier formal spezifizierte Nachbedingungen nach jedem Analyselauf verifiziert.

Die Pipeline selbst ist nicht trivial. Die scheinbar einfache Invariante "Kanten zeigen nach unten" verbirgt eine Implementierungs-Challenge: Ein korrektes Schichtlayout erfordert die Lösung dreier interdependenter Constraint-Systeme — Klassen-Ordnung, Package-Hierarchie und SCC-Gleichheit — die sich gegenseitig beeinflussen. Ein naiver Single-Pass-Algorithmus liefert bei zyklischen Graphen iterationsordnungsabhängige, nicht-deterministische Ergebnisse. Der konzeptionelle Kern der Lösung: die Pipeline berechnet **zwei semantisch verschiedene Level-Konzepte**. Phase 1 berechnet die *architektonische Tiefe* jeder Klasse als längsten Pfad im globalen, zyklenfreien SCC-DAG (paketübergreifend, Werte können in die Hunderte gehen). Phase 2 berechnet davon unabhängig die *lokale Layout-Position* innerhalb eines Packages — beginnend bei 0, ausschließlich auf Geschwister-Abhängigkeiten, externe Abhängigkeiten bewusst ausgeblendet. Erst die Trennung beider Konzepte ermöglicht ein Layout das gleichzeitig architektonisch korrekt und visuell lesbar ist. Stark zyklischer Code — Minecraft Forge enthält eine SCC aus 4.038 Klassen mit 3.585 Rückkanten — wird durch einen heuristischen SCC-Breaker (In-/Out-Degree-Rankscore) zu einer nutzbaren Hierarchie aufgelöst, anstatt in einer einzigen flachen Ebene zu kollabieren. (Siehe Anhang A für eine ausführliche Analyse der Single-Pass-Fehlermodi.)

Der Invarianten-Checker (R1–R5) unterscheidet präzise zwischen Algorithmenfehlern und bewusst tolerierten Heuristik-Verletzungen: R1 prüft Abwärts-Orientierung nicht-heuristischer Kanten; R2 verlangt gleiche Level für Package-SCCs im gefilterten Abhängigkeitsgraphen; R3 stellt sicher dass Container-Level ihr Inneres dominieren; R5 prüft Konsistenz der Kantenklassifikation. Die Entwicklung dieser Regeln war selbst nicht fehlerfrei: eine frühe Variante von R2 ("zyklische Kind-Packages teilen ein Level") erwies sich als zu grob — Parent↔Child-Kanten und Rückkanten erzeugten Schein-Zyklen und mussten explizit herausgefiltert werden. Der Checker ist damit nicht nur ein Sicherheitsnetz für die Implementierung, sondern auch für die Constraints selbst: er validiert gleichzeitig die Korrektheit der Darstellung und die Präzision der Regeln — ein doppelter Boden den kein manueller Review ersetzen kann. Bei Funden erzeugt der Checker einen copy-paste-fähigen Reproducer-Block. Dieselben vier Regeln wurden portiert auf eine unabhängige 3D-Software-City in Unity/.NET — mit byte-identischen Befunden auf gemeinsamen Eingaben.

Praktisch bedeutsam: Ein R2-Befund in der 92-Modul-Codebasis *software-ekg-7* identifizierte einen fehlenden Package-SCC-Equalisierungsschritt im Pipeline-Code und löste direkt einen Fix aus. Für die interaktive Schuldenbereinigung zeigt ein *Top-Cut-Targets*-Panel welche konkreten Methodenaufrufe in den meisten vom SCCBreaker identifizierten Schnitt-Kanten stecken — ein direkter Weg von der visuellen Diagnose zur gezielten Refactoring-Maßnahme.

Nach unserem Kenntnisstand hat keine frühere geschichtete Architekturvisualisierung diesen Grad an Selbstvalidierung realisiert. Wir demonstrieren den Checker live auf realen Codebasen, zeigen den Weg von einem markierten Layout-Pixel zu einem scheiternden Reproducer-Test, und diskutieren die Verallgemeinerbarkeit auf andere Visualisierungstechniken.

**Wortanzahl:** ~410

---

---

## Anhang A — Warum Single-Pass nicht funktioniert: eine Analyse der Fehlermodi

### Das Grundproblem: drei interagierende Constraint-Systeme

Ein korrektes Schichtlayout muss gleichzeitig drei Constraints erfüllen:

1. **Klassen-Constraint**: Wenn A → B (A hängt von B ab), dann muss `level(A) > level(B)`.
2. **Hierarchie-Constraint (R3)**: Ein Package muss auf mindestens dem Level seines höchsten Inhalts liegen.
3. **SCC-Constraint (R2)**: Alle Klassen in einer zyklischen Abhängigkeit bekommen dasselbe Level; alle Packages die eine Package-SCC bilden ebenso.

Diese drei Constraints sind einzeln lösbar. Zusammen erzeugen sie ein **zirkuläres Abhängigkeitssystem zwischen den Ebenen der Berechnung selbst** — nicht zwischen den Klassen.

### Die konkreten Fehlermodi eines Single-Pass-Ansatzes

**Fehlermodus 1: Retroaktive Korrektur**

Der Algorithmus iteriert über die Klassen und vergibt Level. Er sieht Klasse `A` (in Package `P1`), berechnet `level(A) = 3`, setzt damit `level(P1) = 3`. Weiter in der Iteration trifft er Klasse `B` (in Package `P2`) — und stellt fest: A und B sind in einer SCC und müssen gleiches Level bekommen, jetzt `level(A) = level(B) = 5`. Damit muss `level(P1)` neu berechnet werden. P1 hängt möglicherweise von P3 ab, das nun anders eingeordnet werden muss — eine Kaskade. Im Single-Pass wurden P1 und P3 aber bereits abgeschlossen. Die SCC-Mitgliedschaft ist erst nach vollständiger Graphtraversierung bekannt; im Single-Pass entscheidet die Reihenfolge, ob die Korrektur noch rechtzeitig kommt.

**Fehlermodus 2: Verwobene Zykluserkennung erzeugt classpath-abhängige Ergebnisse**

Der subtilste und in der Praxis gefährlichste Fehlermodus entsteht wenn Zykluserkennung und Level-Berechnung nicht getrennt werden, sondern ineinander ablaufen: Der Algorithmus traversiert den Klassengrafen, vergibt Level, und behandelt dabei entdeckte Zyklen "on the fly" — je nachdem welche Klasse er gerade bearbeitet.

Klassen kommen aus JARs. Die Reihenfolge, in der eine JVM Klassen aus einem JAR liest, folgt der Reihenfolge, in der sie in das JAR gepackt wurden. Diese wiederum hängt ab von: Build-Tool (Maven vs. Gradle), Modul-Reihenfolge im Multi-Module-Build, Datei-System-Sortierung auf dem Build-Server. Keine dieser Ordnungen ist semantisch bedeutsam — keine codiert Abhängigkeitsrichtung.

Das Ergebnis: Derselbe Quellcode liefert unterschiedliche Level-Layouts je nach Build-Konfiguration. Die Fehler sind **subtil**: Das Layout sieht plausibel aus, Pfeile zeigen grob in die richtige Richtung, kein offensichtlicher Bruch. Die präzise Hierarchieinformation ist korrumpiert, aber nicht so drastisch dass es sofort auffällt. Genau das macht diesen Fehlermodus gefährlich — er überlebt lange unentdeckt.

Die Lösung: Tarjan's Algorithmus verlangt eine **vollständige** Sicht auf den Graphen, bevor irgendwelche Level vergeben werden. Phase 1 trennt deshalb strikt: erst alle SCCs finden (Tarjan, O(V+E), deterministisch), dann SCC-DAG bauen, dann Level vergeben. Die classpath-Reihenfolge beeinflusst das Ergebnis nicht mehr.

**Fehlermodus 3: Gegenseitiger Verweis zwischen Package- und Klassen-Level**

Package `P1` liegt auf dem Level seiner höchsten Klasse (R3). Welches Level hat die höchste Klasse? Das hängt davon ab, welche anderen Klassen sie aufruft — die wiederum in Packages liegen, deren Level davon abhängt... Der Package-Graph ist nicht vorab berechenbar, weil er aus dem Klassen-Graph abgeleitet werden muss. Dieser zirkuläre Verweis macht einen gemeinsamen Pass über Klassen und Packages inhärent instabil.

**Fehlermodus 4: Der große See — SCCs ohne Heuristik kollabieren das Layout**

Ohne Mechanismus zum Aufbrechen großer SCCs landen alle Mitglieder einer zyklischen Gruppe zwangsläufig auf demselben Level. Bei kleinen Zyklen (2–5 Klassen) ist das akzeptabel. Bei Minecraft Forge — eine einzige SCC aus 4.038 Klassen — bedeutet das: über 40 % aller Klassen des Projekts liegen auf Level 0. Sie bilden einen riesigen, flachen "See" am unteren Rand des Layouts. Alles andere thront darüber, aber das Interessante, die interne Struktur des Kerns, ist vollständig unsichtbar. Das Layout ist formal korrekt (alle SCC-Mitglieder gleiches Level — R2 erfüllt), aber praktisch wertlos.

Der heuristische SCC-Breaker (In-/Out-Degree-Rankscore) löst dieses Problem: Er identifiziert "Rückkanten" — Abhängigkeiten die architektonisch gegen die natürliche Flussrichtung laufen — und entfernt sie temporär für die Level-Berechnung. Das Ergebnis ist eine nutzbare Hierarchie auch für pathologisch zyklische Codebasen. Die entfernten Kanten werden als Verletzungen (rot) visualisiert, nicht ignoriert — das Layout ist ehrlich über die architektonische Schuld.

### Formaler Beweis: Warum ein naiver rekursiver Algorithmus versagt

Der intuitivste Ansatz zur Level-Berechnung ist ein rekursiver DFS mit Memoization:

```
level(A):
  if A already computed: return level(A)
  if A has no dependencies: return 0
  return max(level(dep) for dep in A.dependencies) + 1
```

Für azyklische Graphen (DAGs) ist das korrekt und äquivalent zum Longest-Path-Algorithmus. Für Graphen mit Zyklen versagt er strukturell.

**Gegenbeispiel mit zwei verschachtelten SCCs:**

Gegeben: SCC₁ = {A, B} mit `A → B → A`, SCC₂ = {C, D} mit `C → D → C`, Querabhängigkeit `A → C`.

Korrekte Level: `level(C) = level(D) = 0`, `level(A) = level(B) = 1`.

Rekursiver Algorithmus, startend bei A:

```
level(A)  [A "in progress", Sentinel = 0]
  → level(B)
      → level(A): Zyklus erkannt, return 0
    B.level = 0 + 1 = 1
  → level(C)  [C "in progress", Sentinel = 0]
      → level(D)
          → level(C): Zyklus erkannt, return 0
        D.level = 0 + 1 = 1
    C.level = 1 + 1 = 2   ← FALSCH (korrekt wäre 0)
  A.level = max(1, 2) + 1 = 3   ← FALSCH (korrekt wäre 1)
```

Nach SCC-Equalisierung: SCC₁ = max(3,1) = 3, SCC₂ = max(2,1) = 2 — beides falsch.

**Ursache**: Der rekursive Algorithmus betritt SCC₂ aus dem Kontext von SCC₁ heraus. Die interne Zyklusauflösung von SCC₂ (Sentinel-basiert) produziert ein aufgeblähtes Level, das dann in die Level-Berechnung von SCC₁ eingeht. Das korrekte Level von SCC₂ (0, bestimmt durch externe Abhängigkeiten) ist erst bekannt wenn der gesamte Graph traversiert wurde.

**Satz**: Ein rekursiver Level-Algorithmus mit Sentinel-basierter Zykluserkennung produziert für Graphen mit mehreren interagierenden SCCs Ergebnisse, die von der Traversierungsreihenfolge abhängen. Da die Traversierungsreihenfolge bei JVM-basierter Klassenladung der Classpath-Reihenfolge folgt, sind die Ergebnisse **nicht deterministisch über verschiedene Build-Konfigurationen**. Die Fehler sind subtil — das Layout sieht plausibel aus, die präzise Hierarchieinformation ist korrumpiert.

Die korrekte Lösung kollabiert SCCs vor der Level-Vergabe zu atomaren Knoten (Tarjan → SCC-DAG → Longest-Path). Interne Zyklusstruktur beeinflusst die Level-Berechnung nie, weil sie vor der Level-Vergabe vollständig aufgelöst wurde.

### Die Lösung: zwei semantisch verschiedene Level-Konzepte

Die Fehlermodi 1–3 haben eine gemeinsame Ursache: der Versuch, *architektonische Tiefe* und *lokale Layoutposition* in einem einzigen Level-Begriff zu vereinen. Die Lösung trennt beide Konzepte konsequent:

- **Phase 1** berechnet die *architektonische Tiefe* — den längsten Pfad im globalen, zyklenfreien SCC-DAG über alle Klassen des Projekts.
- **Phase 2** berechnet davon unabhängig die *lokale Layout-Position* innerhalb eines Packages, beginnend bei 0, ausschließlich auf Geschwister-Abhängigkeiten.

Beide Phasen verwenden denselben Algorithmus (Tarjan → SCC-DAG → Longest-Path), aber auf verschiedenen Graphen mit verschiedenen Constraints:

```
Phase 1: Klassen-Graph → Tarjan (SCCs) → SCC-DAG (azyklisch)
              → Longest-Path-Topologiesort → globale Level
              → SCC-Equalisierung (alle Mitglieder = Maximum)
              → Package-Level (Propagation nach oben)

Phase 2: Pro Package: Geschwister-Graph (nur interne Kanten)
              → Tarjan (lokale SCCs) → lokaler SCC-DAG
              → Topologiesort → lokale Visualisierungs-Positionen
```

**Phase 1** ist deterministisch weil:
- Tarjan's Algorithmus ist O(V+E) und produziert auf demselben Graphen immer dieselben SCCs — stack-basiert, rein deterministisch.
- Das SCC-DAG ist azyklisch per Konstruktion — topologisches Sortieren ist jetzt möglich.
- Level-Vergabe via Longest-Path ist auf einem DAG eindeutig.
- SCC-Equalisierung (alle Mitglieder bekommen das Maximum) ist reihenfolgeunabhängig.

**Phase 2** ignoriert externe Abhängigkeiten bewusst. Wenn Package `ui` von `domain` abhängt und `domain` von `persistence`, würden externe Kanten alle Geschwister auf denselben globalen Level ziehen — die lokale Struktur wäre unsichtbar. Phase 2 sieht nur Geschwister-Kanten und erzeugt damit eine von Phase 1 vollständig unabhängige, paketlokale Ordnung.

**Das zentrale Argument:** Kein einzelnes Level-Konzept kann beide Rollen gleichzeitig erfüllen. Ein rein globales Level ist lokal visuell unbrauchbar; ein rein lokales Level hat keine architektonische Aussagekraft. Erst die explizite Trennung — und die Erkenntnis dass es sich um zwei verschiedene Fragen handelt — macht ein Layout möglich das gleichzeitig korrekt und lesbar ist.

Die Invarianten R1–R5 sind dann maschinell prüfbare Nachbedingungen genau dieser Pipeline-Trennung. Dass der Checker tatsächlich Fehler in der Pipeline gefunden hat (R2-Befund in software-ekg-7), zeigt dass die Invarianten nicht trivial zu erfüllen sind — selbst für die Autoren des Algorithmus.

### Implementierung: die vollständige Pipeline

Die konzeptuelle Zwei-Phasen-Struktur entfaltet sich in der Implementierung zu folgenden konkreten Schritten:

**Phase 1 — Globale Levels** (`LevelCalculator`):

```
Step 1:  Klassen-Objekte anlegen        (alle Level = 0)
Step 2:  Package-Objekte anlegen        (alle Level = 0)
Step 3:  Klassen-Level berechnen
           → Tarjan auf Klassen-Graph
           → SCCBreaker: Rückkanten identifizieren
           → Azyklischen SCC-DAG bauen
           → Longest-Path-Topologiesort → Level pro SCC
           → Level auf alle SCC-Mitglieder verteilen
Step 4:  Package-Level = max(Level aller enthaltenen Klassen)
         ┌── Iterationsloop (bis stabil, max. 20×) ────────────┐
         │  Step 5:  Eltern-Package-Level hochziehen           │
         │             parent.level = max(child.level)         │
         │  Step 4b: Package-SCCs equalisieren                 │
         │             Tarjan auf gefiltertem Package-Graph    │
         │             SCC-Mitglieder auf max(Level) heben     │
         └─────────────────────────────────────────────────────┘
Step 6:  Rückwärts-Abhängigkeiten (dependents) setzen
```

> **Hinweis:** Nach Step 3 ist Level keine Eigenschaft einer einzelnen Klasse, sondern des SCC-Knotens dem sie angehört — gemessen als längster Pfad im zyklenfreien Quotienten-Graphen (SCC-DAG). Dieser Graph umfasst **alle Klassen des gesamten Projekts**, paketübergreifend. Zyklen beeinflussen die Level-Berechnung nicht, weil sie vor der Longest-Path-Berechnung durch SCC-Kollaps vollständig aus dem Graphen entfernt wurden. Das ist der fundamentale Unterschied zu naiven rekursiven Ansätzen — und der Grund warum Phase 2 (lokale Paket-Positionen) eine separate Berechnung erfordert.

**Phase 2 — Lokale Visualisierungs-Positionen** (`DistrictRowLevelCalculator`, rekursiv pro Package):

```
  Für jedes Package P:
    Schritt 1: Subtree-Klassen pro Geschwister sammeln
                 (Klasse: {sich selbst}, Package: alle Klassen rekursiv)
    Schritt 2: Geschwister-Graph aufbauen via Distinct-Target-Counting
                 countAtoB = |{c ∈ subtree(B) : A.deps ∋ c}|
                 countBtoA = |{c ∈ subtree(A) : B.deps ∋ c}|
                 Kante A→B wenn countAtoB > countBtoA
    Schritt 3: Tarjan → lokaler SCC-DAG → Longest-Path → lokale Level
    Schritt 4: Level auf alle Geschwister anwenden
    Schritt 5: Rekursion in Package-Kinder
```

**Der 4b/5-Loop ist kein Implementierungs-Detail**, sondern die direkte Konsequenz aus Fehlermodus 3: Package-SCC-Equalisierung (4b) kann ein Package-Level *heben*, was das Eltern-Package (5) heben muss, was die SCC-Struktur ändern kann, was wieder Equalisierung auslöst. Der Loop terminiert, weil Level **monoton wachsen** — kein Schritt senkt je ein Level. Das ist der Terminierbarkeitsbeweis, und er rechtfertigt das 20-Iterationen-Limit als reines Sicherheitsnetz (in der Praxis: 1–3 Iterationen).

Die "zwei Phasen" im Paper benennen die konzeptuelle Trennlinie (global vs. lokal), nicht die Anzahl der Algorithmus-Schritte. Phase 1 ist ein geordnetes System mit iterativem Stabilisierungsloop; Phase 2 ist eine davon vollständig unabhängige lokale Berechnung.

**Der konzeptionelle Kern:** Die Pipeline berechnet zwei semantisch verschiedene Level-Konzepte, die beide eigenständige Bedeutung tragen:

| | Phase 1 | Phase 2 |
|---|---|---|
| **Bedeutung** | Architektonische Tiefe im zyklenfreien SCC-DAG | Visuelle Layout-Position unter Geschwistern |
| **Scope** | Global — alle Klassen des Projekts | Lokal — nur Kinder eines Packages |
| **Startpunkt** | Blattklassen = 0, wächst mit Abhängigkeitstiefe | Immer 0 für das "unterste" Geschwister |
| **Zyklen** | Durch SCC-Kollaps vor der Berechnung eliminiert | Durch lokalen Tarjan behandelt |
| **Wertebereich** | Projekt-abhängig, kann in die Hunderte gehen | Kleine Zahlen (Geschwisteranzahl − 1) |

Kein anderes Level-Konzept kann beide Rollen erfüllen: ein rein globales Level wäre lokal visuell unbrauchbar (externe Abhängigkeiten dominieren), ein rein lokales Level hätte keine architektonische Aussagekraft. Erst die Kombination beider ermöglicht ein Layout das gleichzeitig korrekt und lesbar ist.

### R4: eine verworfene Regel — und was das lehrt

Eine frühe Fassung des Invarianten-Checkers enthielt eine R4: *"Zyklisch voneinander abhängige Kind-Packages müssen dasselbe Level teilen."* Die Regel klingt offensichtlich richtig — und ist es im einfachen Fall auch. In der Praxis scheiterte sie:

- **Parent↔Child-Kanten** erzeugen im rohen Package-Graphen immer wechselseitige Abhängigkeiten: jedes Package "enthält" seine Kinder und jedes Kind "lebt in" seinem Eltern-Package. Ohne Filterung erscheint fast jedes Package-Paar als zyklisch.
- **Rückkanten** des SCCBreakers verbinden Packages die architektonisch keine Peers sind. Eine Regel die diese Kanten mitberücksichtigt equalisiert falsche Kandidaten.
- **Gemeinsame Klassen-SCCs** lassen zwei Packages als paketabhängig erscheinen ohne dass eine echte strukturelle Peer-Beziehung vorliegt.

R4 wurde durch R2 ersetzt, das denselben Anspruch mit dem richtigen gefilterten Graphen umsetzt: Rückkanten entfernt, intra-Subtree-Kanten entfernt, Kanten durch gemeinsame Klassen-SCCs entfernt — erst dann Tarjan auf dem Rest.

**Die eigentliche Lehre** geht über R4 hinaus: Constraints für ein komplexes Constraint-System korrekt zu formulieren ist selbst schwer. Niemand ist so schlau, dass er auf Anhieb fehlerfreie Invarianten für ein System mit drei interagierenden Constraint-Ebenen aufschreibt. Der Checker schafft deshalb einen *doppelten Boden*: er validiert nicht nur die Pipeline-Implementierung gegen die Regeln, sondern deckt auch auf wenn die Regeln selbst zu grob oder zu weit gefasst sind — durch False Positives die den Autor zwingen die Regel zu schärfen. Dass R2 danach einen echten Pipeline-Bug in software-ekg-7 fand, ist der Beweis dass die verschärfte Regel präzise genug war um echte Fehler von Schein-Fehlern zu trennen.

### Einordnung der Heuristik in die Literatur

Das Problem hinter Fehlermodus 4 — welche Kanten entfernen um einen Graphen azyklisch zu machen — heißt **Minimum Feedback Arc Set (FAS)** und ist NP-hart [Karp 1972]. Jeder polynomielle Algorithmus ist daher zwangsläufig eine Heuristik oder Approximation.

Die bekannteste verwandte Heuristik stammt von **Eades, Lin & Smyth (1993)**: Knoten werden iterativ nach ihrem `(outDegree - inDegree)`-Wert in eine lineare Sequenz eingeordnet; Kanten die in der fertigen Sequenz rückwärts zeigen, bilden den Feedback Arc Set. Das Grundprinzip — *hoher outDegree = hochrangig, hoher inDegree = fundamental* — ist dieselbe Intuition wie in Structure202.

Die Structure202-Variante ist bewusst einfacher: kein iterativer Sequenzaufbau, sondern ein direkter normalisierter Score pro Knoten (`(out - in) / max(1, out + in)`), der Kanten direkt klassifiziert. Sie ist damit schneller zu implementieren und ausreichend präzise für Software-Abhängigkeitsgraphen — wo die Heuristik architektonische Intuition widerspiegelt, nicht mathematisches Optimum.

Der entscheidende Unterschied zu bestehenden Tools: Andere Werkzeuge brechen Zyklen ebenfalls irgendwie, aber ohne explizite Klassifikation. In Structure202 sind heuristisch entfernte Kanten als `VIOLATION` markiert, visuell rot hervorgehoben, und dem Invarianten-Checker bekannt. Algorithmusfehler und tolerierte Heuristik-Artefakte sind damit trennbar — was bei opaken Implementierungen nicht möglich ist.

---

## Referenzen

1. Dashuber, V., Philippsen, M., & Weigend, J. (2021). *A Layered Software City for Dependency Visualization.* IVAPP 2021, pp. 277–285. SCITEPRESS. **Best Paper Award.**

2. Dashuber, V., Philippsen, M., & Weigend, J. (2022). *Static and Dynamic Dependency Visualization in a Layered Software City.* SN Computer Science, 3(4), 305. Springer.

3. Eades, P., Lin, X., & Smyth, W. F. (1993). *A fast and effective heuristic for the feedback arc set problem.* Information Processing Letters, 47(6), 319–323. Elsevier.

4. Karp, R. M. (1972). *Reducibility among combinatorial problems.* In R. E. Miller & J. W. Thatcher (Eds.), Complexity of Computer Computations (pp. 85–103). Plenum Press.

5. Structure202 (this work) — Apache 2.0, https://github.com/jweigend/Structure202

---

## Glossar

### Allgemeine Begriffe

**DAG** *(Directed Acyclic Graph)*
Gerichteter Graph ohne Zyklen. Ermöglicht topologisches Sortieren und deterministische Longest-Path-Berechnung. In der Pipeline das Ziel-Format nach SCC-Kollaps.

**Feedback Arc Set (FAS)**
Minimale Menge von Kanten, deren Entfernung einen gerichteten Graphen azyklisch macht. NP-hart [Karp 1972]; in der Pipeline durch eine Heuristik approximiert.

**In-Degree / Out-Degree**
Anzahl eingehender bzw. ausgehender Kanten eines Knotens. Hoher In-Degree = viele Abhängige (foundational); hoher Out-Degree = viele Abhängigkeiten (high-level). Basis des SCCBreaker-Rankscores.

**Longest-Path**
Längster gewichteter Pfad in einem DAG von einem Knoten zu einem Blatt. Auf dem SCC-DAG die Grundlage der Level-Vergabe: Level = Länge des längsten Abhängigkeitspfads zu einer blattnahen Klasse.

**Memoization**
Technik bei rekursiven Algorithmen: bereits berechnete Ergebnisse werden gecacht. Bei zyklischen Graphen führt Memoization mit Sentinel-Werten zu traversierungsordnungsabhängigen Fehlern (→ Fehlermodus 2).

**SCC** *(Strongly Connected Component)*
Maximale Menge von Knoten in einem gerichteten Graphen, zwischen denen in beide Richtungen ein Pfad existiert — d.h. eine zyklische Abhängigkeitsgruppe. Im Kontext der Architekturvisualisierung auch *Tangle* genannt.

**SCC-DAG** *(Quotienten-Graph)*
Azyklischer Graph der entsteht, wenn jede SCC zu einem einzigen Knoten kollabiert wird und Kanten zwischen SCCs erhalten bleiben. Grundlage der deterministischen Level-Berechnung in Phase 1.

**Tarjan's Algorithmus**
Algorithmus zur Erkennung aller SCCs in einem gerichteten Graphen in O(V+E). Stack-basiert, deterministisch, unabhängig von Iterationsreihenfolge. In der Pipeline an drei Stellen eingesetzt: globale Klassen-SCCs (Phase 1), Package-SCCs (Step 4b) und lokale Geschwister-SCCs (Phase 2).

**Topologisches Sortieren**
Lineare Anordnung der Knoten eines DAG, sodass alle Kanten in dieselbe Richtung zeigen. Voraussetzung: der Graph muss azyklisch sein. Bildet die Grundlage für Longest-Path und Level-Vergabe.

---

### Anwendungsspezifische Begriffe

**Architektonische Tiefe** *(Phase-1-Level)*
Das in Phase 1 berechnete Level einer Klasse: der längste Pfad von ihr zu einem Blatt im globalen SCC-DAG. Projektweite absolute Größe; kann bei tief verschachtelten Codebasen in die Hunderte gehen. Semantisch: "Wie weit steht diese Klasse von der Basis entfernt?"

**Cut Edge**
Vom SCCBreaker identifizierte Kante, deren Entfernung eine SCC auflöst oder verkleinert. In der Visualisierung orange hervorgehoben; im *Top-Cut-Targets*-Panel als Refactoring-Ziel priorisiert.

**Distinct-Target-Counting**
Heuristik in Phase 2 zur Bestimmung der Abhängigkeitsrichtung zwischen zwei Geschwister-Elementen: gezählt werden die *distinkt* erreichbaren Zielklassen in der jeweils anderen Subtree. Verhindert, dass häufige interne Calls zwischen zwei eng gekoppelten Klassen die Richtung kippen.

**Feedback Arc Set Heuristik (SCCBreaker)**
Structure202-spezifische Approximation des FAS-Problems: Rankscore `(outDegree − inDegree) / max(1, outDegree + inDegree)` pro Knoten; Kanten von niedrig- zu hochrangigen Knoten werden als Rückkanten markiert, bis zu `|Kanten| / 3` Kandidaten. Verwandt mit Eades et al. [1993], aber direkter Score statt iterativem Sequenzaufbau.

**Invariante R1**
*ClassDepDownward*: Jede Klassen-Abhängigkeit A→B muss `level(A) > level(B)` erfüllen, es sei denn die Kante ist eine heuristische Rückkante oder beide Endpunkte liegen in derselben aufgebrochenen SCC.

**Invariante R2**
*PkgSccEqualLevel*: Alle Packages die auf Package-Ebene eine SCC bilden (nach Filterung heuristischer Kanten, intra-SCC-Kanten und Parent↔Child-Kanten) müssen dasselbe Level haben.

**Invariante R3**
*ContainerLevelGEContent*: Das Level eines Packages muss ≥ dem Level jeder enthaltenen Klasse und jedes enthaltenen Sub-Packages sein.

**Invariante R5**
*ViolationFlagConsistency*: Die Klassifikation einer Kante (NORMAL / VIOLATION / INTRA_SCC) muss mit dem aktuellen Level-Zustand und der SCC-Zugehörigkeit übereinstimmen.

**Layout-Invarianten-Checker**
Komponente die nach jedem Analyselauf R1–R5 maschinell prüft. Unterscheidet Algorithmusfehler von tolerierten Heuristik-Artefakten. Gibt bei Befunden einen copy-paste-fähigen Reproducer-Block aus.

**Lokale Layout-Position** *(Phase-2-Level)*
Das in Phase 2 berechnete Level eines Elements: seine visuelle Zeilen-Position unter Geschwistern innerhalb desselben Packages, beginnend bei 0. Lokal, relativ, unabhängig von Phase-1-Levels. Semantisch: "In welcher Zeile steht dieses Element im Package-Raster?"

**Reproducer-Block**
Vom Invarianten-Checker ausgegebener Text mit Eingabepfaden, Graphdimensionen und Befundliste — direkt als Unit-Test oder LLM-Prompt verwendbar. Schließt die Lücke zwischen visuellem Befund und automatisiertem Regressionstest.

**Rückkante** *(Back Edge)*
Im Kontext der Pipeline: eine Abhängigkeitskante die architektonisch gegen die natürliche Flussrichtung läuft (niedrig-rangige Klasse hängt von hoch-rangiger ab). Vom SCCBreaker identifiziert, als VIOLATION markiert, in der Visualisierung rot dargestellt.

**Schichtlayout** *(Layered Layout)*
Visualisierungsparadigma bei dem Elemente gemäß einer vertikalen Ordnung (Level) angeordnet werden. Abhängigkeitspfeile zeigen in dieser Ordnung konstant in dieselbe Richtung, was sie bei klarer Hierarchie überflüssig macht.

**Software City**
Visualisierungsmetapher: Klassen als Gebäude, Packages als Stadtbezirke. In der 3D-Variante (Unity/.NET) kodieren X/Y-Position das Schichtlayout, Gebäudehöhe kodiert Metriken (z.B. Zeilenzahl, Komplexität).

**Tangle**
Umgangssprachlicher Begriff für eine SCC mit mehr als einem Mitglied — eine zyklische Abhängigkeitsgruppe die im Layout visuell hervorgehoben wird. Tangles sind die primären Refactoring-Ziele der interaktiven Zyklusauflösung.

**Top-Cut-Targets**
Panel in Structure202 das die Methoden mit den meisten Cut-Edge-Vorkommen über alle Tangles auflistet. Zeigt zu jeder Methode die beteiligten Caller-Klassen; "Cut All"-Kontextmenü kappt alle zugehörigen Kanten auf einmal.

---

## Offene Punkte

- **Co-Autoren** anfragen (Dashuber, Philippsen) — weiche interne Frist: 17. Mai.
- **Zahlen verifizieren:** 92 Module software-ekg-7, 4.038 Klassen / 3.585 Rückkanten Minecraft.
- **Kürzen auf ~350 Wörter** nach Co-Autoren-Freigabe — aktueller Text ist ~500 Wörter, INSTICC-Template lässt weniger Platz als Standard-LaTeX.
- **Englische Übersetzung** für die Einreichung erstellen sobald der deutsche Text freigegeben ist.
- **Keywords (PRIMORIS-Portal):** software architecture visualization, layout invariants, software city, strongly connected components, dependency analysis, tool demonstration.
