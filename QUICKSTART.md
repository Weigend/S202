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

### 3. Code laden
Drei Einstiegspunkte im **File**-Menü:

- **Open JAR…** — eine oder mehrere JARs (Mehrfachauswahl öffnet einen Staging-Dialog)
- **Open Maven Project…** — auf das Wurzel-`pom.xml` zeigen, alle Modul-JARs aus `target/` werden gesammelt
- **Open Gradle Project…** — auf `settings.gradle(.kts)` oder `build.gradle(.kts)` zeigen, alle Modul-JARs aus `build/libs/` werden gesammelt

Voraussetzung für Maven/Gradle-Projekte: `mvn package` bzw. `gradle build` muss bereits gelaufen sein.

## Beispiel: S202 selbst analysieren

```bash
mvn clean package -DskipTests
mvn javafx:run
# Im UI: File → Open JAR... → analyzer/target/s202-code-analyzer-1.0.0.jar
```

## Hauptfunktionen

| Feature | Beschreibung |
|---------|--------------|
| **Open JAR / Maven / Gradle** | JARs einzeln oder ganze Multi-Modul-Projekte laden |
| **Package Tree** | Hierarchische Paket-Ansicht |
| **Level-Layout** | Pakete nach Abhängigkeitstiefe sortiert |
| **Violations** | Fette gestrichelte Pfeile zeigen architektonische Probleme (Paket-Aggregate mit gefülltem Kreis für die Aufrufanzahl) |
| **Invariant Check** | Fünf Invarianten als Plausibilitäts-Alerts; vier melden Algorithmus-Bugs in der Level-Pipeline, R1-visual zeigt echte Architekturverletzungen |

## Level-Bedeutung

- **Level 0** = Basispakete (keine Abhängigkeiten)
- **Level 1** = Hängen von Level 0 ab
- **Level 2+** = Hängen von tieferen Schichten ab

## Nützliche Befehle

```bash
mvn clean install    # Projekt bauen
mvn test             # Tests ausführen
mvn javafx:run       # UI starten
```

## Weitere Dokumentation

- [README.md](README.md) - Projekt-Übersicht
- [docs/VS_CODE_SETUP.md](docs/VS_CODE_SETUP.md) - VS Code Integration

**F: Kann ich eine bestimmte Paket-Struktur exportieren?**
- Noch nicht - Export-Feature ist in der TODO-Liste (PlantUML, SVG, etc.)
