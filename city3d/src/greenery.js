import * as THREE from 'three';

/**
 * Grünflächen: kleine Parks in den freien Plaza-Bereichen der Paket-Plattformen
 * und ein lockerer Grüngürtel auf dem Boden rund um die Stadt.
 *
 * Vorgehen: pro Plattform werden Cluster-Punkte gesampelt und gegen alles
 * getestet, was dort schon steht — Gebäude (Anker-Footprints), Straßen,
 * Zufahrten, Rampen und höhere Kind-Plattformen. Ein akzeptierter Cluster
 * bekommt eine Rasenfläche, 2–5 Bäume (Kugel- oder Kegelkrone) und Büsche.
 * Alles deterministisch (Seed) und instanziert.
 */

const TREE_CAP = 2200;
const LAWN_CAP = 700;

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function makeGreenery({ slabs, roadNet, anchors, spanX, spanZ }) {
  const group = new THREE.Group();
  group.name = 'greenery';
  const rng = mulberry32(0x67ee7);

  // ---- Hindernis-Listen vorbereiten ----------------------------------------
  const anchorList = Object.values(anchors); // {x, z, baseY, w, d}
  const roadsByPkg = new Map();
  for (const r of roadNet.roads) {
    if (!roadsByPkg.has(r.pkg)) roadsByPkg.set(r.pkg, []);
    roadsByPkg.get(r.pkg).push(r);
  }
  const roadById = new Map(roadNet.roads.map((r) => [r.id, r]));
  const rampRects = roadNet.ramps.map((rp) => {
    const ra = roadById.get(rp.aRoad), rb = roadById.get(rp.bRoad);
    const z0 = Math.min(rp.aPos, rb.c), z1 = Math.max(rp.aPos, rb.c);
    return { x0: ra.c - rp.w / 2 - 1, x1: ra.c + rp.w / 2 + 1, z0: z0 - 1, z1: z1 + 1 };
  });

  // Freiflächen-Test für einen Punkt auf einer Fläche (Plattform oder Boden).
  function isFree(x, z, surfY, pkg, rect, margin) {
    // innerhalb der Fläche bleiben (nicht an der Kante stehen)
    if (x < rect.x0 + margin || x > rect.x1 - margin || z < rect.z0 + margin || z > rect.z1 - margin) return false;
    // Gebäude-Footprints auf dieser Höhe
    for (const a of anchorList) {
      if (Math.abs(a.baseY - surfY) > 0.2) continue;
      if (x > a.x - a.w / 2 - 1.4 && x < a.x + a.w / 2 + 1.4
        && z > a.z - a.d / 2 - 1.4 && z < a.z + a.d / 2 + 1.4) return false;
    }
    // Straßen dieses Pakets (inkl. Gehsteigbreite)
    for (const r of roadsByPkg.get(pkg) ?? []) {
      const half = r.w / 2 + 2.1;
      if (r.axis === 'x') {
        if (Math.abs(z - r.c) < half && x > r.a0 - 1 && x < r.a1 + 1) return false;
      } else if (Math.abs(x - r.c) < half && z > r.a0 - 1 && z < r.a1 + 1) return false;
    }
    // höhere (Kind-)Plattformen, die diesen Punkt überdecken
    for (const s of slabs) {
      if (s.topY <= surfY + 0.1) continue;
      if (x > s.x - s.w / 2 - 1 && x < s.x + s.w / 2 + 1
        && z > s.z - s.d / 2 - 1 && z < s.z + s.d / 2 + 1) return false;
    }
    // Rampenbänder
    for (const rr of rampRects) {
      if (x > rr.x0 && x < rr.x1 && z > rr.z0 && z < rr.z1) return false;
    }
    return true;
  }

  // ---- Cluster sampeln -------------------------------------------------------
  const lawns = [], trunks = [], crownsRound = [], crownsCone = [], bushes = [];
  let treeCount = 0;

  function plantCluster(cx, cz, y, pkg, rect, onLawn = false) {
    const r = 2.6 + rng() * 2.8;
    if (!onLawn && lawns.length < LAWN_CAP) lawns.push({ x: cx, z: cz, y, r });
    const n = 2 + Math.floor(rng() * 4);
    for (let i = 0; i < n && treeCount < TREE_CAP; i++) {
      const a = rng() * Math.PI * 2, d = rng() * r * 0.8;
      const tx = cx + Math.cos(a) * d, tz = cz + Math.sin(a) * d;
      if (!isFree(tx, tz, y, pkg, rect, 1.2)) continue;
      const h = 2.2 + rng() * 2.6;
      trunks.push({ x: tx, z: tz, y, h, radius: 0.09 + rng() * 0.05 });
      const cr = 0.8 + rng() * 0.9;
      const tint = 0.75 + rng() * 0.5;
      if (rng() < 0.72) crownsRound.push({ x: tx, z: tz, y: y + h + cr * 0.55, r: cr, tint });
      else crownsCone.push({ x: tx, z: tz, y: y + h, r: cr * 0.75, h: cr * 2.6, tint });
      treeCount++;
    }
    const nb = 2 + Math.floor(rng() * 3);
    for (let i = 0; i < nb; i++) {
      const a = rng() * Math.PI * 2, d = r * (0.5 + rng() * 0.6);
      const bx = cx + Math.cos(a) * d, bz = cz + Math.sin(a) * d;
      if (!isFree(bx, bz, y, pkg, rect, 1.0)) continue;
      bushes.push({ x: bx, z: bz, y, r: 0.35 + rng() * 0.4, tint: 0.7 + rng() * 0.5 });
    }
  }

  // Größere Freiflächen: rechteckige Rasenfelder (mit Bäumen) und Beton-Plätze.
  const lawnRects = [], padRects = [];
  const RECT_PROBE = [[0, 0], [-0.5, -0.5], [0.5, -0.5], [-0.5, 0.5], [0.5, 0.5], [0.5, 0], [-0.5, 0], [0, 0.5], [0, -0.5]];
  function seedRects(rect, y, pkg, area) {
    const n = Math.min(3, Math.round(area / 1500));
    for (let k = 0; k < n; k++) {
      for (let attempt = 0; attempt < 12; attempt++) {
        const w = 7 + rng() * 13, d = 6 + rng() * 11;
        const x = rect.x0 + rng() * (rect.x1 - rect.x0);
        const z = rect.z0 + rng() * (rect.z1 - rect.z0);
        let ok = true;
        for (const [fx, fz] of RECT_PROBE) {
          if (!isFree(x + fx * w, z + fz * d, y, pkg, rect, 1.4)) { ok = false; break; }
        }
        if (!ok) continue;
        if (rng() < 0.55 && lawnRects.length < LAWN_CAP) {
          lawnRects.push({ x, z, y, w, d });
          plantCluster(x, z, y, pkg, rect, true); // Park: Bäume auf dem Rasen
        } else {
          padRects.push({ x, z, y, w, d });       // Beton-Freifläche
        }
        break;
      }
    }
  }

  function seedArea(rect, y, pkg, area) {
    seedRects(rect, y, pkg, area);
    const clusters = Math.min(8, Math.max(1, Math.round(area / 850)));
    for (let c = 0; c < clusters && treeCount < TREE_CAP; c++) {
      for (let attempt = 0; attempt < 14; attempt++) {
        const x = rect.x0 + rng() * (rect.x1 - rect.x0);
        const z = rect.z0 + rng() * (rect.z1 - rect.z0);
        if (isFree(x, z, y, pkg, rect, 2.2)) { plantCluster(x, z, y, pkg, rect); break; }
      }
    }
  }

  // Plattformen (Plaza-Bereiche zwischen Ring, Straßen und Gebäuden)
  for (const s of slabs) {
    if (treeCount >= TREE_CAP) break;
    const rect = { x0: s.x - s.w / 2, x1: s.x + s.w / 2, z0: s.z - s.d / 2, z1: s.z + s.d / 2 };
    seedArea(rect, s.topY, s.fqn, s.w * s.d);
  }
  // Boden: Stadtgebiet + Grüngürtel — bleibt auf der Insel (Wasser beginnt bei +28).
  const belt = 18;
  const groundRect = { x0: -spanX / 2 - belt, x1: spanX / 2 + belt, z0: -spanZ / 2 - belt, z1: spanZ / 2 + belt };
  seedArea(groundRect, 0, '<root>', (spanX + 2 * belt) * (spanZ + 2 * belt) * 0.55);

  // ---- Instanzen bauen --------------------------------------------------------
  const m4 = new THREE.Matrix4(), q = new THREE.Quaternion();
  const p = new THREE.Vector3(), sc = new THREE.Vector3();
  const col = new THREE.Color();

  if (lawns.length) {
    const mesh = new THREE.InstancedMesh(
      new THREE.CylinderGeometry(1, 1, 1, 14),
      new THREE.MeshStandardMaterial({ color: 0x2f4c28, roughness: 0.95, metalness: 0 }),
      lawns.length);
    mesh.receiveShadow = true;
    lawns.forEach((l, i) => {
      p.set(l.x, l.y + 0.045, l.z); sc.set(l.r, 0.09, l.r);
      m4.compose(p, q, sc); mesh.setMatrixAt(i, m4);
    });
    mesh.instanceMatrix.needsUpdate = true;
    group.add(mesh);
  }
  if (lawnRects.length) {
    const mesh = new THREE.InstancedMesh(
      new THREE.BoxGeometry(1, 1, 1),
      new THREE.MeshStandardMaterial({ color: 0x37582e, roughness: 0.95, metalness: 0 }),
      lawnRects.length);
    mesh.receiveShadow = true;
    lawnRects.forEach((r, i) => {
      p.set(r.x, r.y + 0.035, r.z); sc.set(r.w, 0.07, r.d);
      m4.compose(p, q, sc); mesh.setMatrixAt(i, m4);
    });
    mesh.instanceMatrix.needsUpdate = true;
    group.add(mesh);
  }
  if (padRects.length) {
    const mesh = new THREE.InstancedMesh(
      new THREE.BoxGeometry(1, 1, 1),
      new THREE.MeshStandardMaterial({ color: 0x6d7176, roughness: 0.88, metalness: 0, envMapIntensity: 0.15 }),
      padRects.length);
    mesh.receiveShadow = true;
    padRects.forEach((r, i) => {
      p.set(r.x, r.y + 0.028, r.z); sc.set(r.w, 0.055, r.d);
      m4.compose(p, q, sc); mesh.setMatrixAt(i, m4);
    });
    mesh.instanceMatrix.needsUpdate = true;
    group.add(mesh);
  }
  if (trunks.length) {
    const mesh = new THREE.InstancedMesh(
      new THREE.CylinderGeometry(1, 1.25, 1, 6),
      new THREE.MeshStandardMaterial({ color: 0x4a3421, roughness: 0.95, metalness: 0 }),
      trunks.length);
    mesh.castShadow = true;
    trunks.forEach((t, i) => {
      p.set(t.x, t.y + t.h / 2, t.z); sc.set(t.radius, t.h, t.radius);
      m4.compose(p, q, sc); mesh.setMatrixAt(i, m4);
    });
    mesh.instanceMatrix.needsUpdate = true;
    group.add(mesh);
  }
  const leafMat = new THREE.MeshStandardMaterial({
    color: 0xffffff, roughness: 0.9, metalness: 0, envMapIntensity: 0.12,
  });
  if (crownsRound.length) {
    const mesh = new THREE.InstancedMesh(new THREE.IcosahedronGeometry(1, 1), leafMat, crownsRound.length);
    mesh.castShadow = true;
    crownsRound.forEach((c, i) => {
      p.set(c.x, c.y, c.z); sc.set(c.r, c.r * 0.92, c.r);
      m4.compose(p, q, sc); mesh.setMatrixAt(i, m4);
      mesh.setColorAt(i, col.setRGB(0.16 * c.tint, 0.30 * c.tint, 0.13 * c.tint));
    });
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    group.add(mesh);
  }
  if (crownsCone.length) {
    const mesh = new THREE.InstancedMesh(new THREE.ConeGeometry(1, 1, 8), leafMat.clone(), crownsCone.length);
    mesh.castShadow = true;
    crownsCone.forEach((c, i) => {
      p.set(c.x, c.y + c.h / 2, c.z); sc.set(c.r, c.h, c.r);
      m4.compose(p, q, sc); mesh.setMatrixAt(i, m4);
      mesh.setColorAt(i, col.setRGB(0.11 * c.tint, 0.26 * c.tint, 0.14 * c.tint));
    });
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    group.add(mesh);
  }
  if (bushes.length) {
    const mesh = new THREE.InstancedMesh(new THREE.IcosahedronGeometry(1, 1), leafMat.clone(), bushes.length);
    mesh.castShadow = true;
    bushes.forEach((b, i) => {
      p.set(b.x, b.y + b.r * 0.7, b.z); sc.set(b.r, b.r * 0.8, b.r);
      m4.compose(p, q, sc); mesh.setMatrixAt(i, m4);
      mesh.setColorAt(i, col.setRGB(0.15 * b.tint, 0.32 * b.tint, 0.15 * b.tint));
    });
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    group.add(mesh);
  }
  return group;
}
