# Strategy Pattern - Level Calculation Architecture

## Übersicht

Der S202 Code Analyzer implementiert nun ein **pluggables Strategy-Pattern** für die Level-Berechnung von Klassen und Paketen. Dies ermöglicht flexible, erweiterbare Algorithmen zur Berechnung von Abhängigkeitsebenen.

## Architektur

### Strategische Schichten

```
┌─────────────────────────────────────────────┐
│     LevelCalculationStrategyContext         │
│  (Factory & Strategy Aggregator)            │
└────────────┬────────────────────────────────┘
             │
    ┌────────┴──────────────────────┐
    │                               │
┌───▼──────────────────┐   ┌───────▼───────────────┐
│ ClassLevelCalculation│   │ PackageLevelCalculation│
│ Strategy             │   │ Strategy               │
└───┬──────────────────┘   └───────┬───────────────┘
    │                               │
    └────┬───────────────────────┬──┘
         │                       │
    ┌────▼────────────────────────▼──┐
    │ ClassAggregationStrategy       │
    │ (kombiniert Abhängigkeitslevels)│
    └────────────────────────────────┘
```

## Kern-Interfaces

### 1. `ClassAggregationStrategy`
**Zweck:** Kombiniert die Levels von mehreren Abhängigkeiten zu einem einzelnen Level.

```java
public interface ClassAggregationStrategy {
    int aggregate(Set<Integer> dependencyLevels);
    String getName();
}
```

**Implementierungen:**
- `SimpleMaxAggregationStrategy`: Level = max(Abhängigkeit-Levels) + 1
- `WeightedAggregationStrategy`: Gewichtete Kombination von Max und Durchschnitt

### 2. `ClassLevelCalculationStrategy`
**Zweck:** Berechnet Level für Klassen basierend auf ihren Abhängigkeiten.

```java
public interface ClassLevelCalculationStrategy {
    Map<String, Integer> calculateClassLevels(Map<String, Set<String>> classDependencies);
    String getName();
}
```

**Implementierung:** `BasicClassLevelCalculationStrategy`
- Iterative Berechnung bis zur Stabilität
- Verwendet `ClassAggregationStrategy` zur Aggregation
- Basis-Algorithmus: Max-basierte Tiefenberechnung

### 3. `PackageLevelCalculationStrategy`
**Zweck:** Berechnet Level für Pakete basierend auf Package-Dependencies.

```java
public interface PackageLevelCalculationStrategy {
    Map<String, Integer> calculatePackageLevels(Map<String, Set<String>> packageDependencies);
    String getName();
}
```

**Implementierung:** `BasicPackageLevelCalculationStrategy`
- Ähnliche iterative Berechnung wie ClassLevelCalculationStrategy
- Package Level = 1 + max(abhängige Package Levels)

## Factory-Klasse

### `LevelCalculationStrategyContext`
Verwaltung und Konfiguration aller Strategien.

```java
// Default-Strategien mit SimpleMaxAggregation
LevelCalculationStrategyContext context = LevelCalculationStrategyContext.createDefault();

// Oder custom-Konfiguration
LevelCalculationStrategyContext context = new LevelCalculationStrategyContext(
    classStrategy,
    packageStrategy,
    aggregationStrategy
);
```

## Integration in LevelCalculator

Die `LevelCalculator`-Klasse wurde aktualisiert, um Strategien zu unterstützen:

```java
public class LevelCalculator {
    private final LevelCalculationStrategyContext strategyContext;
    
    public LevelCalculator() {
        this(LevelCalculationStrategyContext.createDefault());
    }
    
    public LevelCalculator(LevelCalculationStrategyContext strategyContext) {
        this.strategyContext = strategyContext;
    }
    
    // ... vorhandene Methoden nutzen nun strategyContext ...
}
```

## Algorithmus-Details

### Klassenlevel-Berechnung

**Iterative Berechnung:**
1. Initialisiere alle Klassen mit Level 0
2. Für jede Iteration:
   - Für jede Klasse mit Abhängigkeiten:
     - Wenn alle abhängigen Klassen ein Level haben:
       - Berechne neues Level = Aggregation(dependency-levels)
       - Wenn geändert, markiere als "changed"
3. Wiederhole bis keine Änderungen mehr oder maxIterations erreicht

**Beispiel (Diamond-Pattern):**
```
Input:
  ClassD -> []
  ClassB -> [ClassD]
  ClassC -> [ClassD]
  ClassA -> [ClassB, ClassC]

Iteration 1:
  ClassD: Level 0 (keine Abhängigkeiten)
  ClassB: aggregate({0}) = 1
  ClassC: aggregate({0}) = 1
  ClassA: aggregate({1, 1}) = 2

Ergebnis:
  ClassD: L0, ClassB: L1, ClassC: L1, ClassA: L2
```

### Package-Level-Berechnung

Ähnliche iterative Berechnung, aber mit Package-Dependencies:
- Input: Map von Package-Name zu Set von Package-Dependencies
- Outputput: Map von Package-Name zu Level
- Package Level wird basierend auf den Levels seiner Abhängigkeiten berechnet

## Test-Abdeckung

Die neuen Strategien haben umfassende Unit-Tests:

| Test-Klasse | Szenarios |
|-------------|-----------|
| `SimpleMaxAggregationStrategyTest` | Leere Sets, Single/Multiple Levels, Max-Berechnung |
| `BasicClassLevelCalculationStrategyTest` | Simple Chain, Diamond Pattern, Keine Abhängigkeiten |
| `BasicPackageLevelCalculationStrategyTest` | Gleiche Szenarien wie Class-Tests |
| `LevelCalculationStrategyContextTest` | Factory-Methoden, Custom-Konfiguration |

**Alle Tests bestanden! ✅**

## Verwendungsbeispiel

```java
// 1. Standard-Strategien verwenden
LevelCalculator calculator = new LevelCalculator();
DomainModel model = calculator.calculate(rawModel);

// 2. Custom-Aggregation
ClassAggregationStrategy weightedAggregation = 
    new WeightedAggregationStrategy();
ClassLevelCalculationStrategy classStrat = 
    new BasicClassLevelCalculationStrategy(weightedAggregation);
PackageLevelCalculationStrategy packageStrat = 
    new BasicPackageLevelCalculationStrategy(weightedAggregation);

LevelCalculationStrategyContext context = 
    new LevelCalculationStrategyContext(classStrat, packageStrat, weightedAggregation);

LevelCalculator customCalculator = new LevelCalculator(context);
DomainModel model = customCalculator.calculate(rawModel);
```

## Zukünftige Erweiterungen

Die Architektur ist vorbereitet für:

1. **Alternative Aggregation-Strategien:**
   - Fibonacci-basierte Levels
   - Kritischer-Pfad-basierte Berechnung
   - Zyklus-bewusste Aggregation

2. **Alternative Class-Level-Strategien:**
   - SCC-basierte Berechnung
   - Gewichts-basierte Berechnung
   - Kritikalitäts-basierte Berechnung

3. **Alternative Package-Level-Strategien:**
   - Import-Häufigkeit-basiert
   - Coupling-basiert
   - Module-Boundary-basiert

## Paket-Struktur

```
de/weigend/s202/analysis/strategy/
├── ClassAggregationStrategy.java
├── ClassLevelCalculationStrategy.java
├── PackageLevelCalculationStrategy.java
├── LevelCalculationStrategyContext.java
├── aggregation/
│   ├── SimpleMaxAggregationStrategy.java
│   └── WeightedAggregationStrategy.java
└── impl/
    ├── BasicClassLevelCalculationStrategy.java
    └── BasicPackageLevelCalculationStrategy.java
```

## Vorteile dieser Architektur

✅ **Testbarkeit:** Jede Strategie kann isoliert getestet werden  
✅ **Erweiterbarkeit:** Neue Strategien können leicht hinzugefügt werden  
✅ **Wartbarkeit:** Klare Trennung von Algorithmus-Logik  
✅ **Flexibilität:** Laufzeit-Konfiguration verschiedener Kombinationen  
✅ **Dokumentation:** Strategien dokumentieren ihre Zwecke und Algorithmen  

