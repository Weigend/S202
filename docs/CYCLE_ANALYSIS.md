# Zyklusanalyse für LayerAssigner

## Aktuelle Situation

### 1. Test-JAR Abhängigkeitsstruktur

```
Direkte Abhängigkeiten:
  A → B
  B → C
  B → E
  E → A

Zyklus erkannt:
  A → B → E → A  (3-er Zyklus)

Unabhängige Pakete:
  C → (keine internen Abhängigkeiten)
  D → (keine internen Abhängigkeiten)
```

### 2. Aktuelles Verhalten (zufällig)

**Problem**: Der aktuelle Code bricht Zyklen zufällig ab:
```java
if (visiting.contains(pkgName)) {
    return 0;  // Zufällige Abbruch!
}
```

Das führt zu nicht-deterministischen Ergebnissen:
- Der Zyklus wird an der ERSTEN Stelle gebrochen, wo eine Package erneut besucht wird
- Diese Stelle hängt von der Verarbeitungsreihenfolge ab
- Welche Abhängigkeit "gekappt" wird, ist dadurch Zufall

**Beispiel aus aktuellem Code:**
```
CALC_LAYER: A
  → dep B
    CALC_LAYER: B
      → dep C
      → dep E
        CALC_LAYER: E
          → dep A
          → CYCLE DETECTED at A, assigning layer 0  ← HIER!
```

Die A→B→E→A-Abhängigkeit wird bei **E→A** gekappt, weil A als erstes "besucht" wurde.

### 3. Problem mit reiner Zyklus-Vermeidung

Nur die Rekursion zu vermeiden ist nicht genug. Wir müssen entscheiden:
- **Welche Abhängigkeit sollte entfernt werden** um den Zyklus zu unterbrechen?
- **Auf welcher Basis** entscheiden wir das?

**Mögliche Strategien:**

#### Strategie 1: "Weakest Link" (Abhängigkeit mit den wenigsten Abhängigen)
```
A → B      (A hat einen Abhängigen: keinen)
B → E      (B hat zwei Abhängige: A)
E → A      (E hat einen Abhängigen: B)

→ Entferne E → A, weil E die wenigsten Abhängigen hat
```

#### Strategie 2: "Highest Cost" (Abhängigkeit mit höchstem Layer-Unterschied)
```
Wenn wir die Abhängigkeit entfernen würden:
- A → B:     A bräuchte B's Layer, B bräuchte A's Layer (Differenz: X)
- B → E:     B bräuchte E's Layer, E bräuchte B's Layer (Differenz: Y)  
- E → A:     E bräuchte A's Layer, A bräuchte E's Layer (Differenz: Z)

→ Entferne die Abhängigkeit mit der kleinsten Differenz
```

#### Strategie 3: "Weight-based" (Abhängigkeitshäufigkeit)
```
Zähle, wie oft jede Package in Abhängigkeiten vorkommt:
- A: 2x (B→A, E→A)
- B: 2x (A→B, E→B indirekt)
- E: 1x (B→E)
- C: 1x (B→C)

→ Entferne die Abhängigkeit zur Package mit den MEISTEN Abhängigkeiten
  (um kritische Komponenten zu schützen)
```

#### Strategie 4: "Leaf-aware" (Blätter schützen)
```
Gebe Blattknoten (mit wenig/keine Abhängigen) Vorrang:
- C: Blatt (keine Abhängigen) → nicht entfernen
- D: Blatt (keine Abhängigen) → nicht schützen (nicht im Zyklus)

→ Entferne keine Abhängigkeiten zu Blättern, entferne nur
  Abhängigkeiten zwischen "großen" Paketen
```

### 4. Gewählte Strategie: "Breaking Point Analysis"

**Ansatz:** Für jeden Zyklus analysieren, welche Abhängigkeit das "beste" Breaking Point ist.

**Kriterien in Priorität:**
1. **Vermeidung von Blatt-Dependencies**: Nicht die Abhängigkeiten zu Blatt-Paketen entfernen
2. **Minimale Abhängigen-Zahl**: Entferne Abhängigkeit bei der Package mit WENIGER Abhängigen
3. **Deterministische Auswahl**: Bei Gleichstand: alphabetische Reihenfolge

**Für unser Beispiel A → B → E → A:**
```
Kanten und ihre Abhängigen:
  A → B:   B hat Abhängige: {A, E → B (indirekt)}
  B → E:   E hat Abhängige: {B, A → E (indirekt)}
  E → A:   A hat Abhängige: {E, B → A (indirekt)}

Abhängigen-Zahl:
  A: {B, E}      → 2 Abhängige
  B: {A, E}      → 2 Abhängige
  E: {A, B}      → 2 Abhängige

Gleichstand → alphabetisch: A < B < E
→ Kappt E → A (E ist die Source)
```

## Implementierungs-Plan

### Phase 1: Zyklus-Erkennung erweitern
- Nicht nur erkennen DASS ein Zyklus existiert
- Sondern auch WELCHE Kanten den Zyklus bilden

### Phase 2: Breaking Point Analysis
- Für jeden erkannten Zyklus die beste Kante zum Kappen analysieren
- Abhängigkeitsgewichte berechnen

### Phase 3: Deterministisches Kappen
- Abhängigkeit entfernen (virtuelle Entfernung für Layer-Berechnung)
- Sicherstellen, dass gleiches Ergebnis immer beim Lauf kommt

## Fragen für die Implementierung

1. **Wie erkennen wir den Zyklus Pfad?** (nicht nur dass ein Zyklus existiert)
   → DFS mit Pfad-Tracking während calculatePackageLayer()

2. **Wie berechnen wir die "Abhängigen"?** (Reverse Dependencies)
   → Bereits vorhanden in reverseDeps Map, aber noch nicht verwendet

3. **Wann entfernen wir die Abhängigkeit?** 
   → Vor der Layer-Berechnung (virtuelle Entfernung aus internalDeps)
   → Oder während (mit Marker)?

4. **Sollen mehrere Zyklen unabhängig behandelt werden?**
   → Ja, eventuell mehrere Zyklen in großen Graphs
