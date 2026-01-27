# Level-Berechnungsstrategien

> **📖 Ausführliche Dokumentation**: Für eine detaillierte Beschreibung des vollständigen Algorithmus inkl. Paket-Hierarchien und Kreuz-Paket-Abhängigkeiten siehe [LEVEL_CALCULATION_ALGORITHM.md](LEVEL_CALCULATION_ALGORITHM.md).

## Übersicht

Diese Kurzreferenz beschreibt die **Strategy-Pattern-Architektur** der Level-Berechnung.

S202 berechnet für jede Klasse und jedes Paket ein **Level** (Schicht). Das Level gibt an, wie "tief" ein Element in der Abhängigkeitshierarchie liegt:

- **Level 0** = Basis-Elemente ohne Abhängigkeiten (Blätter)
- **Level 1** = Elemente, die nur von Level-0-Elementen abhängen
- **Level N** = Elemente, die von Level-(N-1)-Elementen abhängen

## Klassen-Level-Berechnung

S202 bietet zwei Strategien zur Klassen-Level-Berechnung:

| Strategie | Beschreibung | Default |
|-----------|--------------|---------|
| `HeuristicSCCBreakingStrategy` | Bricht große Zyklen intelligent auf | ✅ |
| `BasicClassLevelCalculationStrategy` | Alle Klassen im Zyklus = gleiches Level | |

Die **Default-Strategie** ist `HeuristicSCCBreakingStrategy`, da sie bei Projekten mit vielen Zyklen (z.B. Minecraft) eine sinnvolle Hierarchie erzeugt.

### Algorithmus

#### Schritt 1: Initialisierung
Alle Elemente starten mit Level 0.

```
A → 0
B → 0
C → 0
```

#### Schritt 2: Iterative Berechnung
Der Algorithmus iteriert, bis sich keine Levels mehr ändern:

```
Für jedes Element:
    1. Sammle die Levels aller Abhängigkeiten
    2. Berechne neues Level = max(Abhängigkeits-Levels) + 1
    3. Falls Level sich ändert → weitere Iteration nötig
```

#### Schritt 3: Aggregation
Die **SimpleMaxAggregationStrategy** (Default) berechnet:

```
Level = max(alle Dependency-Levels) + 1

Beispiel:
  A hängt ab von B (Level 0) und C (Level 1)
  → A.level = max(0, 1) + 1 = 2
```

### Zyklen-Behandlung

Für Zyklen (A → B → A) verwendet S202 den **Tarjan-SCC-Algorithmus** zur Erkennung von Strongly Connected Components.

- **HeuristicSCCBreakingStrategy** (Default): Bricht Zyklen durch Identifikation von "Rückkanten" auf. Erzeugt eine sinnvolle Hierarchie auch bei stark vernetzten Projekten.
- **BasicClassLevelCalculationStrategy**: Alle Klassen innerhalb eines Zyklus erhalten das gleiche Level.

**Details**: Siehe [LEVEL_CALCULATION_ALGORITHM.md](LEVEL_CALCULATION_ALGORITHM.md#schritt-3-klassen-level-berechnung-scc-aware).

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

## Code-Struktur

```
domain/
└── LevelCalculationStrategyFactory.java  # Factory für Strategy-Erstellung

analysis/strategy/
├── ClassAggregationStrategy.java          # Interface für Aggregation
├── ClassLevelCalculationStrategy.java     # Interface für Klassen-Level
├── LevelCalculationStrategyContext.java   # Context mit Strategies
├── aggregation/
│   ├── SimpleMaxAggregationStrategy.java  # max + 1 (Default)
│   └── WeightedAggregationStrategy.java   # gewichteter Durchschnitt
└── impl/
    ├── BasicClassLevelCalculationStrategy.java     # Standard SCC-Berechnung
    └── HeuristicSCCBreakingStrategy.java           # SCC-Aufbrechen (Default)
```

## Verwendung

```java
// Default: HeuristicSCCBreakingStrategy (empfohlen)
LevelCalculationStrategyContext context = 
    LevelCalculationStrategyFactory.createDefault();

// Alternative: BasicClassLevelCalculationStrategy (strenge SCC-Gruppierung)
LevelCalculationStrategyContext context = 
    LevelCalculationStrategyFactory.createWithBasicStrategy();

// Verwendung mit LevelCalculator
LevelCalculator calculator = new LevelCalculator(context);
```

**Hinweis**: Die Paket-Level-Berechnung sowie die Anpassung von Klassen-Level für 
gemischte Pakete (Pakete mit Klassen UND Unterpaketen) ist direkt im `LevelCalculator` 
implementiert (8-Schritte-Algorithmus). Siehe [LEVEL_CALCULATION_ALGORITHM.md](LEVEL_CALCULATION_ALGORITHM.md) für Details.

## Aggregationsstrategien

| Strategie | Formel | Beschreibung |
|-----------|--------|--------------|
| `SimpleMaxAggregationStrategy` | `max(deps) + 1` | Standard: Höchstes Dependency-Level + 1 |
| `WeightedAggregationStrategy` | `0.7*max + 0.3*avg + 1` | Gewichteter Durchschnitt (70% Max, 30% Avg) |

## Erweiterbarkeit

Durch das Strategy-Pattern können alternative Berechnungen implementiert werden:

- **MedianAggregationStrategy**: Median der Dependency-Levels
- **CustomAggregationStrategy**: Projekt-spezifische Logik
- **Custom ClassLevelCalculationStrategy**: Komplett eigene Level-Berechnung
