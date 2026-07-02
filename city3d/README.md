# City3D — data-driven 3D city renderer (Phase 0)

Structure202's own copy of the [City3JS](../../City3JS) idea: render the analysed
architecture as a Manhattan-style 3D city, **buildings = classes, districts =
packages**, driven by real analysis data (no RNG).

This is the **Phase 0 Durchstich** from
[`docs/exploration/CITY3D_WEBVIEW_SPEC.md`](../docs/exploration/CITY3D_WEBVIEW_SPEC.md):
a thin end-to-end slice that gets real buildings on screen with zero embedding
risk. It runs in a normal browser; the JavaFX/JCEF embedding (V2) and the richer
City3JS shaders come in later phases.

## How to run

**1. Export a city model** from a `.jar` (headless, plain Java — no UI):

```bash
cd analyzer
mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
  -Dexec.mainClass=de.weigend.s202.ui.city3d.CityModelExporter \
  -Dexec.args="../test-example/target/test-example-1.0.0.jar ../city3d/city.json"
```

This writes `city3d/city.json` (a checked-in sample is already there).

**2. Serve this folder and open it** (ES modules + `fetch` need http, not `file://`):

```bash
cd city3d
python3 -m http.server 8099
# open http://localhost:8099/
```

Drag to orbit, scroll to zoom, hover a building for its class / level / fan-in.

## What maps to what (Phase 0)

| Visual | Encodes |
|---|---|
| Building height | architecture level (deeper level = taller) |
| Building width | fan-in (log-scaled) |
| Building colour | architecture level (blue → amber → red) |
| Emissive tint | interface types |
| Row (depth) | architecture level |

Richer mapping (LoC → floors, coverage → lit windows, SCC → red facade) is Phase 1.

## Pieces

- `de.weigend.s202.ui.city3d.CityModel` — the JSON data contract (districts, buildings, dependencies).
- `de.weigend.s202.ui.city3d.CityModelSerializer` — tree → `CityModel` (reuses 2D footprints when available; `null` headless).
- `de.weigend.s202.ui.city3d.CityModelExporter` — headless CLI: jar → `city.json`.
- `main.js` — the Three.js renderer; `vendor/` holds a self-contained Three.js copy (no CDN).
