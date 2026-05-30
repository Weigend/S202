# Algorithmus-Änderungen: Vorher / Nachher

**Branch:** `feat/deterministic-algorithm`  
**Datum:** 2026-05-30

---

## Überblick

Der Kern-Algorithmus zur Level-Berechnung wurde von einem Heuristik-basierten Verfahren
auf einen deterministischen, schwellwertfreien Ansatz umgestellt. Die Rank-Score-Formel
bleibt erhalten — was sich ändert ist, wann und wie sie angewendet wird.

---

## Paket-Level (LevelCalculator.calculatePackageLevels)

### Vorher

```
1. Tarjan → alle SCCs
2. Für jede SCC mit size ≥ 2:
   - Rank-Score je Knoten:  rank(P) = (Σ aus − Σ ein) / max(1, Σ aus + Σ ein)
   - Schneide ALLE Kanten wo  rank(from) < rank(to) − 0.1   ← Schwellwert
   - Wenn Rankdifferenz < 0.1: Kante bleibt → SCC bleibt bestehen → gleicher Level
3. Längster-Pfad auf kondensiertem DAG
```

**Probleme:**
- Schwellwert `0.1` ist eine Heuristik ohne architektonische Begründung.
- SCCs mit Gewichtsdifferenzen, aber Rankdifferenz < 0.1, blieben unaufgelöst.
- `MIN_SCC_SIZE_TO_BREAK = 3` in `SCCBreaker`: 2-Knoten-SCCs wurden nie gebrochen.

### Nachher

```
1. Tarjan → alle SCCs
2. Für jede SCC mit size ≥ 2:
   - Rank-Score je Knoten (gleiche Formel wie vorher)
   - Schneide ALLE Kanten wo  rank(to) > rank(from)   ← kein Schwellwert
   - Wenn keine Kante qualifiziert (alle Ranks gleich → echte Symmetrie):
       → ALLE internen Kanten entfernen
       → Level aus externen Abhängigkeiten (sonst gleicher Level)
3. Längster-Pfad auf kondensiertem DAG
```

**Schlüsseländerung:**  
Der Übergang von "asymmetrisch" zu "symmetrisch" wird nicht mehr über `minW == maxW`
(Gewichtsvergleich) entschieden, sondern über den Rank-Score. Eine SCC mit lauter
gleichgewichtigen Kanten kann trotzdem eine klare Richtung haben, wenn die Topologie
(In/Out-Grade) unterschiedlich ist — das erkennt der Rank-Score, der reine Gewichtsvergleich nicht.

---

## Klassen-Level (LevelCalculator.calculateClassLevels)

### Vorher

```
Phase 1: Pakethypothese-geführter Schnitt (unverändert)
   Kante A→B schneiden wenn pkgLevel(A) < pkgLevel(B) und A,B in selber SCC

Phase 2 (Fallback): SCCBreaker-Heuristik
   SCCBreaker.getGraphWithoutBackEdges()
   → In/Out-Grad Rank-Score mit Schwellwert 0.1
   → MIN_SCC_SIZE_TO_BREAK = 3  (2-Knoten-SCCs ausgelassen!)
   → max. 1/3 der internen Kanten wurden geschnitten

Phase 3: Längster-Pfad
```

### Nachher

```
Phase 1: Pakethypothese-geführter Schnitt (unverändert)

Phase 2 (Fall B): Alle Kanten verbleibender SCCs entfernen
   Keine Heuristik, kein Schwellwert, keine Größenbeschränkung.
   Alle entfernten Kanten werden als classBackEdges registriert
   (damit der LayoutInvariantChecker keine falschen R1-Befunde meldet).

Phase 3: Längster-Pfad
```

---

## LocalLevelCalculator

### Vorher

`BreakMode.MAX_CYCLE_BREAK` (Standard):
- Für jede SCC: teste alle Kanten via BFS + inline-Tarjan
- Wähle die Kante, deren Entfernung die SCC am meisten zerlegt (maximaler `cycleBreakScore`)
- Architektur-Widerspruch als erster Tie-Breaker, dann Gewicht, dann Rank

`BreakMode.RANK` (Legacy):
- Entferne alle Kanten wo rank-Differenz > 0.1 (identisch zum alten Paket-Algorithmus)

**Probleme:**
- `cycleBreakScore` erforderte O(n²) BFS + Tarjan-Läufe pro SCC-Kante → teuer.
- Architektur-Widerspruch als Tie-Breaker mischte globale und lokale Level.
- Zwei konkurrierende Verfahren (RANK / MAX_CYCLE_BREAK) in derselben Klasse.

### Nachher

Einziges Verfahren, identisch zur neuen Paket-Phase:
- Rank-Score je Mitglied
- Alle Kanten schneiden wo `rank(to) > rank(from)`
- Fallback (alle Ranks gleich): alle internen Kanten entfernen
- Längster-Pfad auf dem resultierenden DAG

---

## Was entfernt wurde

| Komponente | Grund |
|---|---|
| `RANK_THRESHOLD = 0.1` in `LevelCalculator` | Schwellwert ohne Begründung |
| `SCCBreaker` in der Produktions-Pipeline | Durch den neuen Fall-B-Ansatz ersetzt |
| `MIN_SCC_SIZE_TO_BREAK = 3` in `SCCBreaker` | Führte zu 2-Knoten-SCCs mit falschen gleichen Levels |
| `BreakMode` Enum in `LocalLevelCalculator` | Nur noch ein Verfahren nötig |
| `MAX_CYCLE_BREAK`-Logik (BFS + inline Tarjan) | O(n²)-Aufwand, durch O(n)-Rank ersetzt |
| `architectureContradiction` als Tie-Breaker | Mischte globale und lokale Level |
| `EdgeCutCandidate` Record | Wurde nur für MAX_CYCLE_BREAK benötigt |

`SCCBreaker.java` bleibt im Code (für bestehende Tests), ist aber mit `@Deprecated` markiert
und wird von keiner Produktions-Klasse mehr aufgerufen.

---

## Was gleich blieb

- **Rank-Score-Formel:** `rank(P) = (Σ aus − Σ ein) / max(1, Σ aus + Σ ein)` — unverändert.
- **Pakethypothese (Phase 1 Klassen):** Kanten gegen die Paket-Ordnung werden weiterhin
  in Phase 1 von `calculateClassLevels` geschnitten.
- **Längster-Pfad:** Level-Zuweisung per Longest-Path auf dem kondensierten DAG — unverändert.
- **TarjanSCCFinder:** Sortierte Traversierung für Determinismus — unverändert.

---

## Warum „alle schneiden statt einer"

Der alte Ansatz schnitt in manchen Konfigurationen eine Kante pro Tarjan-Lauf
(conservatively). Das führte dazu, dass in Multi-Knoten-SCCs die globale Dominanz-Ordnung
(`wAB > wBA → level(A) > level(B)`) nicht in allen Fällen erhalten blieb, weil nach
dem ersten Schnitt die Ranks für die verbleibende Sub-SCC neu berechnet wurden und
ein anderes Ergebnis lieferten.

Der neue Ansatz schneidet **alle** Kanten mit `rank(to) > rank(from)` in einem
einzigen Pass pro Tarjan-Lauf. Das entspricht dem ursprünglichen Verhalten des alten
Codes (der ebenfalls alle qualifizierenden Kanten in einem Pass schnitt) — ohne Threshold.

---

## Symmetrie-Kriterium: Gewicht vs. Topologie

**Alt:** `minW == maxW` → symmetrisch → kein Schnitt (Kanten bleiben, SCC kollabiert)

**Neu:** Rank-Scores alle gleich → symmetrisch → alle Kanten entfernen

Der Unterschied: Eine SCC mit Kanten `A→B: 2, B→A: 2, B→C: 2, C→B: 2, A→C: 2` hat
`minW == maxW == 2` (alt: symmetrisch, alle gleich behandelt). Die Topologie ist aber
nicht symmetrisch — B hat mehr In-Kanten als Out-Kanten. Der Rank-Score erkennt das
und schneidet korrekt.

---

## Messergebnis: Minecraft 1.12.2

| Metrik | Alt (Rank + Threshold 0.1) | Neu (Rank ohne Threshold) | Delta |
|---|---|---|---|
| Wrong-Direction-Edges | ~4100 | 3842 | **−6 %** |

Die Reduktion zeigt, dass der neue Algorithmus architektonisch korrektere Schnitt-Entscheidungen
trifft. Der Schwellwert `0.1` ließ zuvor knapp-asymmetrische Zyklen unaufgelöst; die
verzerrt platzierten Knoten erzeugten nachgelagert mehr Aufwärtskanten. Ohne Threshold
werden diese Zyklen korrekt aufgelöst.
