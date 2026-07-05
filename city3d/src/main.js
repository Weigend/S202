import * as THREE from 'three';
import { Atmosphere } from './sky.js';
import { buildCity } from './city.js';
import { buildTrips } from './roads.js';
import { Traffic } from './vehicles.js';
import { Navigation } from './controls.js';
import { createComposer } from './postfx.js';
import { createRain } from './weather.js';
import { DependencyViz } from './deps.js';

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
// Verkehr = Abhängigkeiten: für jede (gesampelte) Kante fährt ein Pod die
// Straßenroute von der nutzenden zur genutzten Klasse (Dijkstra über den
// Straßengraphen; cyan = regelkonform, rot = Verstoß).
let trafficDensity = 0.5;

// Kreuzungslast-Hotspots: sanft glühende Flächen auf den meistbefahrenen
// Kreuzungen (wie viele Abhängigkeits-Routen dort durchlaufen) — zuschaltbar
// über den "Hotspots"-Knopf, Fläche/Helligkeit wachsen mit der Last.
let hotspotsOn = false;
function makeHotspots(graph, nodeLoad) {
  const entries = [...nodeLoad.entries()]
    .filter(([, n]) => n >= 3)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 28);
  if (!entries.length) return { update() {}, setVisible() {}, dispose() {} };
  const geo = new THREE.CircleGeometry(1, 24);
  geo.rotateX(-Math.PI / 2);
  const mat = new THREE.MeshBasicMaterial({
    color: 0xff9a30, transparent: true, opacity: 0.2, toneMapped: false,
    depthWrite: false, blending: THREE.AdditiveBlending,
  });
  const mesh = new THREE.InstancedMesh(geo, mat, entries.length);
  const m4 = new THREE.Matrix4(), q = new THREE.Quaternion();
  entries.forEach(([id, n], i) => {
    const p = graph.nodes[id];
    const r = 2.4 + Math.sqrt(n) * 0.8;
    m4.compose(new THREE.Vector3(p.x, p.y + 0.16, p.z), q, new THREE.Vector3(r, 1, r));
    mesh.setMatrixAt(i, m4);
  });
  mesh.instanceMatrix.needsUpdate = true;
  mesh.visible = hotspotsOn;
  mesh.userData.noAO = true;
  scene.add(mesh);
  return {
    update(t) { mat.opacity = 0.14 + 0.14 * (0.5 + 0.5 * Math.sin(t * 2.2)); },
    setVisible(on) { mesh.visible = on; },
    dispose() { geo.dispose(); mat.dispose(); scene.remove(mesh); },
  };
}

let hotspots = { update() {}, setVisible() {}, dispose() {} };
function makeTraffic() {
  const { trips, nodeLoad } = buildTrips(city.roadGraph, cityModel);
  // Diagnose: wie viele Abhängigkeiten haben eine fahrbare Route gefunden?
  const nCars = trips.filter((t) => !t.local).length;
  console.info(`City3D traffic: ${trips.length} routes (${nCars} cars, ${trips.length - nCars} pedestrians) `
    + `of ${cityModel.dependencies?.length ?? 0} dependencies, ${city.roadGraph.nodes.length} graph nodes`);
  hotspots.dispose();
  hotspots = makeHotspots(city.roadGraph, nodeLoad);
  return new Traffic(scene, trips);
}
let traffic = makeTraffic();

// Verkehrsdichte = Regler × Tag/Nacht-Rhythmus (nachts leeren sich die Straßen).
function applyDensity() {
  traffic.setDensity(trafficDensity, atmosphere.dayFactor ?? 1);
}
applyDensity();
document.title = `City3D · ${cityModel.buildings.length} classes · ${cityModel.districts.length} packages`;

// ---- Abhängigkeits-Visualisierung (Bögen + Datenpakete über der Stadt) ------
let depMode = 'sel';     // 'off' | 'sel' | 'all' | 'viol'
let metricMode = 'off';  // 'off' | 'fanin' | 'fanout' | 'methods'
let deps = new DependencyViz(scene, cityModel, city.anchors);
deps.setMode(depMode);

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

// SSAO-Ausschluss: GTAO rendert die Szene intern mit Override-Material für
// Tiefe/Normalen neu. Overlay-Objekte (Sprites, additive Bögen, Glow) landen
// dabei als un-gebillboardete Quads im Tiefenpuffer und werfen "AO-Schatten"
// — die schwarzen Balken neben den Textlabels. Alles mit userData.noAO wird
// deshalb für die Dauer des GTAO-Passes ausgeblendet.
{
  const gtaoRender = gtao.render.bind(gtao);
  const hidden = [];
  gtao.render = (...args) => {
    hidden.length = 0;
    scene.traverse((o) => {
      if (o.userData.noAO && o.visible) { o.visible = false; hidden.push(o); }
    });
    gtaoRender(...args);
    for (const o of hidden) o.visible = true;
  };
}
atmosphere.clouds.userData.noAO = true;
atmosphere.stars.userData.noAO = true;

// ---- Picking: click a class building -> info overlay ------------------------
const infoEl = document.getElementById('info');
const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();
let pressXY = null;

// Selection highlight: a bright outline around the selected class' boxes or the
// selected package's platform.
const highlightGroup = new THREE.Group();
highlightGroup.name = 'selection';
highlightGroup.userData.noAO = true;
scene.add(highlightGroup);
const glowBoxBuilding = new THREE.BoxGeometry(1, 1, 1).translate(0, 0.5, 0); // base at y=0
const glowBoxSlab = new THREE.BoxGeometry(1, 1, 1);                          // centred
// Fresnel rim-glow shell: bright at the silhouette, transparent to the front;
// additive + the scene bloom turns it into an edge glow. No z-writing.
const glowMat = new THREE.ShaderMaterial({
  uniforms: { uColor: { value: new THREE.Color(0x7fe0ff) }, uPower: { value: 2.2 }, uIntensity: { value: 3.4 } },
  transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
  vertexShader: `
    varying vec3 vN; varying vec3 vV;
    void main() {
      vec4 wp = modelMatrix * vec4(position, 1.0);
      vN = normalize(mat3(modelMatrix) * normal);
      vV = normalize(cameraPosition - wp.xyz);
      gl_Position = projectionMatrix * viewMatrix * wp;
    }`,
  fragmentShader: `
    uniform vec3 uColor; uniform float uPower; uniform float uIntensity;
    varying vec3 vN; varying vec3 vV;
    void main() {
      float f = pow(1.0 - abs(dot(normalize(vN), normalize(vV))), uPower);
      gl_FragColor = vec4(uColor * uIntensity * f, f);
    }`,
});
const selMat4 = new THREE.Matrix4();
const selScale = new THREE.Matrix4().makeScale(1.1, 1.06, 1.1);

function clearHighlight() {
  for (let i = highlightGroup.children.length - 1; i >= 0; i--) {
    const o = highlightGroup.children[i];
    o.geometry?.dispose?.();
    if (o.material !== glowMat) o.material?.dispose?.();
    highlightGroup.remove(o);
  }
}

function glowInstances(mesh, geo, ids) {
  if (!mesh || !ids.length) return;
  mesh.updateMatrixWorld();
  for (const i of ids) {
    mesh.getMatrixAt(i, selMat4);
    selMat4.premultiply(mesh.matrixWorld).multiply(selScale);
    const shell = new THREE.Mesh(geo, glowMat);
    shell.matrixAutoUpdate = false;
    shell.matrix.copy(selMat4);
    shell.renderOrder = 999;
    highlightGroup.add(shell);
  }
}

function glowClass(fqn) {
  const ids = [];
  for (let i = 0; i < city.boxFqns.length; i++) if (city.boxFqns[i] === fqn) ids.push(i);
  glowInstances(city.buildingMesh, glowBoxBuilding, ids);
}

function highlight(sel) {
  clearHighlight();
  if (!sel) return;
  if (sel.kind === 'class') {
    glowClass(sel.fqn);
  } else if (sel.kind === 'pod') {
    // Fahrt: Quelle und Ziel glühen, die Route leuchtet auf der Straße.
    glowClass(sel.data.from);
    glowClass(sel.data.to);
    const pts = sel.data.path.map((p) => new THREE.Vector3(p.x, p.y + 0.35, p.z));
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(pts),
      new THREE.LineBasicMaterial({
        color: sel.data.violation ? 0xff5040 : sel.data.local ? 0x54e6b8 : 0x59d0ff,
        transparent: true, opacity: 0.95, blending: THREE.AdditiveBlending, depthWrite: false,
      }));
    line.renderOrder = 998;
    highlightGroup.add(line);
  } else {
    const i = city.slabFqns.indexOf(sel.fqn);
    if (i >= 0) glowInstances(city.slabPickMesh, glowBoxSlab, [i]);
  }
}

let currentSel = null;

function hideInfo() {
  infoEl.style.display = 'none';
  clearHighlight();
  currentSel = null;
  deps.setFocus(null);
}

function rows(pairs) {
  return pairs.map(([k, v]) => `<div class="row"><span class="k">${k}</span><span class="v">${v}</span></div>`).join('');
}

// Klickbare Abhängigkeits-Chips (uses / used by) für das Info-Panel.
function depListHtml(title, list, cls) {
  if (!list.length) return '';
  const MAX = 10;
  const items = list.slice(0, MAX).map((d) =>
    `<span class="dep ${d.violation ? 'viol' : cls}" data-fqn="${d.fqn}" title="${d.fqn}">${d.fqn.split('.').pop()}</span>`
  ).join('');
  const more = list.length > MAX ? `<span class="dep-more">+${list.length - MAX} weitere</span>` : '';
  return `<div class="dep-sec"><div class="dep-title">${title} (${list.length})</div><div class="dep-wrap">${items}${more}</div></div>`;
}

const flyBtnHtml = '<div class="btn-row" style="margin-top:12px"><button class="ui" data-fly>✈ Anfliegen</button></div>';

function showClass(b) {
  const tags =
    ' <span class="tag" style="background:rgba(127,179,255,.14);color:#7fb3ff">class</span>' +
    (b.isInterface ? ' <span class="tag iface">interface</span>' : '') +
    (b.inCycle ? ' <span class="tag cycle">⟲ cycle</span>' : '');
  const { uses, usedBy } = deps.edgesFor(b.fullName);
  infoEl.innerHTML =
    '<span class="close" title="schließen">×</span>' +
    `<div class="cls">${b.simpleName}${tags}</div>` +
    `<div class="fqn">${b.fullName}</div>` +
    rows([
      ['package', `<span style="color:#7f97c2">${b.districtFullName || '–'}</span>`],
      ['architecture level', b.architectureLevel],
      ['methods', b.methodCount >= 0 ? b.methodCount : '–'],
      ['fan-in / fan-out', `${b.fanIn} / ${b.fanOut}`],
    ]) +
    depListHtml('→ verwendet', uses, 'out') +
    depListHtml('← wird verwendet von', usedBy, 'in') +
    flyBtnHtml;
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
    ]) +
    flyBtnHtml;
}

// Fahrt-Info: welcher Pod fährt hier, von wo nach wo, und warum in dieser Farbe.
function showPod(t) {
  const kind = t.local ? '🚶 Fußgänger' : '🚕 Cab';
  const tags =
    ` <span class="tag" style="background:rgba(120,180,255,.14);color:#9fc3ff">${t.local ? 'paketlokal' : 'paketübergreifend'}</span>` +
    (t.violation ? ' <span class="tag cycle">⚠ Verstoß</span>' : '');
  infoEl.innerHTML =
    '<span class="close" title="schließen">×</span>' +
    `<div class="cls">${kind}${tags}</div>` +
    `<div class="fqn">${t.from} → ${t.to}</div>` +
    rows([
      ['Beziehung', t.violation ? 'aufwärts / Zyklus' : 'regelkonform (Level fällt)'],
      ['Weg', t.local ? 'Gehsteig' : 'Fahrbahn'],
      ['Routenlänge', `${Math.round(t.len)}`],
    ]) +
    `<div class="dep-sec"><div class="dep-title">Quelle → Ziel</div><div class="dep-wrap">` +
    `<span class="dep out" data-fqn="${t.from}" title="${t.from}">${t.from.split('.').pop()}</span>` +
    `<span class="dep-more">→</span>` +
    `<span class="dep in" data-fqn="${t.to}" title="${t.to}">${t.to.split('.').pop()}</span>` +
    `</div></div>` +
    '<div class="btn-row" style="margin-top:12px"><button class="ui" data-follow>📍 Verfolgen</button></div>';
}

function select(sel) {
  stopFollow(); // neue Auswahl beendet eine laufende Verfolgung
  if (!sel) { hideInfo(); return; }
  currentSel = sel;
  if (sel.kind === 'class') showClass(sel.data);
  else if (sel.kind === 'pod') showPod(sel.data);
  else showPackage(sel.data);
  infoEl.style.display = 'block';
  infoEl.querySelector('.close').onclick = hideInfo;
  highlight(sel);
  deps.setFocus(sel.kind === 'class' ? sel.fqn : null);
}

// Delegierter Klick im Info-Panel: Chip = dorthin springen, ✈ = Auswahl anfliegen.
infoEl.addEventListener('click', (e) => {
  const chip = e.target.closest('[data-fqn]');
  if (chip) {
    const fqn = chip.dataset.fqn;
    selectByFqn(fqn);
    flyTo(fqn);
    pushSelectionToHost(fqn);
    return;
  }
  if (e.target.closest('[data-fly]') && currentSel) flyTo(currentSel.fqn);
  if (e.target.closest('[data-follow]') && currentSel?.kind === 'pod') startFollow(currentSel.ref);
});

// Select by fqn — resolves class vs package from the model (used by the host-app sync).
function selectByFqn(fqn) {
  if (!fqn) { hideInfo(); return; }
  const b = cityModel.buildings.find((x) => x.fullName === fqn);
  if (b) { select({ kind: 'class', data: b, fqn }); return; }
  const d = cityModel.districts.find((x) => x.fullName === fqn);
  if (d) select({ kind: 'package', data: d, fqn });
  else hideInfo();
}

function pick(clientX, clientY) {
  camera.updateMatrixWorld(); // frische Matrizen, auch wenn direkt nach dem Start geklickt wird
  // Pods zuerst, per Screen-Space-Abstand: der Raycast gegen die winzigen,
  // beweglichen Instanzen verlor sonst immer gegen das Paket darunter.
  const pod = traffic.podAtScreen(camera, clientX, clientY, window.innerWidth, window.innerHeight);
  if (pod) return { kind: 'pod', data: pod.trip, ref: pod.ref, fqn: null };
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

// ---- Verfolger-Kamera: klebt hinter einem ausgewählten Pod -------------------
// Fährt der Pod an sein Ziel, übernimmt derselbe Wagen die nächste Abhängigkeit
// — die Verfolgung wird so zur endlosen Stadtrundfahrt entlang echter Kanten.
let followRef = null;
const followDesired = new THREE.Vector3();
const followLook = new THREE.Vector3();
function startFollow(ref) {
  if (!ref) return;
  flight = null;
  followRef = ref;
  traffic.setProtected(ref); // der verfolgte Pod darf nie weggedrosselt werden
  nav.setMode('orbit');
  nav.orbit.autoRotate = false;
  document.getElementById('follow-hint').style.display = 'block';
  document.getElementById('b-tour').classList.add('active');
}
function stopFollow() {
  if (!followRef) return;
  followRef = null;
  traffic.setProtected(null);
  document.getElementById('follow-hint').style.display = 'none';
  document.getElementById('b-tour').classList.remove('active');
}

// ---- Kamera-Anflug: sanfter Tween auf eine Klasse oder ein Paket -------------
let flight = null;

function flyTo(fqn) {
  let target = null, dist = 90;
  const a = city.anchors?.[fqn];
  if (a) {
    target = new THREE.Vector3(a.x, (a.baseY + a.roofY) / 2, a.z);
    dist = Math.max(65, (a.roofY - a.baseY) * 1.7, Math.max(a.w, a.d) * 4);
  } else {
    const s = city.slabs?.find((x) => x.fqn === fqn);
    if (!s) return;
    target = new THREE.Vector3(s.x, s.topY, s.z);
    dist = Math.max(s.w, s.d) * 1.15 + 40;
  }
  nav.setMode('orbit');
  nav.orbit.autoRotate = false;
  // Aktuellen Blickwinkel (Azimut) beibehalten, nur Position/Ziel wechseln.
  const dir = camera.position.clone().sub(target);
  dir.y = 0;
  if (dir.lengthSq() < 1) dir.set(0.6, 0, 1);
  dir.normalize();
  const end = target.clone().addScaledVector(dir, dist).add(new THREE.Vector3(0, dist * 0.55, 0));
  flight = {
    p0: camera.position.clone(), p1: end,
    t0: nav.orbit.target.clone(), t1: target,
    s: 0,
  };
}

renderer.domElement.addEventListener('dblclick', (e) => {
  const sel = pick(e.clientX, e.clientY);
  if (sel && sel.fqn) flyTo(sel.fqn);
});

// Gepickt wird im Moment des DRÜCKENS: zwischen pointerdown und pointerup
// driftet die Kamera weiter (Kino-Rotation, Orbit-Damping) — ein Pick bei
// pointerup traf deshalb regelmäßig das Nachbargebäude statt des gemeinten.
let pressPick = null;
renderer.domElement.addEventListener('pointerdown', (e) => {
  pressXY = [e.clientX, e.clientY];
  pressPick = pick(e.clientX, e.clientY);
  flight = null;
  stopFollow();
});
renderer.domElement.addEventListener('pointerup', (e) => {
  if (!pressXY) return;
  const moved = Math.hypot(e.clientX - pressXY[0], e.clientY - pressXY[1]);
  pressXY = null;
  if (moved > 5) return; // an orbit drag, not a click
  const sel = pressPick;
  // Toggle: Klick auf die bereits ausgewählte Klasse/das Paket hebt die Auswahl auf.
  if (sel && sel.fqn && currentSel && currentSel.fqn === sel.fqn && currentSel.kind === sel.kind) {
    select(null);
    pushSelectionToHost('');
    return;
  }
  select(sel);
  // Pods haben keinen fqn — die Auswahl in der Host-App bleibt dann unberührt.
  if (!sel || sel.fqn) pushSelectionToHost(sel ? sel.fqn : '');
});
renderer.domElement.addEventListener('pointermove', (e) => {
  if (pressXY) return;
  renderer.domElement.style.cursor = pick(e.clientX, e.clientY) ? 'pointer' : '';
});

// ---- Bidirectional selection sync with the host app (over the loopback server) ----
// app -> browser: highlight what was selected in the 2D architecture view.
let applyingHostSelection = false;
try {
  const hostEvents = new EventSource('/events');
  hostEvents.onmessage = (ev) => {
    applyingHostSelection = true;
    try { selectByFqn(ev.data); } finally { applyingHostSelection = false; }
  };
} catch (err) { /* no host channel (e.g. opened standalone) */ }

// browser -> app: tell the host which node was picked here.
function pushSelectionToHost(fqn) {
  if (applyingHostSelection) return;   // don't echo a selection the host just pushed
  try { fetch('/select', { method: 'POST', body: fqn || '' }).catch(() => {}); } catch (err) { /* ignore */ }
}

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
  applyDensity();
  $('v-time').textContent = timeLabel(t);
});

// Zeitraffer: Sonne (und Mond) rotieren kontinuierlich durch den Tagesbogen.
let cycleOn = false;
let cycleT = 0.11;
$('b-cycle').addEventListener('click', () => {
  cycleOn = !cycleOn;
  $('b-cycle').classList.toggle('active', cycleOn);
  cycleT = parseFloat($('s-time').value);
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

$('s-traffic').addEventListener('input', (e) => {
  trafficDensity = parseFloat(e.target.value);
  applyDensity();
  $('v-traffic').textContent = Math.round(trafficDensity * 100) + '%';
});

let speedScale = 1;
$('s-speed').addEventListener('input', (e) => {
  speedScale = parseFloat(e.target.value);
  traffic.setSpeedScale(speedScale);
  $('v-speed').textContent = '×' + speedScale.toFixed(2);
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

// ---- Abhängigkeits-Modi -------------------------------------------------------
const depButtons = { off: 'b-dep-off', sel: 'b-dep-sel', all: 'b-dep-all', viol: 'b-dep-viol' };
function setDepMode(mode) {
  depMode = mode;
  deps.setMode(mode);
  for (const [k, id] of Object.entries(depButtons)) $(id).classList.toggle('active', k === mode);
}
for (const [k, id] of Object.entries(depButtons)) $(id).addEventListener('click', () => setDepMode(k));

function updateDepStats() {
  const s = deps.stats;
  $('dep-stats').innerHTML = s.violations > 0
    ? `<span class="n">${s.total}</span> · <span class="v">${s.violations} ⚠</span>`
    : `<span class="n">${s.total}</span>`;
}
updateDepStats();

// ---- Metrik-Fensterlicht --------------------------------------------------------
const metricButtons = { off: 'b-met-off', fanin: 'b-met-fanin', fanout: 'b-met-fanout', methods: 'b-met-methods' };
function setMetricMode(mode) {
  metricMode = mode;
  city.setMetric(mode);
  for (const [k, id] of Object.entries(metricButtons)) $(id).classList.toggle('active', k === mode);
}
for (const [k, id] of Object.entries(metricButtons)) $(id).addEventListener('click', () => setMetricMode(k));

// ---- Rundfahrt: der Kamera-Chase auf einen zufälligen Pod, ein Klick genügt ----
$('b-tour').addEventListener('click', () => {
  if (followRef) { stopFollow(); return; }
  hideInfo();
  startFollow(traffic.randomActiveRef());
});

// ---- Hotspots: meistbefahrene Kreuzungen ein-/ausblenden -----------------------
$('b-hotspots').addEventListener('click', () => {
  hotspotsOn = !hotspotsOn;
  $('b-hotspots').classList.toggle('active', hotspotsOn);
  hotspots.setVisible(hotspotsOn);
});

// ---- Legende ------------------------------------------------------------------
$('b-legend').addEventListener('click', () => {
  $('legend').classList.toggle('open');
  $('b-legend').classList.toggle('active', $('legend').classList.contains('open'));
});

// ---- Suche (Klassen + Pakete, Anflug bei Auswahl) -------------------------------
const searchEl = $('search');
const searchResEl = $('search-results');
function buildSearchIndex() {
  return [
    ...cityModel.buildings.map((b) => ({ fqn: b.fullName, simple: b.simpleName, kind: 'class' })),
    ...cityModel.districts.map((d) => ({ fqn: d.fullName, simple: d.simpleName, kind: 'package' })),
  ];
}
let searchIndex = buildSearchIndex();

function hideSearch() { searchResEl.style.display = 'none'; searchResEl.innerHTML = ''; }

function chooseSearch(fqn) {
  hideSearch();
  searchEl.value = '';
  searchEl.blur();
  selectByFqn(fqn);
  flyTo(fqn);
  pushSelectionToHost(fqn);
}

searchEl.addEventListener('input', () => {
  const q = searchEl.value.trim().toLowerCase();
  if (q.length < 2) { hideSearch(); return; }
  // Treffer auf dem einfachen Namen zuerst, dann Volltreffer im FQN.
  const starts = [], contains = [];
  for (const it of searchIndex) {
    const simple = it.simple.toLowerCase();
    if (simple.startsWith(q)) starts.push(it);
    else if (simple.includes(q) || it.fqn.toLowerCase().includes(q)) contains.push(it);
    if (starts.length >= 10) break;
  }
  const hits = [...starts, ...contains].slice(0, 10);
  if (!hits.length) { hideSearch(); return; }
  searchResEl.innerHTML = hits.map((h, i) =>
    `<div class="sr${i === 0 ? ' hot' : ''}" data-fqn="${h.fqn}">${h.simple}` +
    `<span class="srk">${h.kind === 'class' ? 'CLASS' : 'PKG'}</span>` +
    `<div class="srf">${h.fqn}</div></div>`
  ).join('');
  searchResEl.style.display = 'block';
});
searchResEl.addEventListener('pointerdown', (e) => {
  const row = e.target.closest('.sr');
  if (row) { e.preventDefault(); chooseSearch(row.dataset.fqn); }
});
searchEl.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    const first = searchResEl.querySelector('.sr');
    if (first) chooseSearch(first.dataset.fqn);
  } else if (e.key === 'Escape') {
    hideSearch(); searchEl.value = ''; searchEl.blur();
  }
  e.stopPropagation();
});
searchEl.addEventListener('blur', () => setTimeout(hideSearch, 150));
window.addEventListener('keydown', (e) => {
  if (e.key === '/' && document.activeElement !== searchEl
    && !/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName ?? '')) {
    e.preventDefault();
    searchEl.focus();
  }
  if (e.key === 'Escape') stopFollow();
});

const cineBtn = $('b-cinematic');
cineBtn.addEventListener('click', () => {
  const on = !cineBtn.classList.contains('active');
  cineBtn.classList.toggle('active', on);
  nav.setCinematic(on);
});

$('b-regen').addEventListener('click', async () => {
  hideInfo();
  stopFollow(); // alte Pool-Referenz nicht in die neue Stadt mitnehmen
  traffic.dispose();
  deps.dispose();
  city.dispose();
  cityModel = await loadCityModel(); // re-read city.json (pick up a fresh export)
  city = buildCity(scene, atmosphere, cityModel);
  traffic = makeTraffic();
  deps = new DependencyViz(scene, cityModel, city.anchors);
  deps.setMode(depMode);
  city.setMetric(metricMode);
  applyDensity();
  traffic.setSpeedScale(speedScale);
  searchIndex = buildSearchIndex();
  updateDepStats();
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
let uiAccum = 0;
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

  // Während der Verfolgungsfahrt bleibt die Orbit-Steuerung stumm: ihr
  // Damping schob die Kamera jeden Frame gegen den Follow-Lerp zurück —
  // das war das Restruckeln der Kamerafahrt.
  if (!followRef) nav.update(dt);

  // Zeitraffer: Sonnen-/Mondstand rotiert kontinuierlich.
  if (cycleOn) {
    cycleT = (cycleT + dt / 150) % 1; // voller Tag in 2,5 Minuten
    atmosphere.setTime(cycleT);
    syncCityLight();
    uiAccum += dt;
    if (uiAccum > 0.4) {
      uiAccum = 0;
      $('s-time').value = cycleT;
      $('v-time').textContent = timeLabel(cycleT);
      applyDensity();
    }
  }

  // Kamera-Anflug (überschreibt Orbit-Damping, solange der Flug läuft).
  if (flight) {
    flight.s = Math.min(1, flight.s + dt / 1.3);
    const e = flight.s * flight.s * (3 - 2 * flight.s); // smoothstep
    camera.position.lerpVectors(flight.p0, flight.p1, e);
    nav.orbit.target.lerpVectors(flight.t0, flight.t1, e);
    if (flight.s >= 1) flight = null;
  }

  traffic.update(dt);
  traffic.updateLabels(camera, dt); // Schilder über nahen Pods

  // Verfolger-Kamera: hinter dem Pod bleiben, Blick voraus.
  if (followRef) {
    const st = traffic.carState(followRef);
    if (st) {
      const back = followRef.pool === traffic.pedPool ? 8 : 15;
      followDesired.set(st.x - st.dx * back, st.y + back * 0.55, st.z - st.dz * back);
      followLook.set(st.x + st.dx * 7, st.y + 1.4, st.z + st.dz * 7);
      const k = 1 - Math.exp(-3.4 * dt);
      camera.position.lerp(followDesired, k);
      nav.orbit.target.lerp(followLook, k);
      camera.lookAt(nav.orbit.target);
    } // st == null: Pod pausiert gerade (Respawn) — Kamera hält kurz an
  }

  deps.update(clock.elapsedTime);
  atmosphere.update(dt); // Wolken driften
  hotspots.update(clock.elapsedTime); // Kreuzungslast pulsiert
  if (city.waterUniforms) {
    city.waterUniforms.uTime.value = clock.elapsedTime;              // Wellen
    city.waterUniforms.uHazeCol.value.copy(scene.fog.color);         // Horizontdunst
  }
  city.labels?.update(camera, dt);
  // Zyklus-Beacons: harter Doppelherzschlag-Blink, nachts kräftiger.
  if (city.beaconMat) {
    const blink = Math.pow(Math.max(0, Math.sin(clock.elapsedTime * 3.4)), 6);
    city.beaconMat.emissiveIntensity = 0.2 + blink * (0.9 + 2.8 * atmosphere.cityLightFactor);
  }
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
