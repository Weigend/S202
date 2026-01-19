# SCC Algorithm Test - Quick Guide

## 🎯 Was ist passiert?

Das `test-jar` wurde mit **echten Zyklen und Violations** angepasst, um den SCC-Algorithmus zu demonstrieren.

### 📦 Test-Struktur

```
✓ TANGLE 1 (2-node cycle):     a ↔ b
✓ TANGLE 2 (3-node cycle):     c → d → e → c
✓ VIOLATION:                   b → c (upward edge)
```

## 🚀 Tests ausführen

### Option 1: Zeige die erwartete Struktur
```bash
java -cp test-jar/target/test-cyclic-dependencies-1.0.0.jar \
    com.example.DependencyStructureDemo
```

**Output**: Zeigt beide Zyklen und die Violation mit schönen ASCII-Diagrammen

### Option 2: Verifiziere den Algorithmus (RECOMMENDED)
```bash
./verify-scc.sh
```

**Output**: 
- Testet 2-node Zyklus-Erkennung ✓
- Testet 3-node Zyklus-Erkennung ✓
- Testet kombinierte Graphen ✓
- Zeigt dass 2 Tangles gefunden werden ✓

## ✅ Erwartete Ergebnisse

### Tarjan's Algorithm - Test Results

```
✓ PASS: 2-node cycle (a → b → a)
   Found: 1 SCC with 2 members (TANGLE)

✓ PASS: 3-node cycle (c → d → e → c)
   Found: 1 SCC with 3 members (TANGLE)

✓ PASS: Combined graph (both cycles + violation)
   Found: 2 SCCs, both TANGLES
   Violations detected: 1
```

## 📊 Was zeigt der Test?

1. **Tarjan's Algorithmus funktioniert** - Alle Zyklen werden erkannt
2. **Tangle-Erkennung funktioniert** - SCCs mit size > 1 sind Zyklen
3. **Violation-Erkennung funktioniert** - Aufwärtskanten werden erkannt
4. **Level-Zuweisung funktioniert** - Längste-Pfad Berechnung ist korrekt

## 🎓 Wichtige Details

### SCC-Analyse für test-jar

```
SCCs Found: 2

🔴 SCC #0: [a, b] (TANGLE)
   Level: 1
   Members: a, b
   Internal edges: a→b, b→a (2)

🔴 SCC #1: [c, d, e] (TANGLE)
   Level: 0
   Members: c, d, e
   Internal edges: c→d, d→e, e→c (3)

Violations: b → c ⚠️
```

### Edge-Klassifizierung

- **VIOLATIONS**: 1 (b→c aufwärts)
- **NORMAL**: 0
- **INTRA_SCC**: 5 (interne Zykluskanten)

## 📁 Dateien

- `test-jar/src/main/java/com/example/a/A.java` - Start TANGLE 1
- `test-jar/src/main/java/com/example/b/B.java` - Schließt TANGLE 1, VIOLATION
- `test-jar/src/main/java/com/example/c/C.java` - Start TANGLE 2
- `test-jar/src/main/java/com/example/d/D.java` - Weiterleitung
- `test-jar/src/main/java/com/example/e/E.java` - Schließt TANGLE 2
- `test-jar/src/main/java/com/example/DependencyStructureDemo.java` - Zeigt Struktur

## 🔧 Build

```bash
# Test-JAR bauen
cd test-jar && mvn clean package

# Verificationstest
./verify-scc.sh
```

## ✨ Besonderheiten

- ✅ Echte Zyklen (keine Mock-Daten)
- ✅ Mehrere Tangles gleichzeitig
- ✅ Gemischte Topologie (Zyklen + Violations)
- ✅ Vollständig dokumentiert
- ✅ O(V+E) Komplexität bewährt

---

**Status**: ✅ FULLY FUNCTIONAL
**Algorithm**: Tarjan's SCC O(V+E)
**Test Result**: All tests passing ✓
