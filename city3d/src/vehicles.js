import * as THREE from 'three';

/**
 * Verkehr = Abhängigkeiten in Bewegung, in zwei Populationen:
 *
 *  - AUTOS (paketübergreifende Beziehungen): leuchtende Pods auf der Fahrbahn,
 *    schnell, gebündelt auf den Boulevards (Kantengewichte im Routing).
 *  - FUSSGÄNGER (paketlokale Beziehungen): kleine Figuren auf dem Gehsteig,
 *    langsam, mit leichtem Geh-Wippen.
 *
 * Farben in beiden Fällen: cyan = regelkonform, rot = Architektur-Verstoß.
 * Beide Meshes sind pickbar: tripAt(object, instanceId) liefert die Fahrt
 * (Quelle/Ziel/Route) für das Info-Panel.
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
    return { trip, s: 0, seg: 0, speed: this.speedMin + this.rnd() * (this.speedMax - this.speedMin), pause: this.rnd() * 1.5 };
  }

  setDensity(f) { this._active = Math.round(this.max * f); }

  /** Fahrt des Instanz-Index (für das Picking) — null, wenn gerade geparkt. */
  tripAt(i) {
    const car = this.cars[i];
    return car && i < this._active && car.pause <= 0 ? car.trip : null;
  }

  update(dt, time) {
    if (!this.max) return;
    for (let i = 0; i < this.mesh.count; i++) {
      const car = this.cars[i];
      if (i >= this._active || car.pause > 0) {
        if (car && car.pause > 0) car.pause -= dt;
        _m4.makeScale(0, 0, 0);
        this.mesh.setMatrixAt(i, _m4);
        continue;
      }
      car.s += car.speed * dt;
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
      const horiz = Math.hypot(_dir.x, _dir.z) || 1;
      _eul.set(-Math.atan2(_dir.y, horiz), Math.atan2(_dir.x, _dir.z), 0);
      _quat.setFromEuler(_eul);
      _scl.set(this.dims[0], this.dims[1], this.dims[2]);
      _pos.y += 0.12 + (this.bob ? Math.abs(Math.sin(car.s * 4.2)) * this.bob : 0);
      _m4.compose(_pos, _quat, _scl);
      this.mesh.setMatrixAt(i, _m4);
    }
    this.mesh.instanceMatrix.needsUpdate = true;
  }

  dispose() {
    this.mesh.geometry.dispose();
    this.mat.dispose();
    this.scene.remove(this.mesh);
  }
}

export class Traffic {
  constructor(scene, trips) {
    const carGeo = new THREE.BoxGeometry(1, 1, 1);
    carGeo.translate(0, 0.5, 0);
    // Fußgänger: Kapsel, Basis auf y=0 (Radius 0.3, Gesamthöhe ~1.5)
    const pedGeo = new THREE.CapsuleGeometry(0.3, 0.9, 3, 8);
    pedGeo.translate(0, 0.75, 0);

    this.carPool = new Pool(scene, trips.filter((t) => !t.local), {
      geo: carGeo, dims: [1.35, 0.8, 2.9], speedMin: 8, speedMax: 13,
      cap: 320, minCount: 16, colOk: CAR_OK, colViol: CAR_VIOL, seed: 0xbee5,
    });
    this.pedPool = new Pool(scene, trips.filter((t) => t.local), {
      geo: pedGeo, dims: [0.85, 0.85, 0.85], speedMin: 1.7, speedMax: 2.9,
      cap: 220, minCount: 8, colOk: PED_OK, colViol: PED_VIOL, bob: 0.07, seed: 0x5eed,
    });
  }

  /** Meshes fürs Raycasting (Pods anklickbar). */
  get pickTargets() {
    return [this.carPool.mesh, this.pedPool.mesh].filter((m) => m.count > 0);
  }

  /** Fahrt zu einem Raycast-Treffer — {trip, local} oder null. */
  tripAt(object, instanceId) {
    if (object === this.carPool.mesh) return this.carPool.tripAt(instanceId);
    if (object === this.pedPool.mesh) return this.pedPool.tripAt(instanceId);
    return null;
  }

  setDensity(f) {
    this.carPool.setDensity(f);
    this.pedPool.setDensity(f);
  }

  update(dt, time = 0) {
    this.carPool.update(dt, time);
    this.pedPool.update(dt, time);
  }

  dispose() {
    this.carPool.dispose();
    this.pedPool.dispose();
  }
}
