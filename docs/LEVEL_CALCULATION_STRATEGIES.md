# Level-Berechnungsstrategien

> **📖 Ausführliche Dokumentation**: Für eine detaillierte Beschreibung des vollständigen Algorithmus inkl. Paket-Hierarchien und Kreuz-Paket-Abhängigkeiten siehe [LEVEL_CALCULATION_ALGORITHM.md](LEVEL_CALCULATION_ALGORITHM.md).

## Übersicht

Diese Kurzreferenz beschreibt die **Strategy-Pattern-Architektur** der Level-Berechnung.

S202 berechnet für jede Klasse und jedes Paket ein **Level** (Schicht). Das Level gibt an, wie "tief" ein Element in der Abhängigkeitshierarchie liegt:

- **Level 0** = Basis-Elemente ohne Abhängigkeiten (Blätter)
- **Level 1** = Elemente, die nur von Level-0-Elementen abhängen
- **Level N** = Elemente, die von Level-(N-1)-Elementen abhängen

## Klassen-Level-Berechnung

Die `BasicClassLevelCalculationStrategy` berechnet Klassen-Level mit SCC-Algorithmus:

### Schritt 1: Initialisierung
Alle Elemente starten mit Level 0.

```
A → 0
B → 0
C → 0
```

### Schritt 2: Iterative Berechnung
Der Algorithmus iteriert, bis sich keine Levels mehr ändern:

```
Für jedes Element:
    1. Sammle die Levels aller Abhängigkeiten
    2. Berechne neues Level = max(Abhängigkeits-Levels) + 1
    3. Falls Level sich ändert → weitere Iteration nötig
```

### Schritt 3: Aggregation
Die **SimpleMaxAggregationStrategy** berechnet:

```
Level = max(alle Dependency-Levels) + 1

Beispiel:
  A hängt ab von B (Level 0) und C (Level 1)
  → A.level = max(0, 1) + 1 = 2
```

## Beispiel

Gegeben:
```
A → B, C
B → D
C → (keine)
D → (keine)
```

Iteration 1:
```
D: keine Deps → Level 0
C: keine Deps → Level 0
B: Dep D=0 → Level max(0)+1 = 1
A: Deps B=1, C=0 → Level max(1,0)+1 = 2
```

Ergebnis:
```
Level 0: C, D
Level 1: B
Level 2: A
```

## Zyklen-Behandlung

Für Zyklen (A → B → A) verwendet S202 den **Tarjan-SCC-Algorithmus** zur Erkennung von Strongly Connected Components. Alle Klassen innerhalb eines Zyklus erhalten automatisch das gleiche Level.

**Details**: Siehe [LEVEL_CALCULATION_ALGORITHM.md](LEVEL_CALCULATION_ALGORITHM.md#schritt-3-klassen-level-berechnung-scc-aware).

## Code-Struktur

```
analysis/strategy/
├── ClassAggregationStrategy.java       # Interface für Aggregation
├── ClassLevelCalculationStrategy.java  # Interface für Klassen-Level
├── LevelCalculationStrategyContext.java # Context mit Strategies
├── aggregation/
│   └── SimpleMaxAggregationStrategy.java  # max + 1
└── impl/
    └── BasicClassLevelCalculationStrategy.java  # SCC-aware Berechnung
```

**Hinweis**: Die Paket-Level-Berechnung sowie die Anpassung von Klassen-Level für 
gemischte Pakete (Pakete mit Klassen UND Unterpaketen) ist direkt im `LevelCalculator` 
implementiert (8-Schritte-Algorithmus). Siehe [LEVEL_CALCULATION_ALGORITHM.md](LEVEL_CALCULATION_ALGORITHM.md) für Details.

## Erweiterbarkeit

Durch das Strategy-Pattern können alternative Berechnungen implementiert werden:

- **WeightedAggregationStrategy**: Gewichteter Durchschnitt statt Maximum
- **MedianAggregationStrategy**: Median der Dependency-Levels
- **CustomAggregationStrategy**: Projekt-spezifische Logik
