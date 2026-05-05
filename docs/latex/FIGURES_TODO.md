# Figures TODO -- ICSOFT 2026 Abstract

## Priorität 1: Muss haben

### Fig 1 -- software-ekg-7: R2-Befund (vor/nach Fix)
- **Was:** Zwei Screenshots nebeneinander
  - Links: fehlerhaftes Layout — Package auf falschem Level (R2-Verletzung sichtbar)
  - Rechts: korrigiertes Layout nach Pipeline-Fix
- **Wo in Structure202:** software-ekg-7 Projekt laden, Checker-Output mit R2-Befund
- **Caption-Entwurf:** "An R2 violation in software-ekg-7 (left) exposed a missing
  package-SCC equalization step; the corrected pipeline produces the expected
  level assignment (right)."

### Fig 2 -- Minecraft Forge: Big Lake vs. aufgelöste Hierarchie
- **Was:** Zwei Screenshots nebeneinander
  - Links: ohne SCC-Breaker — 4.038 Klassen auf einem flachen Level (der "See")
  - Rechts: mit SCC-Breaker — nutzbare Schichtung, rote Violation-Kanten sichtbar
- **Wo in Structure202:** Minecraft Forge Projekt, SCC-Breaker ein-/ausschalten
- **Caption-Entwurf:** "Minecraft Forge without the SCC breaker (left): over 40% of
  classes collapse onto a single level. With the breaker (right): a usable
  hierarchy emerges; heuristic cut edges are rendered as violations (red)."

---

## Priorität 2: Gut zu haben

### Fig 3 -- Checker-Output: Reproducer-Block
- **Was:** Screenshot der Checker-Ausgabe mit einem konkreten R2-Befund
  und dem copy-paste-fähigen Reproducer-Block
- **Wo:** Checker-Ausgabe in der Konsole oder IDE nach Analyse von software-ekg-7
- **Caption-Entwurf:** "The invariant checker emits a copy-paste-ready reproducer
  block for every finding, linking the visual violation to a reproducible test input."

### Fig 4 -- Top-Cut-Targets Panel
- **Was:** Screenshot des Top-Cut-Targets-Panels in Structure202
  - Methodenliste mit Cut-Edge-Häufigkeit
  - "Cut All"-Kontextmenü sichtbar
- **Wo:** Structure202 UI, Tangle mit mehreren Cut-Edges auswählen
- **Caption-Entwurf:** "The Top-Cut-Targets panel identifies the method calls
  responsible for the most cut edges, providing a direct path from visual
  diagnosis to targeted refactoring."

---

## Priorität 3: Optional (Short Paper)

### Fig 5 -- Unity/.NET 3D Software City
- **Was:** Screenshot der Unity-Implementierung mit denselben Eingaben
  wie software-ekg-7 oder Minecraft Forge
- **Belegt:** Portierbarkeits-Claim ("byte-identical findings on shared inputs")
- **Caption-Entwurf:** "The same four invariants ported to an independent
  Unity/.NET 3D software city produce byte-identical findings on shared inputs."

---

## Technische Hinweise
- Format: PNG, mind. 150 dpi für Print
- Breite: bei zwei nebeneinander je ~0.48\textwidth
- Rote Violation-Kanten bei Minecraft gut sichtbar machen (ggf. Zoom)
- Draft-Subtitle im Titel vor Einreichung entfernen
