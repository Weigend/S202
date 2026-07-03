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
const NODE_GAP = 9;    // gap between siblings in a level row  (streets)
const GROUP_GAP = 15;  // gap between level rows                (avenues)
const PAD = 7;         // inner margin of a package             (its surrounding street)
const STEP = 9;        // elevation added per nesting level     (terracing)
const SLAB_T = 3;      // package platform thickness

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
      level: d.architectureLevel, depth: d.nestingDepth,
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
      level: b.architectureLevel, horiz: b.horizontalOrder,
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
    const levels = [...byLevel.keys()].sort((a, b) => a - b);
    let z = PAD, maxRight = PAD;
    for (const lv of levels) {
      const row = byLevel.get(lv).sort((a, b) =>
        (a.horiz - b.horiz) || (a.simple || '').localeCompare(b.simple || ''));
      let x = PAD, rowD = 0;
      for (const it of row) {
        it._lx = x; it._lz = z;
        x += it.w + NODE_GAP;
        rowD = Math.max(rowD, it.d);
      }
      maxRight = Math.max(maxRight, x - NODE_GAP); // drop trailing gap
      z += rowD + GROUP_GAP;
    }
    node.w = Math.max(maxRight + PAD, 2 * PAD);
    node.d = Math.max(z - GROUP_GAP + PAD, 2 * PAD); // drop trailing gap, add bottom pad
  }
  measure(root);

  // ---- place (top-down) + emit geometry ------------------------------------
  const boxes = [];
  const rooftops = [];
  const slabs = [];

  function emitBuilding(c, ox, oz, parentDepth) {
    const cx = ox + c.w / 2, cz = oz + c.d / 2;
    const slabTop = Math.max(0, parentDepth) * STEP + SLAB_T; // sits on its package platform
    const floors = c.methodCount > 0 ? c.methodCount : (c.level + 1);
    let h = Math.max(12, 10 + floors * 9);
    const style = styleFor(c.level, maxLevel, c.isInterface);
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

  function place(node, ox, oz) {
    if (node.kind === 'pkg' && node.depth >= 0) {
      slabs.push({
        x: ox + node.w / 2, z: oz + node.d / 2, w: node.w, d: node.d,
        y: node.depth * STEP, depth: node.depth, level: node.level,
        inCycle: node.inCycle, simple: node.simple,
      });
    }
    for (const child of node.children) place(child, ox + child._lx, oz + child._lz);
    for (const cls of node.classes) emitBuilding(cls, ox + cls._lx, oz + cls._lz, node.depth);
  }
  // Centre the city on the origin.
  place(root, -root.w / 2, -root.d / 2);

  return {
    boxes, rooftops, slabs,
    groundFacades: [],           // shopfronts assume ground-level buildings; N/A on platforms
    streetsX: [], streetsZ: [],  // streets are the gaps between nested platforms
    spanX: root.w, spanZ: root.d, x0: -root.w / 2, z0: -root.d / 2,
    maxDepth: Math.max(0, ...slabs.map((s) => s.depth), 0),
    grid: [slabs.length, boxes.length],
  };
}
