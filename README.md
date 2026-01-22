# S202 Code Analyzer

Ein JavaFX-basiertes Tool zur Analyse von Java-Bytecode und Visualisierung der Code-Architektur.

![UI Screenshot](docs/Beispiel.png)

## Features

- **Bytecode-Analyse**: Parst Java `.class`-Dateien mit ASM 9.6
- **Abhängigkeitserkennung**: Extrahiert Klassen- und Paket-Abhängigkeiten
- **Zykluserkennung**: Findet zyklische Abhängigkeiten (Strongly Connected Components)
- **Architektur-Layering**: Topologische Sortierung nach Abhängigkeitstiefe
- **Hierarchische Visualisierung**: JavaFX TreeView mit aufklappbaren Paketen
- **Violation-Erkennung**: Markiert architektonische Verletzungen (Rückwärts-Abhängigkeiten)

## Schnellstart

```bash
# Build
mvn clean install

# Anwendung starten
mvn javafx:run

# Tests ausführen
mvn test
```

Dann: **📂 Load JAR** → JAR-Datei auswählen → Architektur wird analysiert und visualisiert.

## Systemanforderungen

- **Java 17+**
- **Maven 3.6+**
- **JavaFX 21.0.1** (wird automatisch via Maven geladen)

## Projektstruktur

```
analyzer/src/main/java/de/weigend/s202/
├── analysis/       # Analyse-Logik (Level-Berechnung, SCC, Strategien)
├── io/             # JAR-Loading, Bytecode-Parsing (ASM)
└── ui/             # JavaFX-Oberfläche
```

## Verwendung

1. **JAR laden**: Über "📂 Load JAR" eine JAR-Datei auswählen
2. **Analyse**: Pakete und Klassen werden automatisch analysiert
3. **Navigation**: Pakete auf-/zuklappen, Abhängigkeiten einsehen
4. **Violations**: Rote gestrichelte Linien zeigen architektonische Probleme

## VS Code Integration

```bash
code .
# Ctrl+Shift+P → "Maven: Run from Terminal" → javafx:run
```

Details: [docs/VS_CODE_SETUP.md](docs/VS_CODE_SETUP.md)

## Dokumentation

- [QUICKSTART.md](QUICKSTART.md) - Schneller Einstieg
- [docs/](docs/) - Weitere technische Dokumentation
