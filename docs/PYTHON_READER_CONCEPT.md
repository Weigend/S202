# Konzept: Python Reader fuer S202

## Ziel

Der Python Reader soll Python-Quellcode in dasselbe Rohmodell ueberfuehren,
das der Java-Bytecode-Reader heute liefert:

```text
Python source tree
  -> DependencyModel
      -> ClassInfo(fullName, simpleName, packageName)
      -> ClassInfo.dependencies + dependencyKinds
      -> MethodInfo.methodCalls + methodCallDescriptors
      -> PackageInfo hierarchy
  -> LevelCalculator
  -> DomainModel / Architecture views
```

Damit bleiben Levelberechnung, Package-Aggregation, Tangle-Erkennung,
What-if-Views und Outline-UI grundsaetzlich unveraendert. Der Reader darf
keine eigene Architektursemantik einfuehren; er liefert nur stabile Elemente
und Abhaengigkeiten.

## Wichtigste Modellentscheidung

S202 arbeitet aktuell auf "Klassen" als kleinster sichtbarer Architektur-
Einheit. Python hat aber Module/Dateien, Top-Level-Funktionen und Klassen.
Fuer den ersten Reader sollte deshalb gelten:

**Default Class = Python-Datei / Modul.**

Eine Python-Datei wird als `DependencyModel.ClassInfo` modelliert. Python-
Funktionen, Python-Klassen und Methoden werden als `MethodInfo`-Scopes dieser
Datei modelliert.

Begruendung:

- Python-Module sind die natuerlichste architektonische Einheit: Imports,
  APIs, Services und Skripte sind sehr oft datei-/modulorientiert.
- Das bestehende Modell kann damit sofort weiterverwendet werden.
- `LevelCalculator.extractPackageName(...)` nimmt aktuell den Package-Namen
  aus dem `fullName` per letztem Punkt. Wuerden Python-Klassen als eigene
  ClassInfo-Knoten `pkg.module.PythonClass` modelliert, wuerde `pkg.module`
  automatisch als Package interpretiert. Das waere fuer die gewuenschte
  Ordnerstruktur falsch, solange das Modell keine explizite Package-Resolution
  aus `ClassInfo.packageName` nutzt.

Eine spaetere "Python class as S202 class"-Variante ist moeglich, sollte aber
erst kommen, wenn die Domain-Logik `ClassInfo.packageName` statt String-
Parsing als autoritative Package-Zuordnung verwendet.

## FQN- und Package-Abbildung

Quelle:

```text
repo/
  pyproject.toml
  src/
    shop/
      __init__.py
      orders/
        service.py
        model.py
```

Mapping:

| Python-Datei | S202 ClassInfo.fullName | ClassInfo.packageName | simpleName |
|---|---|---|---|
| `src/shop/orders/service.py` | `shop.orders.service` | `shop.orders` | `service` |
| `src/shop/orders/model.py` | `shop.orders.model` | `shop.orders` | `model` |
| `src/shop/orders/__init__.py` | `shop.orders.__init__` | `shop.orders` | `__init__` |
| `src/main.py` ohne Package | `<root>.main` | `<root>` | `main` |

Regeln:

- Source roots werden explizit oder heuristisch bestimmt: `src/`, `lib/`, Projektroot,
  konfigurierte Pfade.
- Ordner unterhalb des Source roots werden zu Packages, getrennt mit `.`.
- Eine Datei wird zu einer ClassInfo mit FQN `<package>.<moduleName>`.
- Top-Level-Dateien ohne Package bekommen ein synthetisches Root-Package,
  z.B. aus Projektname oder Konfiguration. Leere Packages vermeiden, weil
  mehrere bestehende Algorithmen mit nicht-leeren Namen stabiler arbeiten.
- `__init__.py` wird als eigenes Modul `.__init__` aufgenommen. Das Package
  selbst bleibt PackageInfo, nicht ClassInfo.
- Wird ein Package-Verzeichnis direkt ausgewaehlt, z.B.
  `/usr/lib/python3/dist-packages/ansible`, scannt der Reader nur diesen
  Teilbaum, bildet die Modul-FQNs aber relativ zum Parent. Dadurch wird
  `playbook/base.py` zu `ansible.playbook.base`, nicht zu `playbook.base`.
- Namespace-Packages ohne `__init__.py` koennen im Default erlaubt werden,
  wenn sie Python-Dateien enthalten. Eine Option `requireInitPy=true` kann fuer
  konservative Projekte angeboten werden.

PackageInfo wird wie beim Java-Reader aus allen ClassInfo-Eintraegen gebaut.
Die Package-Hierarchie-Logik sollte aus `InputAnalyzer` in einen gemeinsamen
`PackageHierarchyBuilder` ausgelagert werden, damit Java- und Python-Reader
dieselbe Struktur erzeugen.

## Method-/Scope-Abbildung

Python-Scopes werden in `DependencyModel.MethodInfo` abgelegt:

| Python-Konstrukt | MethodInfo.name | descriptor |
|---|---|---|
| Top-Level-Code einer Datei | `__module__` | `()` |
| `def load_order(id): ...` | `load_order` | `(id)` |
| `async def publish(event): ...` | `publish` | `(event)` |
| `class OrderService: ...` Klassendefinition | `OrderService.__classdef__` | `()` |
| `OrderService.__init__(self, repo)` | `OrderService.__init__` | `(self, repo)` |
| `OrderService.place(self, order)` | `OrderService.place` | `(self, order)` |
| geschachtelte Funktion `outer.inner` | `outer.inner` | `(...)` |

Der Descriptor ist kein JVM-Descriptor. Er ist ein stabiler Python-
Signaturstring fuer Anzeige und Persistenz. Fuer unbekannte oder zu teure
Signaturen reicht `()`.

Call-Details nutzen weiterhin das bestehende Format:

```text
sourceClass = shop.orders.service
sourceMethod = OrderService.place(self, order)
targetClass = shop.orders.repository
targetMethod = OrderRepository.save

MethodInfo.methodCalls["shop.orders.repository.OrderRepository.save"] += 1
MethodInfo.methodCallDescriptors["shop.orders.repository.OrderRepository.save"].add("(self, order)")
sourceClass.addDependency("shop.orders.repository", EdgeKind.CALLS)
```

Damit funktionieren bestehende Call-Drilldowns, Hotspot-Erkennung und
Package-Gewichtung weiter, weil alle auf `targetClass + "." + targetMethod`
pruefen.

## Dependency-Abbildung

### Imports

Imports erzeugen eine Abhaengigkeit auf das Zielmodul, wenn dieses im
analysierten Projekt vorhanden ist:

| Python | Edge |
|---|---|
| `import shop.orders.model` | current module -> `shop.orders.model`, `IMPORTS` |
| `import shop.orders.model as model` | alias `model` -> `shop.orders.model`, `IMPORTS` |
| `from shop.orders import model` | current module -> `shop.orders.model`, `IMPORTS` falls Datei existiert |
| `from shop.orders.model import Order` | current module -> `shop.orders.model`, `IMPORTS` |
| `from .model import Order` | relative Aufloesung -> `shop.orders.model`, `IMPORTS` |
| `from . import model` | relative Aufloesung -> `shop.orders.model`, `IMPORTS` |
| `from x import *` | konservativ: `IMPORTS` auf Modul `x`, keine Symbolauflosung im MVP |

Externe Imports werden ignoriert, wenn kein passendes Projektmodul im
DependencyModel existiert oder sie per Exclusion ausgeschlossen sind.

### Calls

Calls werden in zwei Stufen aufgeloest:

1. Symbolindex ueber das ganze Projekt bauen:
   - Modul-FQN -> Datei
   - Top-Level-Funktion -> Modul
   - Top-Level-Klasse -> Modul
   - Methoden einer Top-Level-Klasse -> Modul + Python-Klasse
2. Pro Datei Alias-/Scope-Tabelle aus Imports und lokalen Definitionen bauen.

Mapping-Beispiele:

| Python-Code in `shop.orders.service` | Ziel | EdgeKind |
|---|---|---|
| `model.Order(...)` nach `from . import model` | `shop.orders.model.Order.__init__` | `INSTANTIATES` |
| `Order(...)` nach `from .model import Order` | `shop.orders.model.Order.__init__` | `INSTANTIATES` |
| `repository.save(order)` nach `import shop.orders.repository as repository` | `shop.orders.repository.save` | `CALLS` |
| `publish(event)` nach `from shop.events.bus import publish` | `shop.events.bus.publish` | `CALLS` |
| `self.repo.save(order)` | heuristisch, nur wenn `self.repo = Repository(...)` bekannt ist | `CALLS` |
| `getattr(plugin, name)()` | nicht aufloesbar im MVP | keine Kante oder Warnung |

Ein Constructor-Call ist `INSTANTIATES`, wenn das Zielsymbol im Projektindex
eine Python-Klasse ist. Sonst ist es `CALLS`.

### Vererbung und Typen

| Python-Konstrukt | EdgeKind |
|---|---|
| `class A(B): ...` | `EXTENDS` auf das Modul, das `B` definiert |
| `class A(Protocol): ...` | `USES` oder extern ignorieren, wenn `typing.Protocol` |
| Typannotation `repo: Repository` | `USES` auf Definitionsmodul |
| Return-Typ `-> Receipt` | `USES` auf Definitionsmodul |
| Decorator `@transactional` | `USES`, bei aufgeloestem Call optional `CALLS` |
| Dataclass/Pydantic/attrs-Basen | normale Imports/Uses; keine Spezialsemantik im MVP |

`IMPLEMENTS` sollte im Python-MVP nicht verwendet werden. Python hat keine
explizite Interface-Implementierung wie Java; `Protocol` und ABCs koennen
spaeter als optionale Heuristik klassifiziert werden.

## Parser-Strategie

Der Reader sollte nicht mit Regex arbeiten. Er braucht einen echten AST.

Zwei realistische Optionen:

| Option | Vorteil | Nachteil |
|---|---|---|
| Java-integrierter Parser, z.B. Tree-sitter Python | Java-App bleibt ein Prozess; gute inkrementelle Parser-Basis | Native/Grammar-Abhaengigkeiten im Build und Packaging |
| Externer CPython-AST-Helper, der JSON ausgibt | Sehr nahe an Python-Syntax der installierten Version; schneller MVP | Benotigt Python-Executable; IPC/Fehlerbehandlung |

Empfehlung:

- Fuer einen schnellen, validierbaren MVP: CPython-AST-Helper als internes
  Analysewerkzeug, Ausgabe als JSON, Java baut daraus das DependencyModel.
- Fuer ein spaeteres Produkt-Package ohne externe Runtime-Abhaengigkeit:
  Parser austauschbar machen, z.B. Interface `PythonAstProvider`.

Wichtiger als die Parserwahl ist die stabile Modellgrenze:

```text
PythonAstProvider
  -> ParsedPythonModule(moduleFqn, file, imports, definitions, references)
  -> PythonDependencyResolver
  -> DependencyModel
```

## Analyse-Pipeline

```text
PythonSourceAnalyzer.analyze(sourceRoots)
  1. Dateien sammeln
     - *.py
     - exclude: .venv, venv, env, site-packages, __pycache__, .git, build, dist
     - optionale excludes analog excluded-prefixes.txt

  2. Modul-FQNs bilden
     - source root entfernen
     - Pfadsegmente -> Package
     - Dateiname ohne .py -> simpleName
     - ClassInfo je Datei anlegen

  3. ASTs parsen
     - Syntaxfehler pro Datei melden, aber Analyse fortsetzen
     - AST-Daten nicht in DomainModel, sondern nur im Reader verwenden

  4. Projektweiten Symbolindex bauen
     - Module
     - Top-Level-Funktionen
     - Top-Level-Klassen
     - Klassenmethoden

  5. Pro Modul Dependencies extrahieren
     - Imports -> IMPORTS
     - Base classes -> EXTENDS/USES
     - Typen/Decoratoren -> USES
     - Calls -> CALLS/INSTANTIATES + MethodInfo.methodCalls

  6. PackageInfo-Hierarchie bauen
     - gemeinsamer PackageHierarchyBuilder

  7. DependencyModel zurueckgeben
```

## Beispiel

Python:

```python
# src/shop/orders/service.py
from .model import Order
from .repository import OrderRepository
from shop.events.bus import publish

class OrderService:
    def __init__(self, repo: OrderRepository):
        self.repo = repo

    def place(self, data):
        order = Order(data)
        self.repo.save(order)
        publish(order)
```

Zielmodell:

```text
ClassInfo
  fullName: shop.orders.service
  simpleName: service
  packageName: shop.orders
  dependencies:
    shop.orders.model          [USES, INSTANTIATES]
    shop.orders.repository     [USES, CALLS]
    shop.events.bus            [USES, CALLS]

MethodInfo OrderService.__init__(self, repo)
  methodCalls: none or heuristic assignment metadata only

MethodInfo OrderService.place(self, data)
  methodCalls:
    shop.orders.model.Order.__init__ -> 1
    shop.orders.repository.OrderRepository.save -> 1
    shop.events.bus.publish -> 1
```

Package-Aggregation daraus:

```text
shop.orders -> shop.events   weight 1
```

Die interne Beziehung `service -> model/repository` bleibt innerhalb von
`shop.orders` und beeinflusst lokale Layer/Drilldowns, aber nicht zwingend die
globale Package-Richtung zwischen externen Packages.

## Grenzen und Heuristiken

Python ist dynamisch. Der Reader sollte praezise sein, wo statische Information
vorliegt, und konservativ bleiben, wo sie fehlt.

Nicht oder nur heuristisch im MVP:

- `importlib.import_module(...)`
- dynamische `getattr`-/`setattr`-Calls
- Monkey-Patching
- Dependency Injection Container ohne explizite Typen
- Star imports mit Re-Exports aus `__init__.py`
- Laufzeitgenerierung von Klassen/Funktionen
- Calls ueber untypisierte Variablenketten

Heuristiken mit hohem Nutzen:

- Import-Aliase konsequent verfolgen.
- `self.x = ImportedClass(...)` in `__init__` als Feldtyp merken.
- Lokale Variable `repo = OrderRepository()` innerhalb einer Methode merken.
- Typannotation `repo: OrderRepository` fuer `repo.save(...)` nutzen.
- `if TYPE_CHECKING:`-Imports fuer Typ-Uses auswerten, aber nicht als Runtime-
  Calls interpretieren.

## Integration in den Code

Neue/veraenderte Bausteine:

| Baustein | Aufgabe |
|---|---|
| `reader.PythonSourceAnalyzer` | Einstiegspunkt, analog `InputAnalyzer`, gibt `DependencyModel` zurueck |
| `reader.PackageHierarchyBuilder` | gemeinsame PackageInfo-Erzeugung fuer Java und Python |
| `reader.python.PythonAstProvider` | Parser-Abstraktion |
| `reader.python.ParsedPythonModule` | parsernahes DTO ohne Domain-Semantik |
| `reader.python.PythonSymbolIndex` | projektweiter Modul-/Symbolindex |
| `reader.python.PythonDependencyResolver` | AST/Symbolindex -> ClassInfo dependencies + MethodInfo calls |
| `reader.python.PythonNameResolver` | relative Imports, Aliase, einfache Typ-/Variablenauflosung |

Die bestehende `DependencyModel`-Struktur kann fuer den MVP unveraendert
bleiben. Sinnvolle kleine Erweiterungen fuer spaeter:

- `language` oder `sourceKind` pro `ClassInfo`, falls UI Java/Python
  unterschiedlich beschriften soll.
- `sourcePath` pro ClassInfo fuer "open source file".
- Explizite Package-Resolution ueber `ClassInfo.packageName` in
  `LevelCalculator`, damit spaeter echte Python-Klassen als eigene ClassInfo-
  Knoten moeglich werden.

## Tests

MVP-Tests sollten klein und modellnah sein:

1. `import pkg.mod` erzeugt `USES` auf `pkg.mod`.
2. `from .model import Order; Order()` erzeugt `INSTANTIATES` und MethodCall
   auf `pkg.model.Order.__init__`.
3. `from .repo import Repository; repo: Repository; repo.save()` erzeugt
   `CALLS` auf `pkg.repo.Repository.save`.
4. `class Service(BaseService)` erzeugt `EXTENDS` auf das Modul von
   `BaseService`.
5. `__init__.py` wird als `pkg.__init__` ClassInfo angelegt und Package `pkg`
   bleibt PackageInfo.
6. Top-Level-Dateien ohne Package landen in einem synthetischen Root-Package.
7. Externe Imports und `.venv/site-packages` werden ausgeschlossen.
8. Das resultierende `DependencyModel` laeuft durch `LevelCalculator` ohne
   Sonderfall.

## Umsetzungsreihenfolge

1. `PackageHierarchyBuilder` aus dem Java-Reader extrahieren.
2. Python-FQN-/SourceRoot-Discovery implementieren.
3. AST-Provider und DTOs bauen.
4. Symbolindex fuer Module, Top-Level-Funktionen, Klassen und Methoden.
5. Import-Resolver plus `USES`-Edges.
6. Call-Resolver fuer direkte Modul-/Symbolcalls plus `CALLS`/
   `INSTANTIATES`.
7. Type-/Inheritance-Resolver fuer `USES`/`EXTENDS`.
8. UI-Einstieg fuer Python-Source-Roots ergaenzen.
9. Fokus auf Tests gegen `DependencyModel` und einen Full-Pipeline-Test bis
   `DomainModel`.

## Kernregel fuer den MVP

Der Python Reader soll lieber wenige, stabile und nachvollziehbare
Abhaengigkeiten liefern als viele spekulative. Imports, direkte Calls,
Konstruktoraufrufe, Vererbung und Typannotationen reichen fuer eine erste
architektonische Sicht. Alles Dynamische wird markiert oder ignoriert, bis eine
konkrete Heuristik in Tests belegbar ist.
