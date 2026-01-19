# Running the S202 Code Analyzer UI

## Quick Start

### Option 1: Without a JAR file (opens file selector)
```bash
./run-ui.sh
```

### Option 2: Directly load a JAR file
```bash
./run-ui.sh path/to/your/file.jar
```

## Examples

### Load the test example JAR
```bash
./run-ui.sh test-example/target/test-example-1.0.0.jar
```

### Load a JAR with absolute path
```bash
./run-ui.sh /home/user/myproject/target/myapp.jar
```

## How it works

The `run-ui.sh` script:
1. Accepts an optional JAR file path as the first argument
2. Passes it to the JavaFX application via the `app.jar` system property
3. The UI automatically loads and analyzes the JAR file on startup
4. If no JAR is provided, the file selector dialog opens as usual

## Requirements

- Maven 3.9+
- Java 17+
- JavaFX libraries (configured in pom.xml)
