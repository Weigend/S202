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

### 3. JAR-Datei laden
Klick auf **📂 Load JAR** und wähle eine JAR-Datei aus.

## Beispiel: S202 selbst analysieren

```bash
mvn clean package -DskipTests
mvn javafx:run
# Im UI: Load JAR → analyzer/target/s202-analyzer-1.0.0.jar
```

## Hauptfunktionen

| Feature | Beschreibung |
|---------|--------------|
| **Load JAR** | JAR-Datei zur Analyse laden |
| **Package Tree** | Hierarchische Paket-Ansicht |
| **Level-Layout** | Pakete nach Abhängigkeitstiefe sortiert |
| **Violations** | Rote Linien zeigen architektonische Probleme |

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
