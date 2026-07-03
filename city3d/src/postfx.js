import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { GTAOPass } from 'three/addons/postprocessing/GTAOPass.js';
import { SMAAPass } from 'three/addons/postprocessing/SMAAPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { ShaderPass } from 'three/addons/postprocessing/ShaderPass.js';

/**
 * Finaler Grade-Pass (läuft NACH Tone-Mapping, also in sRGB/LDR):
 * sanftes Teal-Orange, leichter Kontrast, Vignette und Filmkorn — gibt dem
 * Bild den fotografischen "gefilmt statt gerendert"-Eindruck.
 */
const GradeShader = {
  uniforms: {
    tDiffuse:  { value: null },
    uTime:     { value: 0 },
    uVignette: { value: 0.5 },
    uGrain:    { value: 0.05 },
    uContrast: { value: 1.06 },
    uGrade:    { value: 1.0 }, // 0 = neutral, 1 = voller Look
  },
  vertexShader: /* glsl */`
    varying vec2 vUv;
    void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }
  `,
  fragmentShader: /* glsl */`
    uniform sampler2D tDiffuse;
    uniform float uTime, uVignette, uGrain, uContrast, uGrade;
    varying vec2 vUv;
    float hash(vec2 p){ p = fract(p * vec2(123.34, 345.45)); p += dot(p, p + 34.345); return fract(p.x * p.y); }
    void main() {
      vec4 tex = texture2D(tDiffuse, vUv);
      vec3 c = tex.rgb;
      vec3 graded = c;
      // Teal-Orange: Schatten leicht ins Kühle, Lichter ins Warme
      float luma = dot(c, vec3(0.299, 0.587, 0.114));
      vec3 shadowTint = vec3(0.92, 1.0, 1.06);
      vec3 highTint   = vec3(1.07, 1.0, 0.93);
      graded *= mix(shadowTint, highTint, smoothstep(0.0, 0.6, luma));
      graded = (graded - 0.5) * uContrast + 0.5;
      c = mix(c, graded, uGrade);
      // Vignette
      float vig = smoothstep(1.15, 0.32, length(vUv - 0.5));
      c *= mix(1.0, vig, uVignette * uGrade);
      // Filmkorn (pro Frame variierend via uTime)
      float g = hash(vUv * vec2(1280.0, 720.0) + fract(uTime)) - 0.5;
      c += g * uGrain * uGrade;
      gl_FragColor = vec4(clamp(c, 0.0, 1.0), tex.a);
    }
  `,
};

/**
 * Render-Pipeline. Reihenfolge:
 *   Render → SSAO(GTAO) → Bloom → Tone-Mapping(Output) → SMAA → Grade
 * Alle optionalen Pässe sind über `.enabled` einzeln abschaltbar — so lässt
 * sich die Qualität bei riesigen Architekturen herunterregeln.
 */
export function createComposer(renderer, scene, camera) {
  const w = window.innerWidth, h = window.innerHeight;
  const composer = new EffectComposer(renderer);
  composer.setPixelRatio(renderer.getPixelRatio());
  composer.addPass(new RenderPass(scene, camera));

  // --- SSAO: Kontakt-Verschattung in den Straßenschluchten ---
  const gtao = new GTAOPass(scene, camera, w, h);
  gtao.output = GTAOPass.OUTPUT.Default;
  gtao.blendIntensity = 0.9;
  gtao.updateGtaoMaterial({
    radius: 0.5,
    distanceExponent: 1.0,
    thickness: 1.0,
    scale: 1.0,
    samples: 16,
    screenSpaceRadius: true, // robust trotz großer near/far-Spanne der Stadt
  });
  composer.addPass(gtao);

  // --- Bloom: beleuchtete Fenster und Lichter glühen ---
  const bloom = new UnrealBloomPass(new THREE.Vector2(w, h), 0.4, 0.45, 0.6);
  composer.addPass(bloom);

  // --- Tone-Mapping + sRGB ---
  composer.addPass(new OutputPass());

  // --- Kantenglättung (auf LDR) ---
  const smaa = new SMAAPass(w, h);
  composer.addPass(smaa);

  // --- Color-Grade / Vignette / Filmkorn ---
  const grade = new ShaderPass(GradeShader);
  composer.addPass(grade);

  return { composer, bloom, gtao, smaa, grade };
}
