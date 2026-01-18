# S202 Code Analyzer - Quickstart

## 🚀 In 3 Schritten starten

### 1. Terminal öffnen
```bash
cd /home/johannes/Programieren/Structure202
```

### 2. Anwendung bauen & starten
```bash
mvn javafx:run
```

Alternativ im VS Code:
- Drücke `Ctrl+Shift+P` → `Maven: Run from Terminal`

### 3. JAR-Datei laden
Klick auf **📂 Load JAR** im UI und wähle eine `.jar`-Datei:
- Eigenständig gebaute JAR aus `target/`
- Oder System JARs (z.B. `/usr/lib/jvm/java-17-openjdk/`)
- Das Tool analysiert sofort die Struktur

## 📝 Beispiel: Analyse der S202 selbst

```bash
# JAR bauen
mvn clean package -DskipTests

# Tool starten
mvn javafx:run

# Im UI: Load JAR → target/s202-code-analyzer-1.0.0.jar
```

Die S202-Architektur wird angezeigt:
```
📦 de
  📦 weigend
    📦 s202
      ├── 📦 model         (Layer 0 - keine Abhängigkeiten)
      ├── 📦 io            (Layer 1 - nutzt model)
      ├── 📦 analysis      (Layer 1 - nutzt model)
      ├── 📦 ui            (Layer 2 - nutzt analysis, io)
      └── 📦 example       (Layer 1 - nutzt analysis)
```

**Layer-Bedeutung:**
- Layer 0 = Unabhängige Pakete (model)
- Layer 1 = Hängen von Layer 0 ab (io, analysis)
- Layer 2 = Hängen von Layer 1+ ab (ui)

## 🎯 Hauptfunktionen

| Feature | Funktion |
|---------|----------|
| **Load JAR** | Datei-Dialog für JAR-Auswahl |
| **Auto-Expand Spinner** | Hierarchie-Tiefe (1-10, Default: 3) |
| **Package Tree** | Hierarchische Baumansicht |
| **Layer Layout** | Pakete horizontal nach Schicht angeordnet |
| **Parent Wrapping** | Zeigt vollständige Paket-Hierarchie |
| **Status Bar** | Klassen, Pakete, erkannte Zyklen |

## 🔍 Verständnis: Architektur-Schichten

Das Tool sortiert Pakete in **Layer** basierend auf Abhängigkeitstiefe:

- **Layer 0** = Basispakete ohne externe Abhängigkeiten (z.B. `model`)
- **Layer 1** = Hängen von Layer 0 ab (z.B. `io`, `analysis`)
- **Layer 2+** = Hängen von tieferen Schichten ab (z.B. `ui`)

**Grafisch:** Pakete der gleichen Schicht stehen **nebeneinander** (horizontal).

## 🔧 Tipps & Tricks

| Problem | Lösung |
|---------|--------|
| **JAR lädt nicht** | Schau im Terminal nach Fehler-Ausgabe |
| **Struktur wird nicht angezeigt** | JAR muss valide .class-Dateien enthalten |
| **Große JAR laden?** | Reduce Auto-Expand Depth auf 2 |
| **RAM-Probleme?** | `export MAVEN_OPTS="-Xmx2g"` vor Start |
| **Tests ausführen?** | `mvn test` |
| **Projekt neu bauen?** | `mvn clean install` |

## 📚 Weitere Infos

- **Vollständige Doku**: [README.md](README.md)
- **VS Code Setup**: [VS_CODE_SETUP.md](VS_CODE_SETUP.md)
- **Quellcode**: [src/main/java/](src/main/java/)

## ❓ FAQ

**F: Wo sind JAR-Dateien zum Testen?**
- `target/` (nach Maven Build)
- System JARs: `/usr/lib/jvm/java-17-openjdk/lib/`

**F: Warum zeigt es keine Abhängigkeiten?**
- Manche JARs haben keine internen Cross-Package Dependencies
- Versuche größere JARs wie `java.base` oder `java.util.logging`

**F: Kann ich eine bestimmte Paket-Struktur exportieren?**
- Noch nicht - Export-Feature ist in der TODO-Liste (PlantUML, SVG, etc.)
