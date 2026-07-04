import * as THREE from 'three';
import { Reflector } from 'three/addons/objects/Reflector.js';
import { layoutFromModel } from './adapter.js';
import { makePackageLabels } from './labels.js';
import { makeGreenery } from './greenery.js';
import { buildRoadGraph } from './roads.js';

/**
 * Prozedurale Manhattan-Stadt.
 *
 * - Regelmäßiges Raster aus Avenues (N-S) und Streets (O-W) mit langen Blöcken.
 * - Jeder Block wird in Parzellen unterteilt, jedes Gebäude kann gestufte
 *   Setbacks haben (klassische NYC-Silhouette).
 * - Alle Boxen liegen in EINEM InstancedMesh; ein angepasster Standard-Shader
 *   erzeugt prozedurale Fensterraster mit zufällig beleuchteten Fenstern,
 *   reflektierendem Glas (Env-Map) und Mauerwerk.
 */

// Reflector-Shader für nasse Straßen: spiegelt die echte Szene (Gebäude,
// Neon, Scheinwerfer), gedämpft per Fresnel (im flachen Blickwinkel stärker)
// und über die Nässe (uWet) als transparenter Schleier über den Asphalt geblendet.
const WetReflectorShader = {
  uniforms: {
    color: { value: null },
    tDiffuse: { value: null },
    textureMatrix: { value: null },
    uWet: { value: 0 },
  },
  vertexShader: /* glsl */`
    uniform mat4 textureMatrix;
    varying vec4 vUv;
    varying vec3 vWorld;
    #include <common>
    #include <logdepthbuf_pars_vertex>
    void main() {
      vUv = textureMatrix * vec4(position, 1.0);
      vWorld = (modelMatrix * vec4(position, 1.0)).xyz;
      gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      #include <logdepthbuf_vertex>
    }`,
  fragmentShader: /* glsl */`
    uniform vec3 color;
    uniform sampler2D tDiffuse;
    uniform float uWet;
    varying vec4 vUv;
    varying vec3 vWorld;
    #include <logdepthbuf_pars_fragment>
    void main() {
      #include <logdepthbuf_fragment>
      vec4 base = texture2DProj(tDiffuse, vUv);
      // Flache Fläche (Normale nach oben): Fresnel über den Blickwinkel.
      vec3 viewDir = normalize(cameraPosition - vWorld);
      float fres = pow(1.0 - clamp(viewDir.y, 0.0, 1.0), 4.0);
      float strength = mix(0.10, 1.0, fres);
      vec3 refl = base.rgb * color; // color < 1 dunkelt/tönt → nasser Asphalt
      gl_FragColor = vec4(refl, clamp(uWet * strength, 0.0, 1.0));
      #include <tonemapping_fragment>
      #include <colorspace_fragment>
    }`,
};

// ---- deterministischer RNG ---------------------------------------------------
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---- Layout-Parameter --------------------------------------------------------
// Rastergröße per URL überschreibbar (?cols=35&rows=68) — für Skalierungstests.
// Geclamped, damit ein Tippfehler nicht den Browser sprengt.
const _q = new URLSearchParams(globalThis.location?.search ?? '');
const _grid = (key, def, max) => {
  const v = Math.round(+_q.get(key));
  return Number.isFinite(v) && v >= 1 ? Math.min(v, max) : def;
};
const CFG = {
  cols: _grid('cols', 11, 80),  // Anzahl Avenue-Spalten (Blöcke in X)
  rows: _grid('rows', 22, 160), // Anzahl Street-Reihen (Blöcke in Z) — Manhattan ist länglich
  blockW: 66,          // Blockbreite (X)
  blockD: 38,          // Blocktiefe (Z)
  avenue: 22,          // Avenue-Breite (Lücke in X)
  street: 14,          // Street-Breite (Lücke in Z)
};

export function buildCity(scene, atmosphere, model, seed = 1) {
  const group = new THREE.Group();
  group.name = 'city';

  // Hierarchical data-driven layout: NESTED package platforms + one building per
  // class. Fills the same building/roof arrays the original generator produced,
  // so the building rendering (facade shader, setbacks, roofs) is unchanged; adds
  // stacked package slabs, and the gaps between them are the (hierarchical) streets.
  // Meaning: package nesting = terraces raised by depth, height = methods,
  // footprint = fan-in/out, building type = architecture level. See adapter.js.
  const { boxes, rooftops, groundFacades, slabs, roadNet, anchors, spanX, spanZ, x0, z0, maxDepth }
    = layoutFromModel(model);

  // Routbarer Straßengraph — fürs Rendering (Gehsteig-Lücken an Kreuzungen)
  // und für den Verkehr (Dijkstra-Routen der Abhängigkeits-Fahrten).
  const roadGraph = buildRoadGraph(roadNet);

  // ---- InstancedMesh aufbauen ------------------------------------------------
  const count = boxes.length;
  const geo = new THREE.BoxGeometry(1, 1, 1);
  geo.translate(0, 0.5, 0); // Basis auf y=0

  const mat = makeBuildingMaterial(atmosphere);
  const mesh = new THREE.InstancedMesh(geo, mat, count);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  mesh.frustumCulled = true;

  const aDim = new Float32Array(count * 3);
  const aSeed = new Float32Array(count);
  const aStyle = new Float32Array(count);
  const aLayout = new Float32Array(count);
  const aBaseY = new Float32Array(count);
  const aAct = new Float32Array(count); // Metrik-Aktivität 0..1 (Fensterlicht-Dichte)
  const m4 = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const pos = new THREE.Vector3();
  const scl = new THREE.Vector3();

  for (let i = 0; i < count; i++) {
    const b = boxes[i];
    pos.set(b.x, b.baseY, b.z);
    scl.set(b.w, b.h, b.d);
    m4.compose(pos, q, scl);
    mesh.setMatrixAt(i, m4);
    aDim.set([b.w, b.h, b.d], i * 3);
    aSeed[i] = b.antenna ? -1 : b.seed; // -1 markiert Antennen (kein Fenster)
    aStyle[i] = b.style;
    aLayout[i] = b.layout;
    aBaseY[i] = b.baseY;
  }
  geo.setAttribute('aDim', new THREE.InstancedBufferAttribute(aDim, 3));
  geo.setAttribute('aSeed', new THREE.InstancedBufferAttribute(aSeed, 1));
  geo.setAttribute('aStyle', new THREE.InstancedBufferAttribute(aStyle, 1));
  geo.setAttribute('aLayout', new THREE.InstancedBufferAttribute(aLayout, 1));
  geo.setAttribute('aBaseY', new THREE.InstancedBufferAttribute(aBaseY, 1));
  const actAttr = new THREE.InstancedBufferAttribute(aAct, 1);
  geo.setAttribute('aAct', actAttr);
  mesh.instanceMatrix.needsUpdate = true;
  group.add(mesh);

  // Metrik-Beleuchtung: Fensterlicht-Dichte pro Gebäude nach gewählter Metrik.
  // Log-normalisiert, damit einzelne Ausreißer die Skala nicht plattdrücken.
  function setMetric(metric) {
    const get = metric === 'fanin' ? (b) => b.fanIn ?? 0
      : metric === 'fanout' ? (b) => b.fanOut ?? 0
        : metric === 'methods' ? (b) => Math.max(0, b.methodCount ?? 0)
          : null;
    if (!get) {
      mat.userData.uniforms.uMetricOn.value = 0;
      return;
    }
    const max = Math.max(1, ...boxes.map(get));
    const norm = Math.log2(1 + max);
    for (let i = 0; i < count; i++) aAct[i] = Math.log2(1 + get(boxes[i])) / norm;
    actAttr.needsUpdate = true;
    mat.userData.uniforms.uMetricOn.value = 1;
  }

  const roofDetails = makeRoofDetails(rooftops);
  group.add(roofDetails);

  // Rot blinkende Warnleuchten auf Gebäuden, die in einem Zyklus stecken.
  const beacons = makeCycleBeacons(rooftops);
  group.add(beacons);

  const groundDetails = makeGroundFloorDetails(groundFacades, atmosphere);
  group.add(groundDetails);

  // Rectangular package platforms (districts), stacked by nesting depth.
  const slabMesh = makePackageSlabs(slabs, maxDepth);
  group.add(slabMesh);

  // Paket-Namensschilder an den Plattform-Vorderkanten (distanzabhängig sichtbar).
  const labels = makePackageLabels(slabs, maxDepth);
  group.add(labels.group);

  // Grünflächen: Parks in den freien Plaza-Bereichen + Grüngürtel um die Stadt.
  const greenery = makeGreenery({ slabs, roadNet, anchors, spanX, spanZ });
  group.add(greenery);

  // Das Straßennetz: Asphalt, Gehsteige (mit Lücken an Kreuzungen/Zufahrten),
  // Mittellinien und Laternen — aus den Graph-Daten des Adapters.
  const streetMesh = makeRoadsMesh(roadNet, roadGraph);
  group.add(streetMesh);

  // Rampen: Kind-Deck -> Eltern-Korridor, als Cosinus-Bänder (wie die Fahrwege).
  const rampMesh = makeRamps(roadNet);
  group.add(rampMesh);

  // ---- Boden -----------------------------------------------------------------
  // Flat wet-reflective asphalt; the streets are the gaps between the platforms.
  const ground = makeGroundBase(spanX, spanZ, atmosphere);
  group.add(ground);

  scene.add(group);

  return {
    group,
    material: mat,
    buildingMesh: mesh,                       // the class buildings (InstancedMesh) for picking
    boxFqns: boxes.map((b) => b.fqn),          // instanceId -> class fqn (undefined for antennas)
    slabPickMesh: slabMesh.userData.mesh,      // the package platforms (InstancedMesh) for picking
    slabFqns: slabs.map((s) => s.fqn),         // instanceId -> package fqn
    slabs,                                     // Plattform-Geometrie (Kamera-Anflug auf Pakete)
    streetLightMaterial: null,
    nightMaterials: [
      ...(groundDetails.userData.nightMaterials ?? []),
      ...(streetMesh.userData.nightMaterials ?? []),
    ].filter(Boolean),
    roadNet, roadGraph,                        // Straßengraph (Verkehrs-Routing)
    bounds: { spanX, spanZ },
    stats: { buildings: rooftops.length, boxes: count, grid: [slabs.length, count] },
    anchors,                                   // fqn -> Position (Bögen, Kamera-Anflug)
    beaconMat: beacons.userData.beaconMat,     // für den Blink-Puls im Render-Loop
    setMetric,
    labels,                                    // Paket-Schilder: labels.update(camera, dt)
    setWetness: (w) => {
      ground.userData.setWetness?.(w);
      streetMesh.userData.setWetness?.(w);
      rampMesh.userData.setWetness?.(w);
    },
    dispose() {
      geo.dispose();
      mat.dispose();
      slabMesh.traverse((o) => { o.geometry?.dispose(); o.material?.dispose(); });
      streetMesh.traverse((o) => { o.geometry?.dispose(); o.material?.dispose(); });
      rampMesh.traverse((o) => { o.geometry?.dispose(); o.material?.dispose(); });
      roofDetails.traverse((o) => { o.geometry?.dispose(); o.material?.dispose(); });
      beacons.traverse((o) => { o.geometry?.dispose(); o.material?.dispose(); });
      greenery.traverse((o) => { o.geometry?.dispose(); o.material?.dispose(); });
      labels.dispose();
      groundDetails.traverse((o) => { o.geometry?.dispose(); o.material?.dispose(); });
      ground.traverse((o) => {
        if (o.isReflector) o.dispose();        // Render-Target + Material
        o.geometry?.dispose();
        if (!o.isReflector) o.material?.dispose();
      });
      scene.remove(group);
    },
  };
}

// ---- Rectangular package platforms (districts) -----------------------------
// One box per package, a solid pedestal from the parent's top up to its own top
// (so terraces stack without an air gap); colour pale->dark by depth, red in a cycle.
function makePackageSlabs(slabs, maxDepth) {
  const group = new THREE.Group();
  group.name = 'package-slabs';
  if (!slabs.length) return group;

  const geo = new THREE.BoxGeometry(1, 1, 1);
  const mesh = new THREE.InstancedMesh(
    geo, new THREE.MeshStandardMaterial({ roughness: 0.85, metalness: 0.05 }), slabs.length);
  mesh.receiveShadow = true;

  // Gedeckte Töne — Zitronengelb blendete unter voller Tagessonne.
  const base = new THREE.Color(0xe7dfc2), deep = new THREE.Color(0xbfa050), cyc = new THREE.Color(0x8a3b34);
  const m4 = new THREE.Matrix4(), q = new THREE.Quaternion();
  const pos = new THREE.Vector3(), scl = new THREE.Vector3(), col = new THREE.Color();
  for (let i = 0; i < slabs.length; i++) {
    const s = slabs[i];
    pos.set(s.x, (s.botY + s.topY) / 2, s.z);
    scl.set(s.w, s.topY - s.botY, s.d);
    m4.compose(pos, q, scl);
    mesh.setMatrixAt(i, m4);
    col.copy(base).lerp(deep, maxDepth > 0 ? s.depth / maxDepth : 0);
    if (s.inCycle) col.lerp(cyc, 0.5);
    mesh.setColorAt(i, col);
  }
  mesh.instanceMatrix.needsUpdate = true;
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  group.add(mesh);
  group.userData.mesh = mesh; // exposed for picking (instanceId -> slab)
  return group;
}

// ---- Zyklus-Beacons ----------------------------------------------------------
// Klassen in einem Abhängigkeits-Zyklus tragen eine rot blinkende Warnleuchte
// auf dem Dach (Luftfahrt-Look). Der Blink-Puls wird im Render-Loop über
// beaconMat.emissiveIntensity gefahren; Bloom macht daraus ein echtes Signal.
function makeCycleBeacons(rooftops) {
  const group = new THREE.Group();
  group.name = 'cycle-beacons';
  const spots = rooftops.filter((r) => r.inCycle);
  if (!spots.length) return group;

  const mastMat = new THREE.MeshStandardMaterial({ color: 0x1c1e22, roughness: 0.5, metalness: 0.6 });
  const beaconMat = new THREE.MeshStandardMaterial({
    color: 0x2a0605, emissive: 0xff2418, emissiveIntensity: 2.0,
    roughness: 0.4, metalness: 0, toneMapped: false,
  });

  const masts = spots.map((r) => ({ x: r.x, y: r.y + 1.1, z: r.z, w: 0.16, h: 2.2, d: 0.16 }));
  group.add(makeBoxInstances(masts, mastMat, true));

  const bulbGeo = new THREE.SphereGeometry(0.55, 10, 8);
  const bulbs = new THREE.InstancedMesh(bulbGeo, beaconMat, spots.length);
  const m4 = new THREE.Matrix4();
  for (let i = 0; i < spots.length; i++) {
    const r = spots[i];
    m4.makeTranslation(r.x, r.y + 2.35, r.z);
    bulbs.setMatrixAt(i, m4);
  }
  bulbs.instanceMatrix.needsUpdate = true;
  group.add(bulbs);
  group.userData.beaconMat = beaconMat;
  return group;
}

// ---- Das Straßennetz ---------------------------------------------------------
// Asphaltbänder je Straßensegment, Gehsteige mit Bordstein beidseitig (mit
// Lücken an Kreuzungen, Zufahrten und Rampen-Anschlüssen — aus den Stationen
// des Straßengraphen), Mittellinien und Laternen entlang der Boulevards.
function makeRoadsMesh(roadNet, graph) {
  const group = new THREE.Group();
  group.name = 'roads';
  const roads = roadNet.roads;
  if (!roads.length) return group;

  const m4 = new THREE.Matrix4(), q = new THREE.Quaternion();
  const p = new THREE.Vector3(), s = new THREE.Vector3();
  const asphaltMat = makeWetRoadMaterial({ roughness: 0.82, envMapIntensity: 0.45 });
  // Minimaler Höhenversatz pro Straße gegen Z-Fighting an Kreuzungsflächen.
  const jit = (r) => (r.id % 5) * 0.005;

  const asphalt = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), asphaltMat, roads.length);
  asphalt.receiveShadow = true;

  const DASH = 4.2, GAP = 5.2;
  const dashes = [];
  const walks = [];
  for (let i = 0; i < roads.length; i++) {
    const r = roads[i];
    const len = r.a1 - r.a0, mid = (r.a0 + r.a1) / 2;
    const cx = r.axis === 'x' ? mid : r.c;
    const cz = r.axis === 'x' ? r.c : mid;
    p.set(cx, r.y + 0.045 + jit(r), cz);
    s.set(r.axis === 'x' ? len : r.w, 0.09, r.axis === 'x' ? r.w : len);
    m4.compose(p, q, s);
    asphalt.setMatrixAt(i, m4);

    // Mittellinie (nicht auf Zufahrten)
    if (r.kind !== 'drive' && len > 10) {
      for (let a = r.a0 + 2 + DASH / 2; a < r.a1 - 2; a += DASH + GAP) {
        dashes.push(r.axis === 'x'
          ? { x: a, y: r.y + 0.105 + jit(r), z: r.c, w: DASH, d: 0.22 }
          : { x: r.c, y: r.y + 0.105 + jit(r), z: a, w: 0.22, d: DASH });
      }
    }

    // Gehsteige: Segmente zwischen den Stationen, mit Freischnitt an jeder
    // Kreuzung (halbe Breite der querenden Straße + Bordsteinradius).
    if (r.kind === 'ring' || r.kind === 'cross' || r.kind === 'side') {
      const sts = graph.stationsByRoad.get(r.id) ?? [];
      for (let k = 0; k < sts.length - 1; k++) {
        const from = sts[k].pos + (sts[k].cw ? sts[k].cw / 2 + 0.9 : 0.4);
        const to = sts[k + 1].pos - (sts[k + 1].cw ? sts[k + 1].cw / 2 + 0.9 : 0.4);
        if (to - from < 1.6) continue;
        const wm = (from + to) / 2, wl = to - from;
        for (const side of [-1, 1]) {
          const off = r.c + side * (r.w / 2 + 0.65);
          walks.push(r.axis === 'x'
            ? { x: wm, y: r.y + 0.07, z: off, w: wl, d: 1.3 }
            : { x: off, y: r.y + 0.07, z: wm, w: 1.3, d: wl });
        }
      }
    }
  }
  asphalt.instanceMatrix.needsUpdate = true;
  group.add(asphalt);
  group.userData.setWetness = (w) => asphaltMat.userData.setWetness?.(w);

  if (dashes.length) {
    const dm = new THREE.InstancedMesh(
      new THREE.BoxGeometry(1, 1, 1),
      new THREE.MeshStandardMaterial({ color: 0xd2b866, roughness: 0.8, metalness: 0 }),
      dashes.length);
    for (let i = 0; i < dashes.length; i++) {
      const d = dashes[i];
      p.set(d.x, d.y, d.z); s.set(d.w, 0.03, d.d); m4.compose(p, q, s);
      dm.setMatrixAt(i, m4);
    }
    dm.instanceMatrix.needsUpdate = true;
    group.add(dm);
  }

  if (walks.length) {
    const wm = new THREE.InstancedMesh(
      new THREE.BoxGeometry(1, 1, 1),
      new THREE.MeshStandardMaterial({ color: 0x54585c, roughness: 0.92, metalness: 0, envMapIntensity: 0.15 }),
      walks.length);
    wm.receiveShadow = true;
    for (let i = 0; i < walks.length; i++) {
      const d = walks[i];
      p.set(d.x, d.y, d.z); s.set(d.w, 0.14, d.d); m4.compose(p, q, s);
      wm.setMatrixAt(i, m4);
    }
    wm.instanceMatrix.needsUpdate = true;
    group.add(wm);
  }

  // Straßenlaternen entlang der längeren Ring-/Querstraßen, alternierend.
  const LAMP_EVERY = 30;
  const poles = [], heads = [];
  for (const r of roads) {
    if (r.kind !== 'ring' && r.kind !== 'cross') continue;
    const len = r.a1 - r.a0;
    if (len < LAMP_EVERY * 0.9) continue;
    const n = Math.floor(len / LAMP_EVERY);
    for (let i = 0; i < n; i++) {
      const a = r.a0 + len / 2 - (n * LAMP_EVERY) / 2 + LAMP_EVERY * (i + 0.5);
      const side = i % 2 === 0 ? 1 : -1;
      const off = r.c + side * (r.w / 2 + 2.2);
      const lx = r.axis === 'x' ? a : off;
      const lz = r.axis === 'x' ? off : a;
      poles.push({ x: lx, y: r.y + 2.1, z: lz, radius: 0.07, h: 4.2 });
      heads.push({ x: lx, y: r.y + 4.25, z: lz, w: 0.5, h: 0.22, d: 0.5 });
    }
  }
  if (poles.length) {
    const poleMat = new THREE.MeshStandardMaterial({ color: 0x24282a, roughness: 0.58, metalness: 0.62 });
    const headMat = new THREE.MeshStandardMaterial({
      color: 0x4a4033, emissive: 0xffd18c, emissiveIntensity: 1.2,
      roughness: 0.34, metalness: 0, toneMapped: false,
    });
    headMat.userData.nightBase = 0.06;
    headMat.userData.nightScale = 2.2;
    group.add(makeCylinderInstances(poles, poleMat, 6, true));
    group.add(makeBoxInstances(heads, headMat));
    group.userData.nightMaterials = [headMat];
  }
  return group;
}

// ---- Soft ramp connectors (child deck -> parent corridor) --------------------
// A curved ribbon following a cosine profile: tangent-flat where it meets the
// upper street and the lower one, steepest in the middle — no sharp edges.
function makeRamps(roadNet) {
  const group = new THREE.Group();
  group.name = 'ramps';
  const roadById = new Map(roadNet.roads.map((r) => [r.id, r]));
  const ramps = roadNet.ramps.map((rp) => {
    const ra = roadById.get(rp.aRoad), rb = roadById.get(rp.bRoad);
    // a auf der Kind-Verbindungsstraße (axis z), b auf dem Eltern-Korridor (axis x)
    return { ax: ra.c, ay: ra.y, az: rp.aPos, bx: rp.bPos, by: rb.y, bz: rb.c, w: rp.w };
  });
  if (!ramps.length) return group;
  const mat = makeWetRoadMaterial({ roughness: 0.82, envMapIntensity: 0.45 });
  mat.side = THREE.DoubleSide;
  const N = 16; // profile segments
  for (const r of ramps) {
    // horizontal run direction (in the XZ plane) + perpendicular width direction
    let hx = r.bx - r.ax, hz = r.bz - r.az;
    const hl = Math.hypot(hx, hz) || 1; hx /= hl; hz /= hl;
    const wx = -hz * r.w / 2, wz = hx * r.w / 2;
    const pos = [], idx = [];
    for (let i = 0; i <= N; i++) {
      const t = i / N;
      const ease = (1 - Math.cos(Math.PI * t)) / 2; // cosine S-curve for the height
      const px = r.ax + (r.bx - r.ax) * t;
      const py = r.ay + (r.by - r.ay) * ease + 0.06; // lift clear of the slab tops (no z-fighting)
      const pz = r.az + (r.bz - r.az) * t;
      pos.push(px + wx, py, pz + wz, px - wx, py, pz - wz);
    }
    for (let i = 0; i < N; i++) {
      const a = i * 2;
      idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2);
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    geo.setIndex(idx);
    geo.computeVertexNormals();
    const mesh = new THREE.Mesh(geo, mat);
    mesh.receiveShadow = true;
    group.add(mesh);
  }
  group.userData.setWetness = (w) => mat.userData.setWetness?.(w);
  return group;
}

const DRY_ROAD = new THREE.Color(0x11151a);
const WET_ROAD = new THREE.Color(0x070a0e);

function makeWetRoadMaterial({ roughness = 0.72, envMapIntensity = 0.35 } = {}) {
  const mat = new THREE.MeshStandardMaterial({
    color: DRY_ROAD.clone(), roughness, metalness: 0, envMapIntensity,
  });
  mat.userData.wet = { value: 0 };
  mat.onBeforeCompile = (sh) => {
    sh.uniforms.uWet = mat.userData.wet;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n         varying vec2 vWorldXZ;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\n         vWorldXZ = (modelMatrix * vec4(transformed, 1.0)).xz;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\n         varying vec2 vWorldXZ;\n         uniform float uWet;')
      .replace('#include <roughnessmap_fragment>',
        `#include <roughnessmap_fragment>
         {
           vec2 p = vWorldXZ * 0.03;
           float n = sin(p.x) * sin(p.y) + 0.6 * sin(p.x * 2.3 + 1.7) * sin(p.y * 1.9 - 0.6);
           float puddle = smoothstep(-0.1, 0.7, n);
           float wetRough = mix(0.42, 0.05, puddle);
           roughnessFactor = mix(roughnessFactor, wetRough, uWet);
         }`);
  };
  mat.userData.setWetness = (w) => {
    mat.userData.wet.value = w;
    mat.color.copy(DRY_ROAD).lerp(WET_ROAD, w);
    mat.roughness = THREE.MathUtils.lerp(roughness, 0.14, w);
    mat.envMapIntensity = THREE.MathUtils.lerp(envMapIntensity, 1.4, w);
  };
  return mat;
}

// ---- Flat wet-reflective ground (asphalt in the street gaps) ---------------
function makeGroundBase(spanX, spanZ, atmosphere) {
  const g = new THREE.Group();
  g.name = 'ground';
  const pad = 600;

  const geo = new THREE.PlaneGeometry(spanX + pad, spanZ + pad);
  geo.rotateX(-Math.PI / 2);
  const mat = makeWetRoadMaterial();
  const plane = new THREE.Mesh(geo, mat);
  plane.position.y = -0.05;
  plane.receiveShadow = true;
  g.add(plane);

  const reflector = new Reflector(new THREE.PlaneGeometry(spanX + pad, spanZ + pad), {
    textureWidth: 1024, textureHeight: 1024, color: 0x99a3ad, shader: WetReflectorShader,
  });
  reflector.rotateX(-Math.PI / 2);
  reflector.position.y = -0.03;
  reflector.material.transparent = true;
  reflector.material.depthWrite = false;
  reflector.visible = false;
  g.add(reflector);

  g.userData.setWetness = (w) => {
    mat.userData.setWetness(w);
    reflector.material.uniforms.uWet.value = w;
    reflector.visible = w > 0.001;
  };
  return g;
}

// ---- Dachlandschaft: Brüstungen, Technik und klassische NYC-Wassertanks -----
function makeRoofDetails(rooftops) {
  const group = new THREE.Group();
  group.name = 'roof-details';

  const parapets = [];
  const bulkheads = [];
  const hvac = [];
  const tanks = [];
  const tankRoofs = [];
  const tankSupports = [];

  for (const roof of rooftops) {
    if (roof.w < 4 || roof.d < 4) continue;

    const parapetH = roof.h > 90 ? 0.75 : 0.48;
    const parapetT = roof.h > 90 ? 0.32 : 0.24;
    parapets.push(
      { x: roof.x, y: roof.y + parapetH / 2, z: roof.z - roof.d / 2 + parapetT / 2, w: roof.w, h: parapetH, d: parapetT },
      { x: roof.x, y: roof.y + parapetH / 2, z: roof.z + roof.d / 2 - parapetT / 2, w: roof.w, h: parapetH, d: parapetT },
      { x: roof.x - roof.w / 2 + parapetT / 2, y: roof.y + parapetH / 2, z: roof.z, w: parapetT, h: parapetH, d: roof.d },
      { x: roof.x + roof.w / 2 - parapetT / 2, y: roof.y + parapetH / 2, z: roof.z, w: parapetT, h: parapetH, d: roof.d },
    );

    const angle = hashNumber(roof.seed + 11.0) * Math.PI * 2;
    const dirX = Math.cos(angle);
    const dirZ = Math.sin(angle);

    if (roof.bulkhead && roof.w > 7 && roof.d > 7) {
      const bw = Math.min(roof.w * 0.28, 5.5);
      const bd = Math.min(roof.d * 0.30, 5.0);
      const bh = 1.8 + hashNumber(roof.seed + 21.0) * 2.2;
      bulkheads.push({
        x: roof.x + dirX * Math.max(0, roof.w * 0.16),
        y: roof.y + bh / 2,
        z: roof.z + dirZ * Math.max(0, roof.d * 0.14),
        w: bw, h: bh, d: bd,
      });
    }

    for (let i = 0; i < roof.hvacCount; i++) {
      const unitW = 1.5 + hashNumber(roof.seed + 30.0 + i) * 1.8;
      const unitD = 1.2 + hashNumber(roof.seed + 40.0 + i) * 1.5;
      const unitH = 0.8 + hashNumber(roof.seed + 50.0 + i) * 0.75;
      const offsetX = (hashNumber(roof.seed + 60.0 + i) - 0.5) * Math.max(0, roof.w - unitW - 1.8);
      const offsetZ = (hashNumber(roof.seed + 70.0 + i) - 0.5) * Math.max(0, roof.d - unitD - 1.8);
      hvac.push({
        x: roof.x + offsetX,
        y: roof.y + unitH / 2,
        z: roof.z + offsetZ,
        w: unitW, h: unitH, d: unitD,
      });
    }

    if (roof.waterTank && roof.w > 8 && roof.d > 8) {
      const radius = THREE.MathUtils.clamp(Math.min(roof.w, roof.d) * 0.115, 1.2, 2.4);
      const tankH = radius * 1.55;
      const supportH = 2.1 + hashNumber(roof.seed + 81.0) * 1.8;
      const tx = roof.x - dirX * Math.min(roof.w * 0.17, 3.0);
      const tz = roof.z - dirZ * Math.min(roof.d * 0.17, 3.0);
      tanks.push({ x: tx, y: roof.y + supportH + tankH / 2, z: tz, radius, h: tankH });
      tankRoofs.push({ x: tx, y: roof.y + supportH + tankH + radius * 0.38, z: tz, radius: radius * 1.04, h: radius * 0.76 });

      const legOffset = radius * 0.62;
      const legSize = 0.13;
      for (const sx of [-1, 1]) {
        for (const sz of [-1, 1]) {
          tankSupports.push({
            x: tx + sx * legOffset,
            y: roof.y + supportH / 2,
            z: tz + sz * legOffset,
            w: legSize, h: supportH, d: legSize,
          });
        }
      }
    }
  }

  const masonryMat = new THREE.MeshStandardMaterial({
    color: 0x2f3438,
    roughness: 0.9,
    metalness: 0,
    envMapIntensity: 0.12,
  });
  const equipmentMat = new THREE.MeshStandardMaterial({
    color: 0x5e6262,
    roughness: 0.68,
    metalness: 0.34,
    envMapIntensity: 0.45,
  });
  const woodMat = new THREE.MeshStandardMaterial({
    color: 0x4b2f20,
    roughness: 0.88,
    metalness: 0,
    envMapIntensity: 0.08,
  });
  const supportMat = new THREE.MeshStandardMaterial({
    color: 0x26282a,
    roughness: 0.62,
    metalness: 0.48,
  });

  group.add(makeBoxInstances(parapets, masonryMat, true));
  group.add(makeBoxInstances(bulkheads, masonryMat, true));
  group.add(makeBoxInstances(hvac, equipmentMat, true));
  group.add(makeBoxInstances(tankSupports, supportMat, true));

  if (tanks.length) {
    const tankGeo = new THREE.CylinderGeometry(1, 1, 1, 12);
    const tankMesh = new THREE.InstancedMesh(tankGeo, woodMat, tanks.length);
    const coneGeo = new THREE.ConeGeometry(1, 1, 12);
    const coneMesh = new THREE.InstancedMesh(coneGeo, woodMat, tankRoofs.length);
    const matrix = new THREE.Matrix4();
    const position = new THREE.Vector3();
    const scale = new THREE.Vector3();
    const rotation = new THREE.Quaternion();

    for (let i = 0; i < tanks.length; i++) {
      const tank = tanks[i];
      position.set(tank.x, tank.y, tank.z);
      scale.set(tank.radius, tank.h, tank.radius);
      matrix.compose(position, rotation, scale);
      tankMesh.setMatrixAt(i, matrix);

      const cap = tankRoofs[i];
      position.set(cap.x, cap.y, cap.z);
      scale.set(cap.radius, cap.h, cap.radius);
      matrix.compose(position, rotation, scale);
      coneMesh.setMatrixAt(i, matrix);
    }
    tankMesh.instanceMatrix.needsUpdate = true;
    coneMesh.instanceMatrix.needsUpdate = true;
    tankMesh.castShadow = true;
    tankMesh.receiveShadow = true;
    coneMesh.castShadow = true;
    group.add(tankMesh, coneMesh);
  }

  return group;
}

// ---- Erdgeschoss: Eingänge, Markisen und dezente Ladenbeschilderung ----------
function makeGroundFloorDetails(facades, atmosphere) {
  const group = new THREE.Group();
  group.name = 'ground-floor-details';
  const doors = [];
  const doorFrames = [];
  const awningsByColor = [[], [], []];
  const signsByColor = [[], [], []];

  for (const facade of facades) {
    if (facade.width < 5) continue;
    const yaw = facade.axis === 'x' ? Math.PI / 2 : 0;
    const nx = facade.axis === 'x' ? facade.side : 0;
    const nz = facade.axis === 'z' ? facade.side : 0;
    const tangentX = facade.axis === 'z' ? 1 : 0;
    const tangentZ = facade.axis === 'x' ? 1 : 0;
    const bayCount = Math.max(1, Math.floor(facade.width / 6));
    const bayWidth = facade.width / bayCount;
    const colorIndex = Math.floor(hashNumber(facade.seed + facade.side * 17.0) * 3);

    for (let i = 0; i < bayCount; i++) {
      const along = -facade.width / 2 + bayWidth * (i + 0.5);
      const bx = facade.x + tangentX * along;
      const bz = facade.z + tangentZ * along;
      const shopSeed = facade.seed + i * 13.7 + facade.side * 31.0;

      if (hashNumber(shopSeed) < 0.68) {
        const depth = 0.9;
        awningsByColor[colorIndex].push({
          x: bx + nx * (depth / 2 + 0.04),
          y: 3.22,
          z: bz + nz * (depth / 2 + 0.04),
          w: Math.max(2.4, bayWidth * 0.76),
          h: 0.16,
          d: depth,
          yaw,
        });
      }

      if (hashNumber(shopSeed + 5.0) < 0.72) {
        signsByColor[colorIndex].push({
          x: bx + nx * 0.09,
          y: 3.82,
          z: bz + nz * 0.09,
          w: Math.max(2.2, bayWidth * 0.7),
          h: 0.42,
          d: 0.11,
          yaw,
        });
      }
    }

    // Ein klar lesbarer Haupteingang pro Straßenfassade.
    const entranceAlong = (hashNumber(facade.seed + facade.side * 71.0) - 0.5)
      * Math.max(0, facade.width - 3.4);
    const ex = facade.x + tangentX * entranceAlong + nx * 0.07;
    const ez = facade.z + tangentZ * entranceAlong + nz * 0.07;
    doors.push({ x: ex, y: 1.42, z: ez, w: 1.55, h: 2.72, d: 0.1, yaw });
    doorFrames.push(
      { x: ex + tangentX * 0.91, y: 1.42, z: ez + tangentZ * 0.91, w: 0.16, h: 2.95, d: 0.16, yaw },
      { x: ex - tangentX * 0.91, y: 1.42, z: ez - tangentZ * 0.91, w: 0.16, h: 2.95, d: 0.16, yaw },
      { x: ex, y: 2.87, z: ez, w: 1.98, h: 0.15, d: 0.16, yaw },
    );
  }

  const doorMat = new THREE.MeshStandardMaterial({
    color: 0x0b1620,
    roughness: 0.16,
    metalness: 0,
    envMapIntensity: 1.15,
  });
  const frameMat = new THREE.MeshStandardMaterial({
    color: 0x272c30,
    roughness: 0.42,
    metalness: 0.58,
  });
  group.add(makeBoxInstances(doors, doorMat));
  group.add(makeBoxInstances(doorFrames, frameMat, true));

  const awningColors = [0x304b43, 0x612f32, 0x3f465d];
  const signColors = [0xffc26d, 0x6dd9ff, 0xff7f72];
  const nightMaterials = [];
  for (let i = 0; i < 3; i++) {
    const awningMat = new THREE.MeshStandardMaterial({
      color: awningColors[i],
      roughness: 0.82,
      metalness: 0,
    });
    const signMat = new THREE.MeshStandardMaterial({
      color: signColors[i],
      emissive: signColors[i],
      emissiveIntensity: 0.04 + (atmosphere?.cityLightFactor ?? 1) * 1.15,
      roughness: 0.42,
      metalness: 0,
      toneMapped: false,
    });
    signMat.userData.nightScale = 1.15;
    group.add(makeBoxInstances(awningsByColor[i], awningMat, true));
    group.add(makeBoxInstances(signsByColor[i], signMat));
    nightMaterials.push(signMat);
  }
  group.userData.nightMaterials = nightMaterials;
  return group;
}

function makeBoxInstances(items, material, castShadow = false) {
  const geometry = new THREE.BoxGeometry(1, 1, 1);
  const mesh = new THREE.InstancedMesh(geometry, material, items.length);
  const matrix = new THREE.Matrix4();
  const position = new THREE.Vector3();
  const scale = new THREE.Vector3();
  const rotation = new THREE.Quaternion();
  const up = new THREE.Vector3(0, 1, 0);
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    position.set(item.x, item.y, item.z);
    scale.set(item.w, item.h, item.d);
    rotation.setFromAxisAngle(up, item.yaw ?? 0);
    matrix.compose(position, rotation, scale);
    mesh.setMatrixAt(i, matrix);
  }
  mesh.instanceMatrix.needsUpdate = true;
  mesh.castShadow = castShadow;
  mesh.receiveShadow = true;
  return mesh;
}

function makeCylinderInstances(items, material, radialSegments = 8, castShadow = false) {
  const geometry = new THREE.CylinderGeometry(1, 1, 1, radialSegments);
  const mesh = new THREE.InstancedMesh(geometry, material, items.length);
  const matrix = new THREE.Matrix4();
  const position = new THREE.Vector3();
  const scale = new THREE.Vector3();
  const rotation = new THREE.Quaternion();
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    position.set(item.x, item.y, item.z);
    scale.set(item.radius, item.h, item.radius);
    matrix.compose(position, rotation, scale);
    mesh.setMatrixAt(i, matrix);
  }
  mesh.instanceMatrix.needsUpdate = true;
  mesh.castShadow = castShadow;
  mesh.receiveShadow = true;
  return mesh;
}

function makeSphereInstances(items, material, castShadow = false) {
  const geometry = new THREE.IcosahedronGeometry(1, 1);
  const mesh = new THREE.InstancedMesh(geometry, material, items.length);
  const matrix = new THREE.Matrix4();
  const position = new THREE.Vector3();
  const scale = new THREE.Vector3();
  const rotation = new THREE.Quaternion();
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    position.set(item.x, item.y, item.z);
    scale.setScalar(item.radius);
    matrix.compose(position, rotation, scale);
    mesh.setMatrixAt(i, matrix);
  }
  mesh.instanceMatrix.needsUpdate = true;
  mesh.castShadow = castShadow;
  mesh.receiveShadow = true;
  return mesh;
}

function hashNumber(value) {
  const x = Math.sin(value * 12.9898) * 43758.5453;
  return x - Math.floor(x);
}

// ---- Straßen, Gehwege und Markierungen --------------------------------------
function makeGround(spanX, spanZ, x0, z0, streetsX, streetsZ, atmosphere) {
  const g = new THREE.Group();
  const pad = 600;
  const geo = new THREE.PlaneGeometry(spanX + pad, spanZ + pad);
  geo.rotateX(-Math.PI / 2);
  const DRY_ROAD = new THREE.Color(0x11151a);
  const WET_ROAD = new THREE.Color(0x070a0e); // nasser Asphalt ist dunkler
  const mat = new THREE.MeshStandardMaterial({
    color: DRY_ROAD.clone(),
    roughness: 0.72,
    metalness: 0,
    envMapIntensity: 0.35,
  });
  // Nässe: pro-Pixel-Pfützen variieren die Rauheit, damit die nasse Straße
  // streifig spiegelt statt wie eine Eisfläche zu wirken.
  mat.userData.wet = { value: 0 };
  mat.onBeforeCompile = (sh) => {
    sh.uniforms.uWet = mat.userData.wet;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n         varying vec2 vWorldXZ;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\n         vWorldXZ = (modelMatrix * vec4(transformed, 1.0)).xz;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\n         varying vec2 vWorldXZ;\n         uniform float uWet;')
      .replace(
        '#include <roughnessmap_fragment>',
        `#include <roughnessmap_fragment>
         {
           vec2 p = vWorldXZ * 0.03;
           float n = sin(p.x) * sin(p.y) + 0.6 * sin(p.x * 2.3 + 1.7) * sin(p.y * 1.9 - 0.6);
           float puddle = smoothstep(-0.1, 0.7, n);
           float wetRough = mix(0.42, 0.05, puddle); // Pfützen fast spiegelnd
           roughnessFactor = mix(roughnessFactor, wetRough, uWet);
         }`
      );
  };
  const plane = new THREE.Mesh(geo, mat);
  plane.position.y = -0.05;
  plane.receiveShadow = true;
  g.add(plane);

  // Planare Reflexion knapp über dem Asphalt: spiegelt die echte Szene, damit
  // die nasse Straße nachts die Lichter zurückwirft (Env-Map ist nachts schwarz).
  const reflectorGeo = new THREE.PlaneGeometry(spanX + pad, spanZ + pad);
  const reflector = new Reflector(reflectorGeo, {
    textureWidth: 1024,
    textureHeight: 1024,
    color: 0x99a3ad, // < weiß: dämpft/tönt die Spiegelung zu nassem Asphalt
    shader: WetReflectorShader,
  });
  reflector.rotateX(-Math.PI / 2);
  reflector.position.y = -0.03; // knapp über dem Asphalt (−0.05), unter den Gehwegen
  reflector.material.transparent = true;
  reflector.material.depthWrite = false;
  reflector.visible = false; // nur bei Nässe rendern (sonst voller Extra-Render)
  g.add(reflector);

  // Nässe-Regler: Material-Parameter überblenden + planare Reflexion zuschalten.
  g.userData.setWetness = (w) => {
    mat.userData.wet.value = w;
    mat.color.copy(DRY_ROAD).lerp(WET_ROAD, w);
    mat.roughness = THREE.MathUtils.lerp(0.72, 0.18, w);
    mat.envMapIntensity = THREE.MathUtils.lerp(0.35, 1.3, w);
    reflector.material.uniforms.uWet.value = w;
    reflector.visible = w > 0.001;
  };

  // Jeder Block liegt auf einem leicht erhöhten Gehweg. Die Gebäude schneiden
  // in die Platte ein; sichtbar bleiben Bürgersteige, Höfe und freie Parzellen.
  const sidewalkGeo = new THREE.BoxGeometry(1, 1, 1);
  const sidewalkMat = new THREE.MeshStandardMaterial({
    color: 0x696b6d,
    roughness: 0.92,
    metalness: 0,
    envMapIntensity: 0.15,
  });
  const sidewalkCount = CFG.cols * CFG.rows;
  const sidewalks = new THREE.InstancedMesh(sidewalkGeo, sidewalkMat, sidewalkCount);
  sidewalks.receiveShadow = true;
  const matrix = new THREE.Matrix4();
  const position = new THREE.Vector3();
  const scale = new THREE.Vector3(CFG.blockW, 0.18, CFG.blockD);
  const rotation = new THREE.Quaternion();
  let index = 0;
  for (let c = 0; c < CFG.cols; c++) {
    for (let r = 0; r < CFG.rows; r++) {
      position.set(
        x0 + c * (CFG.blockW + CFG.avenue) + CFG.blockW / 2,
        0.04,
        z0 + r * (CFG.blockD + CFG.street) + CFG.blockD / 2,
      );
      matrix.compose(position, rotation, scale);
      sidewalks.setMatrixAt(index++, matrix);
    }
  }
  sidewalks.instanceMatrix.needsUpdate = true;
  g.add(sidewalks);

  // Unterbrochene Mittellinien geben den Straßen bereits aus großer Höhe eine
  // lesbare Breite und verhindern den Eindruck einer einzigen schwarzen Fläche.
  const markings = [];
  const dashLength = 6;
  const dashGap = 7;
  for (const x of streetsX) {
    for (let z = -spanZ / 2; z < spanZ / 2; z += dashLength + dashGap) {
      markings.push({ x, z: z + dashLength / 2, w: 0.22, d: dashLength });
    }
  }
  for (const z of streetsZ) {
    for (let x = -spanX / 2; x < spanX / 2; x += dashLength + dashGap) {
      markings.push({ x: x + dashLength / 2, z, w: dashLength, d: 0.22 });
    }
  }

  const markingGeo = new THREE.BoxGeometry(1, 1, 1);
  const markingMat = new THREE.MeshStandardMaterial({
    color: 0xd2b866,
    roughness: 0.8,
    metalness: 0,
  });
  const markingMesh = new THREE.InstancedMesh(markingGeo, markingMat, markings.length);
  for (let i = 0; i < markings.length; i++) {
    const mark = markings[i];
    position.set(mark.x, 0.025, mark.z);
    scale.set(mark.w, 0.025, mark.d);
    matrix.compose(position, rotation, scale);
    markingMesh.setMatrixAt(i, matrix);
  }
  markingMesh.instanceMatrix.needsUpdate = true;
  markingMesh.receiveShadow = true;
  g.add(markingMesh);

  // Zebra-Streifen an jeder Kreuzung. Die beiden Straßentypen erhalten
  // passende Streifenrichtung und -breite.
  const crosswalks = [];
  for (const x of streetsX) {
    for (const z of streetsZ) {
      for (const side of [-1, 1]) {
        const crossZ = z + side * (CFG.street / 2 - 1.8);
        for (let dx = -CFG.avenue / 2 + 1.4; dx < CFG.avenue / 2 - 0.8; dx += 2.15) {
          crosswalks.push({ x: x + dx, y: 0.038, z: crossZ, w: 1.05, h: 0.028, d: 2.6 });
        }

        const crossX = x + side * (CFG.avenue / 2 - 2.0);
        for (let dz = -CFG.street / 2 + 1.1; dz < CFG.street / 2 - 0.7; dz += 2.0) {
          crosswalks.push({ x: crossX, y: 0.038, z: z + dz, w: 3.0, h: 0.028, d: 0.92 });
        }
      }
    }
  }
  const crosswalkMat = new THREE.MeshStandardMaterial({
    color: 0xc7c9c4,
    roughness: 0.86,
    metalness: 0,
  });
  g.add(makeBoxInstances(crosswalks, crosswalkMat));

  const streetFurniture = makeStreetFurniture(streetsX, streetsZ, atmosphere, x0, z0);
  g.userData.streetLightMaterial = streetFurniture.userData.streetLightMaterial;
  g.add(streetFurniture);

  return g;
}

function makeStreetFurniture(streetsX, streetsZ, atmosphere, x0, z0) {
  const group = new THREE.Group();
  group.name = 'street-furniture';
  const poles = [];
  const arms = [];
  const lamps = [];
  const hydrants = [];
  const bins = [];
  const benchSeats = [];
  const benchLegs = [];
  const trunks = [];
  const crowns = [];
  const poleH = 5.2;
  const poleRadius = 0.075;
  const cornerX = CFG.avenue / 2 + 0.95;
  const cornerZ = CFG.street / 2 + 0.95;

  for (const x of streetsX) {
    for (const z of streetsZ) {
      for (const sx of [-1, 1]) {
        for (const sz of [-1, 1]) {
          const px = x + sx * cornerX;
          const pz = z + sz * cornerZ;
          const armLength = 1.15;
          const yaw = sx > 0 ? -Math.PI / 2 : Math.PI / 2;
          poles.push({ x: px, y: poleH / 2 + 0.16, z: pz, radius: poleRadius, h: poleH });
          arms.push({
            x: px - sx * armLength / 2,
            y: poleH + 0.12,
            z: pz,
            w: 0.09,
            h: 0.09,
            d: armLength,
            yaw,
          });
          lamps.push({
            x: px - sx * armLength,
            y: poleH + 0.04,
            z: pz,
            w: 0.34,
            h: 0.18,
            d: 0.62,
            yaw,
          });

          const cornerSeed = x * 0.37 + z * 0.61 + sx * 13.0 + sz * 29.0;
          if (hashNumber(cornerSeed) < 0.34) {
            hydrants.push({
              x: px + sx * 0.58,
              y: 0.48,
              z: pz + sz * 0.28,
              radius: 0.13,
              h: 0.82,
            });
          }
          if (hashNumber(cornerSeed + 7.0) < 0.28) {
            bins.push({
              x: px - sx * 0.5,
              y: 0.55,
              z: pz + sz * 0.48,
              w: 0.58,
              h: 0.94,
              d: 0.58,
            });
          }
        }
      }
    }
  }

  // Bäume und einzelne Bänke entlang der langen Blockseiten geben den
  // Straßenschluchten Maßstab, ohne die Fahrbahn oder Eingänge zu blockieren.
  for (let c = 0; c < CFG.cols; c++) {
    for (let r = 0; r < CFG.rows; r++) {
      const bx = x0 + c * (CFG.blockW + CFG.avenue) + CFG.blockW / 2;
      const bz = z0 + r * (CFG.blockD + CFG.street) + CFG.blockD / 2;
      const blockSeed = c * 97.0 + r * 41.0;
      for (const side of [-1, 1]) {
        for (const offset of [-0.28, 0.28]) {
          const treeSeed = blockSeed + side * 17.0 + offset * 53.0;
          if (hashNumber(treeSeed) < 0.44) {
            const tx = bx + CFG.blockW * offset;
            const tz = bz + side * (CFG.blockD / 2 - 0.62);
            const trunkH = 2.6 + hashNumber(treeSeed + 2.0) * 0.8;
            const crownRadius = 0.72 + hashNumber(treeSeed + 3.0) * 0.36;
            trunks.push({ x: tx, y: trunkH / 2 + 0.16, z: tz, radius: 0.11, h: trunkH });
            crowns.push({ x: tx, y: trunkH + crownRadius * 0.72, z: tz, radius: crownRadius });
          }
        }

        if (hashNumber(blockSeed + side * 83.0) < 0.22) {
          const benchZ = bz + side * (CFG.blockD / 2 - 0.48);
          const yaw = side > 0 ? Math.PI : 0;
          benchSeats.push(
            { x: bx, y: 0.58, z: benchZ, w: 2.0, h: 0.12, d: 0.48, yaw },
            { x: bx, y: 1.02, z: benchZ - side * 0.19, w: 2.0, h: 0.68, d: 0.1, yaw },
          );
          benchLegs.push(
            { x: bx - 0.72, y: 0.3, z: benchZ, w: 0.1, h: 0.55, d: 0.38, yaw },
            { x: bx + 0.72, y: 0.3, z: benchZ, w: 0.1, h: 0.55, d: 0.38, yaw },
          );
        }
      }
    }
  }

  const poleMat = new THREE.MeshStandardMaterial({
    color: 0x24282a,
    roughness: 0.58,
    metalness: 0.62,
    envMapIntensity: 0.3,
  });
  const lampMat = new THREE.MeshStandardMaterial({
    color: 0x4a4033,
    emissive: 0xffd18c,
    emissiveIntensity: 0.1 + (atmosphere?.cityLightFactor ?? 1) * 2.4,
    roughness: 0.34,
    metalness: 0,
    toneMapped: false,
  });
  lampMat.userData.nightBase = 0.1;
  lampMat.userData.nightScale = 2.4;

  const poleGeo = new THREE.CylinderGeometry(1, 1, 1, 8);
  const poleMesh = new THREE.InstancedMesh(poleGeo, poleMat, poles.length);
  const matrix = new THREE.Matrix4();
  const position = new THREE.Vector3();
  const scale = new THREE.Vector3();
  const rotation = new THREE.Quaternion();
  for (let i = 0; i < poles.length; i++) {
    const pole = poles[i];
    position.set(pole.x, pole.y, pole.z);
    scale.set(pole.radius, pole.h, pole.radius);
    matrix.compose(position, rotation, scale);
    poleMesh.setMatrixAt(i, matrix);
  }
  poleMesh.instanceMatrix.needsUpdate = true;
  poleMesh.castShadow = true;
  group.add(poleMesh);
  group.add(makeBoxInstances(arms, poleMat, true));
  group.add(makeBoxInstances(lamps, lampMat));

  const hydrantMat = new THREE.MeshStandardMaterial({
    color: 0x8d2f25,
    roughness: 0.58,
    metalness: 0.34,
  });
  const binMat = new THREE.MeshStandardMaterial({
    color: 0x24322d,
    roughness: 0.74,
    metalness: 0.28,
  });
  const benchMat = new THREE.MeshStandardMaterial({
    color: 0x392a20,
    roughness: 0.84,
    metalness: 0,
  });
  const treeTrunkMat = new THREE.MeshStandardMaterial({
    color: 0x3f2a1c,
    roughness: 0.96,
    metalness: 0,
  });
  const leavesMat = new THREE.MeshStandardMaterial({
    color: 0x28452f,
    roughness: 0.92,
    metalness: 0,
    envMapIntensity: 0.08,
  });
  group.add(makeCylinderInstances(hydrants, hydrantMat, 10, true));
  group.add(makeBoxInstances(bins, binMat, true));
  group.add(makeBoxInstances(benchSeats, benchMat, true));
  group.add(makeBoxInstances(benchLegs, poleMat, true));
  group.add(makeCylinderInstances(trunks, treeTrunkMat, 7, true));
  group.add(makeSphereInstances(crowns, leavesMat, true));
  group.userData.streetLightMaterial = lampMat;
  return group;
}

// ---- Gebäude-Material mit prozeduralem Fenster-Shader ------------------------
function makeBuildingMaterial(atmosphere) {
  const mat = new THREE.MeshStandardMaterial({
    color: 0xffffff,
    roughness: 0.78,
    metalness: 0,
    envMapIntensity: 0.65,
  });

  mat.userData.uniforms = {
    uLightFactor: { value: atmosphere?.cityLightFactor ?? 1.0 },
    uMetricOn: { value: 0 }, // 1 = Fensterlicht folgt der Metrik (aAct) statt Zufall
  };

  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uLightFactor = mat.userData.uniforms.uLightFactor;
    shader.uniforms.uMetricOn = mat.userData.uniforms.uMetricOn;

    // --- Vertex: metrische Wand-Koordinaten an den Fragment-Shader reichen ---
    shader.vertexShader = shader.vertexShader
      .replace(
        '#include <common>',
        `#include <common>
         attribute vec3 aDim;
         attribute float aSeed;
         attribute float aStyle;
         attribute float aLayout;
         attribute float aBaseY;
         attribute float aAct;
         varying vec3 vMetric;
         flat varying vec3 vDim;
         flat varying vec3 vONormal;
         flat varying float vSeed;
         flat varying float vStyle;
         flat varying float vLayout;
         flat varying float vBaseY;
         flat varying float vHeight;
         flat varying float vAct;`
      )
      .replace(
        '#include <begin_vertex>',
        `#include <begin_vertex>
         vMetric = position * aDim;
         vDim = aDim;
         vONormal = normal;
         vSeed = aSeed;
         vStyle = aStyle;
         vLayout = aLayout;
         vBaseY = aBaseY;
         vHeight = aDim.y;
         vAct = aAct;`
      );

    // --- Fragment: Fassadenlayouts, Raumbelegung und Glas ---------------------
    shader.fragmentShader = shader.fragmentShader
      .replace(
        '#include <common>',
        `#include <common>
         uniform float uLightFactor;
         uniform float uMetricOn;
         varying vec3 vMetric;
         flat varying vec3 vDim;
         flat varying vec3 vONormal;
         flat varying float vSeed;
         flat varying float vStyle;
         flat varying float vLayout;
         flat varying float vBaseY;
         flat varying float vHeight;
         flat varying float vAct;

         float hash11(float p){ p=fract(p*0.1031); p*=p+33.33; p*=p+p; return fract(p); }
         float hash21(vec2 p){ vec3 p3=fract(vec3(p.xyx)*0.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }
         float aaRect(vec2 p, vec2 lo, vec2 hi, vec2 pixelWidth) {
           vec2 aa = max(pixelWidth * 0.75, vec2(0.0015));
           vec2 enter = smoothstep(lo - aa, lo + aa, p);
           vec2 leave = 1.0 - smoothstep(hi - aa, hi + aa, p);
           return enter.x * enter.y * leave.x * leave.y;
         }

         // Globale, in späteren Chunks genutzte Werte
         float gGlass = 0.0;
         float gGlassRoughness = 0.12;
         vec3  gEmissive = vec3(0.0);`
      )
      .replace(
        '#include <map_fragment>',
        `#include <map_fragment>
        {
          vec3 n = normalize(vONormal);
          bool isWall = abs(n.y) < 0.5;

          float style = floor(vStyle + 0.5);
          float facadeLayout = floor(vLayout + 0.5);
          float ti = hash11(vSeed * 1.13 + 2.0);
          vec3 glassCol = mix(vec3(0.055, 0.085, 0.12), vec3(0.16, 0.21, 0.24), ti);
          vec3 stoneCol = mix(vec3(0.34, 0.31, 0.27), vec3(0.58, 0.55, 0.48), ti);
          vec3 brickCol = mix(vec3(0.20, 0.095, 0.065), vec3(0.38, 0.19, 0.12), ti);
          vec3 concreteCol = mix(vec3(0.25, 0.27, 0.28), vec3(0.43, 0.44, 0.42), ti);
          vec3 wallCol = style < 0.5 ? glassCol
            : style < 1.5 ? stoneCol
              : style < 2.5 ? brickCol
                : concreteCol;

          vec3 col = wallCol;

          if (vSeed < 0.0) {
            // Antenne / Dachaufbau: dunkles Metall, an der Spitze rotes Blinklicht
            col = vec3(0.05, 0.055, 0.07);
            if (vMetric.y > vHeight - 1.5) { gEmissive += vec3(1.0,0.05,0.03) * 3.0 * uLightFactor; }
          } else if (isWall) {
            // Das Raster wird auf die Fassadenbreite eingepasst. Dadurch enden
            // beide Gebäudekanten mit gleich breiten Pfeilern statt mit
            // abgeschnittenen Fenstern.
            float hcoord = abs(n.x) > abs(n.z) ? vMetric.z : vMetric.x;
            float faceWidth = abs(n.x) > abs(n.z) ? vDim.z : vDim.x;
            float globalY = vMetric.y + vBaseY;
            float faceOff = n.x * 7.13 + n.z * 3.71;

            float targetFW = facadeLayout < 0.5 ? 2.25
              : facadeLayout < 1.5 ? 2.9
                : facadeLayout < 2.5 ? 2.75
                  : 3.4;
            float FH = facadeLayout < 0.5 ? 3.55
              : facadeLayout < 1.5 ? 4.05
                : facadeLayout < 2.5 ? 3.25
                  : 3.7;
            float bayCount = max(1.0, floor(faceWidth / targetFW + 0.5));
            float bayWidth = faceWidth / bayCount;
            float scaledU = clamp((hcoord + faceWidth * 0.5) / bayWidth, 0.0, bayCount - 0.0001);
            float cx = floor(scaledU);
            float cy = floor(globalY / FH);
            vec2 gridCoord = vec2(scaledU, globalY / FH);
            vec2 cell = fract(gridCoord);
            vec2 gridPixel = max(fwidth(gridCoord), vec2(0.001));
            float pixelFootprint = max(gridPixel.x, gridPixel.y);
            float fineDetail = 1.0 - smoothstep(0.025, 0.14, pixelFootprint);
            float farLod = smoothstep(0.14, 0.38, pixelFootprint);

            // Vier echte Fensteranordnungen:
            // 0 Curtain Wall, 1 vertikale Art-déco-Achsen,
            // 2 Lochfassade, 3 horizontale Fensterbänder.
            float left = 0.08, right = 0.92, bottom = 0.13, top = 0.86;
            if (facadeLayout > 0.5 && facadeLayout < 1.5) {
              float majorAxis = step(mod(cx, 4.0), 0.25);
              left = mix(0.18, 0.30, majorAxis);
              right = mix(0.82, 0.70, majorAxis);
              bottom = 0.13;
              top = 0.90;
            } else if (facadeLayout > 1.5 && facadeLayout < 2.5) {
              float stairBay = step(mod(cx, 3.0), 0.25);
              left = mix(0.21, 0.31, stairBay);
              right = mix(0.79, 0.69, stairBay);
              bottom = 0.27;
              top = 0.80;
            } else if (facadeLayout > 2.5) {
              left = 0.045;
              right = 0.955;
              bottom = 0.30;
              top = 0.70;
            }

            float regularWindowMask = aaRect(
              cell,
              vec2(left, bottom),
              vec2(right, top),
              gridPixel
            );
            float averageCoverage = (right - left) * (top - bottom);
            regularWindowMask = mix(regularWindowMask, averageCoverage, farLod);
            bool upperBody = globalY > 4.6 && (vHeight - vMetric.y) > 1.25;

            // Einige Backstein-/Betonbauten besitzen realistische Brandwände.
            bool blindFacade = style > 1.5
              && hash11(vSeed * 0.41 + faceOff * 2.7) < 0.11;

            // Nur das unterste Segment erhält großformatige Schaufenster.
            bool groundFloor = vBaseY < 0.1 && globalY < 4.6;
            float shopCount = max(1.0, floor(faceWidth / 5.2 + 0.5));
            float shopWidth = faceWidth / shopCount;
            float shopU = clamp((hcoord + faceWidth * 0.5) / shopWidth, 0.0, shopCount - 0.0001);
            vec2 shopGrid = vec2(shopU, globalY / 4.6);
            vec2 shopCell = fract(shopGrid);
            vec2 shopPixel = max(fwidth(shopGrid), vec2(0.001));
            float shopMask = groundFloor
              ? aaRect(shopCell, vec2(0.07, 0.12), vec2(0.93, 0.84), shopPixel)
              : 0.0;
            float regularMask = upperBody && !blindFacade ? regularWindowMask : 0.0;
            float glassMask = max(shopMask, regularMask);
            bool isShopGlass = shopMask > regularMask;

            // Rahmenfarbe wird ebenfalls weich berechnet. Dadurch wechseln
            // Fassadenkanten bei Kamerabewegung nicht mehr binär pro Pixel.
            float verticalPier = smoothstep(0.0, 0.11 + gridPixel.x, cell.x)
              * (1.0 - smoothstep(0.89 - gridPixel.x, 1.0, cell.x));
            float floorBand = facadeLayout < 0.5
              ? mix(0.68, 1.0, smoothstep(0.0, 0.19 + gridPixel.y, cell.y))
              : facadeLayout > 2.5
                ? mix(0.72, 1.0, smoothstep(0.0, 0.16 + gridPixel.y, abs(cell.y - 0.5)))
                : 1.0;
            float mortar = style > 1.5
              ? mix(1.0, 0.96 + 0.04 * smoothstep(
                  0.05,
                  0.05 + max(fwidth(globalY * (style < 2.5 ? 2.1 : 0.8)), 0.01),
                  fract(globalY * (style < 2.5 ? 2.1 : 0.8))
                ), fineDetail)
              : 1.0;
            vec3 frameCol = wallCol * (0.76 + 0.24 * verticalPier) * floorBand * mortar;
            col = frameCol;

            if (glassMask > 0.001) {
              gGlass = glassMask;
              vec2 activeCell = isShopGlass ? shopCell : cell;
              vec2 activePixel = isShopGlass ? shopPixel : gridPixel;
              float activeLeft = isShopGlass ? 0.07 : left;
              float activeRight = isShopGlass ? 0.93 : right;
              float activeBottom = isShopGlass ? 0.12 : bottom;
              float activeTop = isShopGlass ? 0.84 : top;
              float wx = clamp((activeCell.x - activeLeft) / (activeRight - activeLeft), 0.0, 1.0);
              float wy = clamp((activeCell.y - activeBottom) / (activeTop - activeBottom), 0.0, 1.0);

              // Beleuchtung wird raumweise vergeben. Benachbarte Scheiben
              // gehören damit sichtbar zu einer gemeinsamen Nutzungseinheit.
              float roomSpanX = facadeLayout < 0.5 ? 2.0 : facadeLayout > 2.5 ? 3.0 : 2.0;
              float roomSpanY = hash11(vSeed * 0.73 + 8.0) > 0.72 ? 2.0 : 1.0;
              float roomX = isShopGlass ? floor(shopU) : floor(cx / roomSpanX);
              float roomY = isShopGlass ? 0.0 : floor(cy / roomSpanY);
              float roomRnd = hash21(vec2(roomX, roomY) + vSeed * 11.7 + faceOff);
              // Metrik-Modus: Die Fensterlicht-Dichte folgt der Gebäude-Aktivität
              // (aAct 0..1) statt dem Zufall — Hotspots leuchten wie Türme mit
              // Nachtschicht, tote Klassen bleiben dunkel.
              float baseProb = isShopGlass ? 0.34 : 0.22;
              float litProb = mix(baseProb, 0.03 + 0.95 * vAct, uMetricOn) * uLightFactor;
              bool lit = roomRnd < litProb;
              float litAmount = mix(lit ? 1.0 : 0.0, litProb, farLod);

              float warmSel = mix(
                hash11(roomX * 1.7 + roomY * 3.1 + vSeed * 5.0),
                hash11(vSeed * 5.0 + faceOff),
                farLod
              );
              vec3 warm = vec3(1.0, 0.74, 0.42);
              vec3 cool = vec3(0.55, 0.82, 1.0);
              vec3 wcol = mix(warm, cool, step(0.82, warmSel));
              float bri = 0.55 + 0.65 * mix(
                hash11(roomX * 9.1 + roomY * 4.3 + vSeed * 2.0),
                hash11(vSeed * 2.0 + faceOff * 0.7),
                farLod
              );
              // Im Metrik-Modus leuchten aktive Gebäude auch heller, nicht nur dichter.
              bri *= mix(1.0, 0.6 + 1.0 * vAct, uMetricOn);

              // Dunkle Scheiben spiegeln den Himmel bei flachem Blickwinkel
              // stärker. MeshStandard liefert die physikalische Reflexion;
              // dieser Fresnel-Anteil macht sie im Stadtmaßstab lesbarer.
              float viewDot = clamp(abs(dot(normalize(vNormal), normalize(vViewPosition))), 0.0, 1.0);
              float fresnel = pow(1.0 - viewDot, 3.0);
              float paneRnd = mix(
                hash21(vec2(cx, cy) + vSeed * 2.3 + faceOff),
                hash11(vSeed * 2.3 + faceOff),
                farLod
              );
              vec3 paneTint = mix(vec3(0.012, 0.024, 0.038), glassCol * 0.42, 0.55 + 0.3 * paneRnd);
              vec3 paneCol = mix(paneTint, vec3(0.22, 0.34, 0.45), fresnel * 0.62);
              paneCol = mix(paneCol, glassCol * 0.24, farLod * 0.82);

              // Ein dunkler Rand simuliert die zurückgesetzte Scheibe.
              float edgeDistance = min(min(wx, 1.0 - wx), min(wy, 1.0 - wy));
              float reveal = smoothstep(
                0.0,
                max(0.105, max(activePixel.x, activePixel.y) * 1.5),
                edgeDistance
              );
              reveal = mix(1.0, reveal, fineDetail);
              paneCol *= mix(0.38, 1.0, reveal);

              // Jalousien und Vorhänge brechen die rein zufällige Pixelwirkung.
              float coverRnd = hash21(vec2(cx * 1.9, cy * 2.7) + vSeed + faceOff);
              float blindDrop = coverRnd > 0.66 ? mix(0.18, 0.72, hash11(coverRnd * 31.0)) : 0.0;
              float blindMask = blindDrop > 0.0
                ? smoothstep(
                    1.0 - blindDrop - activePixel.y,
                    1.0 - blindDrop + activePixel.y,
                    wy
                  ) * fineDetail
                : 0.0;
              float slatCoord = wy * 18.0;
              float slatDistance = abs(fract(slatCoord) - 0.5);
              float slatLine = 1.0 - smoothstep(
                0.055,
                0.055 + max(fwidth(slatCoord), 0.012),
                slatDistance
              );
              float slats = 1.0 - slatLine * 0.2 * fineDetail;
              vec3 blindCol = mix(vec3(0.16, 0.15, 0.13), vec3(0.42, 0.39, 0.33), paneRnd);
              paneCol = mix(paneCol, blindCol * slats, blindMask * 0.82);

              // Vorhang auskommentiert (störte als "Random Vorhang").
              float curtainMask = 0.0;
              // float curtainRnd = hash11(coverRnd * 17.0 + 4.0);
              // curtainMask = curtainRnd > 0.84
              //   ? max(1.0 - smoothstep(0.04, 0.28, wx), smoothstep(0.72, 0.96, wx))
              //     * fineDetail
              //   : 0.0;
              // paneCol = mix(paneCol, vec3(0.24, 0.19, 0.17), curtainMask * 0.74);

              // Leichte Schmutz-/Kondensationszone am unteren Scheibenrand.
              float dirt = (0.025 + 0.055 * paneRnd) * pow(1.0 - wy, 5.0) * fineDetail;
              paneCol = mix(paneCol, vec3(0.19, 0.21, 0.21), dirt);
              gGlassRoughness = mix(0.07, 0.20, 0.25 + 0.75 * paneRnd)
                + blindMask * 0.05;

              if (litAmount > 0.001) {
                float uncovered = 1.0 - blindMask * 0.82 - curtainMask * 0.55;
                // Glow-Boost: hebt beleuchtete Scheiben über die Bloom-Schwelle,
                // damit sie auch aus Distanz (Subpixel-Mittelung) glühen.
                gEmissive += wcol * bri * (isShopGlass ? 0.78 : 1.0) * 1.5
                  * uLightFactor * max(0.08, uncovered) * glassMask * litAmount;
                paneCol = mix(
                  paneCol,
                  wcol * 0.11,
                  0.42 * max(0.15, uncovered) * litAmount
                );
              }
              col = mix(frameCol, paneCol, glassMask);
            }
          } else {
            // Dach: dunkler, leicht aufgeraut
            col = wallCol * 0.42 + vec3(0.01);
          }

          diffuseColor.rgb = col;
        }`
      )
      .replace(
        '#include <roughnessmap_fragment>',
        `#include <roughnessmap_fragment>
         roughnessFactor = gGlass > 0.5 ? gGlassRoughness : 0.78;`
      )
      .replace(
        '#include <metalnessmap_fragment>',
        `#include <metalnessmap_fragment>
         metalnessFactor = 0.0;`
      )
      .replace(
        '#include <emissivemap_fragment>',
        `#include <emissivemap_fragment>
         totalEmissiveRadiance += gEmissive;`
      );
  };

  return mat;
}
