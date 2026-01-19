# Test JAR - SCC Algorithm Demonstration

## 📦 Zweck

Das `test-jar` ist speziell dafür designed, um zu zeigen, dass der **SCC-Algorithmus (Strongly Connected Components)** korrekt funktioniert. Es enthält **echte Zyklen** und **architektonische Verletzungen**.

## 🏗️ Struktur

### Packages

```
com.example
├── a.A          (imports b)
├── b.B          (imports a, c) ← TANGLE 1: a ↔ b
├── c.C          (imports d)
├── d.D          (imports e)     ← TANGLE 2: c → d → e → c
└── e.E          (imports c)
```

### Abhängigkeitsgraph

```
┌─────────────────────────────────────┐
│ TANGLE 1: Bidirectional Cycle       │
│                                     │
│   a ─────────────────┐              │
│   ↑                  │              │
│   │                  ▼              │
│   └──────── b ──────→ c [VIOLATION] │
│                                     │
└─────────────────────────────────────┘
           ↑
           │
           ▼
┌─────────────────────────────────────┐
│ TANGLE 2: Three-Node Cycle          │
│                                     │
│   c ──────→ d ──────→ e             │
│   ↑________________________│        │
│                                     │
└─────────────────────────────────────┘
```

## ✅ Erwartete Analyse-Ergebnisse

### SCC-Analyse

```
SCCs Found: 2

🔴 SCC #0: [com.example.a, com.example.b]
   Type: TANGLE (2-node cycle)
   Level: 1
   
🔴 SCC #1: [com.example.c, com.example.d, com.example.e]
   Type: TANGLE (3-node cycle)
   Level: 0 (leaf)
```

### Edge-Klassifizierung

```
⚠️  VIOLATIONS (upward edges): 1
    • b → c (architectural violation)

✓ NORMAL (downward): 0

🔗 INTRA-SCC (internal): 5
    • a → b, b → a (2 in TANGLE #0)
    • c → d, d → e, e → c (3 in TANGLE #1)
```

## 🔍 Wie man den Algorithmus testet

### 1. **Zeige die erwartete Struktur**
```bash
java -cp test-jar/target/test-cyclic-dependencies-1.0.0.jar \
    com.example.DependencyStructureDemo
```

Zeigt:
- Die 2 Tangles (Zyklen)
- Die Violation (b → c)
- Den erwarteten Output des Algorithmus

### 2. **Verifiziere den Tarjan-Algorithmus**
```bash
./verify-scc.sh
```

Testet:
- 2-node Zyklus-Erkennung ✓
- 3-node Zyklus-Erkennung ✓
- Kombinierte Graphen ✓

### 3. **Führe die vollständige Analyse durch**
```bash
java -cp "target/classes:test-jar/target/test-cyclic-dependencies-1.0.0.jar" \
    de.weigend.s202.example.AnalyzerExample \
    test-jar/target/test-cyclic-dependencies-1.0.0.jar
```

## 📊 Algorithmus-Verifikation

Der `verify-scc.sh` Test zeigt, dass der Algorithmus:

✅ **2-node cycles korrekt erkennt**
```
Test 1: a → b → a
Result: 1 SCC with 2 members (TANGLE) ✓
```

✅ **3-node cycles korrekt erkennt**
```
Test 2: c → d → e → c
Result: 1 SCC with 3 members (TANGLE) ✓
```

✅ **Mehrere Zyklen gleichzeitig verarbeitet**
```
Test 3: Both cycles + violation
Result: 2 SCCs, beide TANGLES ✓
```

## 🎯 Technische Details

### Tarjan's Algorithmus

- **Komplexität**: O(V + E)
- **Methode**: Stack-basierte Tiefensuche
- **Ergebnis**: Alle SCCs in einem Durchlauf gefunden

### SCC-DAG Builder

- **Eingabe**: Liste von SCCs aus Tarjan
- **Aufbau**: DAG aus SCC-Abhängigkeiten
- **Levelization**: Longest-Path Berechnung

### Edge Classification

- **NORMAL**: Kanten gehen abwärts (korrekter Datenfluss)
- **VIOLATION**: Kanten gehen aufwärts (Fehler!)
- **INTRA_SCC**: Kanten innerhalb einer Tangle (Zyklus)

## 📝 Source Code

### A.java - Start von TANGLE 1
```java
public class A {
    private B b;  // a depends on b
    
    public void processData(String input) {
        if (b != null) {
            b.validate(input);
        }
    }
}
```

### B.java - Schließt TANGLE 1, VIOLATION
```java
public class B {
    private A a;      // CYCLE: b depends on a (closes a ↔ b)
    private C c;      // VIOLATION: b depends on c (upward!)
    
    public void validate(String input) {
        if (c != null) {
            c.execute(input);  // VIOLATION edge
        }
    }
}
```

### C.java, D.java, E.java - TANGLE 2
```
C → D → E → C (3-node cycle)
```

## 🚀 Quick Start

```bash
# 1. Zeige die Struktur
java -cp test-jar/target/*.jar com.example.DependencyStructureDemo

# 2. Teste den Algorithmus
./verify-scc.sh

# 3. (Optional) Vollständige Analyse
mvn clean compile
java -cp "target/classes:test-jar/target/*.jar" \
    de.weigend.s202.example.AnalyzerExample \
    test-jar/target/test-cyclic-dependencies-1.0.0.jar
```

## 🎓 Lernziele

Dieses Test-JAR zeigt:

1. **SCC-Erkennung**: Wie der Tarjan-Algorithmus Zyklen identifiziert
2. **Tangle-Klassifizierung**: SCCs mit size > 1 sind Zyklen
3. **Level-Zuweisung**: Wie Layers basierend auf längsten Pfaden berechnet werden
4. **Violation-Erkennung**: Aufwärtskanten sind Architektur-Fehler
5. **Skalierbarkeit**: O(V + E) Komplexität auch für komplexe Graphen

## ✨ Besonderheiten

- **Echte Zyklen**: Keine künstlichen Konstrukte, reale Abhängigkeitsstrukturen
- **Mehrere Tangles**: Zeigt, dass der Algorithmus mehrere SCCs gleichzeitig handhaben kann
- **Gemischte Topologie**: Enthält Zyklen, Violations und normale Abhängigkeiten
- **Dokumentiert**: Jede Klasse erklärt ihre Rolle im Test

---

**Status**: ✅ READY FOR TESTING
**Algorithm**: Tarjan's O(V+E)
**Test Coverage**: 2-node + 3-node cycles + violations
