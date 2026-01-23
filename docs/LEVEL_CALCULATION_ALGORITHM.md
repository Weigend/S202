# Level-Berechnungsalgorithmus - Detaillierte Dokumentation

## Übersicht

S202 berechnet für jede Klasse und jedes Paket ein **Level** (Schicht), das die Position in der Abhängigkeitshierarchie angibt. Die Berechnung unterscheidet dabei zwischen:

1. **Abhängigkeiten innerhalb des gleichen Paket-Teilbaums** (Unterpakete, Eltern-Pakete)
2. **Abhängigkeiten zu Paketen außerhalb des Teilbaums** (komplett separate Paketbäume)

Diese Unterscheidung ist fundamental für eine korrekte Architektur-Visualisierung.

---

## Grundprinzip der Level-Berechnung

### Was bedeutet das Level?

- **Level 0** = Basis-Elemente ohne externe Abhängigkeiten (Blätter der Abhängigkeitshierarchie)
- **Level 1** = Elemente, die nur von Level-0-Elementen abhängen
- **Level N** = Elemente, die von Level-(N-1)-Elementen abhängen

**Kernregel**: Wenn Element A von Element B abhängt, dann gilt: `A.level > B.level`

---

## Der 8-Schritte-Algorithmus

Der `LevelCalculator` führt die Berechnung in **8 aufeinanderfolgenden Schritten** durch:

```
┌─────────────────────────────────────────────────────────────────┐
│  Schritt 1: CalculatedElementInfo für alle Klassen erstellen   │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 2: CalculatedElementInfo für alle Pakete erstellen    │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 3: Klassen-Level berechnen (SCC-aware)                │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 4: Paket-Level = max(Klassen-Level im Paket)          │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 5: Level zu Eltern-Paketen propagieren                │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 6: Paket-Level für Kreuz-Paket-Abhängigkeiten         │
│             anpassen (KERNLOGIK!)                               │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 7: Klassen-Level für Unterpaket-Abhängigkeiten        │
│             anpassen (GEMISCHTE PAKETE!)                        │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 8: Rückwärts-Beziehungen (dependents) aktualisieren   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Schritt 3: Klassen-Level-Berechnung (SCC-aware)

### Tarjan-SCC-Algorithmus

Für zyklische Abhängigkeiten verwendet S202 den **Tarjan-Algorithmus** zur Erkennung von **Strongly Connected Components (SCCs)**:

```
┌─────────────────────────────────────────────────────────────────┐
│  SCC = Gruppe von Klassen, in der jede Klasse von jeder        │
│        anderen erreichbar ist (= Zyklus)                        │
└─────────────────────────────────────────────────────────────────┘
```

**Wichtig**: Alle Klassen in einem SCC erhalten **das gleiche Level**.

### Algorithmus-Ablauf

1. **SCCs finden** mit Tarjan-Algorithmus
2. **SCC-DAG erstellen** (Abhängigkeitsgraph zwischen SCCs, garantiert azyklisch)
3. **SCC-Level berechnen** (topologische Sortierung)
4. **Klassen-Level zuweisen** (jede Klasse erhält Level ihres SCCs)

### Formel

```
Klassen-Level = max(Level aller Abhängigkeiten) + 1
              = 0, wenn keine Abhängigkeiten
```

---

## Schritt 4: Paket-Level aus Klassen-Level

```
Paket-Level = max(Level aller Klassen im Paket)
```

### Beispiel

```
com.example/
  ├── A.java (Level 0)
  ├── B.java (Level 1)  
  └── C.java (Level 2)

→ com.example.level = max(0, 1, 2) = 2
```

---

## Schritt 5: Level-Propagation zu Eltern-Paketen

Eltern-Pakete erben **mindestens** das Level ihrer Kind-Pakete:

```
com/                      (Level = max aller Kinder)
├── com.example/          (Level 2)
├── com.example1/         (Level 0)
└── com.example2/         (Level 3)

→ com.level = max(2, 0, 3) = 3
```

---

## Schritt 6: Kreuz-Paket-Abhängigkeiten (KERNLOGIK!)

### Die zentrale Unterscheidung

**Dies ist die wichtigste Logik**: Es wird unterschieden zwischen:

| Abhängigkeitstyp | Beschreibung | Auswirkung auf Level |
|------------------|--------------|---------------------|
| **Innerhalb des Teilbaums** | Unterpakete oder Eltern-Pakete | Keine direkte Level-Erhöhung |
| **Außerhalb des Teilbaums** | Komplett separate Paketbäume | Level muss höher sein |

### Prüfung: Gleicher Teilbaum oder nicht?

```java
private boolean isInSameSubtree(String pkg1, String pkg2) {
    return pkg1.startsWith(pkg2 + ".") || pkg2.startsWith(pkg1 + ".");
}
```

**Beispiele**:

| pkg1 | pkg2 | Gleicher Teilbaum? |
|------|------|-------------------|
| `com.example.sub` | `com.example` | ✅ Ja (sub ist Kind von example) |
| `com.example` | `com.example.sub` | ✅ Ja (example ist Eltern von sub) |
| `com.example` | `com.example1` | ❌ Nein (verschiedene Teilbäume) |
| `com.example` | `com.other.pkg` | ❌ Nein (verschiedene Teilbäume) |
| `de.weigend.ui` | `de.weigend.domain` | ❌ Nein (verschiedene Teilbäume) |

### Regelwerk für Paket-Level-Anpassung

```
WENN Paket A auf Klassen in Paket B zugreift
UND A und B NICHT im gleichen Teilbaum sind
DANN: A.level > B.level (A muss höher sein)
```

### Algorithmus (iterativ bis stabil)

```java
for (jedes Paket A mit Abhängigkeit zu Paket B) {
    if (!isInSameSubtree(A, B)) {
        // Externe Abhängigkeit!
        if (A.level <= B.level) {
            A.level = B.level + 1;  // A muss höher sein
        }
    }
}
// Nach Anpassung: Level zu Eltern-Paketen propagieren
```

---

## Schritt 7: Klassen-Level für Unterpaket-Abhängigkeiten (GEMISCHTE PAKETE!)

### Das Problem: Gemischte Pakete

Wenn ein Paket **sowohl Klassen als auch Unterpakete** enthält, kann es zu visuellen Inkonsistenzen kommen:

```
de.weigend.s202.ui/
├── ArchitectureView.java (Klasse, Level 1)
├── LevelClassBox.java (Klasse, Level 0)
├── model/                      (Unterpaket, Level 6)
│   └── ArchitectureNode.java   (Klasse, Level 0)
└── demo/                       (Unterpaket, Level 2)
    └── DemoApp.java            (Klasse, Level 2)
```

**Problem**: `ArchitectureView` (L1) hängt von `model.ArchitectureNode` ab. Das Unterpaket `model` hat Level 6 (wegen Cross-Package-Abhängigkeiten zu `domain`). In der Visualisierung würde die Abhängigkeit **nach oben** zeigen!

### Die Lösung

Für jede Klasse C im Paket P, die von einer Klasse D in einem Unterpaket P.sub abhängt:
- C.level muss > P.sub.level sein

```java
for (jede Klasse C im Paket P) {
    for (jede Abhängigkeit D von C) {
        String depPackage = getPackage(D);
        if (depPackage.startsWith(P + ".")) {  // D ist in Unterpaket
            PackageInfo subPkg = getPackage(depPackage);
            if (C.level <= subPkg.level) {
                C.level = subPkg.level + 1;  // C muss höher sein
            }
        }
    }
}
```

### Ergebnis nach Schritt 7

```
de.weigend.s202.ui/
├── ArchitectureView.java (Klasse, Level 7)  ← erhöht!
├── LevelClassBox.java (Klasse, Level 0)
├── model/                      (Unterpaket, Level 6)
│   └── ArchitectureNode.java   (Klasse, Level 0)
└── demo/                       (Unterpaket, Level 2)
    └── DemoApp.java            (Klasse, Level 2)
```

Jetzt zeigt die Abhängigkeit von `ArchitectureView` (L7) zu `model` (L6) **nach unten** ✓

**Wichtig**: Die Paket-Level werden nach diesem Schritt **nicht** neu berechnet, um Kaskadeneffekte zu vermeiden.

---

## Warum diese Unterscheidung?

### Szenario 1: Abhängigkeit innerhalb des Teilbaums

```
com.example/
├── domain/
│   └── Model.java (Level 0)
└── service/
    └── Service.java → Model.java (Level 1)
```

Hier sind `domain` und `service` **Geschwister-Pakete unter `com.example`**. Die Klassen-Level werden normal berechnet, aber für die **Paket-Hierarchie** ist wichtig:

- `com.example` = max(Level domain, Level service) = max(0, 1) = 1

### Szenario 2: Abhängigkeit außerhalb des Teilbaums

```
com.example/                    ←── Unabhängiger Teilbaum
└── util/
    └── Helper.java (Level 0)

com.other/                      ←── Anderer Teilbaum
└── main/
    └── App.java → Helper.java
```

Hier ist `com.other` ein **völlig separater Teilbaum** von `com.example`. 

**Ergebnis**:
- `com.other.level > com.example.level` (weil com.other von com.example abhängt)

---

## Vollständiges Beispiel: test-example Projekt

### Paketstruktur

```
com/
├── example/                    # Teilbaum 1
│   ├── A.java                  # Keine Deps → Level 0
│   ├── B.java → A              # Level 1
│   └── C.java → B              # Level 2
│
├── example1/                   # Teilbaum 2
│   └── X.java                  # Keine Deps → Level 0
│
└── example2/                   # Teilbaum 3
    ├── D.java                  # Keine Deps → Level 0
    ├── B.java → D              # Level 1
    ├── C.java → D              # Level 1
    ├── A.java → B, C           # Level 2
    └── E.java → A, example.B, example1.X  # Level 3
```

### Abhängigkeitsgraph

```
                com.example                     com.example1
                    │                               │
        ┌───────────┴───────────┐                   │
        ▼           ▼           ▼                   ▼
   ┌────────┐  ┌────────┐  ┌────────┐         ┌────────┐
   │   A    │  │   B    │  │   C    │         │   X    │
   │  L0    │  │  L1    │  │  L2    │         │  L0    │
   └────────┘  └────────┘  └────────┘         └────────┘
        ▲                      │
        │                      │
        └──────────────────────┘
                    
                           com.example2
                               │
        ┌──────────┬───────────┼───────────┬──────────┐
        ▼          ▼           ▼           ▼          ▼
   ┌────────┐ ┌────────┐  ┌────────┐  ┌────────┐ ┌────────┐
   │   D    │ │   B    │  │   C    │  │   A    │ │   E    │
   │  L0    │ │  L1    │  │  L1    │  │  L2    │ │  L3    │
   └────────┘ └────────┘  └────────┘  └────────┘ └────────┘
        ▲          ▲           ▲           ▲          │
        │          │           │           │          │
        └──────────┴───────────┘           └──────────┘
                                   ┌──────────────────┤
                                   ▼                  ▼
                           com.example.B      com.example1.X
```

### Schritt-für-Schritt Level-Berechnung

#### Schritt 3: Klassen-Level

| Klasse | Abhängigkeiten | Berechnung | Level |
|--------|---------------|------------|-------|
| com.example.A | - | max() + 1 = 0 | **0** |
| com.example.B | A | max(0) + 1 = 1 | **1** |
| com.example.C | B | max(1) + 1 = 2 | **2** |
| com.example1.X | - | max() + 1 = 0 | **0** |
| com.example2.D | - | max() + 1 = 0 | **0** |
| com.example2.B | D | max(0) + 1 = 1 | **1** |
| com.example2.C | D | max(0) + 1 = 1 | **1** |
| com.example2.A | B, C | max(1,1) + 1 = 2 | **2** |
| com.example2.E | A, example.B, example1.X | max(2,1,0) + 1 = 3 | **3** |

#### Schritt 4: Paket-Level aus Klassen

| Paket | Klassen-Level | Paket-Level |
|-------|---------------|-------------|
| com.example | max(0, 1, 2) | **2** |
| com.example1 | max(0) | **0** |
| com.example2 | max(0, 1, 1, 2, 3) | **3** |

#### Schritt 5: Eltern-Paket-Propagation

| Paket | Kind-Pakete-Level | Paket-Level |
|-------|-------------------|-------------|
| com | max(2, 0, 3) | **3** |

#### Schritt 6: Kreuz-Paket-Abhängigkeiten prüfen

**Analyse der Klassen-Abhängigkeiten nach Paket:**

| Quell-Paket | Ziel-Paket | Gleicher Teilbaum? | Aktion |
|-------------|------------|-------------------|--------|
| com.example2 (L3) | com.example (L2) | ❌ Nein | Prüfen: 3 > 2 ✓ OK |
| com.example2 (L3) | com.example1 (L0) | ❌ Nein | Prüfen: 3 > 0 ✓ OK |

Alle Bedingungen erfüllt - keine Level-Anpassung nötig.

#### Endergebnis

```
Level 3: com, com.example2
Level 2: com.example
Level 0: com.example1

Klassen:
Level 0: com.example.A, com.example1.X, com.example2.D
Level 1: com.example.B, com.example2.B, com.example2.C
Level 2: com.example.C, com.example2.A
Level 3: com.example2.E
```

---

## Beispiel: Warum Teilbaum-Unterscheidung wichtig ist

### Szenario ohne Unterscheidung (falsch!)

```
de.weigend.s202/
├── ui/
│   └── MainView.java → domain.Model
└── domain/
    └── Model.java
```

**Ohne Teilbaum-Logik** würde man sagen:
- `ui` hängt von `domain` ab
- Also: `ui.level = domain.level + 1`

Aber beide sind **Unterpakete von `de.weigend.s202`**! 
In der Paket-Hierarchie sind sie Geschwister, nicht getrennte Bäume.

### Mit korrekter Teilbaum-Prüfung

```java
isInSameSubtree("de.weigend.s202.ui", "de.weigend.s202.domain")
= "de.weigend.s202.ui".startsWith("de.weigend.s202.domain.") 
  || "de.weigend.s202.domain".startsWith("de.weigend.s202.ui.")
= false || false
= false  // NICHT im gleichen Teilbaum!
```

**Korrektur**: Diese sind tatsächlich **verschiedene Teilbäume** (Geschwister-Pakete sind separate Teilbäume!). Die Regel greift:
- `ui.level > domain.level`

### Wann ist es der "gleiche Teilbaum"?

```
de.weigend.s202.domain/
└── de.weigend.s202.domain.model/
    └── Entity.java
```

```java
isInSameSubtree("de.weigend.s202.domain", "de.weigend.s202.domain.model")
= "de.weigend.s202.domain".startsWith("de.weigend.s202.domain.model.")  // false
  || "de.weigend.s202.domain.model".startsWith("de.weigend.s202.domain.")  // true!
= true  // IM gleichen Teilbaum
```

Hier ist `domain.model` ein **Kind** von `domain`. Das ist der gleiche Teilbaum.

---

## Zusammenfassung der Regeln

### Klassen-Level
```
Klassen-Level = max(Level aller Abhängigkeiten) + 1
Klassen im gleichen SCC (Zyklus) = gleiches Level
```

### Paket-Level
```
Paket-Level = max(Level aller Klassen im Paket)
Eltern-Paket ≥ max(Level aller Kind-Pakete)
```

### Kreuz-Paket-Regel (extern)
```
WENN Paket A von Paket B abhängt (via Klassen-Abhängigkeit)
UND A und B sind NICHT im gleichen Teilbaum
DANN: A.level > B.level
```

### Unterpaket-Regel (gemischte Pakete)
```
WENN Klasse C in Paket P von Klasse D in Unterpaket P.sub abhängt
DANN: C.level > P.sub.level
```

### Teilbaum-Definition
```
A und B sind im gleichen Teilbaum ⟺ 
    A.startsWith(B + ".") || B.startsWith(A + ".")
    
(= eines ist Vorfahre/Nachfahre des anderen)
```

---

## Code-Referenzen

| Klasse | Datei | Verantwortung |
|--------|-------|---------------|
| `LevelCalculator` | [domain/LevelCalculator.java](../analyzer/src/main/java/de/weigend/s202/domain/LevelCalculator.java) | Hauptlogik mit 8 Schritten |
| `BasicClassLevelCalculationStrategy` | [analysis/strategy/impl/BasicClassLevelCalculationStrategy.java](../analyzer/src/main/java/de/weigend/s202/analysis/strategy/impl/BasicClassLevelCalculationStrategy.java) | SCC-aware Klassen-Level |
| `TarjanSCCFinder` | [analysis/scc/TarjanSCCFinder.java](../analyzer/src/main/java/de/weigend/s202/analysis/scc/TarjanSCCFinder.java) | Zyklen-Erkennung |
| `SimpleMaxAggregationStrategy` | [analysis/strategy/aggregation/SimpleMaxAggregationStrategy.java](../analyzer/src/main/java/de/weigend/s202/analysis/strategy/aggregation/SimpleMaxAggregationStrategy.java) | max + 1 Aggregation |

---

## Visualisierung der Paket-Hierarchie

```
Level 3: ┌────────────────────────────────────────────────────────┐
         │                         com                            │
         │  ┌──────────────────────────────────────────────────┐  │
         │  │                   com.example2                    │  │
         │  │  E(L3) ─────────► A(L2)                          │  │
         │  │     │                │                            │  │
         │  │     ▼                ▼                            │  │
         │  │  ─────────────► B(L1)  C(L1)                     │  │
         │  │  │                 │      │                       │  │
         │  │  │                 ▼      ▼                       │  │
         │  │  │              D(L0) ◄───┘                       │  │
         │  └──┼──────────────────────────────────────────────┘  │
         │     │                                                  │
Level 2: │     │    ┌───────────────────────────────────┐        │
         │     │    │          com.example               │        │
         │     │    │  C(L2) ──► B(L1) ──► A(L0)        │        │
         │     └────┼─────────────┘                      │        │
         │          └───────────────────────────────────┘        │
         │                                                        │
Level 0: │     ┌───────────────────────────────────┐              │
         │     │          com.example1              │              │
         │     │           X(L0)                    │◄─────────────┤
         │     └───────────────────────────────────┘              │
         └────────────────────────────────────────────────────────┘
         
         ──────► = Klassen-Abhängigkeit
         ======► = Paket-Abhängigkeit (implizit durch Klassen)
```

---

## Fazit

Die Level-Berechnung in S202 verwendet einen ausgeklügelten 8-Schritte-Algorithmus, der:

1. **Zyklen korrekt behandelt** (via Tarjan SCC)
2. **Paket-Hierarchien berücksichtigt** (Eltern erben max von Kindern)
3. **Interne vs. externe Abhängigkeiten unterscheidet** (Teilbaum-Prüfung)
4. **Gemischte Pakete korrekt behandelt** (Klassen neben Unterpaketen)
5. **Architekturverletzungen erkennt** (wenn A von B abhängt, muss A.level > B.level)

Die Unterscheidung zwischen "internen" (gleicher Teilbaum) und "externen" (verschiedene Teilbäume) Abhängigkeiten sowie die spezielle Behandlung von gemischten Paketen ermöglicht eine präzise Darstellung der tatsächlichen Architektur-Schichten, bei der alle Abhängigkeiten nach unten zeigen.
