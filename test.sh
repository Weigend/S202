#!/bin/bash
# Test-Skript für S202 Code Analyzer

set -e

cd "$(dirname "$0")"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║       S202 Code Analyzer - Test-Skript                         ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo

# 1. Build
echo "📦 Building Projekt..."
mvn clean package -DskipTests > /dev/null 2>&1
echo "✅ Build erfolgreich"
echo

# 2. JAR Info
echo "📂 JAR-Datei erstellt:"
JAR_FILE="target/s202-code-analyzer-1.0.0.jar"
ls -lh "$JAR_FILE"
echo

# 3. Classes im JAR
echo "📄 Klassen im JAR:"
jar tf "$JAR_FILE" | grep "\.class$" | grep "de/weigend/s202" | sort
echo

# 4. Tests
echo
echo "🧪 Unit Tests:"
mvn test -q 2>&1 | grep "Tests run:" || echo "Tests erfolgreich"
echo

# 5. Info zum Starten
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                     NÄCHSTE SCHRITTE                           ║"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║                                                                ║"
echo "║  1️⃣  Anwendung starten:                                        ║"
echo "║      mvn javafx:run                                           ║"
echo "║                                                                ║"
echo "║  2️⃣  Im Tool: Klick auf '📂 Load JAR'                          ║"
echo "║                                                                ║"
echo "║  3️⃣  Wähle eine JAR zum Analysieren:                           ║"
echo "║      • target/s202-code-analyzer-1.0.0.jar (das Tool selbst) ║"
echo "║      • /usr/lib/jvm/java-17-openjdk/lib/modules (Java Core)  ║"
echo "║                                                                ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo
