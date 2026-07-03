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

// Layout metrics (Layout3D-style). Gaps become the streets between platforms.
// Street width = the gap the asphalt fills. Keep both equal so horizontal and
// vertical streets are the same width.
const NODE_GAP = 12;   // gap between siblings in a level row (vertical streets)
const GROUP_GAP = 12;  // gap between level rows (horizontal streets) — equal to NODE_GAP
const PAD = 7;         // inner margin of a package             (its surrounding street)
const EDGE = 8;        // keep streets this far from the slab edge (uniform border)
const STREET_FILL = 2 / 3; // asphalt fills 2/3 of the gap (centred, leaving a border)
const STEP = 1.5;      // elevation added per nesting level (minimal / flat terracing)
const SLAB_T = 1.5;    // package platform thickness (kept equal to STEP -> uniform pedestals)

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
  const streets = []; // gap corridors between a package's children (its streets)
  const ramps = [];   // connector segments (traced from the requests below, terrain-following)
  const rampReqs = []; // {ax, ay, az, dir, w}: a street reaching an edge, to descend outward

  // The gaps between a package's children are its streets, drawn on the package's
  // slab surface. Because a package sits inside the free space (streets) of its
  // parent, these nested street grids connect up the hierarchy automatically.
  function emitStreets(node, cellX, cellZ, cellW, cellD, ox, oz) {
    const items = [...node.children, ...node.classes].filter((it) => it._cellX != null);
    if (!items.length) return;
    const y = node.depth >= 0 ? node.depth * STEP + SLAB_T : 0; // this package's ground

    // group items into their level rows by the row band (_rowZ0)
    const rows = new Map();
    for (const it of items) {
      if (!rows.has(it._rowZ0)) rows.set(it._rowZ0, []);
      rows.get(it._rowZ0).push(it);
    }
    const rowKeys = [...rows.keys()].sort((a, b) => a - b);
    const rowD = rowKeys.map((k) => rows.get(k)[0]._rowD);

    // vertical streets (run along Z) between the cells within each row, extended
    // toward the package edge — but only to the cross street (inter-row gap)
    // before the neighbouring row's buildings; to the slab edge if none.
    rowKeys.forEach((zk, ri) => {
      const row = rows.get(zk).sort((a, b) => a._cellX - b._cellX);
      const zFront = ri > 0 ? oz + rowKeys[ri - 1] + rowD[ri - 1] : cellZ + EDGE;         // prev row's back edge, else near slab edge
      const zBack = ri < rowKeys.length - 1 ? oz + rowKeys[ri + 1] : cellZ + cellD - EDGE; // next row's front edge, else near slab edge
      const zc = (zFront + zBack) / 2, dd = zBack - zFront;
      for (let i = 0; i < row.length - 1; i++) {
        const a = row[i], b = row[i + 1];
        const g0 = a._cellX + a._cellW, g1 = b._cellX;
        streets.push({ x: ox + (g0 + g1) / 2, z: zc, w: (g1 - g0) * STREET_FILL, d: dd, y, axis: 'z' });
      }
    });
    // horizontal streets (run along X) between successive level rows, inset from
    // the L/R edges, plus a ramp at each end running DOWN over the package edge to
    // the parent street one terrace below — connecting the higher street to it.
    for (let ri = 0; ri < rowKeys.length - 1; ri++) {
      const g0 = rowKeys[ri] + rowD[ri], g1 = rowKeys[ri + 1];
      const sz = oz + (g0 + g1) / 2, sw = (g1 - g0) * STREET_FILL;
      // Cross street runs to the package edge so it meets the ramp seamlessly.
      streets.push({ x: cellX + cellW / 2, z: sz, w: cellW, d: sw, y, axis: 'x' });
      // Request a connector from each edge; it is traced down the terrain later,
      // so a drop over more than one terrace becomes a staircase, not a straight
      // ramp cutting through the intermediate ledge.
      rampReqs.push({ ax: cellX, ay: y, az: sz, dir: -1, w: sw });        // left
      rampReqs.push({ ax: cellX + cellW, ay: y, az: sz, dir: 1, w: sw }); // right
    }
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
      boxes.push({ x: cx, z: cz, baseY, w: cw, h: segH, d: cd, seed, style, layout, fqn: c.fqn });
      topW = cw; topD = cd;
      baseY += segH; remain -= segH;
      cw *= 0.76 + rng() * 0.1;
      cd *= 0.76 + rng() * 0.1;
    }
    rooftops.push({
      x: cx, z: cz, y: baseY, w: topW, d: topD, h, style, seed,
      bulkhead: rng() < 0.7,
      hvacCount: topW > 12 && topD > 10 ? 1 + (rng() < 0.35 ? 1 : 0) : 0,
      waterTank: (style === 1 || style === 2) && h > 40 && rng() < 0.25,
    });
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
    // Packages that consist only of classes (no sub-packages) get NO internal
    // streets for now — the class-level street layout is still to be designed.
    if (node.kind === 'pkg' && node.children.length > 0) emitStreets(node, cellX, cellZ, cellW, cellD, ox, oz);
    for (const child of node.children) place(child, ox + child._cellX, oz + child._cellZ, child._cellW, child._cellD);
    for (const cls of node.classes) emitBuilding(cls, ox + cls._cellX, oz + cls._cellZ, node.depth);
  }
  // Centre the city on the origin; the root fills a cell equal to its own size.
  place(root, -root.w / 2, -root.d / 2, root.w, root.d);

  // ---- trace each ramp request down the terrain into a staircase of segments --
  // Surface height at a point = the top of the highest slab covering it (else 0).
  function surfaceAt(x, z) {
    let y = 0;
    for (const s of slabs) {
      if (x >= s.x - s.w / 2 && x <= s.x + s.w / 2 && z >= s.z - s.d / 2 && z <= s.z + s.d / 2) y = Math.max(y, s.topY);
    }
    return y;
  }
  const RUN = NODE_GAP / 2; // horizontal length of one step's descent
  for (const req of rampReqs) {
    const { az, dir, w } = req;
    let x = req.ax, curY = req.ay, guard = 0;
    while (guard++ < 40 && curY > 0.01) {
      const ahead = surfaceAt(x + dir * 0.6, az); // surface just outside current x
      if (ahead < curY - 0.01) {
        ramps.push({ ax: x, ay: curY, az, bx: x + dir * RUN, by: ahead, bz: az, w }); // descend one step
        x += dir * RUN; curY = ahead;
      } else if (ahead > curY + 0.01) {
        break; // a higher neighbour blocks the way -> stop (don't run under it)
      } else {
        // same level: run flat until the surface changes (a drop or an obstacle)
        let d = 1;
        while (d < 200 && Math.abs(surfaceAt(x + dir * d, az) - curY) < 0.6) d++;
        if (d >= 200) break; // flat with no further change -> reached the low street
        const flatEnd = x + dir * (d - 1);
        if (Math.abs(flatEnd - x) > 0.5) ramps.push({ ax: x, ay: curY, az, bx: flatEnd, by: curY, bz: az, w }); // flat over the terrace
        x = flatEnd;
      }
    }
  }

  return {
    boxes, rooftops, slabs, streets, ramps,
    groundFacades: [],           // shopfronts assume ground-level buildings; N/A on platforms
    streetsX: [], streetsZ: [],  // no straight full-span avenues (traffic stays off)
    spanX: root.w, spanZ: root.d, x0: -root.w / 2, z0: -root.d / 2,
    maxDepth: Math.max(0, ...slabs.map((s) => s.depth), 0),
    grid: [slabs.length, boxes.length],
  };
}
