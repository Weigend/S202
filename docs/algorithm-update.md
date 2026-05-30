# Deterministischer Level-Algorithmus — Konzeptpapier

**Datum:** 2026-05-30  
**Status:** Entwurf zur Abstimmung vor Implementierung

---

## Ziel

Jeder Klasse einen ganzzahligen **ArchitectureLevel** zuweisen der die architekturelle Schichtung widerspiegelt: niedrige Level = fundamentale Bausteine, hohe Level = Nutzer dieser Bausteine.

---

## Phase 1: Globale SCCs auf dem Klassengraphen

**Eingabe:** Vollständiger Klassen-Abhängigkeitsgraph `G_C`  
**Algorithmus:** Tarjan (alphabetisch sortierte Traversierung → deterministisch)  
**Ergebnis:** Alle SCCs im Klassengraphen

Die SCCs beschreiben welche Klassen in Zyklen miteinander verflochten sind. Wir wissen jetzt *wo* Zyklen sind, aber noch nicht *welche Kanten* wir schneiden sollen.

---

## Phase 2: Pakete lokal ordnen

Der Algorithmus wird **rekursiv auf jeder Ebene der Paket-Hierarchie** angewendet.  
Für jeden Eltern-Knoten `P` werden nur seine **direkten Kind-Pakete** betrachtet.

### 2a. Gewichteten Paket-Kindgraph aufbauen

Für jedes geordnete Paar direkter Kind-Pakete `(A, B)` von `P`:

```
weight(A → B) = aggregierte Anzahl der Abhängigkeiten von A nach B
```

### 2b. Zyklen im Kindgraphen brechen

Zyklen werden in zwei Schritten behandelt. Beide Schritte werden iteriert bis keine Zyklen mehr übrig sind.

**Schritt 1 — Asymmetrische Zyklen (klare Richtung):**

Gibt es in einem Zyklus Kanten mit unterschiedlichen Gewichten, ist die Richtung architektonisch bestimmbar. Für jeden Knoten im SCC wird ein Rank-Score berechnet:

```
rank(P) = (Σ ausgehende Gewichte − Σ eingehende Gewichte)
          / max(1, Σ ausgehend + Σ eingehend)

  Hoher Rank  → Nutzer (viele ausgehende Deps)
  Tiefer Rank → Fundament (viele eingehende Deps)
```

Geschnitten wird die Kante die **am stärksten gegen den Abhängigkeitsstrom läuft** — also die Kante `v→w` mit dem größten Wert `rank(w) − rank(v)`. Bei Gleichstand entscheidet alphabetische Reihenfolge.

```
Beispiel:  A→B: 10,  B→A: 3
  rank(A) = (10−3)/(13) = +0.54  (Nutzer)
  rank(B) = (3−10)/(13) = −0.54  (Fundament)
  B→A läuft gegen den Strom: rank(A)−rank(B) = 1.08  →  schneide B→A  →  A ist höher als B
```

Danach erneut prüfen ob noch Zyklen bestehen.

**Schritt 2 — Symmetrische Zyklen (keine erkennbare Richtung):**

Sind alle Kanten in einem Zyklus gleich stark, gibt es keine architektonisch begründbare Richtungsentscheidung:

```
Beispiel:  A→B: 5,  B→A: 5  →  alle Kanten dieses Zyklus entfernen
```

Die Level entstehen dann ausschließlich aus Abhängigkeiten zu anderen Paketen **außerhalb** des Zyklus.  
Gibt es keine solchen Abhängigkeiten → gleicher Level.

### 2c. Lokale Level per Longest-Path

```
LocalLevel(A) = 0                               falls keine Abhängigkeiten zu Geschwistern
LocalLevel(A) = max(LocalLevel(B)) + 1          für alle Geschwister B auf die A zeigt
```

Für jeden Eltern-Knoten wiederholen → vollständige lokale Ordnung aller Paket-Ebenen.

---

## Phase 3: SCCs gezielt auflösen

**Schlüsselidee:** Kanten außerhalb von SCCs sind per Definition bereits azyklisch — sie müssen nicht angefasst werden. Nur die SCC-internen Kanten sind Kandidaten für Back-Edges. Die SCCs aus Phase 1 sind die **Problemliste**, der Paket-DAG aus Phase 2 ist die **Entscheidungsgrundlage**.

### 3a. Jede SCC aus Phase 1 gezielt behandeln

Für jede SCC aus Phase 1:

**Fall A — SCC-Mitglieder liegen in Paketen mit verschiedenen Levels:**

```
PackageLevel(pkg(v)) < PackageLevel(pkg(w))
  → Kante v→w ist eine Back-Edge → schneiden
```

Alle Kanten innerhalb der SCC die gegen die Paket-Ordnung laufen werden in einem Pass entfernt.  
Die SCC wird dadurch ggf. in kleinere Teile zerlegt — bis hin zu Singleton-SCCs.  
Verbleibende Sub-Zyklen (alle Mitglieder im gleichen Paket-Level) werden als Fall B behandelt.

**Fall B — alle SCC-Mitglieder liegen im selben Paket-Level:**

Kein Paket-Kontext liefert eine Richtungsinformation.  
→ **Alle Kanten** innerhalb der SCC entfernen.  
Die ClassLevel entstehen dann ausschließlich aus Abhängigkeiten zu Klassen **außerhalb** der SCC.  
Gibt es keine solchen Abhängigkeiten → alle Mitglieder bekommen **denselben ClassLevel**.  
Das sind die verbleibenden ~5%.

### 3b. Bereinigter Klassengraph `G_C'`

```
G_C' = G_C  ohne alle Back-Edges aus 3a (Fall A) und ohne alle Zykluskanten aus 3a (Fall B)
```

### 3c. Klassen-Level per Longest-Path auf kondensiertem Graphen

Tarjan auf `G_C'` → Kondensation → DAG → Longest-Path:

```
ClassLevel(v) = Tiefe der SCC in der v liegt (längste eingehende Abhängigkeitskette)
```

---

## Gesamtbild

```
G_C (Klassengraph, mit Zyklen)
    │
    ▼ Phase 1: Tarjan
SCCs = Problemliste (welche Klassen sind zyklisch?)
    │
    ▼ Phase 2: Paket-Graph (rekursiv je Hierarchieebene)
Pakete aggregieren → Zyklen brechen:
    asymmetrisch → Kante mit max(rank(to)−rank(from)) schneiden (iterativ)
    symmetrisch  → alle Zykluskanten entfernen
→ Paket-DAG = Entscheidungsgrundlage (welche Richtung ist architektonisch korrekt?)
    │
    ▼ Phase 3: SCCs gezielt auflösen
    Für jede SCC aus Phase 1:
      Fall A: Mitglieder in verschiedenen Paket-Levels?
              → alle gegen die Ordnung laufenden Kanten schneiden
              → verbleibende Sub-Zyklen → Fall B
      Fall B: alle Mitglieder im gleichen Paket-Level?
              → alle Kanten des Zyklus entfernen
              → Level aus externen Abhängigkeiten (sonst gleicher Level)
    │
    ▼ Longest-Path auf G_C'
ArchitectureLevel je Klasse (deterministisch)
```

---

## Determinismus

| Schritt | Deterministisch? | Warum |
|---|---|---|
| Phase 1: Tarjan | Ja | Sortierte Knotenreihenfolge |
| Phase 2: Gewichte | Ja | Reine Zählung |
| Phase 2: Asymm. Schnitt | Ja | Rank-Score eindeutig; alphabetischer Tie-Break |
| Phase 2: Symm. Schnitt | Ja | Alle Kanten entfernen — keine Auswahl nötig |
| Phase 3a Fall A: Back-Edge-Erkennung | Ja | Level-Vergleich, keine Heuristik |
| Phase 3a Fall B: Zyklus-Entfernung | Ja | Alle Kanten entfernen — keine Auswahl nötig |
| Phase 3c: Longest-Path | Ja | Gleiche Knoten bekommen gleichen Level — mehrere gültige Sortierungen, aber identische Level-Werte |

**Es gibt keine Heuristik und keinen Schwellwert.**

---
