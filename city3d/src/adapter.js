// Structure202 · City3D — data-driven layout adapter.
//
// This is the ONLY meaningful change to the City3JS view: instead of inventing a
// skyline from a seed (grid + height-field + RNG), it maps a real analysed
// architecture (CityModel JSON from de.weigend.s202.ui.city3d.CityModelExporter)
// onto the exact same building/roof/facade/street arrays City3JS renders. All
// the visuals — streets, weather, times of day, building types, bloom, sky — are
// unchanged; only arrangement and size now carry meaning:
//
//   - one BLOCK per package (district), arranged by architecture level
//   - one BUILDING per class inside its package block
//   - height   = method count   (size = amount of code)
//   - footprint = fan-in / fan-out (how connected the class is)
//   - building type (style) = architecture level  (Gebäudetyp A…D)
//   - setbacks / rooftops / shopfronts kept from City3JS for the city look

import * as THREE from 'three';

// Same block grid metrics as the City3JS view, so roads/traffic stay regular.
const L = {
  blockW: 66, blockD: 38, avenue: 22, street: 14,
};

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

// Building type from architecture level: 0 glass, 1 stone/art-déco, 2 brick,
// 3 concrete. Interfaces are always glass. Gives layers a distinct look.
function styleFor(building, maxLevel) {
  if (building.isInterface) return 0;
  if (maxLevel <= 0) return 3;
  const t = building.architectureLevel / maxLevel;      // 0..1
  return Math.min(3, Math.max(0, 3 - Math.floor(t * 3.999))); // low level -> concrete base
}

function layoutFor(style, rng) {
  return style === 0 ? (rng() < 0.72 ? 0 : 3)
    : style === 1 ? 1
      : style === 2 ? 2
        : (rng() < 0.58 ? 3 : 2);
}

/**
 * @param {object} model CityModel JSON { maxLevel, districts[], buildings[], dependencies[] }
 * @returns arrays + span/grid consumed by buildCity, matching the procedural generator's shape.
 */
export function layoutFromModel(model) {
  const rng = mulberry32(0x5202c1); // fixed seed: deterministic incidental variety
  const maxLevel = Math.max(1, model.maxLevel ?? 1);

  const districtMeta = new Map((model.districts ?? []).map((d) => [d.fullName, d]));

  // Group classes by their package (district); only packages with direct
  // classes become city blocks.
  const byDistrict = new Map();
  for (const b of model.buildings ?? []) {
    const k = b.districtFullName ?? '(root)';
    if (!byDistrict.has(k)) byDistrict.set(k, []);
    byDistrict.get(k).push(b);
  }
  // Order packages by architecture level, then name -> level becomes a spatial
  // gradient across the rows of the city.
  const packages = [...byDistrict.keys()].sort((a, b) => {
    const da = districtMeta.get(a), db = districtMeta.get(b);
    return (da?.architectureLevel ?? 0) - (db?.architectureLevel ?? 0) || a.localeCompare(b);
  });

  const n = packages.length;
  const cols = Math.max(1, Math.ceil(Math.sqrt(n / 2)));
  const rows = Math.max(1, Math.ceil(n / cols));

  const spanX = cols * L.blockW + (cols - 1) * L.avenue;
  const spanZ = rows * L.blockD + (rows - 1) * L.street;
  const x0 = -spanX / 2;
  const z0 = -spanZ / 2;

  const streetsX = [];
  const streetsZ = [];
  for (let c = 1; c < cols; c++) streetsX.push(x0 + c * (L.blockW + L.avenue) - L.avenue / 2);
  for (let r = 1; r < rows; r++) streetsZ.push(z0 + r * (L.blockD + L.street) - L.street / 2);

  const boxes = [];
  const rooftops = [];
  const groundFacades = [];

  packages.forEach((pkgKey, pi) => {
    const c = pi % cols;
    const r = Math.floor(pi / cols);
    const bx = x0 + c * (L.blockW + L.avenue);
    const bz = z0 + r * (L.blockD + L.street);

    const classes = byDistrict.get(pkgKey).sort((a, b) =>
      (a.horizontalOrder - b.horizontalOrder) || a.simpleName.localeCompare(b.simpleName));

    // Subdivide the block into a lot grid, wider along X (like avenues).
    const nx = Math.max(1, Math.ceil(Math.sqrt(classes.length * (L.blockW / L.blockD))));
    const nz = Math.max(1, Math.ceil(classes.length / nx));
    const lotW = (L.blockW - (nx - 1) * 2) / nx;
    const lotD = (L.blockD - (nz - 1) * 2) / nz;

    classes.forEach((cls, ci) => {
      const lx = ci % nx;
      const lz = Math.floor(ci / nx);
      const cx = bx + lx * (lotW + 2) + lotW / 2;
      const cz = bz + lz * (lotD + 2) + lotD / 2;

      const inset = 1.0 + rng() * 1.2;
      // Footprint carries connectivity: fan-in widens (X), fan-out deepens (Z).
      const fanW = 0.55 + 0.12 * Math.log2(1 + (cls.fanIn ?? 0));
      const fanD = 0.55 + 0.12 * Math.log2(1 + (cls.fanOut ?? 0));
      const w = Math.max(6, Math.min(lotW - inset * 2, (lotW - inset * 2) * Math.min(1, fanW)));
      const d = Math.max(6, Math.min(lotD - inset * 2, (lotD - inset * 2) * Math.min(1, fanD)));
      if (w < 5 || d < 5) return;

      // Height carries size: methods -> floors. Fallback to level when unknown.
      const floors = cls.methodCount > 0 ? cls.methodCount : (cls.architectureLevel + 1);
      let h = Math.max(12, 10 + floors * 9);

      const style = styleFor(cls, maxLevel);
      const seed = hashName(cls.fullName) * 1000;
      const layout = layoutFor(style, rng);

      // Shopfronts on the block-facing sides (same as City3JS ground floors).
      if (lz === 0) groundFacades.push({ x: cx, z: cz - d / 2, width: w, axis: 'z', side: -1, style, seed });
      if (lz === nz - 1) groundFacades.push({ x: cx, z: cz + d / 2, width: w, axis: 'z', side: 1, style, seed });
      if (lx === 0) groundFacades.push({ x: cx - w / 2, z: cz, width: d, axis: 'x', side: -1, style, seed });
      if (lx === nx - 1) groundFacades.push({ x: cx + w / 2, z: cz, width: d, axis: 'x', side: 1, style, seed });

      // Setbacks: tall buildings step in as they rise.
      let baseY = 0, cw = w, cd = d, remain = h, topW = w, topD = d;
      const steps = h > 90 ? 3 : h > 50 ? 2 : 1;
      for (let s = 0; s < steps; s++) {
        const segH = s === steps - 1 ? remain : remain * (0.45 + rng() * 0.2);
        boxes.push({ x: cx, z: cz, baseY, w: cw, h: segH, d: cd, seed, style, layout, fqn: cls.fullName });
        topW = cw; topD = cd;
        baseY += segH; remain -= segH;
        cw *= 0.74 + rng() * 0.12;
        cd *= 0.74 + rng() * 0.12;
      }

      rooftops.push({
        x: cx, z: cz, y: baseY, w: topW, d: topD, h, style, seed,
        bulkhead: rng() < 0.7,
        hvacCount: topW > 12 && topD > 10 ? 1 + (rng() < 0.35 ? 1 : 0) : 0,
        waterTank: (style === 1 || style === 2) && h > 40 && rng() < 0.25,
      });

      if (h > 120 && rng() < 0.7) {
        const aw = Math.min(cw, cd) * 0.12 + 0.5;
        boxes.push({ x: cx, z: cz, baseY, w: aw, h: 8 + rng() * 22, d: aw, seed, style, layout, antenna: true });
      }
    });
  });

  return { boxes, rooftops, groundFacades, streetsX, streetsZ, spanX, spanZ, x0, z0, grid: [cols, rows] };
}
