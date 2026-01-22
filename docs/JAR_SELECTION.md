# JAR-Datei zum Testen auswählen

Wenn du im S202 Analyzer auf **📂 Load JAR** klickst, kannst du verschiedene JAR-Dateien analysieren:

## Empfohlene Test-JARs

### 1. Das Tool selbst
```
analyzer/target/s202-analyzer-1.0.0.jar
```
Nach `mvn clean package` verfügbar.

### 2. Test-JAR mit Zyklen
```
test-example/target/test-cyclic-dependencies-1.0.0.jar
```
Enthält absichtlich zyklische Abhängigkeiten zum Testen.

### 3. Andere JARs finden
```bash
# JARs im System finden
find /usr -name "*.jar" -type f 2>/dev/null | head -10

# Maven Repository
ls ~/.m2/repository/
```

## Was wird analysiert?

- ✅ Alle Klassen im JAR
- ✅ Package-Hierarchie
- ✅ Abhängigkeiten zwischen Klassen/Paketen
- ✅ Zyklische Abhängigkeiten
- ❌ Dynamische Dependencies (Reflection)

```
Loaded 24 classes | 5 packages | 2 cycles detected
```

Das bedeutet:
- 24 .class-Dateien gefunden
- 5 unterschiedliche Pakete
- 2 zyklische Abhängigkeiten erkannt
