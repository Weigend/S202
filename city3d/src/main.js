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
let traffic = buildTraffic(scene, city.streetsX, city.streetsZ, city.bounds);
document.title = `City3D · ${cityModel.buildings.length} classes · ${cityModel.districts.length} packages`;

const nav = new Navigation(camera, renderer.domElement);
nav.orbit.target.set(0, 70, 0);
nav.setCinematic(true);

const { composer, bloom, gtao, grade } = createComposer(renderer, scene, camera);

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
  traffic.dispose();
  city.dispose();
  cityModel = await loadCityModel(); // re-read city.json (pick up a fresh export)
  city = buildCity(scene, atmosphere, cityModel);
  traffic = buildTraffic(scene, city.streetsX, city.streetsZ, city.bounds);
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
