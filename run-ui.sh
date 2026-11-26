#!/bin/bash
# Starte die S202 Code Analyzer App mit JavaFX
# Optional: zu öffnende Dateien als Parameter — JARs, oder eine pom.xml bzw. ein
# Gradle-Skript. Relative Pfade gelten relativ zum aktuellen Verzeichnis oder zum
# Projekt-Root.
# Beispiel: ./run-ui.sh test-example/target/test-example-1.9.0.jar
# Beispiel: ./run-ui.sh /absolute/path/to/file.jar

# Bestimme das absolute Root-Verzeichnis des Projekts
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

# Die App startet im Verzeichnis analyzer: absolute Pfade übergeben, in
# Anführungszeichen, weil ein Pfad Leerzeichen enthalten kann.
FILES=""
for FILE in "$@"; do
    if [[ ! "$FILE" = /* ]]; then
        if [[ -e "$PWD/$FILE" ]]; then
            FILE="$PWD/$FILE"
        else
            FILE="$PROJECT_ROOT/$FILE"
        fi
    fi
    FILES="$FILES \"$FILE\""
done

cd "$PROJECT_ROOT/analyzer"
if [[ -n "$FILES" ]]; then
    echo "Starte S202 Code Analyzer mit:$FILES"
    exec mvn org.openjfx:javafx-maven-plugin:0.0.8:run "-Djavafx.args=${FILES# }"
fi
echo "Starte S202 Code Analyzer"
exec mvn org.openjfx:javafx-maven-plugin:0.0.8:run
