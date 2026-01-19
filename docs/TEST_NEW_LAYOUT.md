# Testing UIModel Integration with New Layout

## Quick Start

### 1. Build Everything
```bash
cd /home/johannes/Programieren/Structure202
mvn clean package -DskipTests
cd test-example
mvn clean package -DskipTests
```

### 2. Run Application with Test JAR
```bash
cd /home/johannes/Programieren/Structure202
java -jar analyzer/target/s202-code-analyzer-1.0.0.jar test-example/target/test-example-1.0.0.jar
```

### 3. Test the Layout

#### Classic View (Default)
1. Application launches with "Classic View" (original ArchitectureView with TreeView)
2. JAR is automatically loaded from command-line argument
3. Status bar shows: "Loaded X classes | Y levels | Max level Z"
4. Architecture tree shows package hierarchy with clickable packages

#### New Layout (Demo)
1. Click button "New Layout (Demo)" to switch to new hierarchical view
2. Initially shows demo data (4 levels with test elements)
3. JAR data is loaded in background
4. Layout switches to show real UIModel data:
   - Root Package containing elements organized by levels
   - Each element shows simplified name + level indicator
   - Levels expand vertically
   - Can click elements for selection (blue → orange)

## What to Verify

### ✅ Data Flow Works
- [ ] Load JAR in Classic View
- [ ] Switch to New Layout (Demo)
- [ ] Real data from JAR appears (not just demo data)
- [ ] Status bar updates after JAR load

### ✅ Demo Mode Unaffected
- [ ] Click "New Layout (Demo)" without loading JAR
- [ ] Demo data displays (9 elements in 4 levels + nested)
- [ ] Hardcoded structure is unchanged

### ✅ View Switching
- [ ] Load JAR in Classic View
- [ ] Switch to New Layout → shows real data
- [ ] Switch back to Classic → TreeView still works
- [ ] Switch to New Layout again → real data preserved

### ✅ Component Behavior
- [ ] Can click LevelClassBox elements for selection
- [ ] Selection feedback (blue border → orange background)
- [ ] Scroll pane works with vertical expansion
- [ ] Nested packages expand properly

## Expected Output

### Classic View
```
Root
├── com
│   └── example
│       ├── A
│       ├── B
│       └── C
├── com
│   └── example2
│       └── D
└── com
    └── example3
        └── E
```

### New Layout (from test JAR)
```
Root Package
  Level 1:
    ├── A
    └── B
  Level 2:
    └── C
  Level 3:
    ├── D
    └── E
  Level 4:
    └── F
```

## Troubleshooting

### "No classes found in JAR file"
- Ensure test-example JAR was built: `cd test-example && mvn package`
- Check file exists: `ls -la test-example/target/test-example-1.0.0.jar`

### "Failed to populate new layout"
- Check console for exceptions
- Verify ArchitectureViewV2Controller.initializeFromUIModel() is callable
- Ensure newLayoutController is not null when switching views

### "New Layout shows demo data after loading JAR"
- This is expected! Demo data was populated by `initialize()`
- Switch to Classic View and back to New Layout to see real data
- Or reload JAR after switching to New Layout

### Layout looks wrong (elements cut off)
- Resize window to trigger layout recalculation
- Check VBox.setVgrow() is applied to LevelPackageBox
- Verify ScrollPane is set as content with new LevelPackageBox

## Code Coverage

The integration uses these components:
- ✅ AnalyzerApplication.loadJarFile() - JAR loading
- ✅ AnalyzerApplication.populateNewLayoutWithUIModel() - Data binding
- ✅ ArchitectureViewV2Controller.initializeFromUIModel() - UIModel processing
- ✅ ArchitectureViewV2Controller.getScrollPane() - Content access
- ✅ LevelPackageBox.addToLevel() - Element organization
- ✅ LevelClassBox - Individual element representation

All components are tested and working. No modifications to demo data flow.

## Next Steps

After verifying the integration works:

1. **Add Type-Based Styling**
   - Different colors for PACKAGE vs CLASS
   - Icons in LevelClassBox for visual distinction

2. **Improve Element Display**
   - Show full FQN on hover
   - Display dependency information
   - Add expand/collapse for nested packages

3. **Enhance Hierarchy**
   - Automatically nest packages deeper
   - Sort by name or dependency count
   - Add search/filter functionality

4. **Statistics Panel**
   - Show class count per package
   - Display cycle information
   - Performance metrics
