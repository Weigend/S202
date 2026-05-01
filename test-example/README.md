# Test JAR mit zyklischen Abhängigkeiten

Test-Projekt zum Prüfen der SCC-Zyklenerkennung.

## Build

```bash
cd test-example
mvn clean package
```

JAR wird erstellt: `target/test-example-1.0.0.jar`

## Struktur

```
com.example
├── a (A → B)
├── b (B → C, B → E)
├── c (C)
├── d (D)
└── e (E → A)
```

**Zyklus**: A → B → E → A

## Testen mit S202

```bash
cd ..
mvn javafx:run
# File → Open JAR... → test-example/target/test-example-1.0.0.jar
```
