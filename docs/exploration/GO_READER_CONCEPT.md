# Konzept: Go Reader fuer S202

## Ziel

Der Go Reader soll Go-Quellcode grosser Cloud-Native-Projekte (etcd, prometheus,
kubernetes, etc.) in dasselbe Rohmodell ueberfuehren, das der Java-, Python- und
C-Reader liefern:

```text
Go source tree
  -> DependencyModel
      -> ClassInfo(fullName, simpleName, packageName)
      -> ClassInfo.dependencies + dependencyKinds
      -> MethodInfo.methodCalls + methodCallDescriptors
      -> PackageInfo hierarchy
  -> LevelCalculator
  -> DomainModel / Architecture views
```

Levelberechnung, Package-Aggregation, Tangle-Erkennung, What-if-Views und
Outline-UI sollen unveraendert weiterarbeiten. Der Reader darf keine eigene
Architektursemantik einfuehren.

## Varianten im Vergleich

### Variante 1: Externer Go-Helper (go/ast + JSON-Ausgabe)

Analoges Vorgehen zum Python-Reader: Ein kleines Go-Programm (`s202_go_ast.go`)
wird als Unterprozess gestartet und gibt die parste AST-Information als JSON aus.
Das Go-Programm nutzt die Standardbibliothek (`go/ast`, `go/parser`,
`go/token`, `go/types`) und damit exakt die Go-Version des analysierten Projekts.

Vorteile:
- Bewaehrtes Muster aus dem Python-Reader, Architektur ist bekannt
- `go/ast` ist Teil der Standardbibliothek, keine externen Dependencies
- Kennt Go-Semantik exakt: named imports, blank imports, dot imports
- Erweiterbar auf `go/types` fuer Typ-Informationen ohne Architekturanderung
- `go.mod` und `go.sum` werden vom Helper korrekt ausgewertet

Nachteile:
- Benoetigt Go-Installation (>= 1.21 empfohlen)
- IPC-Overhead pro Analyseschritt (milderbar durch Batch-Modus)
- Kein Interface-Matching ohne Typ-Checker (separater Schritt)

### Variante 2: go list -json als Metadatenquelle

`go list -json ./...` liefert fuer jedes Paket: Import-Pfad, Dateiliste,
Imports, Transitive Imports, Build-Tag-Informationen und Test-Dateien.
Kein eigenes Parsing noetig fuer den Import-Graph.

Vorteile:
- Sehr robuste Import-Aufloesung (Go-Toolchain rechnet selbst)
- Vendor-Verzeichnisse, replace-Direktiven und Workspaces werden korrekt
  beruecksichtigt
- Sehr schnell fuer reine Import-Kanten

Nachteile:
- Keine Call-Information (Funktionsaufrufe, Methodenaufrufe)
- Keine Typ-/Struct-Information fuer spaetere EXTENDS/USES-Kanten
- Funktioniert nicht ohne go.mod im analysierten Projekt

### Variante 3: golang.org/x/tools/go/packages (vollstaendiger Typ-Check)

Nutzt das offizielle Go-Tools-Paket fuer vollstaendige Typanalyse inklusive
Interface-Matching und Call-Graph (uber `golang.org/x/tools/go/callgraph`).

Vorteile:
- Praziseste moegliche Analyse (Typ-Level)
- Interface-Implementierungen erkennbar
- Vollstaendiger Call-Graph via pointer analysis

Nachteile:
- Benoetigt vollstaendiges Build (alle Dependencies muessen kompilierbar sein)
- Fuer grosse Projekte wie Kubernetes sehr langsam (Minuten bis Stunden)
- Externe Abhaengigkeit ausserhalb der Standardbibliothek
- Pointer-Analyse skaliert quadratisch, nicht fuer MVP geeignet

### Variante 4: Java-seitiger Regex/Token-Scanner

Analog zum C-Reader-MVP: Java parst Go-Quelldateien mit Regex und einfachen
Token-Regeln.

Vorteile:
- Keine externe Runtime

Nachteile:
- Go-Pakete, Imports und Typen haben Besonderheiten (qualified names, type
  aliases, generics seit 1.18), die Regex-Ansatz schnell inkorrekt machen
- Kein Go-Modul-Bewusstsein, Vendor-Verzeichnisse werden nicht korrekt gehandhabt
- Fuer Cloud-Native-Projekte mit Tausenden von Dateien nicht ausreichend praezise

## Entscheidung: Variante 1 (Externer Go-Helper)

**Begruendung:** Das Python-Reader-Muster ist in Structure202 erprobt und hat
eine saubere Schnittstelle (`ExternalPythonAstProvider` -> `ParsedPythonModule`).
Die Uebertragung auf Go ist direkt. `go/ast` ist stabil, wartungsarm und
sprachpraezise. `go list -json` kann als schnelle Vorstufe fuer Package-
Discovery eingesetzt werden, bevor der AST-Parser fuer Call-Details einsetzt.

Fuer grosse Cloud-Native-Codebases wie etcd (~300 Pakete) oder prometheus
(~150 Pakete) ist diese Variante hinreichend schnell und liefert alle
architektonisch relevanten Informationen.

## Wichtigste Modellentscheidung: Was ist eine ClassInfo?

S202 arbeitet auf "Klassen" als kleinster sichtbarer Architektureinheit. Go hat
Dateien, Pakete (= Verzeichnisse), Types (structs, interfaces) und Funktionen.

**Default Class = exportierter Go-Typ (struct oder interface).**

Jeder exportierte Go-Typ wird als eigene `DependencyModel.ClassInfo` modelliert.
Das Go-Paket (Verzeichnis) wird `PackageInfo`. Die `.go`-Datei ist nur Container
— sie kann eine oder mehrere ClassInfos enthalten.

Begruendung:

- Eine `.go`-Datei kann beliebig viele Typen definieren: `Client`, `Config`,
  `AuthConfig` koennen alle in `client.go` stehen. Die architektonisch
  interessante Frage ist "wer haengt von `Config` ab, wer von `Client`?" —
  nicht von welcher Datei.
- Der Go-Typ entspricht der Java-Klasse: er hat Methoden, Felder, kann
  Interfaces implementieren und von anderen Paketen importiert und genutzt werden.
- Go-Pakete sind Verzeichnisse — analog zu Java-Packages. PackageInfo.
- Das bestehende S202-Modell bleibt unveraendert nutzbar, da ClassInfo
  und PackageInfo unabhaengig vom Reader-Konzept sind.

**Zuordnung freier Funktionen zu Typen:**

Freie Funktionen (kein Receiver) werden dem Typ zugeordnet, dessen Namensraum
sie bearbeiten. Der Namensraum einer Datei sind alle Typen, die in derselben
Datei definiert sind.

Die Zuordnung erfordert **zwei Passes pro Datei**, weil eine Funktion vor ihrer
zugehoerigen Typ-Definition stehen kann:

```
Pass 1 — Typ-Index aufbauen:
  Alle exportierten (und unexportierten) Typen der Datei sammeln.
  Ergebnis: Set<String> lokalerTypIndex  (nur unqualifizierte Namen, z.B. "Client")

Pass 2 — Funktionen zuordnen:
  Fuer jede freie Funktion:
    1. Kandidaten aus Return-Typen und erstem Parameter ermitteln:
       - Typ-Ausdruck unwrappen: *, [], map[K]V, Generics[T] → Basisname
       - Enthaelt Basisname einen Punkt ("pkg.Type")? → externer Typ, ignorieren
       - error, bool, string, int, ... (bekannte stdlib-Primitives)? → ignorieren
       - Verbleibender unqualifizierter Name → Kandidat
    2. dominanterTyp = erster Kandidat aus Return-Typen wenn in lokalerTypIndex
                     sonst erster Kandidat aus Parametern wenn in lokalerTypIndex
                     sonst null
    3. dominanterTyp != null → MethodInfo der Typ-ClassInfo
       dominanterTyp == null → MethodInfo der DefaultClass
```

Zuordnungsregeln im Detail:

**Schritt 1 — Basistypen aus der Signatur extrahieren:**

Go-Typen kommen in Huellen vor, die erst abgezogen werden muessen:
- Pointer: `*Client` → `Client`
- Slice: `[]Client`, `[]*Client` → `Client`
- Map-Value: `map[string]*Client` → `Client` (Key-Typ wird ignoriert)
- Generics: `Set[Client]` → `Set` (Typparameter ignorieren fuer die Zuordnung)
- Tuple-Return mit `error`: `(*Client, error)` → `Client` (`error` und alle
  bekannten stdlib-Typen werden herausgefiltert; erster verbleibender Typ gewinnt)

**Schritt 2 — dominanten Typ bestimmen:**

- Return-Typ (nach Unwrapping und error-Filter) hat Vorrang.
- Erster Parameter (nach Unwrapping) als Fallback, wenn kein lokaler Return-Typ.
- Sind nach dem Unwrapping mehrere lokale Typen im Return-Tuple:
  erster nicht-error Typ gewinnt.

**Schritt 3 — Lokalpruefung:**

Ist der dominante Basistyp im Pass-1-Index der Datei?
- Ja → MethodInfo der Typ-ClassInfo.
- Nein → DefaultClass der Datei.

**Bekannte Ambiguitaet:** `func Convert(a *Config) *Client` — beide Typen
sind lokal. Return-Typ gewinnt → `Client`. Das ist eine Konvention, nicht
semantisch eindeutig. Diese Faelle werden als-is modelliert ohne Warnung;
fuer Architektur-Uebersichten ist die Zuordnung nahe genug.

Beispiele:

| Funktion in `client.go` (Typen: `Client`, `Config`, `AuthConfig`) | Unwrapping / Filter | dominanter Typ | Zuordnung |
|---|---|---|---|
| `func New(cfg *Config) *Client` | `*Client` → `Client` | `Client` (Return) | MethodInfo von `Client` |
| `func New(cfg *Config) (*Client, error)` | `(*Client, error)` → `Client` (error herausgefiltert) | `Client` | MethodInfo von `Client` |
| `func NewList() []*Client` | `[]*Client` → `Client` | `Client` | MethodInfo von `Client` |
| `func processConfig(cfg *Config) error` | Return `error` → kein lokaler Typ; Param `*Config` → `Config` | `Config` (Param) | MethodInfo von `Config` |
| `func Convert(a *Config) *Client` | Return `*Client` → `Client` | `Client` (Return gewinnt) | MethodInfo von `Client` |
| `func doXY(s *Server)` (`Server` nicht lokal) | kein lokaler Typ nach Unwrap | - | DefaultClass `client` |
| `func init()` | kein Typ | - | DefaultClass `client` |

Die DefaultClass einer Datei wird nur angelegt, wenn mindestens eine Funktion
keinem lokalen Typ zugeordnet werden kann. Enthaelt eine Datei ausschliesslich
Funktionen (keine exportierten Typen), ist die DefaultClass die einzige ClassInfo
der Datei.

Unexportierte Typen (`type internalState struct`) werden nicht als eigene
ClassInfo aufgenommen. Methoden auf unexportierten Typen sind MethodInfos der
DefaultClass der Datei.

Sonderfall: `_test.go`-Dateien erzeugen ClassInfos mit `-test`-Suffix,
optional filterbar.

## FQN- und Package-Abbildung

Beispielstruktur etcd:

```text
etcd/
  go.mod     (module go.etcd.io/etcd/v3)
  client/v3/
    client.go
    op.go
    watch.go
  server/
    embed/
      etcd.go
    etcdserver/
      server.go
      api/
        v3rpc/
          grpc.go
```

Mapping:

| Go-Datei | Inhalt | erzeugte ClassInfos |
|---|---|---|
| `client/v3/client.go` | Typen: `Client`, `Config`, `AuthConfig`; `func New(...) *Client` | `client.v3.Client` (inkl. `New`), `client.v3.Config`, `client.v3.AuthConfig` |
| `client/v3/watch.go` | Typen: `Watcher`, `WatchResponse` | `client.v3.Watcher`, `client.v3.WatchResponse` |
| `server/embed/etcd.go` | Typ: `Etcd`; `func StartEtcd(...) *Etcd` | `server.embed.Etcd` (inkl. `StartEtcd`) |
| `server/embed/config.go` | Typ: `Config` | `server.embed.Config` |
| `server/etcdserver/server.go` | Typen: `EtcdServer`, `ServerConfig`, ...; `func NewServer(...) *EtcdServer` | `server.etcdserver.EtcdServer` (inkl. `NewServer`), `server.etcdserver.ServerConfig`, ... |
| `server/etcdserver/api/v3rpc/grpc.go` | nur Funktionen, kein Typ | DefaultClass `server.etcdserver.api.v3rpc.grpc` |

Regeln:

- Der Modulname aus `go.mod` wird als Praefixfilter genutzt: Alles unter
  `module` ist projektinternes Paket, alles andere ist extern.
- Exportierter Typ: `fullName` = `packagePath.TypeName`, `simpleName` = Typname.
- DefaultClass (nur Funktionen): `fullName` = `packagePath.filename`,
  `simpleName` = Dateiname ohne `.go`.
- `packageName` = Verzeichnispfad als dotted FQN (= Go-Package) — fuer beide.
- Dateien direkt im Modulverzeichnis: synthetisches Root-Package.
- Projekte mit mehreren Modulen (Go workspace, `go.work`): Im MVP wird
  das primaere `go.mod` bevorzugt.

Wichtig fuer grosse Projekte:
- `internal/`-Verzeichnisse werden normal aufgenommen.
- `vendor/`-Verzeichnisse werden standardmaessig ausgeschlossen.
- `_test.go`-Dateien erzeugen ClassInfos mit optionalem Test-Flag.
- Generierte Dateien (`*.pb.go`, `*.gen.go`, `zz_generated_*.go`) werden
  optional als generiert markiert; im MVP eingelesen.

PackageInfo entspricht dem Go-Package (Verzeichnis). `PackageHierarchyBuilder`
kann unveraendert verwendet werden.

## Method-Abbildung

Go-Symbole werden als `MethodInfo` ihrer zugehoerigen Typ-ClassInfo modelliert:

| Go-Konstrukt | zugehoerige ClassInfo | MethodInfo.name | descriptor |
|---|---|---|---|
| `func (c *Client) Get(ctx, key) (...)` | `client.v3.Client` | `Get` | `(Context, string)` |
| `func New(cfg *Config) *Client` | `client.v3.Client` (Return-Typ lokal) | `New` | `(*Config)` |
| `func processConfig(cfg *Config) error` | `client.v3.Config` (Param-Typ lokal) | `processConfig` | `(*Config)` |
| `func doXY(s *Server)` (Server nicht lokal) | DefaultClass `client.v3.client` | `doXY` | `(*Server)` |
| `func init()` | DefaultClass der Datei | `init` | `()` |
| Interface-Methode in `Watcher` | `client.v3.Watcher` | `Watch` | `(Context, ...)` |

Fuer Call-Details:

```text
sourceClass = server.etcdserver.EtcdServer
sourceMethod = processInternalRaftRequestOnce
targetClass = server.etcdserver.api.v3rpc.v3rpc  (Synthetic, package-level Fkt.)
targetMethod = responseHeader

MethodInfo.methodCalls["server.etcdserver.api.v3rpc.v3rpc.responseHeader"] += 1
sourceClass.addDependency("server.etcdserver.api.v3rpc.v3rpc", EdgeKind.CALLS)
```

## Dependency-Abbildung

### Imports

Go-Imports sind explizit und exakt aufloesbar. Das ist einer der groessten
Vorteile gegenueber Python oder C.

Ein Go-Import-Pfad benennt ein Paket (= Verzeichnis). Welche Typ-ClassInfo
konkret gemeint ist, ergibt sich aus dem verwendeten Symbol:

| Go-Code | Ziel (Typ-ClassInfo) | EdgeKind |
|---|---|---|
| `import clientv3 "...client/v3"` + `clientv3.New(...)` | `client.v3.client` (Synthetic, package-level) | `IMPORTS` + `CALLS` |
| `import clientv3 "...client/v3"` + `var c *clientv3.Client` | `client.v3.Client` | `IMPORTS` + `USES` |
| `import _ "go.etcd.io/etcd/v3/server/embed"` | Synthetic `server.embed.embed` | `IMPORTS` |
| `import "fmt"` | stdlib, extern -> ignorieren | - |

Im MVP: der Import-Pfad erzeugt mindestens eine IMPORTS-Kante auf die
Synthetic-ClassInfo des Pakets. Zusaetzliche USES/CALLS-Kanten auf konkrete
Typen entstehen bei der Symbol-Aufloesung.

Blank-Imports (`import _`) → IMPORTS auf Synthetic (init-Seiteneffekt).

### Calls

Go-Funktionsaufrufe werden ueber den qualifizierten Bezeichner aufgeloest.
Go erlaubt kein Overloading, was die Aufloesung vereinfacht.

| Go-Code in `EtcdServer.processInternalRaftRequest` | Ziel | EdgeKind |
|---|---|---|
| `clientv3.New(cfg)` | `client.v3.client` (Synthetic, hat `NewClient`) | `CALLS` |
| `embed.StartEtcd(cfg)` | `server.embed.embed` (Synthetic, hat `StartEtcd`) | `CALLS` |
| `cfg := clientv3.Config{...}` | `client.v3.Config` | `INSTANTIATES` |
| `s.cluster.Version()` | `server.etcdserver.Cluster` (wenn Feldtyp bekannt) | `CALLS` |
| `ctx.Err()` (stdlib) | extern -> kein Edge | - |

Aufloesungsregeln:

1. Projektweiten Symbol-Index aufbauen: welche Typ-ClassInfo oder Synthetic
   definiert welche exportierten Symbole (Typen, Methoden, Funktionen)?
2. Qualifizierter Aufruf `pkg.Func(...)`:
   - Ist `Func` ein Typ im Paket? -> INSTANTIATES auf Typ-ClassInfo.
   - Ist `Func` eine package-level Funktion? -> CALLS auf Synthetic.
3. Methoden-Aufruf `recv.Method(...)`:
   - Typ von `recv` aus Deklaration oder Typannotation ermitteln.
   - Ziel: Typ-ClassInfo, auf der `Method` definiert ist.
   - Wenn Typ nicht aufloesbar: kein harter Edge.
4. Unqualifizierter Aufruf `Func()` im selben Paket: Suche im Symbol-Index
   aller ClassInfos und Synthetics des Pakets.

### Struct-Embedding und Vererbung

| Go-Konstrukt | EdgeKind |
|---|---|
| `type Server struct { *etcdserver.EtcdServer }` | `EXTENDS` auf `server.etcdserver.EtcdServer` |
| `type Server struct { EtcdServer }` (value embed) | `EXTENDS` auf `server.etcdserver.EtcdServer` |
| `type Server struct { node *Node }` (named field) | `USES` auf Typ-ClassInfo von `Node` |
| `type Watcher interface { io.Reader }` (interface embed, extern) | ignorieren |
| `type Watcher interface { storage.Reader }` (intern) | `EXTENDS` auf `storage.Reader` |

### Interface-Implementierung

Go hat strukturelles Typing: Eine Implementierung wird nicht deklariert.
Im MVP wird `IMPLEMENTS` nicht automatisch erzeugt.

Optionale Erweiterung via `go/types`:
- Alle exportierten Typen gegen alle projektinternen Interfaces pruefen.
- Paketgrenzen-uebergreifende Matches als `IMPLEMENTS` aufnehmen.

### Typreferenzen

| Go-Konstrukt | EdgeKind |
|---|---|
| Feldtyp `clients []*clientv3.Client` | `USES` auf `client.v3.Client` |
| Parameter `cfg clientv3.Config` | `USES` auf `client.v3.Config` |
| Return-Typ `func NewServer() *EtcdServer` (gleiches Paket) | keine Kante |
| Return-Typ `func New() *embed.Etcd` | `USES` auf `server.embed.Etcd` |
| Typ-Assertion `x.(clientv3.Client)` | `USES` auf `client.v3.Client` |
| Typ-Alias `type MyClient = clientv3.Client` | `USES` auf `client.v3.Client` |
| Generischer Typ-Parameter `func F[T clientv3.Constraint]()` | `USES` auf `client.v3.Constraint` |
| Generischer Typ `type Set[T comparable] struct{}` | ClassInfo `pkg.Set` (Parameter ignoriert) |

### Package-level Variablen und DefaultPackageClass

Package-level Variablen sind Package-scoped, nicht datei-scoped. Sie gehoeren
deshalb in eine **DefaultPackageClass** — eine einzige synthetische ClassInfo
pro Go-Package, die alle Variablendeklarationen des Pakets traegt.

```
DefaultPackageClass
  fullName   = packagePath.packageSimpleName  (z.B. client.v3.v3)
  simpleName = packageSimpleName              (z.B. v3)
  packageName = packagePath                   (z.B. client.v3)
```

Die DefaultPackageClass wird nur angelegt wenn das Paket mindestens eine
package-level Variable oder eine `init()`-Funktion enthaelt.

`init()`-Funktionen gehoeren ebenfalls zur DefaultPackageClass, da sie
Package-Initialisierung ausdruecken, nicht Datei-Logik.

Beispiele:

```go
var defaultLogger = logging.NewLogger()     // USES auf logging.Logger
var defaultTimeout = 30 * time.Second       // stdlib, ignorieren
var mu sync.Mutex                           // stdlib, ignorieren
var registry = prometheus.NewRegistry()     // USES auf prometheus.Registry

func init() { registry.MustRegister(...) }  // MethodInfo der DefaultPackageClass
```

Regeln:

- Typannotation (`var x pkg.Type`): USES-Kante der DefaultPackageClass auf `pkg.Type`.
- Zuweisung mit qualifiziertem Call (`var x = pkg.NewFoo()`): USES-Kante auf
  den aufgeloesten Rueckgabetyp.
- Stdlib-Typen und unaufloesbare externe Typen: ignorieren.
- Eine Datei kann sowohl Typ-ClassInfos, eine Datei-DefaultClass als auch
  Beitraege zur DefaultPackageClass enthalten.

Dreistufiges ClassInfo-Modell pro Package:

```
Typ-ClassInfo          (pro exportiertem Typ)          fullName = pkg.TypeName
DefaultClass           (pro Datei, bei Bedarf)          fullName = pkg.filename
DefaultPackageClass    (pro Package, bei Bedarf)         fullName = pkg.pkgSimpleName
```

**FQN-Kollision DefaultClass vs. DefaultPackageClass:**

In Go ist `package foo` in `foo.go` die haeufigste Konvention. Das fuehrt zur
Kollision: `server/embed/embed.go` → DefaultClass `server.embed.embed` und
DefaultPackageClass des Package `embed` → ebenfalls `server.embed.embed`.

Loesung: **Merge-Regel**. Wenn der Dateiname (ohne `.go`) identisch mit dem
Package-Kurznamen ist, werden DefaultClass und DefaultPackageClass zum selben
Knoten zusammengefuehrt. Der Knoten traegt alle Funktionen ohne lokalen Typ
UND alle package-level Variablen und `init()`-Funktionen der Datei.

```
embed.go  in package embed  →  ein Knoten server.embed.embed  (Merge)
client.go in package v3     →  DefaultClass server.embed.client  (kein Merge, Name verschieden)
```

Wenn mehrere Dateien eines Pakets package-level Variablen haben, tragen alle
zur selben DefaultPackageClass bei — auch wenn Merge nur fuer eine Datei greift.

## Installations-Check analog Python-Reader

Der Go-Helper kann eine Go-Installation voraussetzen, muss dies aber pruefen.

Lookup-Reihenfolge in `GoExecutableFinder` (analog `ExternalPythonAstProvider`):

1. System-Property `s202.go.executable`
2. Umgebungsvariable `GO`
3. Fallback: `go`

Pruefung beim Start:

```java
ProcessBuilder pb = new ProcessBuilder(goExec, "version");
Process p = pb.start();
int exit = p.waitFor();
if (exit != 0) {
    throw new IllegalStateException(
        "Go not found at '" + goExec + "'. Set s202.go.executable or GO env var.");
}
String output = readStdout(p); // "go version go1.22.0 linux/amd64"
parseAndLogVersion(output);    // mind. Go 1.18 fuer Generics-Unterstuetzung
```

Mindestversion-Empfehlung: Go 1.21 (aktuelle stabile LTS-nahe Version).
Warnung bei Go < 1.18 (fehlende Generics-Unterstuetzung im AST).

## Go-Helper: s202_go_ast.go

Das externe Hilfsprogramm wird als Go-Quelldatei in den Resources abgelegt
(`src/main/resources/go/s202_go_ast.go`) und beim ersten Aufruf oder bei
Version-Mismatch in ein temporaeres Verzeichnis compiliert und gecacht.

Vorzugsweise als Single-File-Tool ohne externe Go-Dependencies:
nur `go/ast`, `go/parser`, `go/token`, `encoding/json`.

Aufrufmuster (analog Python-Helper):

```bash
# Einzel-Paket (für Tests und PoC)
go run s202_go_ast.go --module go.etcd.io/etcd/v3 --pkg ./server/embed

# Batch-Modus fuer grosse Projekte (ein JSON-Array pro Aufruf)
go run s202_go_ast.go --module go.etcd.io/etcd/v3 --all --root /path/to/etcd
```

Ausgabe-Format (JSON-Array von Paketen):

```json
[
  {
    "importPath": "go.etcd.io/etcd/v3/server/embed",
    "name": "embed",
    "dir": "/path/to/etcd/server/embed",
    "files": ["etcd.go", "config.go"],
    "imports": [
      "go.etcd.io/etcd/v3/client/v3",
      "go.etcd.io/etcd/v3/server/etcdserver"
    ],
    "types": [
      {
        "name": "Etcd", "kind": "struct", "typeParams": [],
        "embeds": ["*etcdserver.EtcdServer"],
        "fields": [
          {"name": "cfg",     "typeRef": "Config",              "qualified": ""},
          {"name": "Clients", "typeRef": "[]*clientv3.Client",   "qualified": "go.etcd.io/etcd/v3/client/v3"}
        ]
      },
      {"name": "Config", "kind": "struct", "typeParams": [], "embeds": [], "fields": []},
      {"name": "Set",    "kind": "struct", "typeParams": ["T"], "embeds": [], "fields": []}
    ],
    "vars": [
      {"name": "defaultLogger", "typeRef": "logging.Logger", "qualified": "go.etcd.io/etcd/v3/pkg/logging"}
    ],
    "functions": [
      {"name": "StartEtcd", "receiver": "", "typeParams": [], "params": ["cfg *Config"], "results": ["*Etcd", "error"]},
      {"name": "Stop", "receiver": "Etcd", "typeParams": [], "params": [], "results": []}
    ],
    "calls": [
      {"caller": "StartEtcd", "callee": "etcdserver.NewServer", "qualified": "go.etcd.io/etcd/v3/server/etcdserver"},
      {"caller": "Etcd.Close", "callee": "etcdserver.EtcdServer.Close", "qualified": "go.etcd.io/etcd/v3/server/etcdserver"}
    ]
  }
]
```

## Analyse-Pipeline

```text
GoSourceAnalyzer.analyze(projectRoot)
  1. go.mod lesen
     - Modulnamen extrahieren (Praefixfilter)
     - Go-Version pruefen
     - replace-Direktiven fuer modul-lokale Substitutionen merken

  2. Pakete entdecken  [File-Walk ist primaer; go list optional]
     Primaer — File-Walk:
       - Alle Verzeichnisse unter Modulwurzel rekursiv durchlaufen
       - Verzeichnisse mit mindestens einer .go-Datei sind Pakete
       - vendor/, .git/, testdata/ ausschliessen
       - Package-Import-Pfad = Modulname + "/" + relativer Verzeichnispfad
     Optional — go list -json ./... als Ergaenzung:
       - Liefert praezisere Import-Pfade bei replace-Direktiven und Workspaces
       - Schlaegt fehl wenn Modul nicht kompilierbar (fehlende Deps, CGo, kein Netz)
       - Wird versucht; bei Fehler stillschweigend auf File-Walk zurueckgefallen
     Beide Varianten filtern: vendor/, externe Pakete (nicht unter Modulpraefixe)

  3. ASTs parsen (Go-Helper im Batch-Modus)
     - Fehlerhafte Dateien melden, Analyse aber fortsetzen
     - Ergebnis: ParsedGoFile pro Datei (imports, types, functions, calls)

  4. Pass 1 pro Datei — Typ-Index aufbauen
     - Alle exportierten Typen (struct, interface) aus ParsedGoFile sammeln
     - Ergebnis: Map<Datei, Set<TypeName>> lokaler Typ-Index
     - ClassInfos fuer alle exportierten Typen anlegen (fullName = packagePath.TypeName)

  5. Pass 2 pro Datei — Funktionen und Variablen zuordnen
     - Fuer jede freie Funktion: dominanten Typ bestimmen (Return > erster Param,
       nach Unwrapping und error-Filter)
     - Typ im lokalen Typ-Index der Datei? -> MethodInfo der Typ-ClassInfo
     - Kein lokaler Typ -> MethodInfo der DefaultClass der Datei
     - DefaultClass anlegen falls noch nicht vorhanden (fullName = packagePath.filename)
     - Package-level Variablen und init()-Funktionen -> DefaultPackageClass des Pakets
     - DefaultPackageClass anlegen falls noch nicht vorhanden (fullName = packagePath.pkgSimpleName)

  6. Projektweiten Symbol-Index bauen  [erst nach Abschluss von Pass 1+2 aller Dateien]
     - Typen, Methoden und freie Funktionen aller ClassInfos und DefaultClasses indexieren
     - Jeder Eintrag: qualifiedName -> ClassInfo (Typ oder DefaultClass)
     - Import-Alias-Aufloesung: Alias -> Paket-FQN -> ClassInfos des Pakets
     - Generics: Typparameter werden beim Indexieren ignoriert (Set[T] -> Set)
     - Variablendeklarationen mit externem Typ indexieren fuer USES-Aufloesung
     Hinweis: Schritt 6 darf erst beginnen wenn Pass 1 und Pass 2 fuer ALLE
     Dateien abgeschlossen sind, da DefaultClass-Zuordnungen aus Pass 2 im
     Index benoetigt werden.

  7. Abhaengigkeiten extrahieren
     - Imports -> IMPORTS auf Typ-ClassInfo oder DefaultClass des verwendeten Symbols
     - Struct-Embeddings -> EXTENDS auf Typ-ClassInfo
     - Feld-/Parameter-/Return-Typreferenzen -> USES auf Typ-ClassInfo
     - Qualifizierte Calls -> CALLS / INSTANTIATES auf Typ-ClassInfo oder DefaultClass
     - Intra-Package-Calls -> CALLS innerhalb des Pakets via Symbol-Index

  8. PackageInfo-Hierarchie bauen
     - PackageHierarchyBuilder (shared mit anderen Readern)

  9. DependencyModel zurueckgeben
```

## Beispiel: etcd server/embed/etcd.go -> client/v3 und etcdserver

Go:

```go
// server/embed/etcd.go
package embed

import (
    clientv3 "go.etcd.io/etcd/v3/client/v3"
    "go.etcd.io/etcd/v3/server/etcdserver"
)

type Etcd struct {
    *etcdserver.EtcdServer       // EXTENDS auf server.etcdserver.EtcdServer (Typ-ClassInfo)
    cfg Config                   // paket-lokal, kein Cross-Package-Edge
    Clients []*clientv3.Client   // USES auf client.v3.Client (Typ-ClassInfo)
}

func StartEtcd(inCfg *Config) (e *Etcd, err error) {
    srv, err := etcdserver.NewServer(etcdserver.ServerConfig{...})
    // Return-Typ *Etcd ist lokal → StartEtcd gehoert zu Typ-ClassInfo Etcd
    // etcdserver.NewServer → CALLS auf server.etcdserver.EtcdServer (Return-Typ von NewServer)
    e = &Etcd{EtcdServer: srv}
    return
}
```

Zielmodell:

```text
Typ-ClassInfo server.embed.Etcd
  simpleName:  Etcd
  packageName: server.embed
  dependencies:
    client.v3.Client              [USES]      (Feld Clients []*clientv3.Client)
    server.etcdserver.EtcdServer  [EXTENDS]   (Embedding *etcdserver.EtcdServer)
    server.etcdserver.EtcdServer  [CALLS]     (etcdserver.NewServer → Return-Typ EtcdServer)

  MethodInfo StartEtcd(*Config)               (Return *Etcd ist lokal → gehoert zu Etcd)
    methodCalls:
      server.etcdserver.EtcdServer.NewServer -> 1

  MethodInfo Etcd.__typedef__

Hinweis: etcd.go hat Dateiname == Paketname → DefaultClass und DefaultPackageClass
mergen zu server.embed.embed. Hier nicht benoetigt, da alle Funktionen Etcd zugeordnet.
```

Package-Aggregation:

```text
server.embed -> client.v3          weight: 1 (Clients-Feld)
server.embed -> server.etcdserver  weight: 2 (EtcdServer-Embed + NewServer-Call)
```

## Limits des Ansatzes

### Statische Grenzen

**Interface-Dispatch:** Go-Calls via Interfaces sind ohne Typ-Checker nicht
aufloesbar. `handler.Handle(req)` erzeugt einen CALLS-Edge auf die Typ-ClassInfo
des Interface-Typs, nicht auf konkrete Implementierungen. Fuer Architektur-
Level-Berechnungen ist das ausreichend; fuer Call-Graph-Analysen nicht.

**Generics (Go 1.18+):** Typ-Parameter wie `[T constraint.Ordered]` erzeugen
USES-Kanten auf das Constraint-Paket, aber die konkreten Instantiierungstypen
sind ohne Typ-Checker unbekannt. Im MVP werden Constraints als USES modelliert.

**Build-Tags:** Dateien mit `//go:build !linux` o.ae. werden ohne Auswertung
des Build-Environments immer eingelesen. Das kann zu extra Kanten fuehren, die
auf dem Zielplattform nicht existieren. Empfehlung: Dateien mit Build-Tags
markieren; im MVP alle einlesen.

**CGo:** `import "C"` und CGo-Calls sind ohne C-Parser nicht aufloesbar.
Diese Imports werden ignoriert (extern, kein internes Paket). Hinweis in
Diagnose-Output.

**init()-Reihenfolge:** `import _` erzeugt einen IMPORTS-Edge, aber die
genaue init()-Reihenfolge ist Go-compiler-intern. Fuer Architektur-Sicht ist
der IMPORTS-Edge aber das Richtige.

**go:generate:** Generierter Code ist nur sichtbar, wenn er bereits erzeugt
wurde. Fehlende `.pb.go`- oder `mock_*.go`-Dateien fuehren zu unaufloeslichen
References. Diagnose-Output sollte fehlende generierte Dateien ausweisen.

### Modell-Grenzen

**Viele Typen pro Datei:** Grosse Go-Dateien wie `server.go` in kubelet
definieren viele exportierte Typen. Jeder wird zur eigenen Typ-ClassInfo — das
ist gewuenscht und korrekt. Die Anzahl der ClassInfos pro Package kann dadurch
hoch sein; das entspricht aber der tatsaechlichen Komplexitaet.

**Intra-Package-Symbol-Aufloesung:** Wenn Code einen Typ oder eine Funktion aus
demselben Paket (ohne Qualifier) nutzt, muss der Symbol-Index die richtige
Typ-ClassInfo oder DefaultClass finden. Fehlt die Definition (z.B. generierter
Code noch nicht erzeugt), bleibt der Call unaufgeloest — kein falscher Edge.

**Test-Dateien:** `_test.go`-Dateien (`package foo_test`) erzeugen Typ-ClassInfos
und DefaultClasses wie normale Dateien, gehoeren aber zum Test-Paket. Sie
erzeugen IMPORTS-Kanten auf Typ-ClassInfos des Produktiv-Codes. Fuer
Architektur-Sicht sollten Test-Pakete optional ausgeblendet werden.

**Multiple Modules (Go workspace):** Projekte mit `go.work` (z.B. etcd seit v3.6)
haben mehrere `go.mod`. MVP: nur das primaere Modul im gewaehlten Verzeichnis.
Erweiterung: mehrere Modulwurzeln konfigurierbar machen.

**Vendor-Verzeichnisse:** `vendor/` enthaelt Kopien externer Abhaengigkeiten.
Diese werden standardmaessig ausgeschlossen. Option: `includeVendor=true` fuer
Projekte, die Vendor-Code anpassen.

## Typische Cloud-Native-Projekte: Erwartete Ergebnisse

### etcd (~300 Pakete)

```text
Erwartete Top-Level-Packages:
  api        -> Protobuf-generierte API-Typen
  client     -> etcd-Client (v3)
  server     -> Server, embed, etcdserver, raft
  pkg        -> Shared utilities
  tests      -> Integration-Tests

Erwartete Architektur-Richtung (keine Zyklen):
  server.embed -> server.etcdserver -> server.raft
  client.v3    -> api
  server.*     -> api, pkg.*
  pkg.*        -> sehr wenige Abhaengigkeiten (Low-Level)
```

### prometheus (~150 Pakete)

```text
Erwartete Top-Level-Packages:
  cmd           -> Entrypoints (prometheus, promtool)
  discovery     -> Service-Discovery-Plugins
  model         -> Prometheus-Datenmodell (labels, metrics)
  rules         -> Rule-Evaluation
  scrape        -> Target-Scraping
  storage       -> Time-Series-Storage
  tsdb          -> Embedded TSDB
  web           -> HTTP-API und UI

Erwartete Architektur-Richtung:
  cmd -> web, rules, scrape, discovery
  rules -> storage, model
  scrape -> model, discovery
  tsdb -> model (eigenstaendig, minimale externe Deps)
  model -> keine internen Deps (Basis-Layer)
```

## Ausschlussregeln (analog excluded-prefixes.txt)

Standard-Ausschluesse:

```text
# Vendor und externe Module
vendor/

# Go-generierte Dateien (optional)
*.pb.go
*_generated.go
zz_generated_*.go
mock_*.go

# Test-Utilities (optional, per Filter)
*_test.go

# Build-Artefakte
*.out
*.exe
```

Konfigurierbar als `go-excluded-prefixes.txt` oder per UI-Option.

## Integration in den Code

Neue Bausteine (analog Python-Reader-Struktur):

| Baustein | Aufgabe |
|---|---|
| `reader.GoSourceAnalyzer` | Einstiegspunkt, `LanguageAnalyzer`-Impl, gibt `DependencyModel` zurueck |
| `reader.go.ExternalGoAstProvider` | Unterprozess-Management, analog `ExternalPythonAstProvider` |
| `reader.go.ParsedGoFile` | Parser-nahes DTO pro Datei (filePath, packageName, imports, types, functions, calls) |
| `reader.go.GoModuleReader` | `go.mod` lesen, Modulnamen und Go-Version extrahieren |
| `reader.go.GoFileDiscovery` | Datei-Walk fuer `.go`-Dateien, optional via `go list -json` |
| `reader.go.GoSymbolIndex` | Projektweiter Index: Datei-ClassInfo -> Symbole (Typen, Funktionen) |
| `reader.go.GoDependencyResolver` | ParsedGoFile + SymbolIndex -> ClassInfo.dependencies + MethodInfo |
| `resources/go/s202_go_ast.go` | Externer Go-AST-Parser (Go-Quelldatei, wird compiliert) |

`PackageHierarchyBuilder` kann unveraendert aus dem Shared-Bereich verwendet
werden.

## Diagnose-Output

Nach jedem Scan:

```text
Go Analysis Summary
  packages analyzed:         NNN
  go files parsed:           NNN
  types indexed:             NNN
  functions indexed:         NNN
  import edges:              NNN
  call edges resolved:       NNN
  call edges unresolved:     NNN   (interface-dispatch, dynamic)
  uses edges:                NNN
  extends edges (embedding): NNN
  external imports ignored:  NNN
  generated files:           NNN
  test packages:             NNN
  cgo packages:              NNN   (calls not resolved)
  packages with build tags:  NNN
```

## Umsetzungsreihenfolge

1. Go-Executable-Check und `ExternalGoAstProvider` mit `go version`-Pruefung.
2. `GoModuleReader`: `go.mod` parsen, Modulname und Go-Version extrahieren.
3. `GoPackageDiscovery`: `go list -json ./...` ausfuehren, Paket-Metadaten
   sammeln, externe Pakete herausfiltern.
4. `ParsedGoFile`-DTO und `s202_go_ast.go`-Basis (Imports, Types, Funktionen, Calls).
5. Pass-1-Logik: Typ-Index pro Datei, ClassInfo-Erzeugung fuer exportierte Typen.
6. Pass-2-Logik: freie Funktionen per dominantem-Typ-Heuristik zuordnen, DefaultClass anlegen.
7. Projektweiten Symbol-Index aufbauen (`GoSymbolIndex`).
8. `GoDependencyResolver`: IMPORTS, EXTENDS, USES, CALLS / INSTANTIATES.
9. UI-Menuepunkt `Open Go Module...` hinzufuegen.
10. End-to-End-Test mit prometheus (kleiner als etcd, gut strukturiert).

## Kurzfassung

```text
Exportierter Go-Typ (struct/interface)
  -> Typ-ClassInfo,         fullName = packagePath.TypeName,       simpleName = TypeName

Freie Funktion, deren dominanter Typ (Return > erster Param, nach Unwrapping
und error-Filter) in derselben Datei definiert ist
  -> MethodInfo der Typ-ClassInfo dieses Typs
  (z.B. func New() (*Client, error)  ->  MethodInfo von Client)

Freie Funktion ohne lokalen dominanten Typ
  -> MethodInfo der DefaultClass der Datei
  DefaultClass:             fullName = packagePath.filename,        simpleName = filename
  (wird nur angelegt wenn benoetigt)

Package-level Variable  /  init()-Funktion
  -> MethodInfo / USES der DefaultPackageClass des Pakets
  DefaultPackageClass:      fullName = packagePath.pkgSimpleName,   simpleName = pkgSimpleName
  (wird nur angelegt wenn benoetigt)

.go-Datei ohne exportierte Typen  ->  DefaultClass ist die einzige Datei-ClassInfo

Go-Paket (Verzeichnis)     -> S202 PackageInfo
Go-Methode (mit Receiver)  -> MethodInfo der Receiver-Typ-ClassInfo
Struct-Embedding           -> EXTENDS auf Typ-ClassInfo des eingebetteten Typs
Feld-/Parametertyp         -> USES auf Typ-ClassInfo
Direkter qualif. Aufruf    -> CALLS / INSTANTIATES auf Typ-ClassInfo oder DefaultClass
go.mod                     -> Modul-Praefixfilter + Go-Version-Check
vendor/                    -> ausgeschlossen
externe Pakete             -> ausgeschlossen
```

Das Modell folgt Go-Idiomatik: Konstruktoren und Hilfsfunktionen gehoeren zum
Namensraum des Typs, den sie erzeugen oder bearbeiten — auch ohne Receiver.
Nur echte Fremdfunktionen landen in der DefaultClass.
