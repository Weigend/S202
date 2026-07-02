// Structure202 · City3D renderer — Phase 0 Durchstich.
//
// Data-driven Manhattan view: renders REAL analysed classes (buildings) and
// packages (districts) from a CityModel JSON produced by the headless Java
// exporter (de.weigend.s202.ui.city3d.CityModelExporter) — no RNG.
//
// This is Structure202's own copy of the City3JS idea, starting minimal; the
// richer City3JS shaders (facade windows, coverage, SCC) come in Phase 1.

import * as THREE from 'three';
import { OrbitControls } from './vendor/OrbitControls.js';

const COL_W = 16;   // horizontal spacing between buildings in a level row
const ROW_D = 26;   // depth spacing between architecture levels
const BASE  = 8;    // building footprint size
const H_PER_LEVEL = 7;
const H_BASE = 6;

const hud = document.getElementById('hud');

init();

async function init() {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0b0f1a);
  scene.fog = new THREE.Fog(0x0b0f1a, 220, 620);

  const camera = new THREE.PerspectiveCamera(55, window.innerWidth / window.innerHeight, 0.1, 3000);
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(window.devicePixelRatio);
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.shadowMap.enabled = true;
  document.body.appendChild(renderer.domElement);

  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.target.set(0, 10, 0);

  // Lights
  scene.add(new THREE.HemisphereLight(0xbcd2ff, 0x0a0e16, 0.9));
  const sun = new THREE.DirectionalLight(0xffffff, 1.4);
  sun.position.set(120, 200, 80);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.left = -400; sun.shadow.camera.right = 400;
  sun.shadow.camera.top = 400; sun.shadow.camera.bottom = -400;
  scene.add(sun);

  let city;
  try {
    const res = await fetch('./city.json', { cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    city = await res.json();
  } catch (e) {
    hud.innerHTML = '<b>Could not load city.json</b><br>' + e +
      '<br><br>Run the exporter, then serve this folder over http:' +
      '<br>python3 -m http.server';
    return;
  }

  const { buildings, group, bounds } = buildCity(city, scene);

  // Ground
  const groundSize = Math.max(bounds.w, bounds.d) * 1.6 + 200;
  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(groundSize, groundSize),
    new THREE.MeshStandardMaterial({ color: 0x0d1424, roughness: 1 }));
  ground.rotation.x = -Math.PI / 2;
  ground.position.set(bounds.cx, 0, bounds.cz);
  ground.receiveShadow = true;
  scene.add(ground);
  const grid = new THREE.GridHelper(groundSize, Math.round(groundSize / COL_W), 0x1e2a44, 0x141d31);
  grid.position.set(bounds.cx, 0.02, bounds.cz);
  scene.add(grid);

  // Frame the camera on the city
  const span = Math.max(bounds.w, bounds.d);
  camera.position.set(bounds.cx + span * 0.7, span * 0.7 + 60, bounds.cz + span * 1.1 + 80);
  controls.target.set(bounds.cx, 12, bounds.cz);
  controls.update();

  hud.innerHTML = renderHud(city);
  document.title = `City3D · ${city.buildings.length} classes · ${city.districts.length} packages`;

  // Hover picking
  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();
  let hovered = null;
  renderer.domElement.addEventListener('pointermove', (ev) => {
    pointer.x = (ev.clientX / window.innerWidth) * 2 - 1;
    pointer.y = -(ev.clientY / window.innerHeight) * 2 + 1;
    raycaster.setFromCamera(pointer, camera);
    const hits = raycaster.intersectObjects(buildings, false);
    const hit = hits.length ? hits[0].object : null;
    if (hit !== hovered) {
      if (hovered) hovered.material.emissive.setHex(hovered.userData.emissive0);
      hovered = hit;
      if (hovered) {
        hovered.userData.emissive0 = hovered.material.emissive.getHex();
        hovered.material.emissive.setHex(0x334155);
        const b = hovered.userData.building;
        hud.innerHTML = renderHud(city) +
          `<hr style="border-color:#1e293b"><b>${b.simpleName}</b>` +
          (b.isInterface ? ' <i>(interface)</i>' : '') +
          `<br>${b.fullName}` +
          `<br>level ${b.architectureLevel} · fanIn ${b.fanIn} · fanOut ${b.fanOut}`;
      } else {
        hud.innerHTML = renderHud(city);
      }
    }
  });

  window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });

  renderer.setAnimationLoop(() => {
    controls.update();
    renderer.render(scene, camera);
  });
}

function buildCity(city, scene) {
  const group = new THREE.Group();
  scene.add(group);

  // Group buildings by architecture level (row), order within a row by
  // district then the analyser's horizontal order for a stable, grouped layout.
  const byLevel = new Map();
  for (const b of city.buildings) {
    if (!byLevel.has(b.architectureLevel)) byLevel.set(b.architectureLevel, []);
    byLevel.get(b.architectureLevel).push(b);
  }
  const levels = [...byLevel.keys()].sort((a, b) => a - b);
  const maxLevel = Math.max(1, city.maxLevel);

  const buildings = [];
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;

  levels.forEach((level, rowIdx) => {
    const row = byLevel.get(level).sort((a, b) =>
      (a.districtFullName || '').localeCompare(b.districtFullName || '') ||
      a.horizontalOrder - b.horizontalOrder ||
      a.simpleName.localeCompare(b.simpleName));

    const rowWidth = (row.length - 1) * COL_W;
    const z = rowIdx * ROW_D;

    row.forEach((b, colIdx) => {
      const x = colIdx * COL_W - rowWidth / 2;
      const h = H_BASE + level * H_PER_LEVEL;
      const w = BASE + Math.min(6, Math.log2(1 + b.fanIn) * 2); // fan-in widens
      const geo = new THREE.BoxGeometry(w, h, w);
      const mat = new THREE.MeshStandardMaterial({
        color: colorForLevel(level, maxLevel),
        roughness: 0.55, metalness: 0.15,
        emissive: b.isInterface ? 0x14324a : 0x000000,
      });
      const mesh = new THREE.Mesh(geo, mat);
      mesh.position.set(x, h / 2, z);
      mesh.castShadow = true;
      mesh.receiveShadow = true;
      mesh.userData.building = b;
      group.add(mesh);
      buildings.push(mesh);

      minX = Math.min(minX, x); maxX = Math.max(maxX, x);
      minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    });
  });

  const cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
  return { buildings, group, bounds: { cx, cz, w: maxX - minX + COL_W, d: maxZ - minZ + ROW_D } };
}

// Blue (low level) → amber → red (high level): shows the architectural layering.
function colorForLevel(level, maxLevel) {
  const t = maxLevel <= 0 ? 0 : level / maxLevel;
  const c = new THREE.Color();
  c.setHSL(0.62 - 0.62 * t, 0.65, 0.55);
  return c;
}

function renderHud(city) {
  let legend = '<div class="legend">';
  const max = Math.max(1, city.maxLevel);
  for (let l = 0; l <= city.maxLevel; l++) {
    const col = '#' + colorForLevel(l, max).getHexString();
    legend += `<span class="swatch" style="background:${col}"></span>L${l} `;
  }
  legend += '</div>';
  return `<b>Structure202 · City3D</b> <span style="color:#64748b">Phase 0</span>` +
    `<br>${city.buildings.length} classes · ${city.districts.length} packages · ` +
    `${city.dependencies.length} deps · maxLevel ${city.maxLevel}` +
    `<br><span style="color:#94a3b8">height = architecture level</span>` + legend;
}
