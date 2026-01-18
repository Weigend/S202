# Test JAR Project with Cyclic Dependencies

Quick build:
```bash
cd test-jar
mvn clean package
```

The JAR will be created at: `target/test-cyclic-dependencies-1.0.0.jar`

## Package Structure

```
com.example
├── a (contains class A)
├── b (contains class B)
├── c (contains class C)
├── d (contains class D)
└── e (contains class E)
```

## Dependencies

- **A → B** (A uses B)
- **B → C** (B uses C)
- **B → E** (B uses E)
- **E → A** (E uses A)

This creates a **cyclic dependency**: A → B → E → A

## Testing with S202 Analyzer

1. Build this JAR:
   ```bash
   mvn clean package
   ```

2. Start S202:
   ```bash
   cd ..
   mvn javafx:run
   ```

3. Load JAR: `Click "Load JAR" → test-jar/target/test-cyclic-dependencies-1.0.0.jar`

4. View the cycle detection and layer assignment for packages a, b, c, d, e
