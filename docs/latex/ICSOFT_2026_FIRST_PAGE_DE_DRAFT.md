# Konsistente Ebenen fuer geschichtete Softwarearchitektur-Visualisierungen

## Untertitel

Trennung von Architekturhypothese, Paketbaum und lokaler Anordnung

## Autoren

Johannes Weigend, Weigend AM GmbH & Co.KG, Germany/Brazil  
Michael Philippsen, Friedrich-Alexander-Universitaet Erlangen-Nuernberg, Germany

## Keywords

Softwarearchitektur-Visualisierung, Layout-Invarianten, geschichtete 2D-Architekturdarstellung, stark zusammenhaengende Komponenten, Dependency-Analyse, Software-Engineering-Werkzeuge

## Abstract

Geschichtete Architekturvisualisierungen machen Abhaengigkeitsstruktur durch vertikale Anordnung sichtbar: Elemente, die von anderen Elementen abhaengen, stehen hoeher; fundamentalere Elemente stehen tiefer. Ihr praktischer Wert haengt an einem einfachen Versprechen: Eine nach oben laufende Abhaengigkeit soll kein zufaelliger Layout-Artefakt sein, sondern eine echte architektonische Rueckkopplung, ein bewusst offengelegter Heuristik-Schnitt oder eine Regelverletzung, die der Benutzer untersuchen sollte. Wenn die Ebenenberechnung falsch ist, kann das Bild trotzdem plausibel aussehen und gerade deshalb in die Irre fuehren.

S202 ist ein quelloffenes Java-Werkzeug zur Analyse und Visualisierung von Softwarearchitekturen, veroeffentlicht unter der Apache-2.0-Lizenz.[^s202] Die hier untersuchte Architekturdarstellung ist dem frueheren Produkt Structure101 sehr aehnlich. Nach unseren Recherchen wurden jedoch weder der Quellcode noch der zugrundeliegende Levelisierungsalgorithmus von Structure101 oeffentlich dokumentiert. S202 macht diese Art der Darstellung als offenen Algorithmus nachvollziehbar: mit reproduzierbarer Berechnung, expliziten Heuristik-Entscheidungen und pruefbaren Konsistenzbedingungen.

Der Algorithmus erzeugt nicht einfach eine weitere geschichtete 2D-Darstellung, sondern berechnet zuerst eine Architekturhypothese. Ausgangspunkt ist der Java-Paketbaum: Pakete enthalten Subpakete und Klassen, und diese hierarchische Grundstruktur bleibt in der Visualisierung erhalten. Aus den gewichteten gegenseitigen Paketabhaengigkeiten gewinnt S202 eine plausible Architekturordnung dieses Paketbaums. Wenn ein Paket deutlich staerker von einem anderen Paket abhaengt als umgekehrt, wird es als hoeher liegender Nutzer interpretiert; das staerker genutzte Paket wird tiefer als fundamentaler Baustein eingeordnet.

Konzeptionell besteht die Pipeline aus drei Stufen. Erstens analysiert S202 die Klassenabhaengigkeiten ohne Ruecksicht auf die Paketstruktur: globale SCCs werden erkannt, grosse Zyklen heuristisch geschnitten, und daraus entsteht eine globale Sicht auf Rueckkopplungen im Klassengraphen. Zweitens berechnet S202 aus den gewichteten Package-Abhaengigkeiten eine Architekturhypothese fuer den Paketbaum. Das zentrale Ordnungskriterium ist dabei einfach: Die wahrscheinlich normalen Abhaengigkeiten sollen nach unten laufen. Drittens berechnet S202 die sichtbare lokale Anordnung innerhalb jedes Paketcontainers.

Der zentrale Punkt ist die Trennung zwischen Architekturhypothese und Layoutposition. Die Package-Analyse entscheidet, welche Package-Richtungen als normal, zyklisch oder heuristisch geschnitten gelten. Die lokale Anordnung entscheidet dagegen, wo Klassen und sichtbare Container innerhalb eines konkreten Elternpakets stehen. Diese lokale Phase benutzt nur Abhaengigkeiten innerhalb des jeweiligen Containers und darf numerische Row-Indizes neu vergeben. Ihr Ziel ist jedoch nicht, die Zahl sichtbarer Aufwaertskanten lokal zu minimieren. Ziel ist eine stabile Einordnung in den bereits berechneten architektonischen Rahmen: Klassen und sichtbare Container werden so platziert, dass die wahrscheinlichste Paketstruktur erhalten bleibt und lokale Zyklen nachvollziehbar aufgeloest werden.

Auf dieser Trennung baut die sichtbare Anordnung auf. Lokale SCCs werden im jeweiligen Geschwistergraphen erkannt. Fuer die lokale Anordnung wird dabei nicht einfach die erste Rueckwaertskante oder eine rein kantenweise Rank-Heuristik geschnitten. S202 betrachtet den gesamten lokalen SCC und bevorzugt einen Schnitt, dessen Entfernung die zyklische Komponente am staerksten zerlegt. Dadurch wird die lokale Unordnung moeglichst stark reduziert, ohne die Paketstruktur als primaere Architekturhypothese durch ein blosses Layoutkriterium zu ueberstimmen. Eine nach oben laufende Linie ist deshalb kein stillschweigender Layoutfehler: Sie bleibt entweder als Architekturverletzung, als zyklische Rueckkopplung oder als expliziter Heuristik-Schnitt erklaerbar. Horizontale Ordnung kann die Lesbarkeit verbessern, aendert aber nicht, welche Richtung als architektonisch normal gilt.

Der Grund, warum das nicht als einfacher Single-Pass ueber alle Elemente funktioniert, sind Zyklen und Rueckwirkungen zwischen den Ebenen. Klassen koennen sich gegenseitig abhaengen, Pakete koennen zyklisch gekoppelt sein, und lokale Layoutentscheidungen duerfen nicht unkontrolliert in die semantische Package-Analyse zurueckfliessen. S202 behandelt Zyklen deshalb auf der jeweils passenden Ebene: global fuer Klassen- und Package-Zyklen in der Architekturhypothese, lokal fuer Zyklen innerhalb eines Paketcontainers. Kleine oder gleichrangige Zyklen bleiben als gemeinsame Gruppe erhalten. Lokal werden groessere SCCs durch Schnitte reduziert, die moeglichst viel zyklische Struktur auf einmal aufbrechen; die geschnittenen Kanten bleiben explizit klassifiziert.

Wichtig ist dabei, was S202 nicht tut. Der Algorithmus sucht kein globales Minimum verletzter Regeln und probiert nicht iterativ immer neue Layouts aus, bis ein besseres Bild entsteht. Stattdessen folgt er einer festen Pipeline: Paketbaum aufbauen, gewichtete Architekturhypothese ableiten, globale und lokale SCCs erkennen, wahrscheinliche Back-Edges explizit markieren, lokale Reihenfolgen berechnen und danach pruefen, ob die resultierende Darstellung die erwarteten Regeln einhaelt. Diese Invarianten unterscheiden Pipeline-Fehler von absichtlich tolerierten Heuristik-Schnitten und von echten Architekturverletzungen, die die Visualisierung sichtbar machen soll.

Der Beitrag zeigt diese Pipeline am Beispiel von Minecraft Forge 1.19.2. Das System ist gross, stark zyklisch und deshalb ein guter Stresstest fuer die Frage, ob eine geschichtete Darstellung mehr zeigt als ein plausibles Bild. Wir zeigen an diesem Beispiel, wie S202 grosse SCCs aufbricht, Package- und Klassenebene getrennt behandelt und die verbleibenden nach oben laufenden Kanten explizit klassifiziert. Damit demonstriert der Abstract nicht nur ein Layout, sondern eine nachvollziehbare Berechnung: von der Architekturhypothese ueber die lokale Anordnung bis zur maschinellen Konsistenzpruefung.

[^s202]: <https://github.com/jweigend/Structure202>
