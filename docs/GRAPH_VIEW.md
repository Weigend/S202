# 📊 Architecture Graph View - Die neue Visualisierung

## Was ist anders?

Statt eines **TreeView** (aufklappbare Liste) nutzt das Tool jetzt eine **Graph-Visualisierung**:

### Alte Ansicht (TreeView)
```
📦 de
  📦 weigend
    📦 s202
      📦 ui
        📄 AnalyzerApplication
        📄 ArchitectureView
```

### Neue Ansicht (Graph)
```
┌─────────────────┐
│       🔷 UI     │ ← Oben (hat Abhängigkeiten)
│    (5 deps)     │
└─────────────────┘
        ↓
┌─────────────────┐
│   🔷 Analysis   │ ← Unten (weniger Abhängigkeiten)
│    (2 deps)     │
└─────────────────┘
```

## Layout-Logik

Die Pakete werden **vertikal angeordnet** basierend auf Abhängigkeitstiefe:

**Oben stehen:** Pakete mit vielen ausgehenden Abhängigkeiten (z.B. UI)
**Unten stehen:** Pakete mit wenigen/keinen Abhängigkeiten (z.B. Model, Analysis)

```
┌──────────────────────────────────────────────┐
│                                              │
│  ┌──────────┐     ┌──────────┐              │ ← Zeile 1 (UI-Layer)
│  │   UI     │     │   Util   │              │   (5-10 dependencies)
│  └──────────┘     └──────────┘              │
│         ↓               ↓                    │
│  ┌──────────┐     ┌──────────┐              │ ← Zeile 2 (Analysis)
│  │ Analysis │     │   IO     │              │   (2-5 dependencies)
│  └──────────┘     └──────────┘              │
│         ↓               ↓                    │
│  ┌──────────┐     ┌──────────┐              │ ← Zeile 3 (Model)
│  │  Model   │     │ Adapter  │              │   (0-2 dependencies)
│  └──────────┘     └──────────┘              │
│                                              │
└──────────────────────────────────────────────┘
```

## Elemente pro Paket-Box

```
┌─────────────────────┐
│   com.example.ui    │  ← Package-Name
│     (5 deps)        │  ← Abhängigkeits-Count
│    12 classes       │  ← Anzahl Klassen (optional)
└─────────────────────┘
```

### Legende
- **Blauer Rahmen**: Paket-Grenze
- **hellblauer Hintergrund**: Paket-Inhalt
- **(5 deps)**: 5 Abhängigkeiten zu anderen Paketen
- **12 classes**: 12 Java-Klassen in diesem Paket

## Abhängigkeitslinien

Graue Linien zeigen Abhängigkeitsrichtung:
```
Paket A
   ↓ (abhängig von)
Paket B
```

Pfeile zeigen: "A nutzt Code von B"

## Feature im Detail

### Auto-Expand Depth
Der Spinner "Auto-Expand Depth" (1-10) bestimmt wie viele Ebenen der Paket-Hierarchie gezeigt werden:

```
Depth: 1          Depth: 2               Depth: 3
───────           ────────               ────────
de                de                     de
 ↓                 ↓                      ↓
weigend           weigend                weigend
 ↓                 ↓                      ↓
s202              s202                   s202
                   ↓                      ↓
                  ui                     ui
                  model                  model
                  analysis               analysis
                                          ↓
                                        services
                                        utils
```

### Status Bar
```
Loaded 24 classes | 5 packages | 2 cycles detected
```

Zeigt:
- **24 classes**: Gesamt-Anzahl .class-Dateien
- **5 packages**: Unterschiedliche Java-Pakete
- **2 cycles**: 2 zyklische Abhängigkeiten erkannt

## Bedienung

1. **Load JAR** - Datei-Dialog zur JAR-Auswahl
2. **Auto-Expand Depth** - Spinner (1-10) für Hierarchie-Tiefe
3. **Refresh** - UI neu zeichnen (nach Depth-Änderung)

## Beispiel: S202 selbst analysieren

```bash
mvn javafx:run
# → Load JAR → target/s202-code-analyzer-1.0.0.jar

Erwartete Struktur:
┌────────────┐
│   de       │  (Root-Paket)
└────────────┘
```

Mit Depth=2:
```
┌────────────┐
│   de       │
└────────────┘
     ↓
┌────────────┐
│  weigend   │
└────────────┘
     ↓
┌────────────┐
│   s202     │
└────────────┘
```

Mit Depth=3 (zeigt auch Sub-Pakete):
```
┌────────────────────┐
│  ui, analysis,     │
│  model, io, etc.   │
└────────────────────┘
```

## Technische Details

Die Visualisierung nutzt:
- **JavaFX Canvas** für effizientes Zeichnen
- **Topologisches Sorting** für Abhängigkeitsordnung
- **Automatisches Layout** mit Grid-Positioning
- **Skalierbar** für 10-1000+ Pakete

## Zukunft

Geplante Erweiterungen:
- 🎯 Klick auf Paket für Details
- 🔍 Hover für Abhängigkeits-Vorschau
- 📈 Zoom + Pan (für große Strukturen)
- 🎨 Farbcodierung nach Abhängigkeitstiefe
- 🔄 Animierte Übergänge
