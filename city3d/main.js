// Structure202 · City3D renderer — Phase 1 (data-driven, districts + facades).
//
// Renders the analysed architecture as a software city from a CityModel JSON
// (produced by de.weigend.s202.ui.city3d.CityModelExporter):
//   - packages = districts (raised platforms, grouped, labelled)
//   - classes  = buildings with procedural window facades
//   - height   = method count (floors), fallback architecture level
//   - width    = fan-in
//   - colour   = architecture level (blue -> amber -> red)
//   - red facade = class is part of an architectural cycle (back-edge)
//
// This is Structure202's own copy of the City3JS idea; the facade shader is a
// simplified port (see facade.js). Runs in a normal browser over http.

import * as THREE from 'three';
import { OrbitControls } from './vendor/OrbitControls.js';
import { facadeMaterial } from './facade.js';

const SLOT = 13;     // grid slot per building inside a district
const PAD = 7;       // district platform padding
const GAP = 12;      // gap between districts
const MAX_ROW = 280; // shelf-pack row width before wrapping
const BASE_H = 4;    // building base height
const FLOOR = 2.6;   // height per method ("floor")
const SLAB_H = 1.2;  // district platform thickness

const hud = document.getElementById('hud');

init();

async function init() {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0b0f1a);
  scene.fog = new THREE.Fog(0x0b0f1a, 260, 760);

  const camera = new THREE.PerspectiveCamera(52, innerWidth / innerHeight, 0.1, 4000);
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(devicePixelRatio);
  renderer.setSize(innerWidth, innerHeight);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  document.body.appendChild(renderer.domElement);

  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;

  scene.add(new THREE.HemisphereLight(0xbcd2ff, 0x0a0e16, 0.85));
  const sun = new THREE.DirectionalLight(0xffffff, 1.5);
  sun.position.set(140, 230, 90);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -400, right: 400, top: 400, bottom: -400 });
  scene.add(sun);

  let city;
  try {
    const res = await fetch('./city.json', { cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    city = await res.json();
  } catch (e) {
    hud.innerHTML = '<b>Could not load city.json</b><br>' + e +
      '<br><br>Serve this folder over http: <code>python3 -m http.server</code>';
    return;
  }

  const { buildings, bounds } = buildCity(city, scene);

  const groundSize = Math.max(bounds.w, bounds.d) * 1.8 + 240;
  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(groundSize, groundSize),
    new THREE.MeshStandardMaterial({ color: 0x0c1220, roughness: 1 }));
  ground.rotation.x = -Math.PI / 2;
  ground.receiveShadow = true;
  scene.add(ground);
  const grid = new THREE.GridHelper(groundSize, Math.round(groundSize / SLOT), 0x1a2540, 0x121a2c);
  grid.position.y = 0.01;
  scene.add(grid);

  const span = Math.max(bounds.w, bounds.d);
  camera.position.set(span * 0.6, span * 0.65 + 70, span * 1.05 + 90);
  controls.target.set(0, 10, 0);
  controls.update();

  hud.innerHTML = renderHud(city);

  // Hover picking
  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();
  let hovered = null;
  renderer.domElement.addEventListener('pointermove', (ev) => {
    pointer.x = (ev.clientX / innerWidth) * 2 - 1;
    pointer.y = -(ev.clientY / innerHeight) * 2 + 1;
    raycaster.setFromCamera(pointer, camera);
    const hits = raycaster.intersectObjects(buildings, false);
    const hit = hits.length ? hits[0].object : null;
    if (hit !== hovered) {
      if (hovered) hovered.material.emissive.setHex(0x000000);
      hovered = hit;
      if (hovered) {
        hovered.material.emissive.setHex(0x2a3548);
        const b = hovered.userData.building;
        hud.innerHTML = renderHud(city) +
          `<hr style="border-color:#1e293b"><b>${b.simpleName}</b>` +
          (b.isInterface ? ' <i>(interface)</i>' : '') +
          (b.inCycle ? ' <span style="color:#f87171">⟳ cycle</span>' : '') +
          `<br><span style="color:#94a3b8">${b.fullName}</span>` +
          `<br>level ${b.architectureLevel} · ${b.methodCount >= 0 ? b.methodCount + ' methods' : 'methods n/a'}` +
          ` · fanIn ${b.fanIn} · fanOut ${b.fanOut}`;
      } else {
        hud.innerHTML = renderHud(city);
      }
    }
  });

  addEventListener('resize', () => {
    camera.aspect = innerWidth / innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(innerWidth, innerHeight);
  });

  document.title = `City3D · ${city.buildings.length} classes · ${city.districts.length} packages`;
  renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, camera); });
}

function buildCity(city, scene) {
  const group = new THREE.Group();
  scene.add(group);

  const districtMeta = new Map(city.districts.map((d) => [d.fullName, d]));

  // Group buildings by their package (district).
  const byDistrict = new Map();
  for (const b of city.buildings) {
    const k = b.districtFullName || '(root)';
    if (!byDistrict.has(k)) byDistrict.set(k, []);
    byDistrict.get(k).push(b);
  }
  const keys = [...byDistrict.keys()].sort((a, b) => {
    const da = districtMeta.get(a), db = districtMeta.get(b);
    return (da?.architectureLevel ?? 0) - (db?.architectureLevel ?? 0) || a.localeCompare(b);
  });

  const buildings = [];
  let cx = 0, cz = 0, rowDepth = 0;
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;

  for (const key of keys) {
    const list = byDistrict.get(key).sort((a, b) =>
      a.horizontalOrder - b.horizontalOrder || a.simpleName.localeCompare(b.simpleName));
    const icols = Math.ceil(Math.sqrt(list.length));
    const irows = Math.ceil(list.length / icols);
    const blockW = icols * SLOT + PAD * 2;
    const blockD = irows * SLOT + PAD * 2;

    if (cx > 0 && cx + blockW > MAX_ROW) { cx = 0; cz += rowDepth + GAP; rowDepth = 0; }
    const ox = cx, oz = cz;

    const dm = districtMeta.get(key);
    const dLevel = dm?.architectureLevel ?? 0;

    // District platform
    const slab = new THREE.Mesh(
      new THREE.BoxGeometry(blockW, SLAB_H, blockD),
      new THREE.MeshStandardMaterial({
        color: districtColor(dLevel, city.maxLevel),
        roughness: 0.95, metalness: 0.04,
        emissive: dm?.inCycle ? 0x3a0a08 : 0x000000,
      }));
    slab.position.set(ox + blockW / 2, SLAB_H / 2, oz + blockD / 2);
    slab.receiveShadow = true;
    group.add(slab);

    const label = makeLabel((dm ? dm.simpleName : key) + (dm?.inCycle ? ' ⟳' : ''));
    label.position.set(ox + blockW / 2, SLAB_H + 3, oz + 3.5);
    group.add(label);

    list.forEach((b, i) => {
      const gx = i % icols, gy = Math.floor(i / icols);
      const x = ox + PAD + gx * SLOT + SLOT / 2;
      const z = oz + PAD + gy * SLOT + SLOT / 2;
      const floors = b.methodCount > 0 ? b.methodCount : b.architectureLevel + 1;
      const h = BASE_H + floors * FLOOR;
      const w = 6 + Math.min(4, Math.log2(1 + b.fanIn) * 1.5);
      const seed = hashStr(b.fullName);
      const lit = 0.14 + 0.5 * frac(seed * 1.7);

      const mat = facadeMaterial(
        colorForLevel(b.architectureLevel, city.maxLevel),
        new THREE.Vector3(w, h, w), seed, lit, b.inCycle);
      const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, w), mat);
      mesh.position.set(x, SLAB_H + h / 2, z);
      mesh.castShadow = true;
      mesh.receiveShadow = true;
      mesh.userData.building = b;
      group.add(mesh);
      buildings.push(mesh);
    });

    minX = Math.min(minX, ox); maxX = Math.max(maxX, ox + blockW);
    minZ = Math.min(minZ, oz); maxZ = Math.max(maxZ, oz + blockD);
    cx += blockW + GAP;
    rowDepth = Math.max(rowDepth, blockD);
  }

  // Centre the whole city on the origin.
  const mx = (minX + maxX) / 2, mz = (minZ + maxZ) / 2;
  group.position.set(-mx, 0, -mz);
  return { buildings, bounds: { w: maxX - minX, d: maxZ - minZ } };
}

// Blue (low level) -> amber -> red (high level).
function colorForLevel(level, maxLevel) {
  const t = maxLevel <= 0 ? 0 : level / maxLevel;
  return new THREE.Color().setHSL(0.62 - 0.62 * t, 0.62, 0.55).getHex();
}
function districtColor(level, maxLevel) {
  const t = maxLevel <= 0 ? 0 : level / maxLevel;
  return new THREE.Color().setHSL(0.62 - 0.62 * t, 0.30, 0.16).getHex();
}

function makeLabel(text) {
  const c = document.createElement('canvas');
  c.width = 256; c.height = 64;
  const ctx = c.getContext('2d');
  ctx.font = 'bold 34px system-ui, sans-serif';
  ctx.fillStyle = '#cbd5e1';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, 128, 34, 248);
  const tex = new THREE.CanvasTexture(c);
  tex.minFilter = THREE.LinearFilter;
  const spr = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false }));
  spr.scale.set(26, 6.5, 1);
  return spr;
}

function hashStr(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return (h >>> 0) / 4294967295 * 1000;
}
function frac(x) { return x - Math.floor(x); }

function renderHud(city) {
  const max = Math.max(1, city.maxLevel);
  let legend = '<div class="legend">';
  for (let l = 0; l <= city.maxLevel; l++) {
    legend += `<span class="swatch" style="background:#${colorForLevel(l, max).toString(16).padStart(6, '0')}"></span>L${l} `;
  }
  legend += '<span class="swatch" style="background:#8c1410"></span>cycle</div>';
  return `<b>Structure202 · City3D</b> <span style="color:#64748b">Phase 1</span>` +
    `<br>${city.buildings.length} classes · ${city.districts.length} packages · ${city.dependencies.length} deps` +
    `<br><span style="color:#94a3b8">height = methods · colour = level · red = cycle</span>` + legend;
}
