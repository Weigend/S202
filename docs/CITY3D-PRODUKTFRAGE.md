# Gehört City3D in S202 — oder sind das zwei Produkte?

*Stand: Juli 2026 · Diskussionspapier zur Produktarchitektur*

## Die Frage

S202 ist ein Analysewerkzeug: deterministische Level-Berechnung,
Schichtendarstellung, Tangle-Analyse, Komponenten- und Hexagonalsicht.
City3D ist eine 3D-Stadt im Browser: Verkehr, Mondlicht, Kamerafahrten.
Sind die Nutzer so verschieden, dass man das in zwei Apps trennen sollte?

## Wer nutzt was — die Nutzerprofile ehrlich betrachtet

| | **Analyst** (2D-Views) | **Betrachter** (City3D) |
|---|---|---|
| Rolle | Architekt, Senior-Entwickler | Management, Team, Onboardee, Konferenzpublikum |
| Aufgabe | Verstöße finden, Schnitte planen, Refactoring begründen | Verstehen, überzeugt werden, sich orientieren |
| Modus | interaktiv, präzise, wiederholt | rezeptiv, explorativ, einmalig bis gelegentlich |
| Braucht | Tabellen, Diffs, Determinismus, CI | einen Link, keine Installation, Wow + Verständlichkeit |
| Erfolgsmaß | richtige Entscheidung | "Jetzt habe ich's verstanden" |

Die Profile sind real verschieden — **aber**: Der Betrachter erzeugt nie
selbst eine Stadt. Jede City3D-Sitzung beginnt mit einer S202-Analyse.
Die Trennung verläuft also nicht zwischen zwei Produkten, sondern zwischen
**Erzeugen** (Analyst, S202) und **Konsumieren** (alle anderen, Browser).
Und häufig ist es dieselbe Person in zwei Situationen: morgens analysieren,
nachmittags dem Management präsentieren.

## Was für die Integration spricht

1. **Eine Semantik, eine Wahrheit.** Die Stadt ist eine Projektion derselben
   Analyse: Levels → Reihen, Zyklen → Beacons, Verstöße → roter Verkehr.
   Diese Regeln haben wir während der Entwicklung mehrfach nachjustiert
   (Verstoß = *streng* aufwärts, nicht SCC-Mitgliedschaft). Zwei Produkte
   heißt: zwei Stellen, die solche Regeln synchron halten müssen — die
   Drift ist vorprogrammiert und für den Nutzer unsichtbar giftig
   („warum ist das hier rot und dort nicht?").
2. **Der Workflow ist ein Loop, kein Übergabepunkt.** Die bidirektionale
   Selektion (2D ↔ Browser) ist gebaut und ist der eigentliche Mehrwert:
   Tangle im 2D-View finden, in 3D die Geschichte dazu erzählen, per Klick
   zurück in die Analyse. Ein Produkt-Split zerschneidet genau diesen Loop.
3. **Die Kopplung ist schon minimal.** City3D hängt an S202 über genau zwei
   Artefakte: `city.json` (Datenschema) und einen Loopback-Server (Selektion).
   Es läuft standalone gegen ein exportiertes JSON. Die Integrationskosten
   in der App sind ein Menüpunkt — das ist kein architektonischer Ballast,
   den ein Split abwerfen würde.
4. **Produktstrategie: Tür-Öffner + Substanz.** Software-Cities gibt es
   viele (CodeCity & Nachfahren); als eigenständiges Produkt wäre City3D
   eines unter vielen. Sein USP ist exakt die S202-Semantik darunter
   (deterministische Levels, echte Verstoß-Klassifikation). Umgekehrt gibt
   die Stadt S202 den Demo-Wow-Faktor, den ein Tabellen-Werkzeug nie hat.
   Getrennt verlieren beide ihr stärkstes Argument.
5. **Ein Release, ein Analyzer, ein Paper.** Auch die Forschungs-Story
   (Schichtendarstellung → 3D-Projektion) ist eine gemeinsame.

## Was für die Trennung spricht

1. **Der Betrachter will keine JavaFX-App installieren.** Das ist das
   stärkste Argument — nur richtet es sich nicht gegen die Integration,
   sondern gegen die *Auslieferung*: Der Konsument braucht einen Link,
   kein Werkzeug.
2. **Divergierende Stacks und Rhythmen.** JavaFX/wfx vs. three.js/Vite;
   der Analyzer will Stabilität und Tests, die 3D-Sicht iteriert visuell
   und schnell. Gemeinsame Releases koppeln ungleiche Risiken.
3. **Scope-Schutz.** Verkehr, Wetter, Zeitraffer, Kamerafahrten — die
   3D-Sicht zieht naturgemäß „Spielzeug-Features" an. Ohne klare Grenze
   könnten sie Roadmap und Issue-Tracker des Kernprodukts fluten.
4. **UX-Kohärenz.** Wenn Zielgruppen nichts voneinander wissen müssen,
   konkurrieren Menüs, Doku und Onboarding um dieselbe Oberfläche.

## Einschätzung

**Nicht in zwei Produkte aufspalten — aber die Grenze formalisieren:
ein Produkt, zwei Auslieferungen.**

Die Nutzer sind verschieden, ja — aber sie trennen sich entlang
*Erzeugen vs. Konsumieren*, nicht entlang zweier Produkte. Ein Split würde
das teuerste Gut gefährden (eine gemeinsame Semantik + der 2D↔3D-Loop)
und im Gegenzug ein Problem lösen, das sich billiger lösen lässt: der
Betrachter braucht keinen App-Split, sondern einen **teilbaren Snapshot**.

Konkret heißt das:

1. **S202 bleibt die eine App** mit *Show City3D View* — für den Analysten
   ist die Stadt ein weiterer View neben Schichten/Komponenten/Hexagon,
   mit Selektions-Sync als Alleinstellungsmerkmal.
2. **Neu: „Export als teilbare Website".** S202 schreibt `city3d/dist` +
   `city.json` als selbstständiges statisches Bundle (Zip / Verzeichnis /
   interner Webserver). Damit bekommt der Betrachter seinen Link ohne
   Installation — das bedient die „komplett anderen Nutzer" vollständig,
   ohne das Produkt zu teilen. (Der Sync-Kanal fehlt dann einfach; die
   Stadt funktioniert standalone bereits heute.)
3. **Die Schnittstelle ist der Vertrag:** `city.json` bekommt eine
   Schema-Version. Noch besser: Der Analyzer exportiert die *Klassifikation
   gleich mit* (`violation`-Flag pro Kante statt Doppelberechnung im JS) —
   dann *kann* die 3D-Sicht semantisch gar nicht mehr driften und der
   letzte echte Kopplungsschmerz verschwindet.
4. **Scope-Grenze aussprechen:** city3d bleibt ein eigenes Unterprojekt
   mit eigenem Build und eigener README-Roadmap. Visuelle Features landen
   dort und berühren den Analyzer nie; der Analyzer liefert nur Daten.

## Wann man die Entscheidung revidieren sollte

Ein echter Split wird richtig, sobald einer dieser Trigger eintritt:

- City3D bekommt **eigene Datenquellen** (andere Analyzer, andere
  Sprachen/Ökosysteme) — dann ist S202 nur noch *ein* Zulieferer.
- Es entsteht ein **eigenes Deployment-Modell** (gehostete Galerie,
  SaaS, Team-Dashboards) mit eigener Nutzerverwaltung.
- Ein **eigenes Team** übernimmt die 3D-Sicht und braucht unabhängige
  Release-Zyklen wirklich (nicht nur theoretisch).
- Der Snapshot-Export deckt >80 % der City3D-Nutzung ab und der
  Selektions-Sync verkümmert nachweislich — dann trägt das stärkste
  Integrationsargument nicht mehr.

Bis dahin gilt: Die Stadt ist das Schaufenster, die Analyse ist der Laden.
Man baut das Schaufenster nicht auf die andere Straßenseite.
