import * as THREE from 'three';

/**
 * Atmosphäre: prozeduraler Himmel, Sonne/Mond, Beleuchtung, Nebel, Sterne,
 * eine driftende Wolkenschicht und eine aus dem Himmel gerenderte
 * Environment-Map für Reflexionen.
 *
 * `time` ∈ [0,1] steuert den kompletten Tagesbogen:
 *   0.00  tiefe Nacht        0.16  blaue Stunde (default)
 *   0.30  goldene Stunde     0.50  Mittag
 *   0.70  goldene Stunde     0.85  blaue Stunde      1.0  Nacht
 *
 * Das Tagesmodell ist auf "klarer, knackiger Vormittag" abgestimmt: tiefer
 * blauer Himmel (niedrige Turbidity), harte warme Sonne mit vollem Kontrast,
 * kühles Himmelslicht von oben, dünner Horizontdunst statt Milchsuppe.
 */

const tmpColor = new THREE.Color();

// Farbpaletten je nach Sonnenhöhe — werden interpoliert.
// fog = Dunst-/Horizontfarbe. Nachts bewusst NICHT schwarz (Lichtverschmutzung).
const PALETTE = {
  night:  { sun: 0x223047, amb: 0x101d38, hemiSky: 0x1b2d52, hemiGround: 0x0a0f18, fog: 0x1c2740, cloud: 0x232e44, zenith: 0x050a16, horizon: 0x152238 },
  blue:   { sun: 0x4a6fa5, amb: 0x1b2c4a, hemiSky: 0x2a4670, hemiGround: 0x0a1020, fog: 0x243350, cloud: 0x3a4c6e, zenith: 0x0d2148, horizon: 0x3d5a8a },
  golden: { sun: 0xffab5e, amb: 0x4a3c4e, hemiSky: 0xb08cc0, hemiGround: 0x241a1c, fog: 0x9a7a80, cloud: 0xffc9a0, zenith: 0x2c53a0, horizon: 0xffb072 },
  day:    { sun: 0xfff2da, amb: 0x51617c, hemiSky: 0x8fb6ea, hemiGround: 0x3a4148, fog: 0x9fc0e4, cloud: 0xffffff, zenith: 0x2b6bd2, horizon: 0xa8ccf2 },
};

// Regen-/Nässe-Dunst: kühles Grau, in das der Nebel bei "Wetter" überblendet.
const RAIN_FOG = new THREE.Color(0x2c3340);

function lerpPalette(a, b, t, key) {
  return tmpColor.set(a[key]).lerp(new THREE.Color(b[key]), t).clone();
}

// ---- prozedurale Wolkentextur (Value-Noise-FBM auf Canvas, kachelbar) --------
function makeCloudTexture(seed = 7) {
  const S = 256;
  let s = seed >>> 0;
  const rnd = () => {
    s |= 0; s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  // kachelbare Zufallsgitter für 4 Oktaven
  const octaves = [8, 16, 32, 64].map((n) => {
    const g = new Float32Array(n * n);
    for (let i = 0; i < g.length; i++) g[i] = rnd();
    return { n, g };
  });
  const smooth = (t) => t * t * (3 - 2 * t);
  const sample = ({ n, g }, u, v) => {
    const x = u * n, y = v * n;
    const x0 = Math.floor(x) % n, y0 = Math.floor(y) % n;
    const x1 = (x0 + 1) % n, y1 = (y0 + 1) % n;
    const fx = smooth(x - Math.floor(x)), fy = smooth(y - Math.floor(y));
    const a = g[y0 * n + x0], b = g[y0 * n + x1], c = g[y1 * n + x0], d = g[y1 * n + x1];
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
  };
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = S;
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(S, S);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const u = x / S, v = y / S;
      let n = 0, wsum = 0, w = 1;
      for (const o of octaves) { n += sample(o, u, v) * w; wsum += w; w *= 0.55; }
      n /= wsum;
      // weiche Cumulus-Felder: unterhalb der Schwelle klarer Himmel
      const a = THREE.MathUtils.smoothstep(n, 0.52, 0.74);
      const i = (y * S + x) * 4;
      img.data[i] = img.data[i + 1] = img.data[i + 2] = 255;
      img.data[i + 3] = Math.round(a * 235);
    }
  }
  ctx.putImageData(img, 0, 0);
  const tex = new THREE.CanvasTexture(canvas);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(3, 3);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
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

    // --- Himmel: eigener Gradient-Dome (sattes Zenit-Blau -> heller Horizont)
    // mit Sonnenscheibe + Glow und Mondscheibe. Der Preetham-Himmel des
    // Sky-Addons war am Horizont immer weiß — hier haben wir volle Kontrolle.
    this.skyUniforms = {
      uSunDir: { value: new THREE.Vector3(0, 1, 0) },
      uMoonDir: { value: new THREE.Vector3(0, -1, 0) },
      uZenith: { value: new THREE.Color(0x2b6bd2) },
      uHorizon: { value: new THREE.Color(0xa8ccf2) },
      uSunCol: { value: new THREE.Color(0xfff2da) },
      uSunGlow: { value: 1 },
      uMoonVis: { value: 0 },
    };
    this.sky = new THREE.Mesh(
      new THREE.SphereGeometry(29000, 32, 16),
      new THREE.ShaderMaterial({
        side: THREE.BackSide, depthWrite: false, fog: false,
        uniforms: this.skyUniforms,
        vertexShader: /* glsl */`
          varying vec3 vDir;
          void main() {
            vDir = position;
            gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
          }`,
        fragmentShader: /* glsl */`
          uniform vec3 uSunDir, uMoonDir, uZenith, uHorizon, uSunCol;
          uniform float uSunGlow, uMoonVis;
          varying vec3 vDir;
          void main() {
            vec3 d = normalize(vDir);
            float h = clamp(d.y, 0.0, 1.0);
            // Zenit-Blau -> Horizont; unter dem Horizont dunkler ablaufen
            vec3 sky = mix(uHorizon, uZenith, pow(h, 0.42));
            sky = mix(uHorizon * 0.55, sky, smoothstep(-0.12, 0.02, d.y));
            // Sonne: Scheibe + enger und weiter Glow (warm)
            float s = max(dot(d, uSunDir), 0.0);
            sky += uSunCol * smoothstep(0.99955, 0.99985, s) * 5.0;
            sky += uSunCol * (pow(s, 320.0) * 0.9 + pow(s, 24.0) * 0.16) * uSunGlow;
            // Mond: kühle Scheibe mit deutlichem Hof (Mondlichtphase)
            float m = max(dot(d, uMoonDir), 0.0);
            sky += vec3(0.82, 0.88, 1.0) * (smoothstep(0.99978, 0.99993, m) * 2.6 + pow(m, 600.0) * 0.9) * uMoonVis;
            gl_FragColor = vec4(sky, 1.0);
            #include <tonemapping_fragment>
            #include <colorspace_fragment>
          }`,
      }));
    this.sky.frustumCulled = false;
    scene.add(this.sky);

    // --- Sonne / Lichter ---
    this.sun = new THREE.DirectionalLight(0xffffff, 1);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(2048, 2048);
    this.sun.shadow.bias = -0.0004;
    this.sun.shadow.normalBias = 0.02; // weniger Schatten-Akne auf den flachen Plattformen
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

    // --- Wolkenschicht: Ebene hoch über der Stadt, driftet langsam. Ein
    // radialer Alpha-Fade lässt sie zum Horizont hin verschwinden — sonst
    // integriert die fast blickparallele Ebene dort zu einem Milchschleier.
    this.cloudTex = makeCloudTexture();
    const cloudGeo = new THREE.PlaneGeometry(18000, 18000);
    cloudGeo.rotateX(Math.PI / 2); // Fläche zeigt nach unten zur Kamera
    this.cloudMat = new THREE.MeshBasicMaterial({
      map: this.cloudTex, transparent: true, opacity: 0,
      depthWrite: false, fog: false, side: THREE.DoubleSide,
    });
    this.cloudMat.onBeforeCompile = (sh) => {
      sh.vertexShader = sh.vertexShader
        .replace('#include <common>', '#include <common>\n varying vec3 vCloudWorld;')
        .replace('#include <begin_vertex>', '#include <begin_vertex>\n vCloudWorld = (modelMatrix * vec4(position, 1.0)).xyz;');
      sh.fragmentShader = sh.fragmentShader
        .replace('#include <common>', '#include <common>\n varying vec3 vCloudWorld;')
        .replace('#include <map_fragment>',
          `#include <map_fragment>
           diffuseColor.a *= 1.0 - smoothstep(4800.0, 8400.0, length(vCloudWorld.xz));`);
    };
    this.clouds = new THREE.Mesh(cloudGeo, this.cloudMat);
    this.clouds.position.y = 1500;
    this.clouds.renderOrder = -1;
    this.clouds.frustumCulled = false;
    scene.add(this.clouds);

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

  /** Pro Frame: Wolken driften lassen (Windrichtung leicht diagonal). */
  update(dt) {
    this.cloudTex.offset.x += dt * 0.00055;
    this.cloudTex.offset.y += dt * 0.00013;
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
    // Regenwetter = geschlossene, graue Wolkendecke
    this.cloudMat.opacity = Math.min(1, this._cloudBase + wet * 0.5);
    this.cloudMat.color.copy(this._cloudColor).lerp(RAIN_FOG, wet * 0.6);
  }

  setTime(time) {
    this.time = time;
    // Sonnenhöhe als symmetrischer Bogen; die Potenz dehnt den Bereich nahe dem
    // Horizont (blaue/goldene Stunde), damit die Tageszeiten intuitiv verteilt sind.
    const arc = Math.pow(Math.sin(time * Math.PI), 2.2);
    const elevationDeg = -12 + arc * 104;
    const azimuthDeg = 70 + time * 140;            // wandert über den Himmel
    const elev = THREE.MathUtils.degToRad(elevationDeg);
    const azim = THREE.MathUtils.degToRad(azimuthDeg);

    this.sunDir.setFromSphericalCoords(1, Math.PI / 2 - elev, azim);
    this.skyUniforms.uSunDir.value.copy(this.sunDir);

    // Mond: gegenüber der Sonne, steigt auf, wenn die Sonne untergeht.
    const moonElev = THREE.MathUtils.degToRad(THREE.MathUtils.clamp(24 - elevationDeg * 0.85, -30, 75));
    this.moonDir = this.moonDir ?? new THREE.Vector3();
    this.moonDir.setFromSphericalCoords(1, Math.PI / 2 - moonElev, azim + Math.PI);
    this.skyUniforms.uMoonDir.value.copy(this.moonDir);
    const moonVis = THREE.MathUtils.clamp(-elevationDeg / 6, 0, 1); // sichtbar, sobald die Sonne unter dem Horizont ist
    this.skyUniforms.uMoonVis.value = moonVis;

    // Übergangsfaktor für Paletten anhand der Sonnenhöhe
    let from, to, t;
    if (elevationDeg < -2)      { from = PALETTE.night;  to = PALETTE.blue;   t = THREE.MathUtils.clamp((elevationDeg + 10) / 8, 0, 1); }
    else if (elevationDeg < 8)  { from = PALETTE.blue;   to = PALETTE.golden; t = (elevationDeg + 2) / 10; }
    else if (elevationDeg < 30) { from = PALETTE.golden; to = PALETTE.day;    t = (elevationDeg - 8) / 22; }
    else                        { from = PALETTE.day;    to = PALETTE.day;    t = 1; }

    const dayFactor = THREE.MathUtils.clamp((elevationDeg + 4) / 16, 0, 1); // 0 nachts → 1 tags

    // Belichtung: Nacht deutlich angehoben (Mondnacht, kein schwarzes Loch),
    // Tag bewusst knapp — der Kontrast kommt aus der harten Sonne.
    this.renderer.toneMappingExposure = THREE.MathUtils.lerp(1.12, 0.78, dayFactor);

    // Sonnenlicht tagsüber hart; nachts übernimmt der MOND: eine echte
    // Mondlichtphase mit kühlem, klar sichtbarem Licht und Schattenwurf —
    // Boden und Straßen bleiben lesbar, die Stadt badet in Blau.
    if (moonVis > 0.5) {
      this.sun.position.copy(this.moonDir).multiplyScalar(900);
      this.sun.color.set(0x9fb9ea);
      this.sun.intensity = 1.05 * moonVis;
    } else {
      this.sun.position.copy(this.sunDir).multiplyScalar(900);
      this.sun.color.copy(lerpPalette(from, to, t, 'sun'));
      this.sun.intensity = THREE.MathUtils.lerp(0.08, 2.7, dayFactor);
    }

    // Hemisphäre kräftig (kühles Himmelslicht von oben), Ambient tagsüber
    // ZURÜCKGENOMMEN — flaches Füll-Licht war der Hauptgrund für den Milch-Look.
    this.hemi.color.copy(lerpPalette(from, to, t, 'hemiSky'));
    this.hemi.groundColor.copy(lerpPalette(from, to, t, 'hemiGround'));
    this.hemi.intensity = THREE.MathUtils.lerp(0.72, 0.8, dayFactor);
    this.ambient.color.copy(lerpPalette(from, to, t, 'amb'));
    this.ambient.intensity = THREE.MathUtils.lerp(0.7, 0.26, dayFactor);

    // Nebel: tagsüber nur dünner Horizontdunst, nachts dichter Stadt-Dunst.
    this._baseFogColor = lerpPalette(from, to, t, 'fog');
    this._baseFog = THREE.MathUtils.lerp(0.0030, 0.0003, dayFactor);

    // Wolken: tagsüber weiß und präsent, zur Nacht hin dunkel und dünn.
    this._cloudColor = lerpPalette(from, to, t, 'cloud');
    this._cloudBase = THREE.MathUtils.lerp(0.10, 0.5, dayFactor);
    this._applyFog();

    // Himmel-Dome: Zenit/Horizont aus der Palette, Sonnenglühen bei tiefer Sonne
    this.skyUniforms.uZenith.value.copy(lerpPalette(from, to, t, 'zenith'));
    this.skyUniforms.uHorizon.value.copy(lerpPalette(from, to, t, 'horizon'));
    this.skyUniforms.uSunCol.value.copy(lerpPalette(from, to, t, 'sun'));
    this.skyUniforms.uSunGlow.value = THREE.MathUtils.lerp(2.2, 0.75, dayFactor);

    // Sterne sichtbar machen, wenn es dunkel ist
    this.stars.material.opacity = THREE.MathUtils.clamp(1 - dayFactor * 2.2, 0, 1);

    // Reflexionen: tags kräftigere Env-Map (Glasfassaden spiegeln den Himmel)
    this.scene.environmentIntensity = THREE.MathUtils.lerp(0.35, 0.5, dayFactor);

    // "Stadt-Beleuchtung" — Faktor, wie stark Fenster leuchten sollen (1 = volle Nacht)
    this.cityLightFactor = THREE.MathUtils.clamp(1.15 - dayFactor * 1.4, 0.04, 1);
    this.dayFactor = dayFactor; // z.B. für Tag/Nacht-Verkehrsdichte

    this._envDirty = true;
  }

  /** Environment-Map periodisch aus dem Himmel neu rendern (für Reflexionen). */
  updateEnvironment() {
    if (!this._envDirty) return;
    this._envDirty = false;
    if (this._envRT) this._envRT.dispose();
    this._envRT = this.pmrem.fromScene(this.sky, 0, 1, 60000);
    this.scene.environment = this._envRT.texture;
  }

  dispose() {
    this._envRT?.dispose();
    this.pmrem.dispose();
    this.cloudTex.dispose();
    this.cloudMat.dispose();
  }
}
