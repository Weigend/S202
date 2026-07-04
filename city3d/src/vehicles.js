import * as THREE from 'three';

/**
 * Verkehr = Abhängigkeiten in Bewegung, in zwei Populationen:
 *
 *  - AUTOS (paketübergreifende Beziehungen): leuchtende Pods auf der Fahrbahn,
 *    schnell, gebündelt auf den Boulevards (Kantengewichte im Routing).
 *  - FUSSGÄNGER (paketlokale Beziehungen): kleine Figuren auf dem Gehsteig,
 *    langsam, mit leichtem Geh-Wippen.
 *
 * Farben: cyan/türkis = regelkonform, rot = Architektur-Verstoß.
 *
 * Interaktion:
 *  - podAtScreen(): Screen-Space-Picking — der nächste Pod im Pixel-Radius
 *    gewinnt (Raycasts gegen die winzigen, beweglichen Instanzen waren zu
 *    unzuverlässig und kollidierten mit dem darunterliegenden Paket).
 *  - updateLabels(): Proximity-Labels — kommt die Kamera nahe genug, blendet
 *    über den nächsten Pods ein Schild "Quelle → Ziel" ein.
 *  - carState(ref): Position/Richtung eines Pods für die Verfolger-Kamera.
 */

const CAR_OK = new THREE.Color(0.35, 0.85, 1.25);
const CAR_VIOL = new THREE.Color(1.9, 0.28, 0.22);
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

class Pool {
  constructor(scene, trips, { geo, dims, speedMin, speedMax, cap, minCount, colOk, colViol, bob = 0, seed }) {
    this.scene = scene;
    this.trips = trips;
    this.dims = dims;
    this.speedMin = speedMin;
    this.speedMax = speedMax;
    this.bob = bob;
    this.colOk = colOk;
    this.colViol = colViol;
    this.max = trips.length ? Math.min(Math.max(minCount, Math.round(trips.length * 2.2)), cap) : 0;
    this.speedScale = 1;

    this.mat = new THREE.MeshBasicMaterial({ toneMapped: true });
    this.mesh = new THREE.InstancedMesh(geo, this.mat, Math.max(1, this.max));
    this.mesh.frustumCulled = false;
    scene.add(this.mesh);

    let s = seed;
    this.rnd = () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
    this.cars = [];
    for (let i = 0; i < this.max; i++) {
      const car = this._spawn();
      car.s = car.trip.len * this.rnd(); // anfangs über die Route verteilt
      this.cars.push(car);
      this.mesh.setColorAt(i, car.trip.violation ? this.colViol : this.colOk);
    }
    if (this.mesh.instanceColor) this.mesh.instanceColor.needsUpdate = true;
    this._active = this.max;
  }

  _spawn() {
    const trip = this.trips[Math.floor(this.rnd() * this.trips.length)];
    return {
      trip, s: 0, seg: 0,
      speed: this.speedMin + this.rnd() * (this.speedMax - this.speedMin),
      pause: this.rnd() * 1.5,
      x: 0, y: -1e6, z: 0, dx: 0, dz: 1, live: false,
    };
  }

  setDensity(f) { this._active = Math.round(this.max * THREE.MathUtils.clamp(f, 0, 1)); }

  update(dt) {
    if (!this.max) return;
    for (let i = 0; i < this.mesh.count; i++) {
      const car = this.cars[i];
      if (i >= this._active || car.pause > 0) {
        if (car && car.pause > 0) car.pause -= dt;
        car.live = false;
        _m4.makeScale(0, 0, 0);
        this.mesh.setMatrixAt(i, _m4);
        continue;
      }
      car.s += car.speed * this.speedScale * dt;
      const { path, cum, len } = car.trip;
      if (car.s >= len) { // angekommen -> nächste Abhängigkeit
        const fresh = this._spawn();
        this.cars[i] = fresh;
        this.mesh.setColorAt(i, fresh.trip.violation ? this.colViol : this.colOk);
        this.mesh.instanceColor.needsUpdate = true;
        _m4.makeScale(0, 0, 0);
        this.mesh.setMatrixAt(i, _m4);
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
      _scl.set(this.dims[0], this.dims[1], this.dims[2]);
      _pos.y += 0.12 + (this.bob ? Math.abs(Math.sin(car.s * 4.2)) * this.bob : 0);
      _m4.compose(_pos, _quat, _scl);
      this.mesh.setMatrixAt(i, _m4);
      car.x = _pos.x; car.y = _pos.y; car.z = _pos.z;
      car.dx = _dir.x / horiz; car.dz = _dir.z / horiz;
      car.live = true;
    }
    this.mesh.instanceMatrix.needsUpdate = true;
  }

  dispose() {
    this.mesh.geometry.dispose();
    this.mat.dispose();
    this.scene.remove(this.mesh);
  }
}

// ---- Labels: NUR Text, sonst nichts. Kein Hintergrund, keine Kontur — und im
// Material ein alphaTest, der halbtransparente dunkle Randpixel der Canvas-
// Textur verwirft (die erschienen sonst als schattiges Rechteck um den Text).
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
    const carGeo = new THREE.BoxGeometry(1, 1, 1);
    carGeo.translate(0, 0.5, 0);
    const pedGeo = new THREE.CapsuleGeometry(0.3, 0.9, 3, 8);
    pedGeo.translate(0, 0.75, 0);

    this.carPool = new Pool(scene, trips.filter((t) => !t.local), {
      geo: carGeo, dims: [1.35, 0.8, 2.9], speedMin: 8, speedMax: 13,
      cap: 900, minCount: 16, colOk: CAR_OK, colViol: CAR_VIOL, seed: 0xbee5,
    });
    this.pedPool = new Pool(scene, trips.filter((t) => t.local), {
      geo: pedGeo, dims: [0.85, 0.85, 0.85], speedMin: 1.7, speedMax: 2.9,
      cap: 600, minCount: 8, colOk: PED_OK, colViol: PED_VIOL, bob: 0.07, seed: 0x5eed,
    });
    this.pools = [this.carPool, this.pedPool];

    // Label-Sprites (Pool, wiederverwendet für die jeweils nächsten Pods).
    // depthTest an: Schilder verschwinden hinter Gebäuden statt durchzuscheinen.
    this.labelSprites = [];
    for (let i = 0; i < MAX_LABELS; i++) {
      const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
        transparent: true, depthWrite: false, opacity: 0.85, alphaTest: 0.12,
      }));
      sprite.visible = false;
      sprite.renderOrder = 980;
      scene.add(sprite);
      this.labelSprites.push(sprite);
    }
    this._labelAccum = 1;
  }

  /** Screen-Space-Picking: nächster aktiver Pod im Pixelradius um den Klick. */
  podAtScreen(camera, clientX, clientY, width, height, tolPx = 16) {
    let best = null, bestPx = tolPx;
    for (const pool of this.pools) {
      for (let i = 0; i < pool.cars.length; i++) {
        const car = pool.cars[i];
        if (!car.live) continue;
        _v.set(car.x, car.y + 0.6, car.z).project(camera);
        if (_v.z > 1 || _v.z < -1) continue;
        const px = (_v.x * 0.5 + 0.5) * width;
        const py = (-_v.y * 0.5 + 0.5) * height;
        const d = Math.hypot(px - clientX, py - clientY);
        if (d < bestPx) { bestPx = d; best = { trip: car.trip, ref: { pool, i } }; }
      }
    }
    return best;
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

  /** Proximity-Labels: Schilder über den nächsten Pods (gedrosselt). */
  updateLabels(camera, dt) {
    this._labelAccum += dt;
    if (this._labelAccum < 0.15) return;
    this._labelAccum = 0;
    const near = [];
    for (const pool of this.pools) {
      for (const car of pool.cars) {
        if (!car.live) continue;
        const d = camera.position.distanceTo(_v.set(car.x, car.y, car.z));
        if (d < LABEL_DIST) near.push({ car, d });
      }
    }
    near.sort((a, b) => a.d - b.d);
    for (let i = 0; i < this.labelSprites.length; i++) {
      const sprite = this.labelSprites[i];
      const hit = near[i];
      if (!hit) { sprite.visible = false; continue; }
      const { car, d } = hit;
      const tex = labelTexture(car.trip);
      if (sprite.material.map !== tex) {
        sprite.material.map = tex;
        sprite.material.needsUpdate = true;
      }
      const h = 1.1; // klein und konstant — Nähe entscheidet über Sichtbarkeit
      sprite.scale.set(h * car.trip._labelAspect, h, 1);
      sprite.position.set(car.x, car.y + 2.3, car.z);
      sprite.material.opacity = 0.85 * THREE.MathUtils.clamp((LABEL_DIST - d) / 35, 0, 1);
      sprite.visible = true;
    }
  }

  /** Globales Tempo (0.15..2): skaliert Autos und Fußgänger gemeinsam. */
  setSpeedScale(f) {
    for (const pool of this.pools) pool.speedScale = f;
  }

  setDensity(f, dayFactor = 1) {
    // Tag/Nacht-Rhythmus: tagsüber Berufsverkehr, nachts leere Straßen;
    // Fußgänger ziehen sich nachts noch stärker zurück.
    this.carPool.setDensity(f * (0.45 + 0.55 * dayFactor));
    this.pedPool.setDensity(f * (0.22 + 0.78 * dayFactor));
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
    for (const s of this.labelSprites) {
      s.material.dispose();
      this.scene.remove(s);
    }
  }
}
