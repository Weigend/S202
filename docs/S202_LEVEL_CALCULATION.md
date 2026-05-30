# S202 Level Calculation

Jeder Klasse und jedem Paket wird ein ganzzahliger **ArchitectureLevel** zugewiesen.
Niedriger Level = Fundament, hoher Level = Nutzer des Fundaments.

---

## Das Grundproblem

Ohne Zyklen ist die Lösung trivial: Longest-Path auf dem Abhängigkeitsgraphen.
Zyklen (SCCs) machen das unmöglich — in einem Zyklus können nicht alle Kanten
gleichzeitig nach unten zeigen. Irgendwelche Kanten müssen für die
Levelberechnung ignoriert werden. Die Frage ist: welche, und warum genau diese?

Die Antwort darf nicht willkürlich sein. S202 leitet die Schnittentscheidung
aus dem **Paketgraphen** ab — der liefert die architektonische Richtung.

---

## Die drei Phasen

### Phase 1 — Paketordnung (lokal, rekursiv)

Für jeden Paket-Container: baue einen gewichteten Graphen seiner **direkten
Kindpakete**. Kantengewicht = aggregierte Methodenaufrufzahl zwischen den
Subtrees.

Zyklen in diesem Graphen werden mit dem **Rank-Score** gebrochen:

```
rank(P) = (Σ ausgehende Gewichte − Σ eingehende Gewichte)
          / max(1, Σ ausgehend + Σ eingehend)
```

- Hoher Rank → P nutzt mehr als es genutzt wird → gehört nach oben
- Niedriger Rank → P wird eher genutzt → gehört nach unten

**Asymmetrische SCC** (Ranks unterscheiden sich): alle Kanten wo
`rank(from) < rank(to)` werden in einem Pass geschnitten.

**Symmetrische SCC** (alle Ranks gleich → Topologie gibt keine Richtung):
alle internen Kanten entfernen. Level entstehen dann aus externen
Abhängigkeiten; gibt es keine, bleiben die Knoten auf gleichem Level.

**Wichtig:** Symmetrie wird über den Rank erkannt, nicht über Gewichtsgleichheit.
Eine SCC mit lauter gleichgewichtigen Kanten kann trotzdem verschiedene Ranks
haben (wenn In/Out-Grade verschieden sind) — und dann einen klaren Schnitt.

Danach: Longest-Path auf dem DAG → `LocalLevel` je Sibling-Gruppe.

Dieser Schritt wird **rekursiv** für jede Paketebene wiederholt.

---

### Phase 2 — Klassenlevel (geführt durch Paketordnung)

**Fall A:** Klassen A und B liegen in einer SCC, aber in Paketen mit
verschiedenen Levels:

```
pkgLevel(A) < pkgLevel(B)  →  Kante A→B ist Back-Edge → schneiden
```

Die Paketordnung aus Phase 1 ist die Entscheidungsgrundlage. Die Klassen-SCC
wird durch den Paket-DAG aufgebrochen.

**Fall B:** Alle SCC-Mitglieder liegen im gleichen Paket-Level — kein
Paketkontext liefert eine Richtung. Alle internen Kanten entfernen; Level
entstehen aus externen Abhängigkeiten.

Alle entfernten Kanten (Fall A und Fall B) werden als **classBackEdge**
registriert — sie erscheinen in der UI als gestrichelte Verletzungskanten.

Danach: Longest-Path auf dem bereinigten Graphen → `ArchitectureLevel` je Klasse.

---

### Phase 3 — Lokale Darstellungsposition

`LocalLevel` (aus Phase 1) bestimmt die visuelle Position innerhalb des
jeweiligen Eltern-Containers. `ArchitectureLevel` (aus Phase 2) ist der
globale semantische Level — er steuert die Verletzungserkennung.

---

## Knackpunkte

**Kein Threshold.** Der alte Algorithmus verwendete ε = 0.1 als Schwellwert
für den Rank-Score. Das war eine Heuristik: Kanten mit Rankdifferenz < 0.1
wurden nicht geschnitten, die betroffenen Knoten kollabiert. Ohne Threshold
schneidet S202 jede Kante, die architektonisch begründbar ist — auch knapp
asymmetrische Fälle. Ergebnis auf Minecraft 1.12: −6 % Wrong-Direction-Edges.

**Rank schlägt Gewicht.** `minWeight == maxWeight` klingt nach Symmetrie,
ist es aber nicht zwingend. Wenn Knoten unterschiedlich viele Ein- und
Ausgangskanten haben, entstehen verschiedene Ranks — und damit eine klare
Richtung. Die symmetrische Behandlung greift nur wenn wirklich alle Ranks
gleich sind.

**Alles in einem Pass.** Innerhalb einer SCC werden alle qualifizierenden
Kanten gleichzeitig geschnitten (nicht eine nach der anderen mit Neustart).
Ein-Kante-Schnitt und Neuberechnung kann in Multi-Knoten-SCCs die globale
Dominanzordnung zerstören.

**Deterministisch.** Jede Iteration läuft über alphabetisch sortierte
Knotenlisten. Kein Zufallselement, kein Threshold, kein sizeabhängiger
Sonderfall — gleiche Eingabe ergibt immer gleiches Ergebnis.
