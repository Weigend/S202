Das ist ein Platzhalter fuer einen Artikel im Java Spektrum der folgende Idee in Verbindung mit AI aufgreift.

Software entsteht heute mit AI Unterstutzung viel schneller als bisher. Es entsteht damit auch viel mehr Software als noch vor ein paar Jahren. Tools wie Claude Code oder Codex generieren ganze Anwendungen in wenigen Minuten. 

Die Codequalitaet ist oft besser als von vielen Programmierern mit denen ich in meinen Berufsleben zusammengearbeitet habe. Anfaengerfehler sieht man selten. 

Aber genau wie handgeschriebene Software ist Anwendungsentwicklung iterativ. An diesem Punkt wird es problematisch. Klassen koennen mit AI Generierung sehr gross werden und die AI behaelt noch einigermassen den Ueberblick wo Programmierer normalerweise schon STOP, WIR BRAUCHEN EINE ARCHITEKTUR sagen. Die AI baut ein Feature nach dem anderen ohne Murren ein, zyklische Abhaengigkeiten, kein Problem, Fremdbibliotheken nur nach Featurebedarf, schnell und effizient, aber nicht automatisch klar, zukunftssicher und verstaendlich strukturiert, erweiterbar oder wartbar. Technische Architekturschulden sind die Norm, nicht die Ausnahme.

Die AI kann aber rasend schnell Code generieren, schneller als wir das lesen koenne, schnelle Refactorings, meist Fehlerfrei durchfuehren. Tests anpassen oder ganze Anwendungen umbauen. Am Ende braucht es aber Menschen die dafuer die Verantwortung uebernehmen, das Resultat verstehen, die konzeptionellen Schwaechen erkennen, diskutieren und beseitigen. Dafuer braucht es Sachverstand, Kreativitaet, Expertise aber auch gute Tools damit Software Architekten sich schnell in generierten
oder teilgenerierten Codebasen orientieren koennen.

Dieser Artikel stellt im folgenden ein neues Open Source Werkzeug vor (S202 Apache Lizenz) das eine Hilfe zur Erkennung, Planung und Verbesserung der Software Qualitaet von grossen Anwendungen bietet. Weil es offen ist, kann es mit ein paar AI Sessions fuer beliebige Technologien erweitert werden, .NET, GO, GRPC, K8S ... alles schnell eingebaut. 

HIER WIRD S202 VORGESTELLT insbesondere die Architekturhypothese, Verletzungen, Zyklen 

5 Seiten auf Deutsch.