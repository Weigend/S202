#!/bin/bash
cd /home/johannes/Programieren/Structure202
mvn clean install -q 2>&1 | head -5

# Run test to verify loading
java -cp analyzer/target/classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout) \
  de.weigend.s202.analysis.test.TestAnalyzer 2>&1 | grep -A 20 "DEBUG"
