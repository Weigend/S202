# Offene Punkte / Open Points

## 1. Behandlung von inneren Klassen (Inner Classes)

**Status:** Nicht implementiert

**Beschreibung:**
Innere Klassen (Inner Classes, gekennzeichnet durch `$` im Klassennamen, z.B. `DependencyModel$ClassInfo`) werden derzeit in der Analyse ignoriert. Das bedeutet:

- Innere Klassen werden nicht als eigenständige Elemente in der Architekturvisualisierung angezeigt
- Abhängigkeiten von/zu inneren Klassen werden nicht separat verfolgt
- Innere Klassen-Abhängigkeiten werden implizit der äußeren Klasse zugeordnet

**Grund für aktuelle Implementierung:**
Diese Vereinfachung verhindert, dass innere Klassen doppelt gezählt werden und die Visualisierung zu überladen wird. Für die meisten Fälle ist dies ausreichend, da die wichtigsten Abhängigkeiten auf Package- und Klassen-Ebene erfasst werden.

**Ort im Code:**
- Datei: [InputAnalyzer.java](analyzer/src/main/java/de/weigend/s202/analysis/input/InputAnalyzer.java)
- Klasse: `DependencyExtractor`
- Methode: `visit(int version, int access, String name, ...)`

**Potenzielle zukünftige Verbesserungen:**
1. Optional: Innere Klassen als untergeordnete Knoten unter ihrer äußeren Klasse anzeigen
2. Optional: Ein Flag in der Konfiguration zur Aktivierung/Deaktivierung der Behandlung innerer Klassen
3. Optional: Separate Analyse-Statistiken für innere Klassen

---

## Format

Weitere offene Punkte sollten mit folgendem Format dokumentiert werden:

```markdown
## N. Titel des Punkts

**Status:** (Nicht implementiert | In Arbeit | Beobachtung)

**Beschreibung:** 
[Kurze Erklärung des Problems/Features]

**Grund:**
[Warum wird das aktuell so gemacht / nicht gemacht]

**Ort im Code:**
[Welche Dateien/Klassen sind betroffen]

**Potenzielle Lösungen:**
[Ideen für zukünftige Verbesserungen]
```
