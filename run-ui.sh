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
# DEMO_ALERT_INJECTION (Philippsen review, 2026-05-19): activates a deliberate
# R1-algo violation on com.example.A when test-example is loaded, so the
# implausibility alert dialog pops up. Comment out the export to disable.
export JAVA_TOOL_OPTIONS="-Ds202.demo.injectAlert=true"
# Classpath-Start (nicht javafx:run / Module-Path): der eingebettete City3D-Tab
# braucht JCEF im selben Prozess; exec:java startet vom Classpath.
mvn exec:java
