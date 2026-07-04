import * as THREE from 'three';

/**
 * Verkehr = Abhängigkeiten in Bewegung: Für jede (gesampelte) Abhängigkeit fährt
 * ein leuchtendes Transport-Pod die Straßenroute von der nutzenden Klasse zur
 * genutzten — cyan wenn regelkonform, rot bei Architektur-Verstoß. Unlit und
 * leicht über 1.0 gefärbt, damit die Pods nachts als Lichtstrom auf den Straßen
 * lesbar sind (Bloom), tagsüber als kräftig gefärbte Autos.
 */

const COL_OK = new THREE.Color(0.35, 0.85, 1.25);
const COL_VIOL = new THREE.Color(1.9, 0.28, 0.22);

const _m4 = new THREE.Matrix4();
const _pos = new THREE.Vector3();
const _scl = new THREE.Vector3();
const _quat = new THREE.Quaternion();
const _eul = new THREE.Euler(0, 0, 0, 'YXZ');
const _dir = new THREE.Vector3();

export class Traffic {
  constructor(scene, trips) {
    this.scene = scene;
    this.trips = trips;
    this.max = trips.length ? Math.min(Math.max(24, Math.round(trips.length * 2.2)), 320) : 0;
    this.density = 0.5;

    // Karosserie: Basis bei y=0, Länge entlang +Z (Fahrtrichtung).
    const geo = new THREE.BoxGeometry(1, 1, 1);
    geo.translate(0, 0.5, 0);
    this.mat = new THREE.MeshBasicMaterial({ toneMapped: true });
    this.mesh = new THREE.InstancedMesh(geo, this.mat, Math.max(1, this.max));
    this.mesh.frustumCulled = false;
    this.mesh.name = 'traffic';
    scene.add(this.mesh);

    // Autos: jedes hält seinen Trip, die Bogenlänge s und einen Segment-Zeiger.
    this.cars = [];
    let s = 0xbee5;
    this.rnd = () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
    for (let i = 0; i < this.max; i++) {
      const car = this._spawn();
      car.s = car.trip.len * this.rnd(); // anfangs über die Route verteilt (Stadt sofort belebt)
      this.cars.push(car);
      this.mesh.setColorAt(i, car.trip.violation ? COL_VIOL : COL_OK);
    }
    if (this.mesh.instanceColor) this.mesh.instanceColor.needsUpdate = true;
    this._active = this.max;
    this.setDensity(this.density);
  }

  _spawn() {
    const trip = this.trips[Math.floor(this.rnd() * this.trips.length)];
    return { trip, s: 0, seg: 0, speed: 8 + this.rnd() * 5, pause: this.rnd() * 1.5 };
  }

  setDensity(f) {
    this.density = f;
    this._active = Math.round(this.max * f);
  }

  update(dt) {
    if (!this.max) return;
    const n = this.mesh.count;
    for (let i = 0; i < n; i++) {
      const car = this.cars[i];
      if (i >= this._active) { // geparkt: unsichtbar
        _m4.makeScale(0, 0, 0);
        this.mesh.setMatrixAt(i, _m4);
        continue;
      }
      if (car.pause > 0) { car.pause -= dt; _m4.makeScale(0, 0, 0); this.mesh.setMatrixAt(i, _m4); continue; }
      car.s += car.speed * dt;
      const { path, cum, len } = car.trip;
      if (car.s >= len) { // angekommen -> neue Abhängigkeit fahren
        const fresh = this._spawn();
        this.cars[i] = fresh;
        this.mesh.setColorAt(i, fresh.trip.violation ? COL_VIOL : COL_OK);
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
      _scl.set(1.35, 0.8, 2.9);
      _pos.y += 0.12; // auf den Asphalt
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
