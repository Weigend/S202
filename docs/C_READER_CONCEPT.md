# Konzept: C Reader fuer S202

## Ziel

Der C Reader soll C-Quellcode in dasselbe Rohmodell ueberfuehren, das der
Java-Bytecode-Reader und der Python-Reader liefern:

```text
C source tree
  -> DependencyModel
      -> ClassInfo(fullName, simpleName, packageName)
      -> ClassInfo.dependencies + dependencyKinds
      -> MethodInfo.methodCalls + methodCallDescriptors
      -> PackageInfo hierarchy
  -> LevelCalculator
  -> DomainModel / Architecture views
```

Der Reader soll keine eigene Architekturansicht berechnen. Er liefert nur
stabile Elemente und Abhaengigkeiten. Levelberechnung, Package-Aggregation,
Tangle-Erkennung, What-if-Views und UI sollen danach unveraendert weiterlaufen.

Das erste PoC-Ziel ist:

```text
/home/johannes/Programieren/Rosalinde2026
```

Rosalinde ist fuer den C Reader ein guter Testfall, weil es kein modernes
Build-System und keine Namespaces hat, aber trotzdem klare architektonische
Strukturen besitzt: Ordner, Header, Funktionspraefixe, RPC-Generatorartefakte
und Makefile-Libraries.

## Wichtigste Modellentscheidung

S202 arbeitet aktuell auf "Klassen" als kleinster sichtbarer Architektur-
Einheit. C hat keine Klassen. Fuer den ersten C Reader sollte deshalb gelten:

**Default Class = C-Translation-Unit, also eine `.c`-Datei.**

Eine C-Datei wird als `DependencyModel.ClassInfo` modelliert. Funktionen, die
in dieser Datei definiert sind, werden als `MethodInfo` dieser ClassInfo
modelliert.

Begruendung:

- Die `.c`-Datei ist in C die natuerliche Compile- und Link-Einheit.
- Header deklarieren meist APIs, enthalten aber oft nicht die Implementierung.
- Die vorhandene S202-Logik kann sofort weiterverwendet werden.
- Architekturfragen in C-Projekten sind meistens modul-, ordner- und
  library-orientiert, nicht typorientiert.
- Rosalinde passt sehr gut dazu: `knde.c`, `knto.c`, `btab.c`, `brok.c`,
  `list.c`, `strg.c` usw. sind fachlich erkennbare Module.

Header-Dateien werden im MVP nicht als normale Klassen modelliert, ausser sie
enthalten selbst implementierte Funktionen, z.B. `static inline` oder
header-only Code. Header dienen zuerst als API- und Dependency-Aufloesung.

## FQN- und Package-Abbildung

Source roots fuer Rosalinde:

```text
Rosalinde2026/
  src/
    adt/knde/knde.c
    adt/knto/knto.c
    btab/btab.c
    brok/brok.c
    rosa/rosa.c
    util/list/list.c
  include/
    knde.h
    btab.h
    brok.h
    list.h
```

Mapping:

| C-Datei | S202 ClassInfo.fullName | ClassInfo.packageName | simpleName |
|---|---|---|---|
| `src/adt/knde/knde.c` | `adt.knde.knde` | `adt.knde` | `knde` |
| `src/adt/knde/knde_clnt.c` | `adt.knde.knde_clnt` | `adt.knde` | `knde_clnt` |
| `src/btab/btab.c` | `btab.btab` | `btab` | `btab` |
| `src/brok/r_brok.c` | `brok.r_brok` | `brok` | `r_brok` |
| `src/rosa/dlg_pfku.c` | `rosa.dlg_pfku` | `rosa` | `dlg_pfku` |
| `src/util/list/list.c` | `util.list.list` | `util.list` | `list` |
| `src/template.c` | `<root>.template` | `<root>` | `template` |

Regeln:

- `src/` ist der primaere Source root.
- `include/` ist ein Include root, aber nicht automatisch ein Class root.
- Ordner unterhalb von `src/` werden Packages, getrennt mit `.`.
- Der Dateiname ohne `.c` wird der Class-simpleName.
- Top-Level-C-Dateien direkt unter `src/` bekommen ein synthetisches
  Root-Package, z.B. `<root>` oder den Projektkurznamen.
- Build-Artefakte werden ausgeschlossen: `.o`, `.a`, Executables, `exe/`,
  `lib/`, `bin/`.
- Optional ausgeschlossen oder markiert werden koennen generierte Dateien:
  `r_*_clnt.c`, `r_*_svc.c`, `r_*_xdr.c`, `*_svc_main.c`.

PackageInfo wird wie bei Java und Python aus allen ClassInfo-Eintraegen
gebaut. Der vorhandene `PackageHierarchyBuilder` kann dafuer wiederverwendet
werden.

## Method-Abbildung

Globale und dateilokale C-Funktionen werden als `MethodInfo` modelliert.

| C-Konstrukt | MethodInfo.name | descriptor |
|---|---|---|
| `knde_new()` | `knde_new` | `()` |
| `knde_init(knde * k)` | `knde_init` | `(knde*)` oder `(knde * k)` |
| `static void helper(int x)` | `helper` | `(int)` |
| `brok_register_1_svc(...)` | `brok_register_1_svc` | `(...)` |
| Makro `Assert(x)` | nicht als MethodInfo | - |
| Funktionspointer-Aufruf `f(x)` | Call wenn Ziel bekannt, sonst unresolved | - |

Fuer den MVP reicht ein stabiler, lesbarer Descriptor. Er muss kein
compilerexakter ABI-Typ sein. Wichtig ist, dass Methoden im Drilldown
wiedererkennbar sind.

Beispiel:

```text
sourceClass = adt.knde.knde
sourceMethod = knde_get_konten(knde * k)
targetClass = adt.knto.knto
targetMethod = knto_find_by_kunde

sourceClass.addDependency("adt.knto.knto", EdgeKind.CALLS)
sourceMethod.methodCalls["adt.knto.knto.knto_find_by_kunde"] += 1
```

## Dependency-Abbildung

### Includes

`#include` erzeugt eine Architekturabhaengigkeit, wenn der Include auf ein
Projekt-Header aufgeloest werden kann.

| C-Code | Ziel | EdgeKind |
|---|---|---|
| `#include "knde.h"` | Owner von `knde.h`, z.B. `adt.knde.knde` | `IMPORTS` |
| `#include "btab.h"` | `btab.btab` | `IMPORTS` |
| `#include "r_brok.h"` | `brok.r_brok` oder `brok`-RPC-Gruppe | `IMPORTS` |
| `#include <stdio.h>` | extern, ignorieren | - |
| `#include <rpc/rpc.h>` | extern, ignorieren | - |

Intern kann fuer den MVP `EdgeKind.IMPORTS` wiederverwendet werden, weil diese
Kante bereits level-relevant ist. Wenn die UI spaeter sprachspezifische Labels
bekommen soll, kann `IMPORTS` als "includes" angezeigt oder ein eigener
`EdgeKind.INCLUDES` eingefuehrt werden.

### Calls

Direkte Funktionsaufrufe erzeugen `CALLS`-Kanten, wenn die Ziel-Funktion im
Projektindex eindeutig ist.

| C-Code in `adt.knde.knde` | Ziel | EdgeKind |
|---|---|---|
| `pkrn_init(...)` | `pkrn.pkrn` | `CALLS` |
| `strg_init(...)` | `util.strg.strg` | `CALLS` |
| `adrs_init(...)` | `adt.adrs.adrs` | `CALLS` |
| `list_append(...)` | `util.list.list` | `CALLS` |
| `btab_append(...)` | `btab.btab` | `CALLS` |
| `knto_find_by_kunde(...)` | `adt.knto.knto` oder `adt.knto.knto_clnt` je nach Variante | `CALLS` |

Aufloesungsregeln:

1. Alle Funktionsdefinitionen aus `.c`-Dateien indexieren.
2. Alle Funktionsprototypen aus Projekt-Headern indexieren.
3. Header einem wahrscheinlichen Owner zuordnen.
4. Pro `.c`-Datei aus Includes eine lokale Sicht auf sichtbare Funktionen
   bauen.
5. Direkte Calls gegen diese Sicht aufloesen.
6. Falls ein Name mehrfach definiert ist, erst lokale `static`-Definitionen
   bevorzugen, dann Include-Sicht, dann eindeutige globale Definition.
7. Ambigue oder externe Calls nicht als harte Architekturkante aufnehmen,
   aber in Diagnosezahlen ausweisen.

### Typen und Structs

Typreferenzen koennen optional `USES`-Kanten erzeugen:

| C-Konstrukt | EdgeKind |
|---|---|
| Parameter `knde * k` in fremdem Modul | `USES` auf Owner von `knde` |
| Struct-Feld `list konten` | `USES` auf Owner von `list` |
| `typedef struct _der_Kunde ... knde` | Typdefinition im Owner-Modul |
| Makro-Konstante aus Header | keine Kante oder `IMPORTS` ueber Header |

Fuer die Levelberechnung sollte der MVP Calls und Includes priorisieren.
`USES` ist nuetzlich fuer Kontext, aber in S202 aktuell nicht level-relevant.

### Erzeugung und Lebenszyklus

`INSTANTIATES` ist bei C nicht direkt natuerlich. Funktionen wie `knde_new`,
`list_new`, `*_init`, `*_delete` sind Namenskonventionen, keine Sprachfeatures.

Empfehlung fuer den MVP:

- `malloc`, `free`, `*_new`, `*_init`, `*_delete` werden als normale `CALLS`
  modelliert.
- Optional kann spaeter eine Anzeige-Heuristik "Lifecycle call" entstehen.
- `INSTANTIATES` sollte erst verwendet werden, wenn die UI daraus einen echten
  Mehrwert gewinnt.

## Header-Owner-Aufloesung

Die wichtigste C-spezifische Heuristik ist die Zuordnung von Headern zu
Implementierungsmodulen.

Rosalinde hat ein flaches `include/`-Verzeichnis. `#include "knde.h"` sagt
daher sprachlich nicht, dass der Header zu `src/adt/knde/knde.c` gehoert.
Diese Bruecke muss der Reader herstellen.

Owner-Strategien in Reihenfolge:

1. **Basename-Match:** `include/knde.h` -> `src/**/knde.c`.
2. **Funktionsprefix:** Header deklariert viele `knde_*`-Funktionen ->
   Implementation mit denselben Definitionen.
3. **Typname:** Header definiert `typedef ... knde` -> Owner mit
   `knde_*`-Definitionen.
4. **RPC-Namensschema:** `r_knde.h` -> `src/adt/knde/r_knde.c` plus
   `r_knde_clnt.c`, `r_knde_svc.c`, `r_knde_xdr.c`.
5. **Makefile-Library:** Objekt `src/adt/knde/knde.o` ist in
   `KNDE_LOCAL_LIB` -> Header `knde.h` gehoert zur `knde`-Komponente.
6. **Fallback:** Header als Pseudo-Class `include.knde` modellieren.

Beispiele:

| Header | Wahrscheinlicher Owner | Bemerkung |
|---|---|---|
| `include/knde.h` | `adt.knde.knde` | Basename und Prefix `knde_` passen |
| `include/knto.h` | `adt.knto.knto` | Basename und Prefix `knto_` passen |
| `include/btab.h` | `btab.btab` | Persistenzmodul |
| `include/list.h` | `util.list.list` | Utility-Modul |
| `include/r_brok.h` | `brok.r_brok` | RPC-Interface |
| `include/rosa.h` | `rosa.rosa` oder Header-Pseudo-Class | eher globaler GUI-/Basisheader |

Die Diagnose sollte Header ohne eindeutigen Owner separat ausweisen. Gerade
diese Liste ist fuer die Verbesserung des Readers wertvoll.

## Namenskonventionen als Zusatzsignal

C-Projekte bilden Module oft ueber Funktionspraefixe. Rosalinde macht das sehr
sauber:

| Prefix | Modul/Package |
|---|---|
| `knde_` | `adt.knde.knde` |
| `knto_` | `adt.knto.knto` |
| `btab_` | `btab.btab` |
| `brok_` | `brok.brok` |
| `bent_` | `brok.bent` |
| `sadm_` | `sadm.sadm` |
| `cmgr_` | `cmgr.cmgr` |
| `pkrn_` | `pkrn.pkrn` |
| `list_` | `util.list.list` |
| `strg_` | `util.strg.strg` |
| `hash_` | `util.hash.hash` |

Diese Prefixe sollten nicht die primaere Package-Struktur ersetzen. Sie sind
aber ein starkes Zusatzsignal fuer:

- Header-Owner-Aufloesung.
- Call-Aufloesung bei mehrfach sichtbaren Prototypen.
- Erkennung von API-Grenzen.
- Qualitaetsdiagnosen: Funktionen ohne passenden Modul-Prefix in einem Modul.

## Build-Target-Overlay

Der erste Reader kann ohne Build-Auswertung funktionieren. Fuer Rosalinde ist
das Build-System aber architektonisch sehr aussagekraeftig.

Aus `make/lib.mak` lassen sich Komponenten ableiten:

```text
util
brok_local
brok_rpc_clnt
brok_rpc_server
btab_local
btab_rpc_clnt
btab_rpc_server
knde_local
knde_rpc_clnt
knde_rpc_server
knto_local
knto_rpc_clnt
knto_rpc_server
dbms_local
dbms_rpc_clnt
dbms_rpc_server
sadm_local
sadm_rpc
```

Diese Targets koennen als optionales Overlay genutzt werden:

```text
source file -> object file -> make variable -> library target -> architecture component
```

Beispiele:

| Objekt | Library | Bedeutung |
|---|---|---|
| `src/adt/knde/knde.o` | `knde_local` | lokale Kundenlogik |
| `src/adt/knde/knde_clnt_prx.o` | `knde_rpc_clnt` | RPC-Client-Proxy |
| `src/adt/knde/r_knde.o` | `knde_rpc_server` | RPC-Server-Fassade |
| `src/btab/btab.o` | `btab_local`, `btab_rpc_server` | gleiche Implementation in mehreren Varianten |
| `src/brok/r_brok_svc_main.o` | `brok_rpc_server` | Service-Main |

Wichtig: Das Overlay darf nicht die ClassInfo-FQNs zerstoeren. Es sollte als
Metadatum oder zusaetzliche Gruppierungsansicht verwendet werden. Eine Datei
kann in mehreren Targets vorkommen, weil dieselbe Quelle mit verschiedenen
Defines uebersetzt wird.

## Praeprozessor-Varianten

C-Code existiert oft in mehreren Varianten. Rosalinde nutzt z.B.:

```text
-DLOCAL
-DKNDE_PROXY
-DKNTO_PROXY
-DBTAB_PROXY
-DRPC
```

Das ist architektonisch relevant, weil dieselbe `.c`-Datei je nach Define
andere Dependencies haben kann.

MVP-Regel:

- Den Source ohne echte Praeprozessor-Auswertung analysieren.
- Alle sichtbaren `#include`s und Funktionsdefinitionen sammeln.
- `#ifdef`-Bloecke nicht wegwerfen, sondern konservativ mitlesen.
- Kanten optional mit Variant-Hinweisen markieren, wenn einfach erkennbar.

Spaetere Erweiterung:

- Pro Build-Target eine Analysevariante erzeugen.
- Compile-Flags aus Makefiles uebernehmen.
- Bei vorhandener `compile_commands.json` exakt mit clang/libclang auswerten.

Das ist fuer C wichtig, sollte aber nicht den ersten PoC blockieren.

## Parser-Strategie

C ist ohne Praeprozessor und Include-Pfade schwer exakt zu analysieren. Fuer
S202 brauchen wir aber zuerst Architekturtreffer, keine compilerexakte
Semantik.

Realistische Optionen:

| Option | Vorteil | Nachteil |
|---|---|---|
| Leichter Java-Scanner | keine externe Runtime; schneller MVP | keine vollstaendige C-Syntax |
| Tree-sitter C | robuste Syntaxbaeume ohne komplettes Build | Native/Packaging-Aufwand |
| `clang -Xclang -ast-dump=json` | sehr genaue ASTs | braucht Include-Pfade und Flags |
| libclang | beste Basis fuer spaetere Praezision | zusaetzliche Runtime-/Binding-Fragen |
| ctags/universal-ctags | sehr schnell fuer Definitionen | Calls kaum ausreichend |

Empfehlung:

1. **MVP:** eigener toleranter Scanner fuer Includes, Funktionsdefinitionen,
   Prototypen und direkte Calls.
2. **Parser-Grenze sauber halten:** analog zu Python ein Provider-Interface.
3. **Spaeter:** clang/libclang oder Tree-sitter als austauschbarer Provider.

Vorgeschlagene Struktur:

```text
CSourceAnalyzer
  -> CProjectScanner
  -> CHeaderResolver
  -> CFunctionIndex
  -> CCallResolver
  -> DependencyModel

CAstProvider / CSourceParser
  -> ParsedCFile(path, includes, declarations, definitions, calls, typeRefs)
```

Der MVP-Scanner sollte kein Regex-only-Hack sein, aber er darf bewusst
tolerant sein:

- Kommentare und Strings entfernen oder tokenisieren.
- Preprocessor-Zeilen separat behandeln.
- Funktionsdefinitionen ueber Tokenfolge erkennen, nicht ueber einzelne
  Zeilen.
- Kontrollstrukturen wie `if (...)`, `while (...)`, `switch (...)`, `return`
  nicht als Funktionscalls zaehlen.
- Makros wie `Assert(...)`, `LOG(...)`, `try`, `catch`, `end_try` als Makros
  klassifizieren oder ignorieren.

## Rosalinde-spezifische Startkonfiguration

Default fuer den PoC:

```text
projectRoot = /home/johannes/Programieren/Rosalinde2026
sourceRoots = src
includeRoots = include, src
includeExtensions = .h
sourceExtensions = .c
rpcInterfaceExtensions = .x

excludeDirs = .git, exe, lib, bin, doc
excludeFiles = *.o, *.a, *.so, *.dll, *.exe
generatedPatterns = r_*_clnt.c, r_*_svc.c, r_*_xdr.c, *_svc_main.c
testPatterns = tt_*.c, test_*.c
```

Fuer den ersten Scan wuerde ich generierte und Test-Dateien nicht komplett
ausblenden, sondern markierbar machen:

- "All source" zeigt die reale Build-/RPC-Komplexitaet.
- "Production only" blendet `tt_*.c`, `test_*.c` und optional RPC-Generatorcode
  aus.
- "RPC view" laesst RPC-Dateien drin, damit Broker-/Service-Kanten sichtbar
  werden.

Erwartete Package-Struktur:

```text
adt
  adrs
  knde
  knto
brok
btab
cmgr
data
dbms
except
local
otab
pkrn
rosa
sadm
tk
util
  enum
  hash
  list
  log
  misc
  mmgr
  strg
```

Erwartete grobe Architekturdependencies:

```text
rosa -> tk, sadm, btab, otab, adt.knde, adt.knto
adt.knde -> pkrn, util.strg, adt.adrs, util.list, btab, dbms, adt.knto
adt.knto -> pkrn, util.strg, adt.knde, btab, dbms
btab -> util.misc, dbms, RPC glue
brok -> brok.bent, util.list, sadm/RPC glue
sadm -> brok, cmgr, util.strg
util.* -> moeglichst niedrige Level
```

Diese Erwartungen sind keine Constraints, aber gute Plausibilitaetschecks fuer
den PoC.

## Generated/RPC-Code

Rosalinde nutzt ONC/Sun RPC. Das erzeugt typische Dateien:

```text
r_knde.x
r_knde.h
r_knde_clnt.c
r_knde_svc.c
r_knde_xdr.c
r_knde_svc_main.c
```

Fuer die Architektur sind diese Dateien zweischneidig:

- Sie zeigen reale Prozess- und Netzwerkgrenzen.
- Sie koennen sehr viele technische Kanten erzeugen.
- Sie koennen fachliche Dependencies verdecken, wenn sie zu dominant werden.

Empfehlung:

1. RPC-Dateien als normale Classes aufnehmen, aber `generated=true` markieren.
2. `.x`-Dateien als Interface-Quelle aufnehmen, wenn ein einfacher Parser
   vorhanden ist.
3. Kanten von generierten Dateien separat filterbar machen.
4. Service-Programme aus `.x` als optionale Komponenten ableiten:
   `R_KNDE`, `R_KNTO`, `R_BTAB`, `R_BROK`.

MVP ohne `.x`-Parser:

- `.x` nur als Metadatum/Dateityp erkennen.
- Generierte `.c`-Dateien normal scannen.
- Header `r_*.h` ueber Namensschema auf RPC-Module abbilden.

## Diagnose und Qualitaetsmetriken

Der C Reader sollte nach jedem Scan eine kompakte Analyse-Zusammenfassung
liefern:

```text
C Analysis Summary
  source files: 75
  header files: 40
  functions defined: n
  project includes resolved: n
  project includes unresolved: n
  external includes ignored: n
  direct calls resolved: n
  direct calls unresolved: n
  ambiguous function names: n
  generated files: n
  test files: n
  headers without owner: n
```

Besonders wichtig fuer den PoC:

- Liste der Header ohne Owner.
- Liste der Funktionsnamen mit mehreren moeglichen Zielmodulen.
- Top unresolved calls.
- Top external includes.
- Anzahl Kanten pro EdgeKind.
- Anzahl Klassen ohne Methoden.
- Anzahl Methoden ohne Calls.

Damit sieht man schnell, ob fehlende Architekturdependencies an echter
Entkopplung oder an Parser-Luecken liegen.

## Teststrategie

Fokussierte Unit-/Fixture-Tests reichen fuer den Anfang.

Fixtures:

```text
c-simple/
  src/a/a.c
  src/b/b.c
  include/a.h
  include/b.h

c-flat-include/
  src/adt/knde/knde.c
  src/util/list/list.c
  include/knde.h
  include/list.h

c-ambiguous/
  src/a/util.c
  src/b/util.c
  include/a_util.h
  include/b_util.h

c-rpc/
  src/adt/knde/r_knde.c
  src/adt/knde/r_knde_clnt.c
  src/adt/knde/r_knde_svc.c
  include/r_knde.h
```

Testfaelle:

- `.c`-Datei wird zu ClassInfo.
- Ordnerstruktur wird zu PackageInfo-Hierarchie.
- Funktionen werden zu MethodInfo.
- `#include "x.h"` erzeugt `IMPORTS` auf Header-Owner.
- Direkter Funktionscall erzeugt `CALLS`.
- `static`-Funktion bevorzugt lokale Definition.
- Ambigue globale Funktion erzeugt keine falsche Kante.
- System-Includes werden ignoriert.
- Build-Artefakte werden ausgeschlossen.
- RPC-Dateien werden markiert, aber nicht unsichtbar gemacht.

## Umsetzung in Etappen

### Etappe 1: MVP fuer Rosalinde

- Neuer Menuepunkt: `Open C Source...`.
- `CSourceAnalyzer` mit Source-root-Scan.
- `.c`-Dateien als ClassInfo.
- Funktionen als MethodInfo.
- Ordner als Packages.
- `#include`-Kanten ueber Header-Owner-Heuristik.
- Direkte Funktionscalls ueber globalen Funktionsindex.
- Diagnoseausgabe.

Ziel: Rosalinde liefert eine sinnvolle Package-Level-Verteilung und erkennbare
Back Edges/Tangles.

### Etappe 2: bessere Call-Aufloesung

- Pro Datei sichtbare Symbole aus Includes aufbauen.
- Prototypen und Definitionen zusammenfuehren.
- Funktionsprefixe als Aufloesungssignal verwenden.
- Makros und Funktionspointer sauber als unresolved klassifizieren.

Ziel: weniger zufaellige oder fehlende `CALLS`-Kanten.

### Etappe 3: RPC- und Generated-Code-Sicht

- `generated`-Flag fuer `r_*`-Dateien.
- Optionaler Filter in der UI.
- `.x`-Dateien grob parsen: Program, Version, RPC-Methoden.
- RPC-Service-Komponenten als Overlay.

Ziel: technische RPC-Kanten kontrollierbar machen.

### Etappe 4: Makefile-/Library-Overlay

- `make/lib.mak` lesen.
- Objektlisten auf Source-Dateien mappen.
- Library-Zugehoerigkeit als Metadatum speichern.
- Optional Architekturansicht nach Build-Targets.

Ziel: neben Package-Level auch Deployment-/Library-Level analysieren.

### Etappe 5: Praezisionsmodus

- Optional `compile_commands.json` nutzen.
- clang/libclang oder Tree-sitter Provider einhaengen.
- Varianten pro Define/Target analysieren.

Ziel: groessere und moderne C-Projekte robuster analysieren.

## Offene Designentscheidung

Die einzige Entscheidung, die vor der Implementierung bewusst getroffen werden
sollte:

```text
Sollen Header ohne eindeutigen Owner als eigene Pseudo-Classes sichtbar sein?
```

Empfehlung:

- Default: nein, Header bleiben Aufloesungsartefakte.
- Diagnose: ja, Header ohne Owner ausweisen.
- Option: `showHeaderClasses=true` fuer Spezialfaelle.

Damit bleibt die Architekturansicht sauber und dateiorientiert, ohne wichtige
Header-Probleme zu verstecken.

## Kurzfassung

Fuer C bildet der Reader nicht "C-Klassen" nach, sondern uebersetzt das
tatsaechliche C-Modulmodell in S202:

```text
.c-Datei            -> S202 ClassInfo
C-Funktion          -> S202 MethodInfo
Ordner unter src    -> PackageInfo
#include "x.h"      -> IMPORTS auf Header-Owner
Funktionsaufruf     -> CALLS auf Owner der Zielfunktion
Struct-/Typnutzung  -> optional USES
Makefile-Library    -> optionales Architektur-Overlay
RPC-Generatorcode   -> normale ClassInfo mit generated-Markierung
```

Das ist fuer Rosalinde passend, weil das System bereits ueber Ordner,
Funktionsprefixe, Header und Makefile-Libraries eine klare Architektur
ausdrueckt. Der erste Reader sollte diese Signale nutzen, ohne sofort einen
vollstaendigen C-Compiler nachzubauen.
