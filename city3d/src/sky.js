import * as THREE from 'three';
import { Sky } from 'three/addons/objects/Sky.js';

/**
 * Atmosphäre: prozeduraler Himmel, Sonne/Mond, Beleuchtung, Nebel, Sterne und
 * eine aus dem Himmel gerenderte Environment-Map für realistische Reflexionen.
 *
 * `time` ∈ [0,1] steuert den kompletten Tagesbogen:
 *   0.00  tiefe Nacht        0.16  blaue Stunde (default)
 *   0.30  goldene Stunde     0.50  Mittag
 *   0.70  goldene Stunde     0.85  blaue Stunde      1.0  Nacht
 */

const tmpColor = new THREE.Color();

// Farbpaletten je nach Sonnenhöhe — werden interpoliert.
// fog = Dunst-/Horizontfarbe. Nachts bewusst NICHT schwarz, sondern ein
// angehobener "Lichtverschmutzungs"-Schimmer, damit ferne Gebäude in Dunst
// statt in Schwärze verschwinden (sonst wirkt Nebel nur als "hinten dunkel").
const PALETTE = {
  night:  { sun: 0x223047, amb: 0x0a1326, hemiSky: 0x14233f, hemiGround: 0x05070d, fog: 0x1a2335 },
  blue:   { sun: 0x4a6fa5, amb: 0x1b2c4a, hemiSky: 0x2a4670, hemiGround: 0x0a1020, fog: 0x26344f },
  golden: { sun: 0xffb070, amb: 0x40364a, hemiSky: 0x9a7bb0, hemiGround: 0x20161a, fog: 0x5a4660 },
  day:    { sun: 0xfff4e0, amb: 0x62738f, hemiSky: 0x8eaee0, hemiGround: 0x343b49, fog: 0x8195b0 },
};

// Regen-/Nässe-Dunst: kühles Grau, in das der Nebel bei "Wetter" überblendet.
const RAIN_FOG = new THREE.Color(0x2c3340);

function lerpPalette(a, b, t, key) {
  return tmpColor.set(a[key]).lerp(new THREE.Color(b[key]), t).clone();
}

export class Atmosphere {
  constructor(scene, renderer) {
    this.scene = scene;
    this.renderer = renderer;
    this.pmrem = new THREE.PMREMGenerator(renderer);
    this.pmrem.compileEquirectangularShader();
    this._envDirty = true;
    this._fogScale = 1.0;
    this._wet = 0.0;

    // --- Himmel ---
    this.sky = new Sky();
    this.sky.scale.setScalar(60000);
    const u = this.sky.material.uniforms;
    u.turbidity.value = 6;
    u.rayleigh.value = 2.2;
    u.mieCoefficient.value = 0.005;
    u.mieDirectionalG.value = 0.8;
    scene.add(this.sky);

    // --- Sonne / Lichter ---
    this.sun = new THREE.DirectionalLight(0xffffff, 1);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(2048, 2048);
    this.sun.shadow.bias = -0.0004;
    const cam = this.sun.shadow.camera;
    cam.near = 10; cam.far = 1600;
    cam.left = -700; cam.right = 700; cam.top = 700; cam.bottom = -700;
    scene.add(this.sun);
    scene.add(this.sun.target);

    this.hemi = new THREE.HemisphereLight(0x9ec2ff, 0x14233f, 0.6);
    scene.add(this.hemi);

    this.ambient = new THREE.AmbientLight(0x1b2c4a, 0.5);
    scene.add(this.ambient);

    // --- Sterne (faden bei Nacht ein) ---
    this.stars = this._makeStars();
    scene.add(this.stars);

    this.sunDir = new THREE.Vector3();
    scene.fog = new THREE.FogExp2(0x121d33, 0.0016);

    this.setTime(0.11);
  }

  _makeStars() {
    const N = 1800;
    const pos = new Float32Array(N * 3);
    const col = new Float32Array(N * 3);
    const r = 20000;
    for (let i = 0; i < N; i++) {
      // gleichmäßig auf oberer Hemisphäre verteilt
      const u = Math.random(), v = Math.random();
      const theta = 2 * Math.PI * u;
      const phi = Math.acos(0.15 + 0.85 * v); // oberhalb des Horizonts halten
      const x = r * Math.sin(phi) * Math.cos(theta);
      const y = r * Math.cos(phi);
      const z = r * Math.sin(phi) * Math.sin(theta);
      pos.set([x, Math.abs(y), z], i * 3);
      const tint = 0.7 + 0.3 * Math.random();
      const warm = Math.random() > 0.85;
      col.set([tint, tint * (warm ? 0.85 : 1), tint * (warm ? 0.7 : 1)], i * 3);
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
    const mat = new THREE.PointsMaterial({
      size: 22, sizeAttenuation: true, vertexColors: true,
      transparent: true, opacity: 0, depthWrite: false,
      blending: THREE.AdditiveBlending,
    });
    const pts = new THREE.Points(geo, mat);
    pts.frustumCulled = false;
    return pts;
  }

  setFog(scale) { this._fogScale = scale; this._envDirty = true; this._applyFog(); }

  /** Nässe/Wetter 0..1 — verdichtet und vergraut den Dunst (Regenstimmung). */
  setWetness(w) { this._wet = THREE.MathUtils.clamp(w, 0, 1); this._envDirty = true; this._applyFog(); }

  _applyFog() {
    if (!this.scene.fog || !this._baseFogColor) return;
    const wet = this._wet;
    // Nässe hebt die Dichte (Regendunst) und zieht die Farbe ins kühle Grau.
    this.scene.fog.density = this._baseFog * this._fogScale * (1 + wet * 1.5);
    const col = this._baseFogColor.clone().lerp(RAIN_FOG, wet * 0.55);
    this.scene.fog.color.copy(col);
    this.renderer.setClearColor(col, 1);
  }

  setTime(time) {
    this.time = time;
    // Sonnenhöhe als symmetrischer Bogen. Die Potenz dehnt den Bereich nahe dem
    // Horizont (blaue/goldene Stunde), damit die Tageszeiten intuitiv verteilt sind:
    //   ~0.11 blaue Stunde · ~0.22 goldene Stunde · 0.5 Mittag.
    const arc = Math.pow(Math.sin(time * Math.PI), 2.2);
    const elevationDeg = -12 + arc * 104;
    const azimuthDeg = 70 + time * 140;            // wandert über den Himmel
    const elev = THREE.MathUtils.degToRad(elevationDeg);
    const azim = THREE.MathUtils.degToRad(azimuthDeg);

    this.sunDir.setFromSphericalCoords(1, Math.PI / 2 - elev, azim);
    this.sky.material.uniforms.sunPosition.value.copy(this.sunDir);

    // Übergangsfaktor für Paletten anhand der Sonnenhöhe
    let from, to, t;
    if (elevationDeg < -2)      { from = PALETTE.night;  to = PALETTE.blue;   t = THREE.MathUtils.clamp((elevationDeg + 10) / 8, 0, 1); }
    else if (elevationDeg < 8)  { from = PALETTE.blue;   to = PALETTE.golden; t = (elevationDeg + 2) / 10; }
    else if (elevationDeg < 30) { from = PALETTE.golden; to = PALETTE.day;    t = (elevationDeg - 8) / 22; }
    else                        { from = PALETTE.day;    to = PALETTE.day;    t = 1; }

    const dayFactor = THREE.MathUtils.clamp((elevationDeg + 4) / 16, 0, 1); // 0 nachts → 1 tags

    // Einfache Belichtungsanpassung: Nacht darf angehoben werden, während
    // Mittagssonne und heller Himmel nicht ausbrennen.
    this.renderer.toneMappingExposure = THREE.MathUtils.lerp(0.95, 0.72, dayFactor);

    // Sonnenlicht
    this.sun.position.copy(this.sunDir).multiplyScalar(900);
    this.sun.color.copy(lerpPalette(from, to, t, 'sun'));
    this.sun.intensity = THREE.MathUtils.lerp(0.05, 2.2, dayFactor);

    // Hemisphäre / Ambient
    this.hemi.color.copy(lerpPalette(from, to, t, 'hemiSky'));
    this.hemi.groundColor.copy(lerpPalette(from, to, t, 'hemiGround'));
    this.hemi.intensity = THREE.MathUtils.lerp(0.32, 0.88, dayFactor);
    this.ambient.color.copy(lerpPalette(from, to, t, 'amb'));
    this.ambient.intensity = THREE.MathUtils.lerp(0.4, 0.5, dayFactor);

    // Nebel — Basisfarbe/-dichte je Tageszeit; Nässe kommt in _applyFog dazu.
    this._baseFogColor = lerpPalette(from, to, t, 'fog');
    // Dichter angesetzt: greift schon im mittleren Stadtbereich statt erst am
    // hinteren Rand (Stadt ist ~1000+ Units, alte 0.0013 wirkten erst bei ~770).
    this._baseFog = THREE.MathUtils.lerp(0.0030, 0.0011, dayFactor);
    this._applyFog();

    // Atmosphäre dichter bei tiefer Sonne (mehr Streuung)
    this.sky.material.uniforms.rayleigh.value = THREE.MathUtils.lerp(3.2, 1.4, dayFactor);
    this.sky.material.uniforms.turbidity.value = THREE.MathUtils.lerp(10, 5, dayFactor);

    // Sterne sichtbar machen, wenn es dunkel ist
    this.stars.material.opacity = THREE.MathUtils.clamp(1 - dayFactor * 2.2, 0, 1);

    // "Stadt-Beleuchtung" — Faktor, wie stark Fenster leuchten sollen (1 = volle Nacht)
    this.cityLightFactor = THREE.MathUtils.clamp(1.15 - dayFactor * 1.4, 0.04, 1);

    this._envDirty = true;
  }

  /** Environment-Map periodisch aus dem Himmel neu rendern (für Reflexionen). */
  updateEnvironment() {
    if (!this._envDirty) return;
    this._envDirty = false;
    if (this._envRT) this._envRT.dispose();
    this._envRT = this.pmrem.fromScene(this.sky, 0, 1, 60000);
    this.scene.environment = this._envRT.texture;
    this.scene.environmentIntensity = 0.35;
  }

  dispose() {
    this._envRT?.dispose();
    this.pmrem.dispose();
  }
}
