# City3D — The Software City of S202

*As of July 2026 · branch `feat/city3d-prototype` · code under [`city3d/`](../city3d/)*

City3D renders the analysed architecture of a Java system as a 3D city you
can walk through in the browser (three.js/WebGL): an island in the ocean on
which packages become terraced platforms, classes become buildings and
**dependencies become real traffic**. The view is opened from the JavaFX app
(*File → Show City3D View*), but also runs standalone against an exported
`city.json`.

---

## 1. Concept: the layout is the statement

The central design rule of the whole development:

> **The X/Z layout encodes the dependency structure and is untouchable.**
> All visual layers (light, traffic, arcs, atmosphere) are additive and
> must never change the arrangement — at most the spacing.

The city is the 3D projection of the S202 layered representation
(see [`docs/concept`](concept/)):

- **Package tree = nested platforms.** Every package is a rectangle that
  encloses its children; every nesting level raises the platform by one
  terrace step. The city skyline *is* the package tree.
- **Level rows.** Inside a package, children stand in rows by local level
  (the "L:x" of the 2D view): the base at the bottom, the users at the top —
  exactly the arrangement of the 2D architecture view, only as a city map.
- **Dependencies therefore visibly flow "downhill".** Conforming edges run
  from rear rows to front rows; anything that violates the layering stands
  out as oncoming traffic.

So: whoever can read the city reads the architecture — distance, direction
and height are never decoration, always structure.

## 2. What the city encodes

| City element | Meaning |
|---|---|
| Platform (terrace) | Package; stack height = nesting depth, rows = local levels |
| Red platform | The package is part of a package cycle (tangle) |
| Building | Class; one building per class, on the platform of its package |
| Building height | Number of methods (amount of code) |
| Footprint | Fan-in (width) × fan-out (depth) |
| Building type glass → concrete | Global architecture level (top → bottom); interfaces are always glass |
| Red flashing roof beacon | The class is part of a dependency cycle |
| Window light (metric mode) | Density/brightness follows a selectable metric (fan-in / fan-out / methods) — hotspots light up at night, dead classes stay dark |
| Package signs | District names at the platform edges, shown depending on distance |

## 3. Dependencies: two visualisations, one semantics

**The same colour code everywhere:** cyan/blue = conforming (level falls),
**red = violation** (edge upward, or same level = cycle).

### 3.1 Data streams (arcs)

Glowing Bézier arcs from roof to roof with GPU-animated pulses
("data packets" fly from the user to the used). Modes: *off / selection /
all / violations only*; in selection mode, incoming is turquoise and
outgoing amber. The panel shows the edge count and the violation counter.

### 3.2 Traffic (the centrepiece)

Every (sampled) dependency **drives**: a vehicle takes the real street route
from the using class to the used class.

- **Yellow NY cabs** = relationships across packages. Body with a dark glass
  cabin, white headlights, red tail lights; the meaning sits in the
  **taxi roof light** (cyan/red).
- **Pedestrians** (single-coloured game pieces) = relationships within a
  package; they walk on the pavement instead of the road.
- **Speed = metric:** the speed scales with the global level jump of the
  dependency — long trips across the layer stack race, one-level hops
  stroll. (Nice side effect: cycle trips stay close in level and are
  therefore conspicuously slow red cabs.)
- **Day/night rhythm:** traffic density = slider × daylight factor.
- **Labels:** close to the camera, the trip text "source → target" appears
  above the nearest pods (plain text, tracked to the exact position every
  frame).
- **Hotspots** (optional): softly pulsing glow areas on the busiest
  intersections — the route load per graph node makes the "arteries" of the
  architecture visible.

## 4. The street network: a routable graph

Streets are not decoration in the gaps but a **graph** on which Dijkstra
computes routes ([`roads.js`](../city3d/src/roads.js)):

- A **ring road** on the inside of every platform edge (guarantees
  connectivity), **cross streets** between the level rows, **side streets**
  between neighbours, **access roads** on *both* sides of each building —
  routing starts and ends at the cheaper exit via multi-source Dijkstra
  instead of driving around the package —, **ramps** from every child deck
  to the parent corridors (always exactly one terrace step, thanks to the
  hierarchy).
- **Edge weights create bundling:** cars pay a surcharge on narrow streets
  (traffic gathers on the wide boulevards of the root) and a ramp penalty
  (no shortcuts across other packages' decks); pedestrians, conversely,
  prefer the quiet side streets.
- **Made drivable:** right-hand traffic by lane offset, corners as
  quadratic Bézier curves, pavements with kerbs and cut-outs at
  intersections/access roads, round intersection discs (asphalt + pavement
  disc) against the gaps at 90° corners, centre lines, street lamps.
- Boulevards get wider towards the root of the hierarchy — the street width
  itself encodes the level.

## 5. Atmosphere & landscape

- **Custom sky dome** (shader): deep zenith blue → bright horizon, sun disc
  with glow, **moon disc** opposite the sun. At night the moon is a real key
  light (cool blue, casting shadows) — the moonlight phase keeps the ground
  and streets readable. A drifting procedural cloud layer (FBM canvas) with
  a horizon fade; a **time-lapse** button rotates sun and moon through the
  full arc of the day.
- **Island in the ocean:** the city stands on a concrete base; around it an
  animated, reflecting water surface (scene reflection, wave wobble,
  Fresnel, distance blend into the horizon haze).
- **Green spaces:** parks (lawns + tree clusters, two kinds of trees,
  bushes) and open concrete areas are sampled into the free plaza areas with
  a collision check; a green belt lines the island.
- Weather (wetness/rain), fog, bloom, SSAO, colour grading — all adjustable.

## 6. Interaction

| Action | Effect |
|---|---|
| Click on a building/platform | Info panel (metrics, clickable uses/used-by chips for navigation), glow highlight; clicking again deselects (toggle) |
| Click on a pod | Trip info (source → target, type, route length), the route lights up, both end buildings glow; **📍 Follow** starts the chase cam |
| **🎥 Tour** | The camera attaches to a random vehicle — an endless city tour along real dependencies (Esc/click ends it) |
| Search (`/`) | Autocomplete over classes + packages, selection with a camera fly-to |
| Double-click | Fly to the object |
| 2D sync | Selection runs both ways between the browser and the JavaFX app (loopback server) |

## 7. Implementation

```
city3d/src/
  adapter.js   CityModel JSON -> layout (Layout3D port) + street network as graph data + anchors
  roads.js     graph builder (stations/intersections), weighted Dijkstra, drivable routes
  vehicles.js  vehicle pools (cabs/pedestrians, multi-layer instancing), labels, picking, follow
  deps.js      dependency arcs + GPU pulses
  city.js      rendering: buildings (facade shader), platforms, streets, ramps, island, water
  sky.js       sky dome, sun/moon, lighting model, clouds, env map
  greenery.js  parks, trees, bushes, open areas (sampling + collision test)
  labels.js    package name signs
  i18n.js      UI translations (DE/EN/PT), language choice + applying data-i18n
  main.js      orchestration, UI, selection, camera (fly-to/follow), post-FX
```

**Multiple languages:** the whole interface (panels, legend, help, search,
info overlays, tooltips) is available in German, English and Portuguese.
Static texts are tied to the dictionary in `i18n.js` via `data-i18n`
attributes in `index.html`; dynamic texts (info panel, time-of-day label,
FPS line) call `t()` directly. Language choice: `?lang=de|en|pt` in the URL
beats the stored choice (`localStorage`), which beats the browser language;
the switch in the settings panel takes effect live without a reload. Class,
package and vehicle labels in the scene are identifiers from the model and
stay language-neutral.

Data flow: `CityModelExporter` (Java) serialises the analysis to
`city.json` (packages, classes, metrics, **dependency edges**);
`CityView3DServer` serves the Vite bundle and the selection sync.

Performance principles: everything repeated is instanced (buildings in one
InstancedMesh with a procedural facade shader, vehicles as shared matrices
across layer meshes, arcs/pulses as one draw call), routes are computed
once, animation runs on the GPU or via cheap matrix updates. Tested up to
the range of tens of thousands of edges (caps and LOD thresholds catch
outliers).

## 8. Design lessons learned

- **Put the meaning into the roof light:** iconography (yellow cab) and
  meaning (colour) can be separated — the object stays readable, and so does
  the statement.
- **Aiming needs a world that stands still:** moving the mouse pauses the
  cinematic rotation, and picking happens on *pointerdown* — otherwise every
  selection misses the drifting target.
- **Small moving targets are clicked in screen space**, not by raycast
  (which loses against the platform below).
- **Overlays don't belong in the SSAO depth pass** (otherwise invisible
  label quads cast "AO shadows" — the notorious black bars).
- **A follow lerp cannot coexist with competing orbit damping** — while
  following, orbit control is muted completely.

## 9. Open ideas

- Aggregate the arcs by package at large distances (less spaghetti in
  systems with thousands of classes).
- Traffic lights/traffic-jam staging at hotspot intersections; intersection
  load as an export.
- A cinematic tour along the worst violations ("architecture review
  flight").
- Aggregated metric time series (the city changing over commits).
