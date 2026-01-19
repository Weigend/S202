# 🎯 JAR-Datei zum Testen auswählen

Wenn du die S202 Anwendung startest und auf **📂 Load JAR** klickst, wähle eine dieser Dateien:

## Option 1: Das Tool selbst analysieren (Recommended)
```
target/s202-code-analyzer-1.0.0.jar
```
- Größe: ~40 KB (schnell zu analysieren)
- Zeigt: Die S202-Struktur selbst
- Perfect für Demo und Testing

## Option 2: Java Standard Library (groß!)
```
/usr/lib/jvm/java-17-openjdk/lib/modules
```
- Größe: Mehrere MB
- Zeigt: Komplette Java 17 Struktur (java.*, javax.*, etc.)
- ⚠️ Dauert länger zu laden

## Option 3: Andere JARs finden

```bash
# Alle JARs im System finden
find /usr -name "*.jar" -type f 2>/dev/null | head -20

# Oder im Maven Repository
ls ~/.m2/repository/
```

## 🚀 Vollständiger Workflow zum Testen

```bash
# 1. Projekt bauen
mvn clean package

# 2. Tool starten
mvn javafx:run

# 3. Im JavaFX-Fenster:
# - Klick: 📂 Load JAR
# - Datei: target/s202-code-analyzer-1.0.0.jar
# - Warten: Analyse läuft (Status Bar zeigt Fortschritt)
# - Sehen: Paket-Hierarchie mit 📦 und 📄 Icons

# Beispiel Output:
# 📦 de
#   📦 weigend
#     📦 s202
#       📦 analysis [3 deps]
#       📦 io [2 deps]
#       📦 model [1 deps]
#       📦 ui [5 deps]
```

## ⚙️ Tiefe einstellen

Der **Auto-Expand Depth** Spinner (Standard: 3) steuert, wie viele Ebenen automatisch aufgeklappt werden:

- **1**: Nur Wurzel sichtbar
- **2**: 1 Ebene tiefer
- **3**: 2 Ebenen tiefer (Standard - wie Structure101)
- **4+**: Für detaillierte Ansicht größerer JARs

Änderung der Tiefe wird für die nächste Analyse verwendet.

## 🔍 Was wird analysiert?

Das Tool nutzt **ASM (Java Bytecode Parser)** um zu extrahieren:

- ✅ Alle Klassen
- ✅ Package-Hierarchie
- ✅ Abhängigkeiten zwischen Klassen
- ✅ Zyklische Abhängigkeiten (automatisch erkannt)
- ❌ Nicht: Dynamische Dependencies (Reflection, Class.forName)

## 📊 Status Bar Information

Nach erfolgreicher Analyse zeigt die Status Bar:

```
Loaded 24 classes | 5 packages | 2 cycles detected
```

Das bedeutet:
- 24 .class-Dateien gefunden
- 5 unterschiedliche Pakete
- 2 zyklische Abhängigkeiten erkannt
