import * as THREE from 'three';

/**
 * Regen als Linien-Streaks in einem Volumen, das der Kamera folgt — die Dichte
 * (Tropfenzahl) ist damit unabhängig von der Stadtgröße. Die Tropfen fallen,
 * wrappen im Volumen und werden additiv geblendet, sodass sie nachts das Licht
 * fangen und mit dem Bloom interagieren.
 */
export function createRain(scene, opts = {}) {
  const N = opts.count ?? 4200;
  const W = opts.width ?? 440;
  const H = opts.height ?? 440;
  const D = opts.depth ?? 440;
  const speed = opts.speed ?? 235;   // Fallgeschwindigkeit (Units/s)
  const streak = opts.streak ?? 3.6; // Länge eines Tropfen-Streaks
  const wind = opts.wind ?? 0.7;     // horizontaler Versatz (Schräge)

  // Basis-Positionen relativ zur Kamera (Volumen ist um die Kamera zentriert).
  const sx = new Float32Array(N);
  const sy = new Float32Array(N);
  const sz = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    sx[i] = (Math.random() - 0.5) * W;
    sy[i] = Math.random() * H;
    sz[i] = (Math.random() - 0.5) * D;
  }

  const positions = new Float32Array(N * 2 * 3);
  const posAttr = new THREE.BufferAttribute(positions, 3);
  posAttr.setUsage(THREE.DynamicDrawUsage);
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', posAttr);

  const mat = new THREE.LineBasicMaterial({
    color: 0x8aa0b8,
    transparent: true,
    opacity: 0,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
  });
  const mesh = new THREE.LineSegments(geo, mat);
  mesh.frustumCulled = false;
  mesh.visible = false;
  scene.add(mesh);

  let elapsed = 0;

  /** intensity 0..1 — koppelt an den Nässe/Wetter-Regler. */
  function update(dt, camera, intensity) {
    const op = intensity * 0.32;
    if (op <= 0.001) { mesh.visible = false; return; }
    mesh.visible = true;
    mat.opacity = op;

    elapsed += dt;
    const phase = elapsed * speed;
    const cx = camera.position.x, cy = camera.position.y, cz = camera.position.z;
    const dx = wind * streak;

    for (let i = 0; i < N; i++) {
      let y = (sy[i] - phase) % H;
      if (y < 0) y += H;
      const topY = cy + y - H * 0.5;
      const x = cx + sx[i];
      const z = cz + sz[i];
      const o = i * 6;
      positions[o]     = x;       positions[o + 1] = topY;          positions[o + 2] = z;
      positions[o + 3] = x + dx;  positions[o + 4] = topY - streak; positions[o + 5] = z;
    }
    posAttr.needsUpdate = true;
  }

  function dispose() {
    scene.remove(mesh);
    geo.dispose();
    mat.dispose();
  }

  return { mesh, update, dispose };
}
