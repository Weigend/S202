# City3D — data-driven software city (City3JS view)

Structure202's own copy of the [City3JS](../../City3JS) Manhattan view. The **view
is taken over as-is** — streets, weather, times of day, building types, bloom,
sky, traffic, reflections — and **only the generator is adapted** so that the
arrangement and size of buildings carry meaning from the analysed architecture.

What the city encodes (see [`src/adapter.js`](src/adapter.js)):

| City element | Meaning |
|---|---|
| One **block** per package (district) | arranged by architecture level (level = spatial gradient) |
| One **building** per class | inside its package block |
| Building **height** | method count (size = amount of code) |
| Building **footprint** | fan-in (width) / fan-out (depth) |
| Building **type** (glass/stone/brick/concrete) | architecture level — interfaces are glass |
| Setbacks, rooftops, shopfronts, streets | kept from City3JS for the city look |

Everything else (`city.js` rendering, `sky.js`, `weather.js`, `traffic.js`,
`postfx.js`, `controls.js`) is the original City3JS view.

## How to run

**1. Export a city model** from a `.jar` (headless, plain Java — no UI). Writes
`city3d/public/city.json`, which the view fetches:

```bash
cd analyzer
mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
  -Dexec.mainClass=de.weigend.s202.ui.city3d.CityModelExporter \
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
- `de.weigend.s202.ui.city3d.*` (Java) — `CityModel`, `CityModelSerializer`, `CityModelExporter`.
