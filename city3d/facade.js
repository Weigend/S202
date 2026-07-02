// Structure202 · City3D — procedural building-facade material.
//
// A simplified port of the City3JS window shader for individual (non-instanced)
// building meshes: patches MeshStandardMaterial via onBeforeCompile to draw a
// window grid on the walls, with a fraction of panes lit (emissive). Per-mesh
// uniforms carry the box dimensions, a seed, and the lit fraction, so the
// mapping can be driven by metrics (e.g. lit fraction = test coverage).

import * as THREE from 'three';

/**
 * @param {number}       color  base wall colour (hex)
 * @param {THREE.Vector3} dim   building box dimensions (w, h, d)
 * @param {number}       seed   per-building random seed
 * @param {number}       lit    fraction of windows lit at night [0..1]
 * @param {boolean}      scc    part of an architectural cycle -> reddened facade
 */
export function facadeMaterial(color, dim, seed, lit, scc) {
  const mat = new THREE.MeshStandardMaterial({
    color, roughness: 0.7, metalness: 0.12,
  });
  mat.userData.u = {
    uDim: { value: dim },
    uSeed: { value: seed },
    uLit: { value: lit },
    uScc: { value: scc ? 1 : 0 },
  };
  mat.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, mat.userData.u);

    sh.vertexShader = sh.vertexShader
      .replace('#include <common>',
        `#include <common>
         varying vec3 vMetric;
         flat varying vec3 vN;`)
      .replace('#include <begin_vertex>',
        `#include <begin_vertex>
         vMetric = position;
         vN = normal;`);

    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>',
        `#include <common>
         uniform vec3 uDim; uniform float uSeed; uniform float uLit; uniform float uScc;
         varying vec3 vMetric;
         flat varying vec3 vN;
         float h11(float p){ p=fract(p*0.1031); p*=p+33.33; p*=p+p; return fract(p); }`)
      // Window grid + reddened SCC tint on the diffuse colour.
      .replace('#include <map_fragment>',
        `#include <map_fragment>
         float winGlow = 0.0;
         {
           vec3 n = normalize(vN);
           if (abs(n.y) < 0.5) {
             float hcoord  = abs(n.x) > abs(n.z) ? vMetric.z : vMetric.x;
             float faceW   = abs(n.x) > abs(n.z) ? uDim.z : uDim.x;
             float gy      = vMetric.y + uDim.y * 0.5;      // 0 at ground
             float bayW    = 2.4;
             float FH      = 3.3;
             float bays    = max(1.0, floor(faceW / bayW + 0.5));
             float u       = (hcoord + faceW * 0.5) / (faceW / bays);
             float cx      = floor(u);
             float cy      = floor(gy / FH);
             vec2  cell    = fract(vec2(u, gy / FH));
             float m       = step(0.16, cell.x) * step(cell.x, 0.84)
                           * step(0.18, cell.y) * step(cell.y, 0.80);
             vec3  wall    = diffuseColor.rgb;
             if (uScc > 0.5) wall = mix(wall, vec3(0.55, 0.08, 0.07), 0.6);   // cycle -> red
             vec3  pane    = mix(vec3(0.04, 0.06, 0.09), vec3(0.10, 0.14, 0.19),
                                 h11(uSeed + cx * 3.1 + cy * 7.7));
             float litRnd  = h11(uSeed * 1.7 + cx * 12.3 + cy * 7.1);
             float lit     = step(litRnd, uLit) * m;
             if (lit > 0.5) pane = vec3(1.0, 0.83, 0.52);                     // lit window
             diffuseColor.rgb = mix(wall * 0.7, pane, m);
             winGlow = lit;
           } else if (uScc > 0.5) {
             diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.55, 0.08, 0.07), 0.6);
           }
         }`)
      // Lit windows glow.
      .replace('#include <emissivemap_fragment>',
        `#include <emissivemap_fragment>
         totalEmissiveRadiance += vec3(1.0, 0.82, 0.5) * winGlow * 1.6;`);
  };
  return mat;
}
