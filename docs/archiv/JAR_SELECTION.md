# Code zum Testen auswählen

S202 hat drei Einstiegspunkte im **File**-Menü:

- **Open JAR…** — Datei-Dialog, Mehrfachauswahl möglich. Bei mehreren Dateien öffnet sich ein
  Staging-Dialog, in dem du weitere JARs hinzufügen oder einzelne entfernen kannst, bevor die
  Analyse startet.
- **Open Maven Project…** — auf das Wurzel-`pom.xml` zeigen. S202 folgt rekursiv den
  `<modules>`, sammelt aus jedem Modul `target/*.jar` (ohne `*-sources.jar`, `*-javadoc.jar`,
  `*-tests.jar`, `original-*.jar`) und füllt damit den Staging-Dialog vor.
- **Open Gradle Project…** — auf `settings.gradle(.kts)` oder `build.gradle(.kts)` zeigen.
  S202 parst die `include`-Statements (Groovy- und Kotlin-DSL), sammelt aus jedem Sub-Projekt
  `build/libs/*.jar` (gleicher Filter) und füllt den Staging-Dialog vor.

Wichtig: für Maven/Gradle-Projekte muss vorher `mvn package` bzw. `gradle build` gelaufen
sein, sonst gibt's leere Module.

## Empfohlene Test-Quellen

### 1. Das Tool selbst
```
analyzer/target/s202-code-analyzer-1.0.0.jar
```
Nach `mvn clean package` verfügbar.

### 2. Test-JAR mit Zyklen
```
test-example/target/test-example-1.0.0.jar
```
Enthält absichtlich zyklische Abhängigkeiten zum Testen.

### 3. Strukture202 als Multi-Modul-Maven-Projekt
Im UI: `File → Open Maven Project…` und auf `Structure202/pom.xml` zeigen — beide Module
(`analyzer` + `test-example`) werden in einem Rutsch geladen.

### 4. Andere JARs finden
```bash
# JARs im System finden
find /usr -name "*.jar" -type f 2>/dev/null | head -10

# Maven Repository
ls ~/.m2/repository/
```

## Was wird analysiert?

- ✅ Alle Klassen im JAR (bzw. allen geladenen JARs zusammen)
- ✅ Package-Hierarchie
- ✅ Abhängigkeiten zwischen Klassen/Paketen
- ✅ Zyklische Abhängigkeiten
- ❌ Dynamische Dependencies (Reflection)

Nach der Analyse läuft automatisch der **Layout-Invariant-Check**: vier Regeln (R1/R2/R3/R5)
prüfen die Level-Pipeline auf Algorithmus-Bugs. 0 Findings = `... | invariants OK` in der
Statusleiste. ≥ 1 Findings → ein Modaldialog mit dem kompletten Reproducer-Text und einem
Copy-Button öffnet sich.

```
Loaded 5 JAR(s) | 893 classes | 14 levels | Max level 14 | invariants OK
```
