import * as THREE from 'three';
import { Atmosphere } from './sky.js';
import { buildCity } from './city.js';
import { buildTraffic } from './traffic.js';
import { Navigation } from './controls.js';
import { createComposer } from './postfx.js';
import { createRain } from './weather.js';

const app = document.getElementById('app');

// ---- Renderer ---------------------------------------------------------------
const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 0.92;
app.appendChild(renderer.domElement);

// ---- Szene & Kamera ---------------------------------------------------------
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(55, window.innerWidth / window.innerHeight, 1, 30000);
camera.position.set(420, 260, 520);

// ---- Welt -------------------------------------------------------------------
const atmosphere = new Atmosphere(scene, renderer);

// Load the analysed architecture (exported by CityModelExporter) and build the
// city from it. The view is unchanged; only the generator is data-driven now.
async function loadCityModel() {
  const res = await fetch('./city.json', { cache: 'no-store' });
  if (!res.ok) throw new Error('city.json ' + res.status);
  return res.json();
}
let cityModel = await loadCityModel();
let city = buildCity(scene, atmosphere, cityModel);
// Traffic needs straight full-span avenues; the nested layout's streets are the
// gaps between terraced platforms, so it is disabled (no such avenues) for now.
function makeTraffic() {
  return (city.streetsX.length || city.streetsZ.length)
    ? buildTraffic(scene, city.streetsX, city.streetsZ, city.bounds)
    : { update() {}, dispose() {} };
}
let traffic = makeTraffic();
document.title = `City3D · ${cityModel.buildings.length} classes · ${cityModel.districts.length} packages`;

const nav = new Navigation(camera, renderer.domElement);
// Frame the camera on the actual (compact, nested) city instead of a fixed far pose.
function frameCity() {
  const span = Math.max(city.bounds.spanX, city.bounds.spanZ, 120);
  camera.position.set(span * 0.55, span * 0.55 + 40, span * 0.9 + 60);
  nav.orbit.target.set(0, 12, 0);
  nav.orbit.update();
}
frameCity();
nav.setCinematic(true);

const { composer, bloom, gtao, grade } = createComposer(renderer, scene, camera);

// ---- Picking: click a class building -> info overlay ------------------------
const infoEl = document.getElementById('info');
const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();
let pressXY = null;

// Selection highlight: a bright outline around the selected class' boxes or the
// selected package's platform.
const highlightGroup = new THREE.Group();
highlightGroup.name = 'selection';
scene.add(highlightGroup);
const selEdgesBuilding = new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1).translate(0, 0.5, 0)); // base at y=0
const selEdgesSlab = new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1));                          // centred
const selMat = new THREE.LineBasicMaterial({ color: 0x5fc8ff, transparent: true, opacity: 0.95, depthTest: false });
const selMat4 = new THREE.Matrix4();
const selScale = new THREE.Matrix4().makeScale(1.06, 1.03, 1.06);

function clearHighlight() {
  for (let i = highlightGroup.children.length - 1; i >= 0; i--) highlightGroup.remove(highlightGroup.children[i]);
}

function outlineInstances(mesh, edges, ids) {
  if (!mesh || !ids.length) return;
  mesh.updateMatrixWorld();
  for (const i of ids) {
    mesh.getMatrixAt(i, selMat4);
    selMat4.premultiply(mesh.matrixWorld).multiply(selScale);
    const line = new THREE.LineSegments(edges, selMat);
    line.matrixAutoUpdate = false;
    line.matrix.copy(selMat4);
    line.renderOrder = 999;
    highlightGroup.add(line);
  }
}

function highlight(sel) {
  clearHighlight();
  if (!sel) return;
  if (sel.kind === 'class') {
    const ids = [];
    for (let i = 0; i < city.boxFqns.length; i++) if (city.boxFqns[i] === sel.fqn) ids.push(i);
    outlineInstances(city.buildingMesh, selEdgesBuilding, ids);
  } else {
    const i = city.slabFqns.indexOf(sel.fqn);
    if (i >= 0) outlineInstances(city.slabPickMesh, selEdgesSlab, [i]);
  }
}

function hideInfo() { infoEl.style.display = 'none'; clearHighlight(); }

function rows(pairs) {
  return pairs.map(([k, v]) => `<div class="row"><span class="k">${k}</span><span class="v">${v}</span></div>`).join('');
}

function showClass(b) {
  const tags =
    ' <span class="tag" style="background:rgba(127,179,255,.14);color:#7fb3ff">class</span>' +
    (b.isInterface ? ' <span class="tag iface">interface</span>' : '') +
    (b.inCycle ? ' <span class="tag cycle">⟲ cycle</span>' : '');
  infoEl.innerHTML =
    '<span class="close" title="schließen">×</span>' +
    `<div class="cls">${b.simpleName}${tags}</div>` +
    `<div class="fqn">${b.fullName}</div>` +
    rows([
      ['package', `<span style="color:#7f97c2">${b.districtFullName || '–'}</span>`],
      ['architecture level', b.architectureLevel],
      ['methods', b.methodCount >= 0 ? b.methodCount : '–'],
      ['fan-in / fan-out', `${b.fanIn} / ${b.fanOut}`],
    ]);
}

function showPackage(d) {
  const classes = cityModel.buildings.filter((b) => b.districtFullName === d.fullName).length;
  const subs = cityModel.districts.filter((x) => x.parentFullName === d.fullName).length;
  const tags =
    ' <span class="tag" style="background:rgba(214,184,90,.2);color:#d6b85a">package</span>' +
    (d.inCycle ? ' <span class="tag cycle">⟲ tangle</span>' : '');
  infoEl.innerHTML =
    '<span class="close" title="schließen">×</span>' +
    `<div class="cls">${d.simpleName}${tags}</div>` +
    `<div class="fqn">${d.fullName}</div>` +
    rows([
      ['architecture level', d.architectureLevel],
      ['nesting depth', d.nestingDepth],
      ['direct classes', classes],
      ['sub-packages', subs],
    ]);
}

function select(sel) {
  if (!sel) { hideInfo(); return; }
  if (sel.kind === 'class') showClass(sel.data); else showPackage(sel.data);
  infoEl.style.display = 'block';
  infoEl.querySelector('.close').onclick = hideInfo;
  highlight(sel);
}

function pick(clientX, clientY) {
  const targets = [];
  if (city.buildingMesh) targets.push(city.buildingMesh);
  if (city.slabPickMesh) targets.push(city.slabPickMesh);
  if (!targets.length) return null;
  pointer.x = (clientX / window.innerWidth) * 2 - 1;
  pointer.y = -(clientY / window.innerHeight) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const hit = raycaster.intersectObjects(targets, false)[0];
  if (!hit || hit.instanceId == null) return null;
  if (hit.object === city.buildingMesh) {
    const fqn = city.boxFqns[hit.instanceId];
    const b = fqn && cityModel.buildings.find((x) => x.fullName === fqn);
    return b ? { kind: 'class', data: b, fqn } : null;
  }
  const fqn = city.slabFqns[hit.instanceId];
  const d = fqn && cityModel.districts.find((x) => x.fullName === fqn);
  return d ? { kind: 'package', data: d, fqn } : null;
}

renderer.domElement.addEventListener('pointerdown', (e) => { pressXY = [e.clientX, e.clientY]; });
renderer.domElement.addEventListener('pointerup', (e) => {
  if (!pressXY) return;
  const moved = Math.hypot(e.clientX - pressXY[0], e.clientY - pressXY[1]);
  pressXY = null;
  if (moved > 5) return; // an orbit drag, not a click
  select(pick(e.clientX, e.clientY));
});
renderer.domElement.addEventListener('pointermove', (e) => {
  if (pressXY) return;
  renderer.domElement.style.cursor = pick(e.clientX, e.clientY) ? 'pointer' : '';
});

const rain = createRain(scene);

// Aktuelle Nässe — muss nach "Neu bauen" auf das frische Stadt-Material zurück.
let wetness = 0;

// ---- Hilfsfunktion: City-Licht ans Material reichen --------------------------
function syncCityLight() {
  city.material.userData.uniforms.uLightFactor.value = atmosphere.cityLightFactor;
  for (const material of city.nightMaterials ?? []) {
    material.emissiveIntensity = (material.userData.nightBase ?? 0.04)
      + atmosphere.cityLightFactor * (material.userData.nightScale ?? 1);
  }
}
syncCityLight();

// ============================ UI =============================================
const $ = (id) => document.getElementById(id);

function timeLabel(t) {
  if (t < 0.08 || t > 0.92) return 'Nacht';
  if (t < 0.13 || t > 0.87) return 'Blaue Stunde';
  if (t < 0.25 || t > 0.75) return 'Goldene Stunde';
  return 'Tag';
}

$('s-time').addEventListener('input', (e) => {
  const t = parseFloat(e.target.value);
  atmosphere.setTime(t);
  syncCityLight();
  $('v-time').textContent = timeLabel(t);
});

$('s-bloom').addEventListener('input', (e) => {
  bloom.strength = parseFloat(e.target.value);
  $('v-bloom').textContent = bloom.strength.toFixed(2);
});

$('s-fog').addEventListener('input', (e) => {
  const f = parseFloat(e.target.value);
  atmosphere.setFog(f);
  $('v-fog').textContent = f.toFixed(2);
});

$('s-wet').addEventListener('input', (e) => {
  wetness = parseFloat(e.target.value);
  city.setWetness(wetness);
  atmosphere.setWetness(wetness);
  $('v-wet').textContent = wetness.toFixed(2);
});

// Effekt-Schalter — bei riesigen Architekturen für mehr FPS abschaltbar.
const toggle = (id, pass) => {
  const btn = $(id);
  btn.addEventListener('click', () => {
    pass.enabled = !pass.enabled;
    btn.classList.toggle('active', pass.enabled);
  });
};
toggle('b-ssao', gtao);
toggle('b-grade', grade);

const cineBtn = $('b-cinematic');
cineBtn.addEventListener('click', () => {
  const on = !cineBtn.classList.contains('active');
  cineBtn.classList.toggle('active', on);
  nav.setCinematic(on);
});

$('b-regen').addEventListener('click', async () => {
  hideInfo();
  traffic.dispose();
  city.dispose();
  cityModel = await loadCityModel(); // re-read city.json (pick up a fresh export)
  city = buildCity(scene, atmosphere, cityModel);
  traffic = makeTraffic();
  frameCity();
  syncCityLight();
  city.setWetness(wetness); // Nässe auf das neue Stadt-Material übertragen
});

// Fly-Hilfe ein-/ausblenden
nav.onModeChange = (mode) => {
  $('fly-help').style.display = mode === 'fly' ? 'flex' : 'none';
};

// ============================ Resize =========================================
window.addEventListener('resize', () => {
  const w = window.innerWidth, h = window.innerHeight;
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  renderer.setSize(w, h);
  composer.setSize(w, h);
});

// ============================ Loop ===========================================
const clock = new THREE.Clock();
let envAccum = 0;
let firstFrame = true;

// FPS-Anzeige (geglättet) + Gebäudezahl — fürs Skalierungstesten via ?cols=&rows=
const perfEl = $('perf');
let fpsAccum = 0, fpsFrames = 0, fps = 0;
function updatePerf(dt) {
  fpsAccum += dt; fpsFrames++;
  if (fpsAccum >= 0.5) {
    fps = Math.round(fpsFrames / fpsAccum);
    fpsAccum = 0; fpsFrames = 0;
    const s = city.stats;
    perfEl.textContent = `${fps} fps · ${s.buildings} Gebäude · ${s.grid[0]}×${s.grid[1]}`;
  }
}

function animate() {
  requestAnimationFrame(animate);
  const dt = Math.min(clock.getDelta(), 0.05);

  nav.update(dt);
  traffic.update(dt);
  updatePerf(dt);
  grade.uniforms.uTime.value = clock.elapsedTime; // Filmkorn pro Frame variieren
  // Regen setzt erst über leichter Nässe ein (20% = nur feucht, kein Regen).
  rain.update(dt, camera, THREE.MathUtils.smoothstep(wetness, 0.25, 0.9));

  // Environment-Map gedrosselt aktualisieren (für Reflexionen)
  envAccum += dt;
  if (envAccum > 0.2) { atmosphere.updateEnvironment(); envAccum = 0; }

  composer.render();

  if (firstFrame) {
    firstFrame = false;
    const loader = document.getElementById('loader');
    loader.classList.add('hidden');
    setTimeout(() => loader.remove(), 900);
  }
}
animate();
