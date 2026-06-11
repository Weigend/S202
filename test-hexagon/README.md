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

With package-level ring assignment (tertile rule over package levels,
max level 6) the projection lands as follows:

| Ring        | Packages (out of the box)                                   |
|-------------|-------------------------------------------------------------|
| Core        | domain.publisher, domain.book, domain.inventory, domain.logistics |
| Application | application.api, application.spi, application.service       |
| Adapters    | rest, ui, shipping, bootstrap                                |

Both `api` and `spi` carry `@S202Api`, so their interfaces appear as port
candidates at the ring boundary. Marking them as explicit Inbound/Outbound
ports via the context menu makes the cross-segment PORT_BYPASS findings
disappear — that workflow is part of the demo.

**Two packages land one ring too far in (known heuristic limitation):**
`persistence` and `platform` sit at level 4 — exactly the level of
`application.service` — because a driven adapter and the service layer both
live one step above the ports. The level metric cannot tell them apart.
Right-click each and "Mark Package as Adapter" to correct the picture.
(A future heuristic signal — "implements an SPI interface ⇒ adapter" — would
classify `persistence` automatically; this app is the test case for it.)

## Things to try

- Collapse/expand the package boxes in the hexagonal view — the detail cards
  are an overlay, the radial layout never moves.
- Watch arrows roll up onto the package box when you collapse it.
- Sell more Moby-Dicks (edit `PaperWhaleApp`) and watch the restock cascade.
- Delete `ConsoleNotifier` from `ui` and see the SPI wiring complain at the
  only place allowed to know everything: `bootstrap`.
