import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

/**
 * Verkehr = Abhängigkeiten in Bewegung, in zwei Populationen:
 *
 *  - AUTOS (paketübergreifend): stilisierte NY CABS — gelber Korpus, dunkle
 *    Glaskabine, weiße Scheinwerfer vorn, rote Rücklichter hinten. Die
 *    SEMANTIK sitzt in der Taxi-Dachleuchte: cyan = regelkonform,
 *    rot = Architektur-Verstoß.
 *  - FUSSGÄNGER (paketlokal): einfarbige Spielfiguren (Körper + Kopf,
 *    Meeple-Stil) auf dem Gehsteig; Farbe = Semantik, leichtes Geh-Wippen.
 *
 * Interaktion: podAtScreen() (Screen-Space-Picking), carState() (Verfolger-
 * Kamera), updateLabels() (Textschilder über nahen Pods — Position wird jeden
 * Frame nachgeführt, nur die Neuzuweisung der Schilder ist gedrosselt).
 */

const SIGN_OK = new THREE.Color(0.35, 1.6, 2.3);   // Dachleuchte: regelkonform
const SIGN_VIOL = new THREE.Color(2.4, 0.25, 0.18); // Dachleuchte: Verstoß
const PED_OK = new THREE.Color(0.4, 0.95, 0.75);
const PED_VIOL = new THREE.Color(1.7, 0.3, 0.25);

const _m4 = new THREE.Matrix4();
const _pos = new THREE.Vector3();
const _scl = new THREE.Vector3();
const _quat = new THREE.Quaternion();
const _eul = new THREE.Euler(0, 0, 0, 'YXZ');
const _dir = new THREE.Vector3();
const _v = new THREE.Vector3();

const MAX_LABELS = 5;
const LABEL_DIST = 95;

// ---- Geometrie-Bausteine ------------------------------------------------------
function paint(geo, r, g, b) {
  const n = geo.attributes.position.count;
  const col = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) col.set([r, g, b], i * 3);
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return geo;
}
function coloredBox(w, h, d, x, y, z, r, g, b) {
  const geo = new THREE.BoxGeometry(w, h, d);
  geo.translate(x, y, z);
  return paint(geo, r, g, b);
}

// NY Cab: Chassis + Kabine (beleuchtet, Vertex-Farben)
function cabBodyGeometry() {
  return mergeGeometries([
    coloredBox(1.35, 0.5, 2.9, 0, 0.31, 0, 0.92, 0.60, 0.04),      // Chassis: Cab-Gelb
    coloredBox(1.16, 0.42, 1.5, 0, 0.75, -0.15, 0.07, 0.09, 0.13), // Kabine: dunkles Glas
  ]);
}
// Lichter: unbeleuchtet, Werte > 1 -> glühen nachts im Bloom
function cabLightGeometry() {
  return mergeGeometries([
    coloredBox(0.2, 0.12, 0.09, 0.44, 0.32, 1.44, 2.4, 2.4, 2.1),   // Scheinwerfer weiß
    coloredBox(0.2, 0.12, 0.09, -0.44, 0.32, 1.44, 2.4, 2.4, 2.1),
    coloredBox(0.2, 0.12, 0.09, 0.44, 0.34, -1.44, 2.2, 0.16, 0.1), // Rücklichter rot
    coloredBox(0.2, 0.12, 0.09, -0.44, 0.34, -1.44, 2.2, 0.16, 0.1),
  ]);
}
// Taxi-Dachleuchte: Farbe pro Instanz (Semantik)
function cabSignGeometry() {
  const geo = new THREE.BoxGeometry(0.34, 0.2, 0.55);
  geo.translate(0, 1.06, -0.15);
  return geo;
}
// Fußgänger als Spielfigur: kegeliger Körper + Kopf (Meeple)
function pedGeometry() {
  const body = new THREE.CylinderGeometry(0.14, 0.27, 0.88, 10);
  body.translate(0, 0.44, 0);
  paint(body, 0.95, 0.95, 0.95);
  const head = new THREE.SphereGeometry(0.185, 10, 8);
  head.translate(0, 1.06, 0);
  paint(head, 1.15, 1.15, 1.15);
  return mergeGeometries([body, head]);
}

class Pool {
  /**
   * layers: [{geo, mat, semantic, castShadow}] — alle Layer teilen sich die
   * Instanz-Matrizen (ein Fahrzeug = gleiche Matrix in jedem Layer); nur
   * "semantic"-Layer bekommen die Trip-Farbe als Instanzfarbe.
   */
  constructor(scene, trips, { layers, speedMin, speedMax, cap, minCount, colOk, colViol, bob = 0, scaleJitter = 0, mult = 2.2, seed }) {
    this.scene = scene;
    this.trips = trips;
    this.speedMin = speedMin;
    this.speedMax = speedMax;
    this.bob = bob;
    this.colOk = colOk;
    this.colViol = colViol;
    this.max = trips.length ? Math.min(Math.max(minCount, Math.round(trips.length * mult)), cap) : 0;
    this.speedScale = 1;
    this.protectedIndex = -1; // verfolgter Pod: immun gegen Dichte-Drosselung

    this.meshes = [];
    this.semanticMeshes = [];
    for (const layer of layers) {
      const mesh = new THREE.InstancedMesh(layer.geo, layer.mat, Math.max(1, this.max));
      mesh.frustumCulled = false;
      mesh.castShadow = !!layer.castShadow;
      scene.add(mesh);
      this.meshes.push(mesh);
      if (layer.semantic) this.semanticMeshes.push(mesh);
    }

    let s = seed;
    this.rnd = () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
    this.cars = [];
    for (let i = 0; i < this.max; i++) {
      const car = this._spawn();
      car.s = car.trip.len * this.rnd(); // anfangs über die Route verteilt
      car.sc = 1 + (scaleJitter ? (this.rnd() - 0.4) * scaleJitter : 0);
      this.cars.push(car);
      this._applyColor(i, car);
    }
    this._active = this.max;
  }

  _spawn() {
    const trip = this.trips[Math.floor(this.rnd() * this.trips.length)];
    // Tempo folgt dem globalen Level-Sprung der Abhängigkeit (trip.speedT 0..1):
    // weite Fahrten quer durch die Architektur sind schnell, kurze gemütlich.
    const base = this.speedMin + (this.speedMax - this.speedMin) * (trip.speedT ?? 0.5);
    return {
      trip, s: 0, seg: 0,
      speed: base * (0.9 + this.rnd() * 0.2),
      pause: this.rnd() * 1.5, sc: 1,
      x: 0, y: -1e6, z: 0, dx: 0, dz: 1, live: false,
    };
  }

  _applyColor(i, car) {
    for (const mesh of this.semanticMeshes) {
      mesh.setColorAt(i, car.trip.violation ? this.colViol : this.colOk);
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    }
  }

  setDensity(f) { this._active = Math.round(this.max * THREE.MathUtils.clamp(f, 0, 1)); }

  /** Fahrt des Instanz-Index (für das Picking) — null, wenn gerade geparkt. */
  tripAt(i) {
    const car = this.cars[i];
    return car && i < this._active && car.pause <= 0 ? car.trip : null;
  }

  _setMatrixAll(i) {
    for (const mesh of this.meshes) mesh.setMatrixAt(i, _m4);
  }

  update(dt) {
    if (!this.max) return;
    for (let i = 0; i < this.cars.length; i++) {
      const car = this.cars[i];
      if ((i >= this._active && i !== this.protectedIndex) || car.pause > 0) {
        if (car && car.pause > 0) car.pause -= dt;
        car.live = false;
        _m4.makeScale(0, 0, 0);
        this._setMatrixAll(i);
        continue;
      }
      car.s += car.speed * this.speedScale * dt;
      const { path, cum, len } = car.trip;
      if (car.s >= len) { // angekommen -> nächste Abhängigkeit
        const sc = car.sc;
        const fresh = this._spawn();
        fresh.sc = sc;
        this.cars[i] = fresh;
        this._applyColor(i, fresh);
        _m4.makeScale(0, 0, 0);
        this._setMatrixAll(i);
        continue;
      }
      while (car.s > cum[car.seg + 1]) car.seg++;
      const segLen = cum[car.seg + 1] - cum[car.seg] || 1;
      const t = (car.s - cum[car.seg]) / segLen;
      const p0 = path[car.seg], p1 = path[car.seg + 1];
      _pos.lerpVectors(p0, p1, t);
      _dir.subVectors(p1, p0);
      let horiz = Math.hypot(_dir.x, _dir.z);
      if (horiz < 1e-3) { // degeneriertes Segment: letzte bekannte Richtung halten
        _dir.set(car.dx, 0, car.dz);
        horiz = 1;
      }
      _eul.set(-Math.atan2(_dir.y, horiz), Math.atan2(_dir.x, _dir.z), 0);
      _quat.setFromEuler(_eul);
      _scl.setScalar(car.sc);
      _pos.y += 0.12 + (this.bob ? Math.abs(Math.sin(car.s * 4.2)) * this.bob : 0);
      _m4.compose(_pos, _quat, _scl);
      this._setMatrixAll(i);
      car.x = _pos.x; car.y = _pos.y; car.z = _pos.z;
      car.dx = _dir.x / horiz; car.dz = _dir.z / horiz;
      car.live = true;
    }
    for (const mesh of this.meshes) mesh.instanceMatrix.needsUpdate = true;
  }

  dispose() {
    for (const mesh of this.meshes) {
      mesh.geometry.dispose();
      mesh.material.dispose();
      this.scene.remove(mesh);
    }
  }
}

// ---- Labels: NUR Text, sonst nichts (alphaTest verwirft Randpixel) -----------
function labelTexture(trip) {
  if (trip._labelTex) return trip._labelTex;
  const text = `${trip.from.split('.').pop()} → ${trip.to.split('.').pop()}`;
  const PX = 24, PAD = 6;
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  ctx.font = `600 ${PX}px ui-sans-serif, system-ui, sans-serif`;
  const w = Math.ceil(ctx.measureText(text).width) + PAD * 2;
  const h = PX + PAD * 2;
  canvas.width = w; canvas.height = h;
  ctx.font = `600 ${PX}px ui-sans-serif, system-ui, sans-serif`;
  ctx.textBaseline = 'middle'; ctx.textAlign = 'center';
  ctx.fillStyle = trip.violation ? '#ffaba2' : trip.local ? '#bdf2df' : '#d9e9ff';
  ctx.fillText(text, w / 2, h / 2 + 1);
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  trip._labelTex = tex;
  trip._labelAspect = w / h;
  return tex;
}

export class Traffic {
  constructor(scene, trips) {
    this.scene = scene;

    this.carPool = new Pool(scene, trips.filter((t) => !t.local), {
      layers: [
        { geo: cabBodyGeometry(), mat: new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.55, metalness: 0.15 }), castShadow: true },
        { geo: cabLightGeometry(), mat: new THREE.MeshBasicMaterial({ vertexColors: true }) },
        { geo: cabSignGeometry(), mat: new THREE.MeshBasicMaterial(), semantic: true },
      ],
      speedMin: 6.5, speedMax: 16, cap: 900, minCount: 16,
      colOk: SIGN_OK, colViol: SIGN_VIOL, seed: 0xbee5,
    });
    this.pedPool = new Pool(scene, trips.filter((t) => t.local), {
      layers: [
        { geo: pedGeometry(), mat: new THREE.MeshBasicMaterial({ vertexColors: true }), semantic: true },
      ],
      // Fußgänger sind langsam: damit die Gehsteige so belebt wirken wie die
      // Straßen, braucht es deutlich mehr Läufer pro Route als Autos.
      speedMin: 1.4, speedMax: 3.4, cap: 600, minCount: 16, mult: 5,
      colOk: PED_OK, colViol: PED_VIOL, bob: 0.06, scaleJitter: 0.3, seed: 0x5eed,
    });
    this.pools = [this.carPool, this.pedPool];

    // Label-Slots: jeder Slot hält "sein" Fahrzeug fest — die Position wird
    // JEDEN Frame nachgeführt (kein Ruckeln), nur die Neuzuweisung, welche
    // Pods ein Schild verdienen, läuft gedrosselt.
    this.labelSlots = [];
    for (let i = 0; i < MAX_LABELS; i++) {
      const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
        transparent: true, depthWrite: false, opacity: 0.85, alphaTest: 0.12,
      }));
      sprite.visible = false;
      sprite.renderOrder = 980;
      sprite.userData.noAO = true; // nicht in den SSAO-Tiefenpass (schwarze Balken)
      scene.add(sprite);
      this.labelSlots.push({ sprite, ref: null, key: '' });
    }
    this._labelAccum = 1;
  }

  /**
   * Screen-Space-Picking: nächster aktiver Pod um den Klick. Die Trefferzone
   * ist großzügig und skaliert mit der projizierten Pod-Größe — nah heran-
   * gezoomte Cabs haben einen breiten Fangbereich, ferne den Mindestradius.
   * So zielt man nicht daneben (und trifft nicht das Paket darunter).
   */
  podAtScreen(camera, clientX, clientY, width, height) {
    const fovScale = height / (2 * Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2));
    let best = null, bestPx = Infinity;
    for (const pool of this.pools) {
      for (let i = 0; i < pool.cars.length; i++) {
        const car = pool.cars[i];
        if (!car.live) continue;
        _v.set(car.x, car.y + 0.6, car.z).project(camera);
        if (_v.z > 1 || _v.z < -1) continue;
        const px = (_v.x * 0.5 + 0.5) * width;
        const py = (-_v.y * 0.5 + 0.5) * height;
        const d = Math.hypot(px - clientX, py - clientY);
        const dist = Math.max(1, Math.hypot(car.x - camera.position.x, car.y - camera.position.y, car.z - camera.position.z));
        const tol = Math.min(70, 16 + (fovScale / dist) * 2.2); // ~Fahrzeuglänge in Pixeln + Reserve
        if (d < tol && d < bestPx) { bestPx = d; best = { trip: car.trip, ref: { pool, i } }; }
      }
    }
    return best;
  }

  /** Bestandsschutz für den verfolgten Pod (überlebt Dichte-/Zeitänderungen). */
  setProtected(ref) {
    for (const pool of this.pools) pool.protectedIndex = -1;
    if (ref) ref.pool.protectedIndex = ref.i;
  }

  /** Zustand eines verfolgten Pods — Position/Fahrtrichtung, null wenn geparkt. */
  carState(ref) {
    const car = ref?.pool?.cars[ref.i];
    return car && car.live ? car : null;
  }

  /** Zufälliger aktiver Pod (Autos bevorzugt) — für die "Rundfahrt". */
  randomActiveRef() {
    for (const pool of this.pools) {
      const live = [];
      for (let i = 0; i < pool.cars.length; i++) if (pool.cars[i].live) live.push(i);
      if (live.length) return { pool, i: live[Math.floor(Math.random() * live.length)] };
    }
    return null;
  }

  /** Schilder über den nächsten Pods; Positionen laufen pro Frame mit. */
  updateLabels(camera, dt) {
    this._labelAccum += dt;
    if (this._labelAccum >= 0.3) {
      this._labelAccum = 0;
      // Neu entscheiden, welche Pods ein Schild bekommen — bestehende Slots
      // behalten ihr Fahrzeug (kein Textur-Flackern), frei werdende rücken nach.
      const near = [];
      for (const pool of this.pools) {
        for (let i = 0; i < pool.cars.length; i++) {
          const car = pool.cars[i];
          if (!car.live) continue;
          const d = camera.position.distanceTo(_v.set(car.x, car.y, car.z));
          if (d < LABEL_DIST) near.push({ pool, i, d, key: (pool === this.carPool ? 'c' : 'p') + i });
        }
      }
      near.sort((a, b) => a.d - b.d);
      const wanted = new Map(near.slice(0, MAX_LABELS).map((e) => [e.key, e]));
      for (const slot of this.labelSlots) {
        if (slot.ref && wanted.has(slot.key)) wanted.delete(slot.key);
        else { slot.ref = null; slot.key = ''; }
      }
      const rest = [...wanted.values()];
      for (const slot of this.labelSlots) {
        if (slot.ref || !rest.length) continue;
        const e = rest.shift();
        slot.ref = { pool: e.pool, i: e.i };
        slot.key = e.key;
      }
    }
    // Jeden Frame: Position, Textur (bei Trip-Wechsel) und Opacity nachführen.
    for (const slot of this.labelSlots) {
      const sprite = slot.sprite;
      const car = slot.ref ? slot.ref.pool.cars[slot.ref.i] : null;
      if (!car || !car.live) { sprite.visible = false; continue; }
      if (sprite.userData.trip !== car.trip) {
        const tex = labelTexture(car.trip);
        sprite.material.map = tex;
        sprite.material.needsUpdate = true;
        sprite.userData.trip = car.trip;
        sprite.scale.set(1.1 * car.trip._labelAspect, 1.1, 1);
      }
      sprite.position.set(car.x, car.y + 2.3, car.z);
      const d = camera.position.distanceTo(sprite.position);
      sprite.material.opacity = 0.85 * THREE.MathUtils.clamp((LABEL_DIST - d) / 35, 0, 1);
      sprite.visible = sprite.material.opacity > 0.02;
    }
  }

  /** Globales Tempo (0.15..2): skaliert Autos und Fußgänger gemeinsam. */
  setSpeedScale(f) {
    for (const pool of this.pools) pool.speedScale = f;
  }

  setDensity(f, dayFactor = 1) {
    // Tag/Nacht-Rhythmus: tagsüber voller Betrieb, nachts ruhiger — Fußgänger
    // laufen dabei genauso durch wie die Autos (gleiche Kurve).
    const rhythm = 0.45 + 0.55 * dayFactor;
    this.carPool.setDensity(f * rhythm);
    this.pedPool.setDensity(f * rhythm);
  }

  update(dt) {
    this.carPool.update(dt);
    this.pedPool.update(dt);
  }

  dispose() {
    for (const pool of this.pools) {
      for (const t of pool.trips) { t._labelTex?.dispose(); t._labelTex = null; }
      pool.dispose();
    }
    for (const slot of this.labelSlots) {
      slot.sprite.material.dispose();
      this.scene.remove(slot.sprite);
    }
  }
}
