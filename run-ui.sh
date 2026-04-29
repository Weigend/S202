#!/bin/bash
# Starte die S202 Code Analyzer App mit JavaFX
# Optional: JAR-Datei als Parameter angeben
# Beispiel: ./run-ui.sh test-example/target/test-example-1.0.0.jar
# Beispiel: ./run-ui.sh /absolute/path/to/file.jar

# Bestimme das absolute Root-Verzeichnis des Projekts
SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_PATH"

# Nur wenn Parameter übergeben wurde
if [[ -n "$1" ]]; then
    JAR_FILE="$1"
    
    # Konvertiere JAR-Pfad zu absolutem Pfad
    if [[ ! "$JAR_FILE" = /* ]]; then
        # Relativer Pfad - relativ zum Projekt-Root
        JAR_FILE="$PROJECT_ROOT/$JAR_FILE"
    fi
    
    # Setze Umgebungsvariable BEVOR Maven aufgerufen wird
    export APP_JAR="$JAR_FILE"
    echo "Starte S202 Code Analyzer mit JAR: $APP_JAR"
else
    echo "Starte S202 Code Analyzer"
fi

cd "$PROJECT_ROOT/analyzer"
mvn exec:java
