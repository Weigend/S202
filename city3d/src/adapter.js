// Structure202 · City3D — data-driven, HIERARCHICAL layout adapter.
//
// Maps a real analysed architecture (CityModel JSON from CityModelExporter) onto
// City3JS's building/roof/street arrays, PLUS nested package platforms. It mirrors
// the 2D architecture view's nesting and the 3D view's elevation:
//
//   - the package tree is laid out as NESTED rectangles (com > com.example > A):
//     a package is a rectangle containing its children arranged in rows by
//     architecture level (siblings ordered by horizontalOrder), auto-sized to
//     enclose them — a port of Layout3D.
//   - each nesting level is raised (depth * STEP), so packages stack upward into
//     terraces and the gaps between them read as a hierarchical street network.
//   - a class is a building sitting on top of its own package's platform;
//     height = method count, footprint = fan-in/out, building type = level.

// Layout metrics (Layout3D-style). The gaps host a real, routable road network:
// every package gets a perimeter RING road, CROSS streets between its level
// rows, SIDE streets between siblings, a DRIVEWAY stub per building and RAMPS
// down each terrace step — emitted as graph data so traffic can route on it.
const NODE_GAP = 14;   // gap between siblings in a level row (side streets)
const GROUP_GAP = 15;  // gap between level rows (cross streets)
const PAD = 10;        // inner margin of a package (fits the ring road + sidewalk)
const STEP = 1.5;      // elevation added per nesting level (minimal / flat terracing)
const SLAB_T = 1.5;    // package platform thickness (kept equal to STEP -> uniform pedestals)
const RING_IN = 4.5;   // ring-road centreline inset from the slab edge
const DRIVE_W = 3;     // driveway width
// Boulevards are wider low in the hierarchy, lanes narrower deep inside.
const roadW = (depth) => (depth <= 0 ? 6.4 : depth === 1 ? 5.4 : 4.6);

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function hashName(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return (h >>> 0) / 4294967295;
}

// Building type from architecture level (0 glass, 1 stone, 2 brick, 3 concrete).
function styleFor(level, maxLevel, isInterface) {
  if (isInterface) return 0;
  if (maxLevel <= 0) return 3;
  return Math.min(3, Math.max(0, 3 - Math.floor((level / maxLevel) * 3.999)));
}
function layoutFor(style, rng) {
  return style === 0 ? (rng() < 0.72 ? 0 : 3)
    : style === 1 ? 1
      : style === 2 ? 2
        : (rng() < 0.58 ? 3 : 2);
}

export function layoutFromModel(model) {
  const rng = mulberry32(0x5202c1);
  const maxLevel = Math.max(1, model.maxLevel ?? 1);

  // ---- build the package tree from the flat lists --------------------------
  const pkg = new Map();
  for (const d of model.districts ?? []) {
    pkg.set(d.fullName, {
      kind: 'pkg', fqn: d.fullName, simple: d.simpleName,
      // level = LOCAL level (row within parent, the "L:x" of the 2D view);
      // arch = global architecture level (used for building type/colour).
      level: d.localLevel, arch: d.architectureLevel, depth: d.nestingDepth,
      horiz: d.horizontalOrder, inCycle: !!d.inCycle,
      children: [], classes: [], w: 0, d: 0,
    });
  }
  const roots = [];
  for (const d of model.districts ?? []) {
    const node = pkg.get(d.fullName);
    if (d.parentFullName && pkg.has(d.parentFullName)) pkg.get(d.parentFullName).children.push(node);
    else roots.push(node);
  }
  for (const b of model.buildings ?? []) {
    const parent = pkg.get(b.districtFullName);
    if (!parent) continue;
    parent.classes.push({
      kind: 'cls', fqn: b.fullName, simple: b.simpleName,
      level: b.localLevel, arch: b.architectureLevel, horiz: b.horizontalOrder,
      methodCount: b.methodCount, fanIn: b.fanIn ?? 0, fanOut: b.fanOut ?? 0,
      isInterface: !!b.isInterface, inCycle: !!b.inCycle, w: 0, d: 0,
    });
  }
  // A virtual root holds the top packages; depth -1 so real roots are depth 0.
  const root = { kind: 'pkg', fqn: '', simple: '', level: 0, depth: -1,
                 horiz: 0, inCycle: false, children: roots, classes: [], w: 0, d: 0 };

  // ---- measure (bottom-up): each node's footprint encloses its children ----
  function classFootprint(c) {
    c.w = 8 + 4 * Math.log2(1 + c.fanIn);
    c.d = 8 + 4 * Math.log2(1 + c.fanOut);
  }
  function measure(node) {
    if (node.kind === 'cls') { classFootprint(node); return; }
    const items = [...node.children, ...node.classes];
    items.forEach(measure);
    // group by architecture level -> one row per level (ascending: low level near)
    const byLevel = new Map();
    for (const it of items) {
      const lv = it.level ?? 0;
      if (!byLevel.has(lv)) byLevel.set(lv, []);
      byLevel.get(lv).push(it);
    }
    // Highest local level first (top row), like the 2D view.
    const levels = [...byLevel.keys()].sort((a, b) => b - a);

    // Measure each level row (width + depth) first ...
    const rows = levels.map((lv) => {
      const row = byLevel.get(lv).sort((a, b) =>
        (a.horiz - b.horiz) || (a.simple || '').localeCompare(b.simple || ''));
      const width = row.reduce((s, it) => s + it.w, 0) + Math.max(0, row.length - 1) * NODE_GAP;
      const depth = Math.max(...row.map((it) => it.d));
      return { row, width, depth };
    });
    const innerW = Math.max(0, ...rows.map((r) => r.width));

    // ... then stack the rows in Z and give every child a CELL it fills, like the
    // 2D box layout (JavaFX Hgrow / maxSize=MAX_VALUE). A sub-package grows to use
    // ALL the free space in its cell: it fills the row depth (Z — "extends equally
    // to the back") and shares any spare row width (X). A class keeps its size and
    // is centred in its cell (its arrangement is already correct). A row with no
    // sub-package is centred horizontally; a row with sub-packages fills the width.
    let z = PAD;
    for (const r of rows) {
      const stretchers = r.row.filter((it) => it.kind === 'pkg');
      const extra = Math.max(0, innerW - r.width);
      const per = stretchers.length ? extra / stretchers.length : 0;
      let x = stretchers.length ? PAD : PAD + (innerW - r.width) / 2;
      for (const it of r.row) {
        it._rowZ0 = z; it._rowD = r.depth; // row band (for street corridors)
        if (it.kind === 'pkg') {
          it._cellX = x; it._cellZ = z;                 // fill the cell ...
          it._cellW = it.w + per; it._cellD = r.depth;  // ... spare width (X) + full row depth (Z)
        } else {
          it._cellX = x; it._cellZ = z + (r.depth - it.d) / 2; // class: fixed, centred in the band
          it._cellW = it.w; it._cellD = it.d;
        }
        x += it._cellW + NODE_GAP;
      }
      z += r.depth + GROUP_GAP;
    }
    node.w = Math.max(innerW + 2 * PAD, 2 * PAD);
    node.d = Math.max(z - GROUP_GAP + PAD, 2 * PAD); // drop trailing gap, add bottom pad
  }
  measure(root);

  // ---- place (top-down) + emit geometry ------------------------------------
  const boxes = [];
  const rooftops = [];
  const slabs = [];
  const anchors = {}; // fqn -> {x, z, baseY, roofY, w, d} for dependency arcs / fly-to

  // The road network, emitted as GRAPH data (not just decoration): roads are
  // axis-aligned segments on a package surface, ramps join a child deck to its
  // parent's corridor (always exactly one STEP down), access points anchor each
  // building's driveway. roads.js turns this into a routable graph for traffic.
  let roadId = 0;
  const roads = [];     // {id, pkg, axis:'x'|'z', y, c, a0, a1, w, kind:'ring'|'cross'|'side'|'drive'|'link'}
  const rampLinks = []; // {aRoad, aPos, bRoad, bPos, w} — a = upper (child deck), b = lower (parent corridor)
  const access = {};    // class fqn -> {road, pos} (the driveway end at the building)

  function emitRoads(node, cellX, cellZ, cellW, cellD, ox, oz) {
    const items = [...node.children, ...node.classes].filter((it) => it._cellX != null);
    if (!items.length) return;
    const depth = node.depth;
    const y = depth >= 0 ? depth * STEP + SLAB_T : 0; // this package's ground
    const w = roadW(depth);
    const pkg = node.fqn || '<root>';
    const x0 = cellX + RING_IN, x1 = cellX + cellW - RING_IN;
    const z0 = cellZ + RING_IN, z1 = cellZ + cellD - RING_IN;
    const add = (r) => { r.id = roadId++; r.pkg = pkg; r.y = y; roads.push(r); return r; };

    // Perimeter ring road — guarantees every internal street connects.
    const ringF = add({ axis: 'x', c: z0, a0: x0, a1: x1, w, kind: 'ring' });
    const ringB = add({ axis: 'x', c: z1, a0: x0, a1: x1, w, kind: 'ring' });
    add({ axis: 'z', c: x0, a0: z0, a1: z1, w, kind: 'ring' });
    add({ axis: 'z', c: x1, a0: z0, a1: z1, w, kind: 'ring' });

    // group items into their level rows by the row band (_rowZ0)
    const rowsM = new Map();
    for (const it of items) {
      if (!rowsM.has(it._rowZ0)) rowsM.set(it._rowZ0, []);
      rowsM.get(it._rowZ0).push(it);
    }
    const rowKeys = [...rowsM.keys()].sort((a, b) => a - b);
    const rowD = rowKeys.map((k) => rowsM.get(k)[0]._rowD);

    // Cross streets between successive rows; with the two ring segments they
    // form the corridor list: corridor[i] runs in front of row i, [i+1] behind.
    const corridors = [ringF];
    for (let ri = 0; ri < rowKeys.length - 1; ri++) {
      const zc = oz + (rowKeys[ri] + rowD[ri] + rowKeys[ri + 1]) / 2;
      corridors.push(add({ axis: 'x', c: zc, a0: x0, a1: x1, w, kind: 'cross' }));
    }
    corridors.push(ringB);

    rowKeys.forEach((zk, ri) => {
      const row = rowsM.get(zk).sort((a, b) => a._cellX - b._cellX);
      const before = corridors[ri], after = corridors[ri + 1];
      // side streets between neighbouring cells, spanning corridor to corridor
      for (let i = 0; i < row.length - 1; i++) {
        const a = row[i], b = row[i + 1];
        const xc = ox + (a._cellX + a._cellW + b._cellX) / 2;
        add({ axis: 'z', c: xc, a0: before.c, a1: after.c, w, kind: 'side' });
      }
      for (const it of row) {
        if (it.kind === 'cls') {
          // driveway from the building's south face to the corridor behind its row
          const bx = ox + it._cellX + it._cellW / 2;
          const bz1 = oz + it._cellZ + it.d;
          const drv = add({ axis: 'z', c: bx, a0: bz1, a1: after.c + 0.05, w: DRIVE_W, kind: 'drive' });
          access[it.fqn] = { road: drv.id, pos: bz1 };
        } else if (it.children.length || it.classes.length) {
          // sub-package: a short connector on the child's deck (crossing the
          // child's own ring) plus a ramp down to the corridor on either side.
          const chX0 = ox + it._cellX, chZ0 = oz + it._cellZ;
          const chZ1 = chZ0 + it._cellD;
          const cx = chX0 + it._cellW / 2;
          const cw = roadW(depth + 1), cy = y + STEP;
          const front = { axis: 'z', c: cx, a0: chZ0, a1: chZ0 + RING_IN + 0.05, w: cw, kind: 'link' };
          front.id = roadId++; front.pkg = it.fqn; front.y = cy; roads.push(front);
          rampLinks.push({ aRoad: front.id, aPos: chZ0, bRoad: before.id, bPos: cx, w: cw });
          const back = { axis: 'z', c: cx, a0: chZ1 - RING_IN - 0.05, a1: chZ1, w: cw, kind: 'link' };
          back.id = roadId++; back.pkg = it.fqn; back.y = cy; roads.push(back);
          rampLinks.push({ aRoad: back.id, aPos: chZ1, bRoad: after.id, bPos: cx, w: cw });
        }
      }
    });
  }

  function emitBuilding(c, ox, oz, parentDepth) {
    const cx = ox + c.w / 2, cz = oz + c.d / 2;
    const slabTop = Math.max(0, parentDepth) * STEP + SLAB_T; // sits on its package platform
    const floors = c.methodCount > 0 ? c.methodCount : (c.level + 1);
    let h = Math.max(12, 10 + floors * 9);
    const style = styleFor(c.arch, maxLevel, c.isInterface);
    const seed = hashName(c.fqn) * 1000;
    const layout = layoutFor(style, rng);

    let baseY = slabTop, cw = c.w, cd = c.d, remain = h, topW = c.w, topD = c.d;
    const steps = h > 90 ? 3 : h > 50 ? 2 : 1;
    for (let s = 0; s < steps; s++) {
      const segH = s === steps - 1 ? remain : remain * (0.45 + rng() * 0.2);
      boxes.push({
        x: cx, z: cz, baseY, w: cw, h: segH, d: cd, seed, style, layout, fqn: c.fqn,
        fanIn: c.fanIn, fanOut: c.fanOut, methodCount: c.methodCount, inCycle: c.inCycle,
      });
      topW = cw; topD = cd;
      baseY += segH; remain -= segH;
      cw *= 0.76 + rng() * 0.1;
      cd *= 0.76 + rng() * 0.1;
    }
    rooftops.push({
      x: cx, z: cz, y: baseY, w: topW, d: topD, h, style, seed,
      inCycle: c.inCycle, fqn: c.fqn,
      bulkhead: rng() < 0.7,
      hvacCount: topW > 12 && topD > 10 ? 1 + (rng() < 0.35 ? 1 : 0) : 0,
      waterTank: (style === 1 || style === 2) && h > 40 && rng() < 0.25,
    });
    anchors[c.fqn] = { x: cx, z: cz, baseY: slabTop, roofY: slabTop + h, w: c.w, d: c.d };
  }

  function place(node, cellX, cellZ, cellW, cellD) {
    if (node.kind === 'pkg' && node.depth >= 0) {
      // Solid pedestal: from the parent's top surface up to this package's top,
      // so terraces stack without an air gap under the nested platforms.
      const topY = node.depth * STEP + SLAB_T;
      const botY = node.depth >= 1 ? (node.depth - 1) * STEP + SLAB_T : 0;
      slabs.push({
        x: cellX + cellW / 2, z: cellZ + cellD / 2, w: cellW, d: cellD,
        botY, topY, depth: node.depth, level: node.level,
        inCycle: node.inCycle, simple: node.simple, fqn: node.fqn,
      });
    }
    // Centre the node's (tight) content within the cell it was given to fill.
    const ox = cellX + (cellW - node.w) / 2;
    const oz = cellZ + (cellD - node.d) / 2;
    // Roads in every package — including leaf packages, where the gaps between
    // the class buildings themselves become the local street grid.
    if (node.kind === 'pkg') emitRoads(node, cellX, cellZ, cellW, cellD, ox, oz);
    for (const child of node.children) place(child, ox + child._cellX, oz + child._cellZ, child._cellW, child._cellD);
    for (const cls of node.classes) emitBuilding(cls, ox + cls._cellX, oz + cls._cellZ, node.depth);
  }
  // Centre the city on the origin; the root fills a cell equal to its own size.
  place(root, -root.w / 2, -root.d / 2, root.w, root.d);

  return {
    boxes, rooftops, slabs, anchors,
    roadNet: { roads, ramps: rampLinks, access },
    groundFacades: [],           // shopfronts assume ground-level buildings; N/A on platforms
    spanX: root.w, spanZ: root.d, x0: -root.w / 2, z0: -root.d / 2,
    maxDepth: Math.max(0, ...slabs.map((s) => s.depth), 0),
    grid: [slabs.length, boxes.length],
  };
}
