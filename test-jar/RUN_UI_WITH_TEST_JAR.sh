#!/bin/bash

# Run the Analyzer with the test JAR showing violations

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║      Running Analyzer UI with Test JAR (Violations)            ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Get the test JAR path
TEST_JAR_PATH="$(cd "$(dirname "$0")" && pwd)/target/test-cyclic-dependencies-1.0.0.jar"

if [ ! -f "$TEST_JAR_PATH" ]; then
    echo "❌ Test JAR not found: $TEST_JAR_PATH"
    echo ""
    echo "Please build the test-jar first:"
    echo "  cd $(dirname "$0") && mvn clean package"
    exit 1
fi

echo "📦 Test JAR: $TEST_JAR_PATH"
echo ""
echo "✅ The UI will show:"
echo "   • TANGLE 1: Two-node cycle (a ↔ b)"
echo "   • TANGLE 2: Three-node cycle (c → d → e → c)"
echo "   • VIOLATION: b → c (shown as red dashed line!)"
echo ""
echo "🎯 Expected Visualization:"
echo "   • Blue boxes for packages"
echo "   • Red DASHED LINES for violations (architectural violations)"
echo "   • Solid lines for normal dependencies"
echo ""
echo "Launching UI..."
echo ""

# Get parent directory and run from there
cd "$(dirname "$0")/.."

# Build classpath
CLASSPATH="target/classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout 2>/dev/null)"

# Run the application with the test JAR as argument
java -cp "$CLASSPATH" de.weigend.s202.ui.AnalyzerApplication "$TEST_JAR_PATH"
