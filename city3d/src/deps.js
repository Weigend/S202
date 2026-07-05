import * as THREE from 'three';

/**
 * Abhängigkeits-Visualisierung: leuchtende Bögen von Dach zu Dach.
 *
 * Jede Kante (aus model.dependencies) wird als Quadratic-Bézier-Bogen zwischen
 * den Gebäudedächern gezeichnet, plus animierte "Datenpakete" (GPU-Punkte), die
 * vom Nutzer zum Genutzten fliegen. Additiv gerendert — der Bloom-Pass macht
 * daraus Lichtstreifen über der Stadt, ohne das Layout anzufassen.
 *
 * Farb-Semantik (Architektur!):
 *   - regelkonform (Level von → Level nach fällt): kühles Cyan
 *   - Verstoß (aufwärts oder gleiches Level = Zyklus): Rot
 *   - im Auswahl-Modus: ausgehend Amber, eingehend Türkis (Verstöße bleiben rot)
 *
 * Modi: 'off' | 'sel' (nur Auswahl) | 'all' | 'viol' (nur Verstöße).
 */

const COL_OK = new THREE.Color(0x3fa8ff);
const COL_VIOL = new THREE.Color(0xff4040);
const COL_OUT = new THREE.Color(0xffb24d);
const COL_IN = new THREE.Color(0x4de6c8);

const LINE_SHADER = {
  vertexShader: /* glsl */`
    attribute vec3 aColor;
    attribute float aT;
    attribute float aPhase;
    varying vec3 vColor;
    varying float vT;
    varying float vPhase;
    void main() {
      vColor = aColor; vT = aT; vPhase = aPhase;
      gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    }`,
  fragmentShader: /* glsl */`
    uniform float uTime;
    uniform float uBase;   // Grundhelligkeit des Bogens
    uniform float uWave;   // Zusatzhelligkeit der wandernden Welle
    varying vec3 vColor;
    varying float vT;
    varying float vPhase;
    void main() {
      // Eine helle Welle wandert von t=0 (Quelle) nach t=1 (Ziel).
      float f = fract(vT - uTime * 0.22 + vPhase);
      float w = pow(max(0.0, 1.0 - abs(f - 0.5) * 4.0), 2.0);
      float i = uBase + uWave * w;
      gl_FragColor = vec4(vColor * i, 1.0);
    }`,
};

const POINT_SHADER = {
  vertexShader: /* glsl */`
    attribute vec3 aP0;
    attribute vec3 aP1;
    attribute vec3 aP2;
    attribute vec3 aColor;
    attribute float aPhase;
    attribute float aSpeed;
    uniform float uTime;
    uniform float uSize;
    varying vec3 vColor;
    void main() {
      vColor = aColor;
      float t = fract(uTime * aSpeed + aPhase);
      float s = 1.0 - t;
      vec3 p = s * s * aP0 + 2.0 * s * t * aP1 + t * t * aP2; // Quadratic Bézier
      vec4 mv = modelViewMatrix * vec4(p, 1.0);
      gl_PointSize = uSize * (240.0 / max(1.0, -mv.z));
      gl_Position = projectionMatrix * mv;
    }`,
  fragmentShader: /* glsl */`
    uniform float uIntensity;
    varying vec3 vColor;
    void main() {
      float d = length(gl_PointCoord - 0.5) * 2.0;
      float a = smoothstep(1.0, 0.0, d);
      gl_FragColor = vec4(vColor * uIntensity * a * a, a);
    }`,
};

export class DependencyViz {
  constructor(scene, model, anchors) {
    this.scene = scene;
    this.group = new THREE.Group();
    this.group.name = 'dependencies';
    this.group.userData.noAO = true; // additive Bögen gehören nicht in den SSAO-Tiefenpass
    scene.add(this.group);
    this.mode = 'off';
    this.focus = null;
    this._mats = [];

    // Klassen-Metadaten für die Richtungs-Klassifikation.
    const level = new Map();
    for (const b of model.buildings ?? []) level.set(b.fullName, b.architectureLevel ?? 0);

    // Kantenliste: beide Enden müssen ein Gebäude in der Stadt sein.
    this.edges = [];
    this.byFrom = new Map();
    this.byTo = new Map();
    for (const dep of model.dependencies ?? []) {
      const a = anchors[dep.from], b = anchors[dep.to];
      if (!a || !b || dep.from === dep.to) continue;
      // Verstoß nur, wenn die Kante echt AUFWÄRTS läuft (Quell- < Ziel-Level);
      // Gleich-Level-Kanten (SCC-intern) bleiben neutral, sonst ist in
      // hochzyklischen Systemen fast alles rot.
      const violation = (level.get(dep.from) ?? 0) < (level.get(dep.to) ?? 0);
      const e = { from: dep.from, to: dep.to, a, b, violation };
      this.edges.push(e);
      if (!this.byFrom.has(dep.from)) this.byFrom.set(dep.from, []);
      this.byFrom.get(dep.from).push(e);
      if (!this.byTo.has(dep.to)) this.byTo.set(dep.to, []);
      this.byTo.get(dep.to).push(e);
    }
    this.stats = {
      total: this.edges.length,
      violations: this.edges.filter((e) => e.violation).length,
    };
    this._time = 0;
  }

  /** Kanten einer Klasse für das Info-Panel: was nutzt sie, wer nutzt sie. */
  edgesFor(fqn) {
    return {
      uses: (this.byFrom.get(fqn) ?? []).map((e) => ({ fqn: e.to, violation: e.violation })),
      usedBy: (this.byTo.get(fqn) ?? []).map((e) => ({ fqn: e.from, violation: e.violation })),
    };
  }

  setMode(mode) {
    this.mode = mode;
    this._rebuild();
  }

  setFocus(fqn) {
    this.focus = fqn || null;
    if (this.mode === 'sel') this._rebuild();
  }

  update(t) {
    this._time = t;
    for (const m of this._mats) m.uniforms.uTime.value = t;
  }

  _clear() {
    for (let i = this.group.children.length - 1; i >= 0; i--) {
      const o = this.group.children[i];
      o.geometry?.dispose();
      this.group.remove(o);
    }
    for (const m of this._mats) m.dispose();
    this._mats = [];
  }

  _rebuild() {
    this._clear();
    let edges, focus = null;
    if (this.mode === 'all') edges = this.edges;
    else if (this.mode === 'viol') edges = this.edges.filter((e) => e.violation);
    else if (this.mode === 'sel' && this.focus) {
      focus = this.focus;
      edges = this.edges.filter((e) => e.from === focus || e.to === focus);
    } else edges = [];
    if (!edges.length) return;

    // Bei riesigen Graphen Kurven gröber abtasten und weniger Pulse fliegen lassen.
    const seg = edges.length > 3000 ? 8 : edges.length > 800 ? 12 : 20;
    const pulses = edges.length > 3000 ? 1 : 2;
    const focused = !!focus;

    const colorOf = (e) => {
      if (e.violation) return COL_VIOL;
      if (focused) return e.from === focus ? COL_OUT : COL_IN;
      return COL_OK;
    };

    // ---- Bögen als LineSegments (ein Draw-Call) -----------------------------
    const vtx = edges.length * (seg + 1);
    const pos = new Float32Array(vtx * 3);
    const col = new Float32Array(vtx * 3);
    const tAttr = new Float32Array(vtx);
    const phase = new Float32Array(vtx);
    const idx = [];
    const p = new THREE.Vector3();
    let vi = 0;
    for (let ei = 0; ei < edges.length; ei++) {
      const e = edges[ei];
      const c = colorOf(e);
      const ph = ((ei * 2654435761) % 1000) / 1000; // deterministische Pro-Kante-Phase
      const { p0, p1, p2 } = arcFor(e);
      for (let i = 0; i <= seg; i++) {
        const t = i / seg, s = 1 - t;
        p.set(
          s * s * p0.x + 2 * s * t * p1.x + t * t * p2.x,
          s * s * p0.y + 2 * s * t * p1.y + t * t * p2.y,
          s * s * p0.z + 2 * s * t * p1.z + t * t * p2.z,
        );
        pos.set([p.x, p.y, p.z], (vi + i) * 3);
        col.set([c.r, c.g, c.b], (vi + i) * 3);
        tAttr[vi + i] = t;
        phase[vi + i] = ph;
        if (i < seg) idx.push(vi + i, vi + i + 1);
      }
      vi += seg + 1;
    }
    const lineGeo = new THREE.BufferGeometry();
    lineGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    lineGeo.setAttribute('aColor', new THREE.BufferAttribute(col, 3));
    lineGeo.setAttribute('aT', new THREE.BufferAttribute(tAttr, 1));
    lineGeo.setAttribute('aPhase', new THREE.BufferAttribute(phase, 1));
    lineGeo.setIndex(idx);
    const lineMat = new THREE.ShaderMaterial({
      ...LINE_SHADER,
      uniforms: {
        uTime: { value: this._time },
        // Fokus-Bögen deutlich, Gesamtgraph zurückhaltend (Bloom hebt den Rest).
        uBase: { value: focused ? 0.85 : this.mode === 'viol' ? 0.8 : 0.28 },
        uWave: { value: focused ? 1.6 : 0.9 },
      },
      transparent: true, blending: THREE.AdditiveBlending, depthWrite: false,
    });
    const lines = new THREE.LineSegments(lineGeo, lineMat);
    lines.renderOrder = 950;
    lines.frustumCulled = false;
    this.group.add(lines);
    this._mats.push(lineMat);

    // ---- fliegende "Datenpakete" (GPU-Punkte auf denselben Bézier-Bögen) ----
    const n = edges.length * pulses;
    const p0a = new Float32Array(n * 3), p1a = new Float32Array(n * 3), p2a = new Float32Array(n * 3);
    const pcol = new Float32Array(n * 3), pph = new Float32Array(n), pspd = new Float32Array(n);
    let pi = 0;
    for (let ei = 0; ei < edges.length; ei++) {
      const e = edges[ei];
      const c = colorOf(e);
      const { p0, p1, p2, len } = arcFor(e);
      for (let k = 0; k < pulses; k++) {
        p0a.set([p0.x, p0.y, p0.z], pi * 3);
        p1a.set([p1.x, p1.y, p1.z], pi * 3);
        p2a.set([p2.x, p2.y, p2.z], pi * 3);
        pcol.set([c.r, c.g, c.b], pi * 3);
        pph[pi] = (((ei * 40503 + k * 9973) % 1000) / 1000);
        pspd[pi] = 14 / Math.max(30, len); // ~konstante Fluggeschwindigkeit
        pi++;
      }
    }
    const ptGeo = new THREE.BufferGeometry();
    ptGeo.setAttribute('position', new THREE.BufferAttribute(p0a.slice(), 3)); // Dummy (Shader rechnet selbst)
    ptGeo.setAttribute('aP0', new THREE.BufferAttribute(p0a, 3));
    ptGeo.setAttribute('aP1', new THREE.BufferAttribute(p1a, 3));
    ptGeo.setAttribute('aP2', new THREE.BufferAttribute(p2a, 3));
    ptGeo.setAttribute('aColor', new THREE.BufferAttribute(pcol, 3));
    ptGeo.setAttribute('aPhase', new THREE.BufferAttribute(pph, 1));
    ptGeo.setAttribute('aSpeed', new THREE.BufferAttribute(pspd, 1));
    const ptMat = new THREE.ShaderMaterial({
      ...POINT_SHADER,
      uniforms: {
        uTime: { value: this._time },
        uSize: { value: focused ? 26 : 16 },
        uIntensity: { value: focused ? 3.2 : 2.2 },
      },
      transparent: true, blending: THREE.AdditiveBlending, depthWrite: false,
    });
    const points = new THREE.Points(ptGeo, ptMat);
    points.renderOrder = 951;
    points.frustumCulled = false;
    this.group.add(points);
    this._mats.push(ptMat);
  }

  dispose() {
    this._clear();
    this.scene.remove(this.group);
  }
}

// Bogen einer Kante: Start/Ziel knapp über den Dächern, Scheitel angehoben —
// weiter entfernte Ziele fliegen höher (wie eine ballistische Kurve).
function arcFor(e) {
  const p0 = new THREE.Vector3(e.a.x, e.a.roofY + 2, e.a.z);
  const p2 = new THREE.Vector3(e.b.x, e.b.roofY + 2, e.b.z);
  const dx = p2.x - p0.x, dz = p2.z - p0.z;
  const dist = Math.hypot(dx, dz);
  const apex = Math.max(p0.y, p2.y) + THREE.MathUtils.clamp(dist * 0.28, 7, 130);
  const p1 = new THREE.Vector3((p0.x + p2.x) / 2, apex, (p0.z + p2.z) / 2);
  return { p0, p1, p2, len: dist + Math.abs(p2.y - p0.y) };
}
