# Test JAR with Cyclic Dependencies

Test project for checking SCC cycle detection.

## Build

```bash
cd test-example
mvn clean package
```

The JAR is created at: `target/test-example-1.0.0.jar`

## Structure

```
com.example
├── a (A → B)
├── b (B → C, B → E)
├── c (C)
├── d (D)
└── e (E → A)
```

**Cycle**: A → B → E → A

## Test with S202

```bash
cd ..
mvn javafx:run
# File → Open JAR... → test-example/target/test-example-1.0.0.jar
```
