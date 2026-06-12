# The Paper Whale — Hexagonal BookStore Test App

A small, self-contained bookstore ("The Paper Whale") built strictly as a
hexagonal architecture (ports and adapters, SPI style). It exists to exercise
the S202 **Hexagonal View**: package-level ring assignment, port candidates,
and the collapsible package overlay.

Run it:

```bash
mvn -pl test-hexagon -am compile
java -cp test-hexagon/target/classes com.paperwhale.bootstrap.PaperWhaleApp
```

One busy afternoon: the counter sells two copies of Moby-Dick, the stock dips
below the reorder threshold, a restock shipment takes off with Seagull
Express, and REST clients watch it all as JSON.

## Structure

```text
com.paperwhale
├── domain                  the core — depends on nothing outside itself
│   ├── publisher           Publisher, Imprint                    (level 0)
│   ├── book                Isbn, Genre, Book -> publisher        (level 1)
│   ├── inventory           StockItem, ReorderPolicy -> book      (level 2)
│   └── logistics           Shipment, TrackingCode -> book        (level 2)
├── application             the hexagon
│   ├── api                 inbound ports (use-case interfaces)   (level 3)
│   ├── spi                 outbound ports (SPI interfaces)       (level 3)
│   └── service             use-case implementations              (level 4)
├── platform                shared adapter plumbing (JSON, text)  (level 4)
├── persistence             driven adapter: simulated JDBC        (level 4)
├── rest                    driving adapter: REST resources       (level 5)
├── ui                      driving adapter + ConsoleNotifier     (level 5)
├── shipping                driven adapter: Seagull Express       (level 5)
└── bootstrap               composition root, wires everything    (level 6)
```

The SPI model in one sentence: `application.spi` interfaces are defined by
the core and **implemented by the outer ring** (persistence, shipping, and
the ConsoleNotifier in ui — dependency inversion), while `application.api`
interfaces are **called by the outer ring** (rest, ui) and implemented by
`application.service`.

## What the Hexagonal View should show

The segments are the four BUSINESS THEMES — the children of the domain
package (book, publisher, inventory, logistics) — each occupying one sector
across all rings. Every class is assigned to a theme by weighted dependency
voting (e.g. JdbcPublisherDirectory lands in the publisher sector,
SeagullExpressCarrier in logistics).

Ring assignment per package:

| Ring        | Packages (out of the box)                                   |
|-------------|-------------------------------------------------------------|
| Core        | domain.publisher, domain.book, domain.inventory, domain.logistics |
| Application | application.service (implements the API contracts)          |
| Adapters    | persistence, shipping (implement SPI contracts), rest, ui, platform (use API contracts), bootstrap |

The contracts themselves — `application.api` and `application.spi` — appear
as two separately expandable API/SPI sockets on the application-ring
boundary of each theme sector. Contract interfaces carry no bytecode
dependencies, so they sit at architecture level 0 by design: everything
rests on them.

The service/adapter separation comes from the CONTRACT SIGNAL, not from
levels (both sit one step above the ports): a package implementing an SPI
interface is a driven adapter, a package merely using API interfaces is a
driving adapter, and the package implementing the API interfaces is the
application core implementation.

## Things to try

- Collapse/expand the package boxes in the hexagonal view — the detail cards
  are an overlay, the radial layout never moves.
- Watch arrows roll up onto the package box when you collapse it.
- Sell more Moby-Dicks (edit `PaperWhaleApp`) and watch the restock cascade.
- Delete `ConsoleNotifier` from `ui` and see the SPI wiring complain at the
  only place allowed to know everything: `bootstrap`.
