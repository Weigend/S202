# Level-Berechnungsalgorithmus - Detaillierte Dokumentation

## Übersicht

S202 berechnet für jede Klasse und jedes Paket ein **Level** (Schicht), das die Position in der Abhängigkeitshierarchie angibt. Die Berechnung erfolgt in zwei Phasen:

1. **Phase 1 (LevelCalculator)**: Analysiert Abhängigkeiten und berechnet initiale Levels
2. **Phase 2 (DistrictRowLevelCalculator)**: Berechnet finale Layout-Levels **lokal pro Paket** basierend auf Distinct-Target-Counting und SCC-Erkennung

Die Levels werden lokal pro Paket berechnet — nur Abhängigkeiten zwischen Geschwistern sind relevant.

---

## Grundprinzip der Level-Berechnung

### Was bedeutet das Level?

- **Level 0** = Basis-Elemente ohne externe Abhängigkeiten (Blätter der Abhängigkeitshierarchie)
- **Level 1** = Elemente, die nur von Level-0-Elementen abhängen
- **Level N** = Elemente, die von Level-(N-1)-Elementen abhängen

**Kernregel**: Wenn Element A von Element B abhängt, dann gilt: `A.level > B.level`

---

## Die Pipeline

Die Level-Berechnung erfolgt in zwei Phasen:

### Phase 1: LevelCalculator (Abhängigkeiten + initiale Levels)

```
┌─────────────────────────────────────────────────────────────────┐
│  Schritt 1: CalculatedElementInfo für alle Klassen erstellen   │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 2: CalculatedElementInfo für alle Pakete erstellen    │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 3: Klassen-Level berechnen (SCC-aware)                │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 4-5: Paket-Level aus Klassen + Eltern-Propagation     │
├─────────────────────────────────────────────────────────────────┤
│  Schritt 6: Rückwärts-Beziehungen (dependents) aktualisieren   │
└─────────────────────────────────────────────────────────────────┘
```

### Phase 2: DistrictRowLevelCalculator (finale Layout-Levels)

```
┌─────────────────────────────────────────────────────────────────┐
│  Für jedes Paket im Baum:                                      │
│  → Berechne LOKALE Levels für ALLE Kinder (Klassen + Pakete)   │
│  → Basierend auf Inter-Geschwister-Abhängigkeitsrichtung        │
│  → SCC-Erkennung + DAG-Level-Zuweisung                         │
│  → Überschreibt die Levels aus Phase 1                          │
└─────────────────────────────────────────────────────────────────┘
```

**Wichtig**: Phase 2 überschreibt alle Levels aus Phase 1. Die Levels aus Phase 1
dienen als Zwischenergebnis — die finalen Layout-Levels werden lokal pro Paket
berechnet. Klassen, die nur Abhängigkeiten nach außen haben, erhalten Level 0
innerhalb ihres Pakets.

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

### Zwei Strategien für SCC-Behandlung

#### 1. HeuristicSCCBreakingStrategy (Default)

**Große SCCs werden intelligent aufgebrochen.**

- ✅ Erzeugt sinnvolle Hierarchie auch bei stark vernetzten Projekten (z.B. Minecraft)
- ❌ Nicht streng korrekt im Sinne der Graphentheorie (Heuristik)

#### 2. BasicClassLevelCalculationStrategy (strenge SCC-Gruppierung)

**Große SCCs werden intelligent aufgebrochen.**

Bei Projekten mit vielen Zyklen landet sonst alles auf dem gleichen Level (unbrauchbar).
Diese Strategie verwendet Heuristiken, um "Rückkanten" zu identifizieren und zu ignorieren:

```
┌─────────────────────────────────────────────────────────────────┐
│  Heuristik: In-Degree / Out-Degree Analyse                      │
│                                                                  │
│  • Hohe In-Degree (viele Abhängige) → niedrigeres Level         │
│  • Hohe Out-Degree (viele Abhängigkeiten) → höheres Level       │
│  • Rückkanten = Kanten von niedrig→hoch → werden ignoriert      │
└─────────────────────────────────────────────────────────────────┘
```

**Beispiel (Minecraft-ähnlich):**
```
Vorher (Basic):     Alles auf Level 0 (ein großes SCC)
Nachher (Heuristic): GameLoop=L6, Renderer=L4, World=L3, Entity=L1, Item=L0
                     Back-Edges: Entity→World, Block→World (ignoriert)
```

**Aktivierung:**
```java
LevelCalculationStrategyContext context = 
    LevelCalculationStrategyFactory.createWithHeuristicSCCBreaking();
LevelCalculator calculator = new LevelCalculator(context);
```

### Algorithmus-Ablauf (Basic)

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

## Schritte 4-5: Paket-Level (Zwischenergebnis)

- **Schritt 4**: Paket-Level = max(Klassen-Level im Paket)
- **Schritt 5**: Eltern-Pakete erben max(Kind-Paket-Level)

Diese Levels werden in Phase 2 (DistrictRowLevelCalculator) für das Layout überschrieben,
dienen aber als Metadaten im DomainModel.

---

## Phase 2: DistrictRowLevelCalculator (KERNLOGIK!)

### Grundprinzip: Lokale Level-Berechnung

**Dies ist die wichtigste Logik**: Für jedes Paket im Baum werden die Levels aller
direkten Kinder (Klassen UND Unterpakete) **lokal** berechnet. Die Levels aus Phase 1
werden vollständig überschrieben.

**Klassen mit nur externen Abhängigkeiten erhalten Level 0 innerhalb ihres Pakets.**

### Algorithmus

Für jedes Paket P mit ≥ 2 Kindern:

#### Schritt 1: Subtree-Klassen sammeln

Für jedes Kind K von P:
- **Klasse**: Subtree = {K selbst}
- **Paket**: Subtree = alle Klassen rekursiv im Paket

#### Schritt 2: Distinct-Target-Counting zwischen Geschwisterpaaren

Für jedes Paar (A, B) von Geschwistern:

```
countAtoB = |{c ∈ B_subtree : ∃ a ∈ A_subtree mit a.Dependencies ∋ c}|
countBtoA = |{c ∈ A_subtree : ∃ b ∈ B_subtree mit b.Dependencies ∋ c}|

WENN countAtoB > countBtoA → Kante A → B (A hängt von B ab)
WENN countBtoA > countAtoB → Kante B → A (B hängt von A ab)
WENN gleich → keine Kante (unentschieden)
```

#### Schritt 3: SCC-Erkennung + DAG-Level-Zuweisung

1. **Tarjan-SCC** auf dem Geschwister-Graphen → Zyklen erkennen
2. **SCCDAGBuilder** → DAG aus SCCs erstellen
3. **Level-Zuweisung** → topologische Sortierung des DAG

#### Schritt 4: Levels zuweisen

Jedes Kind erhält das Level seines SCC im DAG.

### Beispiel

```
de.weigend.s202.ui/
├── ArchitectureView.java (Klasse)     ── hängt ab von → model/, demo/
├── LevelClassBox.java (Klasse)        ── keine Geschwister-Deps
├── model/              (Unterpaket)   ── hängt ab von → (nichts intern)
└── demo/               (Unterpaket)   ── hängt ab von → model/
```

Distinct-Target-Counting:
- ArchitectureView → model: countAtoB=1, countBtoA=0 → Kante AV→model
- ArchitectureView → demo: countAtoB=1, countBtoA=0 → Kante AV→demo
- demo → model: countAtoB=1, countBtoA=0 → Kante demo→model

DAG-Levels:
```
Level 2: ArchitectureView
Level 1: demo
Level 0: model, LevelClassBox
```

Alle Abhängigkeiten zeigen **nach unten** ✓

---

## Invarianten

Die folgenden Invarianten müssen nach Phase 2 gelten:

### 1. Klassen-Abhängigkeiten zeigen nach unten
```
Für alle Klassen A → B (innerhalb desselben Pakets):
    Level(A) > Level(B)

Ausnahme: Klassen im selben SCC (Zyklus) dürfen gleiches Level haben.
```

### 2. Unterpakete über ihren Abhängigkeitszielen
```
Wenn Unterpaket S Klassen enthält, die von Geschwister-Klasse B abhängen:
    Level(S) > Level(B)
```

### Vollständiges Beispiel

```
com/
├── example/                    (Unterpaket)
│   ├── A.java                  keine Geschwister-Deps
│   ├── B.java → A
│   └── C.java → B
│
├── example1/                   (Unterpaket)
│   └── X.java                  keine Geschwister-Deps
│
└── example2/                   (Unterpaket)
    ├── D.java                  keine Geschwister-Deps
    ├── B.java → D
    ├── C.java → D
    ├── A.java → B, C
    └── E.java → A, example.B, example1.X
```

**Phase 2 für Paket `com`** (Geschwister: example, example1, example2):
- Distinct-Target-Counting:
  - example2 → example: E hängt von example.B ab → countAtoB=1, countBtoA=0 → Kante
  - example2 → example1: E hängt von example1.X ab → countAtoB=1, countBtoA=0 → Kante
  - example → example1: keine → keine Kante
- DAG-Levels: example2=L1, example=L0, example1=L0

**Phase 2 für Paket `com.example`** (Geschwister: A, B, C):
- Distinct-Target-Counting: C→B→A
- DAG-Levels: C=L2, B=L1, A=L0

**Phase 2 für Paket `com.example2`** (Geschwister: D, B, C, A, E):
- E→A, A→B, A→C, B→D, C→D (externe Deps nach example/example1 nicht relevant)
- DAG-Levels: E=L3, A=L2, B=L1, C=L1, D=L0

---

## Zusammenfassung der Regeln

### Phase 1: LevelCalculator (Zwischenergebnis)
```
Klassen-Level = max(Level aller Abhängigkeiten) + 1
Klassen im gleichen SCC (Zyklus) = gleiches Level
Paket-Level = max(Level aller Klassen im Paket)
```

### Phase 2: DistrictRowLevelCalculator (finale Levels)
```
Für jedes Paket P:
  1. Subtree-Klassen für jedes Kind sammeln
  2. Distinct-Target-Counting für alle Geschwister-Paare
  3. Tarjan-SCC + DAG-Level-Zuweisung
  4. Levels an alle Kinder (Klassen + Pakete) zuweisen

Levels aus Phase 1 werden vollständig überschrieben.
Nur Geschwister-interne Abhängigkeiten zählen.
Externe Abhängigkeiten (zu Klassen außerhalb des Elternpakets) → Level 0.
```

---

## Code-Referenzen

| Klasse | Datei | Verantwortung |
|--------|-------|---------------|
| `LevelCalculator` | [domain/LevelCalculator.java](../analyzer/src/main/java/de/weigend/s202/domain/LevelCalculator.java) | Phase 1: Abhängigkeiten + initiale Levels |
| `DistrictRowLevelCalculator` | [ui/model/DistrictRowLevelCalculator.java](../analyzer/src/main/java/de/weigend/s202/ui/model/DistrictRowLevelCalculator.java) | Phase 2: Lokale Level-Berechnung pro Paket |
| `BasicClassLevelCalculationStrategy` | [analysis/strategy/impl/BasicClassLevelCalculationStrategy.java](../analyzer/src/main/java/de/weigend/s202/analysis/strategy/impl/BasicClassLevelCalculationStrategy.java) | SCC-aware Klassen-Level |
| `TarjanSCCFinder` | [analysis/scc/TarjanSCCFinder.java](../analyzer/src/main/java/de/weigend/s202/analysis/scc/TarjanSCCFinder.java) | Zyklen-Erkennung (Phase 1 + Phase 2) |
| `SCCDAGBuilder` | [analysis/scc/SCCDAGBuilder.java](../analyzer/src/main/java/de/weigend/s202/analysis/scc/SCCDAGBuilder.java) | DAG-Erstellung + Level-Zuweisung |
| `SimpleMaxAggregationStrategy` | [analysis/strategy/aggregation/SimpleMaxAggregationStrategy.java](../analyzer/src/main/java/de/weigend/s202/analysis/strategy/aggregation/SimpleMaxAggregationStrategy.java) | max + 1 Aggregation |

---

## Visualisierung der Paket-Hierarchie

```
         ┌────────────────────────────────────────────────────────┐
         │                         com                            │
         │                                                        │
Level 1: │  ┌──────────────────────────────────────────────────┐  │
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
Level 0: │     │    ┌───────────────────────────────────┐        │
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

         Äußere Levels = Phase 2 (Paket-zu-Paket innerhalb com)
         Innere Levels = Phase 2 (Klasse-zu-Klasse innerhalb jedes Pakets)
```

---

## Fazit

Die Level-Berechnung in S202 verwendet eine 2-Phasen-Pipeline:

1. **Phase 1 (LevelCalculator)**: Berechnet Abhängigkeiten und initiale Klassen-/Paket-Levels
2. **Phase 2 (DistrictRowLevelCalculator)**: Berechnet finale Layout-Levels lokal pro Paket

Die Kernlogik in Phase 2:
- **Distinct-Target-Counting** bestimmt die Abhängigkeitsrichtung zwischen Geschwistern
- **Tarjan-SCC + DAG** behandelt Zyklen und berechnet Levels
- **Alle Kinder** (Klassen + Pakete) werden **einheitlich** behandelt
- **Nur Geschwister-Abhängigkeiten** zählen — externe Abhängigkeiten sind irrelevant für die lokale Platzierung

Das Ergebnis: Alle Abhängigkeiten zeigen in der Visualisierung nach unten.
