import * as THREE from 'three';

/**
 * Paket-Namensschilder: ein Canvas-Sprite pro Plattform, an deren Vorderkante —
 * wie Distrikt-Schilder in einer echten Stadt. Sichtbarkeit ist distanz- und
 * tiefenabhängig: Top-Level-Pakete sind von weitem lesbar, tief verschachtelte
 * blenden erst beim Heranfliegen ein. So bleibt das Bild aus der Totale ruhig.
 */
export function makePackageLabels(slabs, maxDepth) {
  const group = new THREE.Group();
  group.name = 'package-labels';
  const entries = [];

  // Bei sehr großen Architekturen die tiefsten Schilder weglassen (Sprite-Budget).
  const MAX_LABELS = 400;
  let candidates = slabs.filter((s) => s.simple);
  if (candidates.length > MAX_LABELS) {
    candidates = [...candidates].sort((a, b) => a.depth - b.depth).slice(0, MAX_LABELS);
  }

  for (const s of candidates) {
    const sprite = makeTextSprite(s.simple, s.inCycle);
    // Vorderkante der Plattform, knapp über deren Oberfläche.
    sprite.position.set(s.x, s.topY + 2.2 + sprite.userData.h / 2, s.z + s.d / 2 + 0.8);
    group.add(sprite);
    // Einblend-Distanz wächst mit der Plattformgröße und sinkt mit der Tiefe.
    const size = Math.max(s.w, s.d);
    entries.push({ sprite, fadeIn: size * 3.2 + 420 / (1 + s.depth), depth: s.depth });
  }

  let accum = 0;
  return {
    group,
    // Gedrosselt aufrufen: Opacity je nach Kameradistanz nachführen.
    update(camera, dt) {
      accum += dt;
      if (accum < 0.12) return;
      accum = 0;
      for (const e of entries) {
        const d = camera.position.distanceTo(e.sprite.position);
        const o = THREE.MathUtils.clamp((e.fadeIn - d) / (e.fadeIn * 0.25), 0, 1);
        e.sprite.material.opacity = o * 0.92;
        e.sprite.visible = o > 0.02;
      }
    },
    dispose() {
      for (const e of entries) {
        e.sprite.material.map?.dispose();
        e.sprite.material.dispose();
      }
      group.parent?.remove(group);
    },
  };
}

function makeTextSprite(text, inCycle) {
  const PX = 44, PAD = 18;
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  ctx.font = `600 ${PX}px ui-sans-serif, system-ui, sans-serif`;
  const w = Math.ceil(ctx.measureText(text).width) + PAD * 2;
  const h = PX + PAD * 2;
  canvas.width = w; canvas.height = h;
  ctx.font = `600 ${PX}px ui-sans-serif, system-ui, sans-serif`;
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'center';
  // dezente Pille als Hintergrund, damit das Schild vor jeder Fassade lesbar ist
  ctx.fillStyle = inCycle ? 'rgba(60, 14, 12, 0.55)' : 'rgba(8, 14, 26, 0.55)';
  roundRect(ctx, 1, 1, w - 2, h - 2, h / 2);
  ctx.fill();
  ctx.strokeStyle = inCycle ? 'rgba(255, 90, 80, 0.7)' : 'rgba(120, 180, 255, 0.35)';
  ctx.lineWidth = 2;
  roundRect(ctx, 1, 1, w - 2, h - 2, h / 2);
  ctx.stroke();
  ctx.fillStyle = inCycle ? '#ffb4ac' : '#d7e6ff';
  ctx.fillText(text, w / 2, h / 2 + 2);

  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 4;
  const mat = new THREE.SpriteMaterial({
    map: tex, transparent: true, opacity: 0, depthWrite: false, toneMapped: false,
  });
  const sprite = new THREE.Sprite(mat);
  const worldH = 4.4; // Schildhöhe in Weltmaß
  sprite.scale.set(worldH * (w / h), worldH, 1);
  sprite.userData.h = worldH;
  sprite.renderOrder = 900;
  return sprite;
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}
