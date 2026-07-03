import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { PointerLockControls } from 'three/addons/controls/PointerLockControls.js';

/**
 * Zwei Navigationsmodi:
 *   - Orbit (default): Maus ziehen zum Drehen, Scroll für Zoom, optional Kinofahrt.
 *   - Fly: Pointer-Lock + WASD/QE wie eine Drohne. Umschalten mit F, Esc verlässt.
 */
export class Navigation {
  constructor(camera, dom) {
    this.camera = camera;
    this.dom = dom;
    this.mode = 'orbit';

    this.orbit = new OrbitControls(camera, dom);
    this.orbit.enableDamping = true;
    this.orbit.dampingFactor = 0.06;
    this.orbit.rotateSpeed = 0.5;
    this.orbit.zoomSpeed = 0.9;
    this.orbit.minDistance = 40;
    this.orbit.maxDistance = 1600;
    this.orbit.maxPolarAngle = Math.PI * 0.495; // nicht unter den Horizont
    this.orbit.target.set(0, 60, 0);
    this.orbit.autoRotate = true;
    this.orbit.autoRotateSpeed = 0.28;

    this.fly = new PointerLockControls(camera, dom);
    this.velocity = new THREE.Vector3();
    this.keys = { f: 0, b: 0, l: 0, r: 0, u: 0, d: 0, boost: 0 };

    this._onKeyDown = (e) => this._key(e, 1);
    this._onKeyUp = (e) => this._key(e, 0);
    window.addEventListener('keydown', this._onKeyDown);
    window.addEventListener('keyup', this._onKeyUp);

    // Nutzerinteraktion stoppt die Kinofahrt
    this.orbit.addEventListener('start', () => { this.orbit.autoRotate = false; this._userMoved = true; });

    this.fly.addEventListener('unlock', () => { if (this.mode === 'fly') this.setMode('orbit'); });
  }

  setCinematic(on) {
    this._cinematic = on;
    if (this.mode === 'orbit') this.orbit.autoRotate = on;
    this._userMoved = false;
  }

  toggleFly() {
    if (this.mode === 'orbit') this.setMode('fly');
    else this.setMode('orbit');
  }

  setMode(mode) {
    if (mode === this.mode) return;
    if (mode === 'fly') {
      this.mode = 'fly';
      this.orbit.enabled = false;
      this.orbit.autoRotate = false;
      this.fly.lock();
    } else {
      this.mode = 'orbit';
      this.fly.unlock();
      this.orbit.enabled = true;
      // Orbit-Target vor die Kamera setzen, damit es nicht springt
      const fwd = new THREE.Vector3();
      this.camera.getWorldDirection(fwd);
      this.orbit.target.copy(this.camera.position).addScaledVector(fwd, 120);
      if (this._cinematic) this.orbit.autoRotate = true;
    }
    this.onModeChange?.(this.mode);
  }

  _key(e, v) {
    switch (e.code) {
      case 'KeyW': case 'ArrowUp': this.keys.f = v; break;
      case 'KeyS': case 'ArrowDown': this.keys.b = v; break;
      case 'KeyA': case 'ArrowLeft': this.keys.l = v; break;
      case 'KeyD': case 'ArrowRight': this.keys.r = v; break;
      case 'KeyE': case 'Space': this.keys.u = v; break;
      case 'KeyQ': this.keys.d = v; break;
      case 'ShiftLeft': case 'ShiftRight': this.keys.boost = v; break;
      case 'KeyF': if (v) this.toggleFly(); break;
    }
  }

  update(dt) {
    if (this.mode === 'fly' && this.fly.isLocked) {
      const accel = (this.keys.boost ? 900 : 320);
      const damp = Math.exp(-6 * dt);
      const k = this.keys;
      this.velocity.x += (k.r - k.l) * accel * dt;
      this.velocity.z += (k.f - k.b) * accel * dt; // wird über moveForward angewandt
      this.velocity.y += (k.u - k.d) * accel * dt;
      this.velocity.multiplyScalar(damp);

      this.fly.moveRight(this.velocity.x * dt);
      this.fly.moveForward(this.velocity.z * dt);
      this.camera.position.y += this.velocity.y * dt;
      if (this.camera.position.y < 3) { this.camera.position.y = 3; this.velocity.y = 0; }
    } else {
      this.orbit.update();
    }
  }

  dispose() {
    window.removeEventListener('keydown', this._onKeyDown);
    window.removeEventListener('keyup', this._onKeyUp);
    this.orbit.dispose();
    this.fly.dispose();
  }
}
