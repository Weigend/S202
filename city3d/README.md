# City3D — data-driven software city (City3JS view)

Structure202's own copy of the [City3JS](../../City3JS) Manhattan view. The **view
is taken over as-is** — streets, weather, times of day, building types, bloom,
sky, traffic, reflections — and **only the generator is adapted** so that the
arrangement and size of buildings carry meaning from the analysed architecture.

What the city encodes (see [`src/adapter.js`](src/adapter.js)) — the layout mirrors
the 2D architecture view's **nested** packages and the 3D view's elevation:

| City element | Meaning |
|---|---|
| **Nested platforms** | the package tree: `com > com.example > class A` as nested rectangles |
| Platform **elevation** | nesting depth — deeper packages terrace upward (stacked) |
| Rows within a package | local level (the "L:x" of the 2D view; siblings ordered by horizontalOrder) |
| The **gaps** between platforms | the (hierarchical) street network |
| One **building** per class | sitting on top of its package platform |
| Building **height** | method count (size = amount of code) |
| Building **footprint** | fan-in (width) / fan-out (depth) |
| Building **type** (glass/stone/brick/concrete) | architecture level — interfaces are glass |
| Setbacks, rooftops, wet reflections, sky, weather | kept from City3JS for the city look |
| **Dependency arcs** (`src/deps.js`) | animated roof-to-roof light arcs with flying "data packets"; cyan = rule-conform (level falls), **red = violation/cycle**; modes off / selection / all / violations-only |
| **Metric lighting** | window-light density per building follows a chosen metric (fan-in / fan-out / methods) — hotspots glow at night, dead classes stay dark |
| **Cycle beacons** | blinking red rooftop warning lights on classes inside a dependency cycle |
| **Road network** (`src/adapter.js` → `src/roads.js`) | a real routable graph: perimeter **ring road** per package, **cross streets** between level rows, **side streets** between siblings, a **driveway** per building and **ramps** down every terrace step; sidewalks with kerb cuts at junctions, centre-line dashes, street lamps |
| **Traffic = dependencies** (`src/vehicles.js`) | every (sampled) dependency moves: cross-package = **car** on the roadway, package-local = **pedestrian** on the sidewalk; cyan = rule-conform, **red = violation**; routing uses edge weights (cars bundle onto wide boulevards, ramps are penalised); pods are clickable → source/target + glowing route; density slider |
| **Greenery** (`src/greenery.js`) | parks (lawn rectangles + tree clusters), concrete plazas and bushes in the free areas + a green belt (sampled against buildings/roads/ramps/platforms) |
| **Island & water** | the city sits on a concrete island in an animated reflective ocean (scene reflection, wave wobble, horizon haze) |
| **Sky & daylight** (`src/sky.js`) | custom gradient sky dome (real blue zenith), sun disc + glow, **moon** with cool moonlight shadows at night, drifting procedural clouds, day/night timelapse button |
| **Hotspots & follow cam** | pulsing rings mark the busiest intersections (route load); pods show proximity labels (source → target) and can be clicked & **followed** (chase cam, endless tour) |
| **Package labels** (`src/labels.js`) | district name signs at platform edges, fading in with camera distance |

UI: search box (press `/`) with fly-to, clickable uses/used-by chips in the info
panel (navigate the dependency graph), double-click = fly to building, legend.

Everything else (`city.js` rendering, `sky.js`, `weather.js`, `postfx.js`,
`controls.js`) is the original City3JS view.

## In the app (Structure202)

Build the web bundle once, then use the menu:

```bash
cd city3d && npm install && npm run build   # produces city3d/dist
```

In Structure202, load/analyse a project and pick **File → Show City3D View**. The
app serialises the current analysis to a `CityModel`, serves the bundle on a
loopback port (`de.weigend.s202.ui.views.city3d.CityView3DServer`) and opens the city
in your browser. Re-run the menu after analysing another jar to update it.

## How to run (standalone dev)

**1. Export a city model** from a `.jar` (headless, plain Java — no UI). Writes
`city3d/public/city.json`, which the view fetches:

```bash
cd analyzer
mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
  -Dexec.mainClass=de.weigend.s202.ui.views.city3d.CityModelExporter \
  -Dexec.args="../test-example/target/test-example-1.0.0.jar ../city3d/public/city.json"
```

**2. Run the view** (Vite dev server):

```bash
cd city3d
npm install      # once: three + vite
npm run dev      # http://localhost:5173/
```

Use the panel to change time of day, bloom, fog and weather; **Neu bauen**
re-reads `city.json` (so re-export + click to update). "Fly" mode with `F`.

## Pieces

- `src/adapter.js` — the only real change: `CityModel` JSON → City3JS building/roof/facade/street arrays.
- `src/city.js` — City3JS generator; its procedural block loop is replaced by a call to the adapter, everything downstream unchanged.
- `src/main.js` — City3JS orchestration; fetches `city.json` and passes the model to `buildCity`.
- `de.weigend.s202.ui.views.city3d.*` (Java) — `CityModel`, `CityModelSerializer`, `CityModelExporter`.
