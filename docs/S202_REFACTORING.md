# Refactoring mit S202

## Ziel

Dieses Dokument beschreibt, wie S202 zur Planung und Bewertung von Refactorings genutzt werden kann. Der Fokus liegt nicht auf der Bedienung einzelner UI-Elemente, sondern auf dem Arbeitsablauf von Befund zu Entscheidung.

## Geplanter Inhalt

1. Architekturhypothese als Ausgangspunkt
2. Verletzungen als Refactoring-Hinweise
3. Back-Edges und Zyklen bewerten
4. CUTs als geplante Trennungen nutzen
5. What-if-Verschiebungen interpretieren
6. Refactoring-Kandidaten priorisieren
7. Vorher-nachher-Vergleich durchführen
8. Grenzen der automatischen Ableitung

## Leitfragen

- Welche Verletzungen sind technisch dringend?
- Welche Verletzungen sind fachlich akzeptiert?
- Welche Abhängigkeiten müssen entkoppelt werden, damit eine Zielordnung tragfähig wird?
- Welche CUTs lösen tatsächlich eine SCC auf?
