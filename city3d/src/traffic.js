import * as THREE from 'three';

/**
 * Verkehr aus dunklen Fahrzeugkörpern mit getrennten Front- und Rücklichtern.
 * Alle drei Geometriegruppen bleiben instanziert, damit auch dichter Verkehr
 * nur wenige Draw Calls verursacht.
 */
export function buildTraffic(scene, streetsX, streetsZ, bounds) {
  const halfZ = bounds.spanZ / 2 + 40;
  const halfX = bounds.spanX / 2 + 40;
  const laneOffset = 5.0;
  const speedRange = [9, 18];

  const cars = [];
  const rnd = (a, b) => a + Math.random() * (b - a);
  const makeCar = (axis, fixed, min, max, dir, speed) => {
    const typeRoll = Math.random();
    const isTaxi = typeRoll < 0.68;
    const isSuv = !isTaxi && typeRoll > 0.9;
    return {
      axis, fixed, min, max, dir, speed,
      pos: rnd(min, max),
      isTaxi,
      isSuv,
      taxiIndex: -1,
      lengthScale: isTaxi ? rnd(0.98, 1.06) : isSuv ? rnd(1.06, 1.14) : rnd(0.92, 1.08),
      widthScale: isSuv ? rnd(1.02, 1.08) : rnd(0.94, 1.04),
      heightScale: isSuv ? rnd(1.18, 1.32) : rnd(0.92, 1.08),
    };
  };

  // Avenues: Fahrt entlang Z
  for (const x of streetsX) {
    for (const dir of [1, -1]) {
      const n = 14;
      for (let i = 0; i < n; i++) {
        cars.push(makeCar(
          'z',
          x + dir * laneOffset,
          -halfZ,
          halfZ,
          dir,
          rnd(speedRange[0], speedRange[1]),
        ));
      }
    }
  }
  // Streets: Fahrt entlang X
  for (const z of streetsZ) {
    for (const dir of [1, -1]) {
      const n = 8;
      for (let i = 0; i < n; i++) {
        cars.push(makeCar(
          'x',
          z + dir * laneOffset,
          -halfX,
          halfX,
          dir,
          rnd(speedRange[0] * 0.75, speedRange[1] * 0.75),
        ));
      }
    }
  }

  const count = cars.length;
  const bodyGeo = new THREE.BoxGeometry(1.85, 0.62, 4.4);
  const bodyMat = new THREE.MeshStandardMaterial({
    roughness: 0.34,
    metalness: 0.08,
    envMapIntensity: 0.7,
  });
  const bodyMesh = new THREE.InstancedMesh(bodyGeo, bodyMat, count);
  bodyMesh.castShadow = true;
  bodyMesh.receiveShadow = true;

  const cabinGeo = new THREE.BoxGeometry(1.48, 0.48, 2.15);
  const cabinMat = new THREE.MeshStandardMaterial({
    roughness: 0.18,
    metalness: 0,
    envMapIntensity: 1.1,
  });
  const cabinMesh = new THREE.InstancedMesh(cabinGeo, cabinMat, count);
  cabinMesh.castShadow = true;

  const lightGeo = new THREE.BoxGeometry(0.34, 0.16, 0.12);
  const lightMat = new THREE.MeshBasicMaterial({
    transparent: true,
    opacity: 0.9,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    toneMapped: false,
  });
  const lightMesh = new THREE.InstancedMesh(lightGeo, lightMat, count * 4);
  lightMesh.frustumCulled = false;

  const taxiPaint = [
    new THREE.Color(0xf2b600),
    new THREE.Color(0xffc400),
    new THREE.Color(0xe9a900),
  ];
  const privatePaint = [
    new THREE.Color(0x11151b), // schwarz
    new THREE.Color(0xd5d8d8), // weiß/silber
    new THREE.Color(0x4b5055), // grau
    new THREE.Color(0x1d344f), // dunkelblau
    new THREE.Color(0x5b1720), // dunkelrot
    new THREE.Color(0x2f493c), // grün
  ];
  const cabinColor = new THREE.Color();
  let taxiCount = 0;
  for (let i = 0; i < count; i++) {
    const car = cars[i];
    const palette = car.isTaxi ? taxiPaint : privatePaint;
    const bodyColor = palette[(Math.random() * palette.length) | 0];
    bodyMesh.setColorAt(i, bodyColor);
    cabinColor.set(0x14202a)
      .offsetHSL(car.isTaxi ? 0.01 : Math.random() * 0.03, 0.04, Math.random() * 0.025);
    cabinMesh.setColorAt(i, cabinColor);
    if (car.isTaxi) car.taxiIndex = taxiCount++;
  }
  bodyMesh.instanceColor.needsUpdate = true;
  cabinMesh.instanceColor.needsUpdate = true;

  const white = new THREE.Color(0xfff2d8);
  const red = new THREE.Color(0xff2b1a);
  for (let i = 0; i < count; i++) {
    lightMesh.setColorAt(i * 4, white);
    lightMesh.setColorAt(i * 4 + 1, white);
    lightMesh.setColorAt(i * 4 + 2, red);
    lightMesh.setColorAt(i * 4 + 3, red);
  }
  lightMesh.instanceColor.needsUpdate = true;

  // Schwarze Reifen geben den Fahrzeugen auch bei Tageslicht eine klare
  // Silhouette und verhindern den Eindruck schwebender Leuchtboxen.
  const wheelGeo = new THREE.CylinderGeometry(0.34, 0.34, 0.18, 10);
  wheelGeo.rotateZ(Math.PI / 2);
  const wheelMat = new THREE.MeshStandardMaterial({
    color: 0x090a0b,
    roughness: 0.92,
    metalness: 0.04,
  });
  const wheelMesh = new THREE.InstancedMesh(wheelGeo, wheelMat, count * 4);
  wheelMesh.castShadow = true;

  // Die beleuchteten Dachzeichen machen die gelben Fahrzeuge als NYC-Taxis
  // lesbar, ohne für jedes Auto ein eigenes Objekt zu erzeugen.
  const taxiSignGeo = new THREE.BoxGeometry(0.64, 0.23, 0.28);
  const taxiSignMat = new THREE.MeshStandardMaterial({
    color: 0xfff2c2,
    emissive: 0xffc968,
    emissiveIntensity: 1.15,
    roughness: 0.38,
    metalness: 0,
  });
  const taxiSignMesh = new THREE.InstancedMesh(taxiSignGeo, taxiSignMat, taxiCount);
  taxiSignMesh.castShadow = true;

  scene.add(bodyMesh, cabinMesh, lightMesh, wheelMesh, taxiSignMesh);

  const m4 = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const pos = new THREE.Vector3();
  const scl = new THREE.Vector3(1, 1, 1);
  const up = new THREE.Vector3(0, 1, 0);
  const offset = new THREE.Vector3();
  const base = new THREE.Vector3();
  const bodyY = 0.52;

  function setHeading(car) {
    let yaw;
    if (car.axis === 'z') yaw = car.dir > 0 ? 0 : Math.PI;
    else yaw = car.dir > 0 ? Math.PI / 2 : -Math.PI / 2;
    q.setFromAxisAngle(up, yaw);
  }

  function setLightMatrix(instance, x, z, basePosition) {
    offset.set(x, 0.06, z).applyQuaternion(q);
    pos.copy(basePosition).add(offset);
    scl.set(1, 1, 1);
    m4.compose(pos, q, scl);
    lightMesh.setMatrixAt(instance, m4);
  }

  function setWheelMatrix(instance, x, z, car, basePosition) {
    offset.set(x * car.widthScale, -0.23, z * car.lengthScale).applyQuaternion(q);
    pos.copy(basePosition).add(offset);
    scl.set(1, 1, 1);
    m4.compose(pos, q, scl);
    wheelMesh.setMatrixAt(instance, m4);
  }

  function update(dt) {
    for (let i = 0; i < count; i++) {
      const c = cars[i];
      c.pos += c.dir * c.speed * dt;
      if (c.pos > c.max) c.pos = c.min;
      if (c.pos < c.min) c.pos = c.max;

      if (c.axis === 'z') base.set(c.fixed, bodyY, c.pos);
      else base.set(c.pos, bodyY, c.fixed);
      setHeading(c);

      pos.copy(base);
      scl.set(c.widthScale, c.heightScale, c.lengthScale);
      m4.compose(pos, q, scl);
      bodyMesh.setMatrixAt(i, m4);

      offset.set(0, 0.49 * c.heightScale, -0.12 * c.lengthScale).applyQuaternion(q);
      pos.copy(base).add(offset);
      scl.set(
        c.widthScale * (c.isSuv ? 1.04 : 1),
        c.heightScale * (c.isSuv ? 1.18 : 1),
        c.lengthScale * (c.isSuv ? 1.08 : 1),
      );
      m4.compose(pos, q, scl);
      cabinMesh.setMatrixAt(i, m4);

      setLightMatrix(i * 4, -0.58 * c.widthScale, 2.23 * c.lengthScale, base);
      setLightMatrix(i * 4 + 1, 0.58 * c.widthScale, 2.23 * c.lengthScale, base);
      setLightMatrix(i * 4 + 2, -0.58 * c.widthScale, -2.23 * c.lengthScale, base);
      setLightMatrix(i * 4 + 3, 0.58 * c.widthScale, -2.23 * c.lengthScale, base);

      setWheelMatrix(i * 4, -0.96, 1.43, c, base);
      setWheelMatrix(i * 4 + 1, 0.96, 1.43, c, base);
      setWheelMatrix(i * 4 + 2, -0.96, -1.43, c, base);
      setWheelMatrix(i * 4 + 3, 0.96, -1.43, c, base);

      if (c.isTaxi) {
        offset.set(0, 0.93 * c.heightScale, -0.1 * c.lengthScale).applyQuaternion(q);
        pos.copy(base).add(offset);
        scl.set(c.widthScale, 1, c.lengthScale);
        m4.compose(pos, q, scl);
        taxiSignMesh.setMatrixAt(c.taxiIndex, m4);
      }
    }
    bodyMesh.instanceMatrix.needsUpdate = true;
    cabinMesh.instanceMatrix.needsUpdate = true;
    lightMesh.instanceMatrix.needsUpdate = true;
    wheelMesh.instanceMatrix.needsUpdate = true;
    taxiSignMesh.instanceMatrix.needsUpdate = true;
  }

  return {
    mesh: bodyMesh,
    update,
    dispose() {
      bodyGeo.dispose();
      bodyMat.dispose();
      cabinGeo.dispose();
      cabinMat.dispose();
      lightGeo.dispose();
      lightMat.dispose();
      wheelGeo.dispose();
      wheelMat.dispose();
      taxiSignGeo.dispose();
      taxiSignMat.dispose();
      scene.remove(bodyMesh, cabinMesh, lightMesh, wheelMesh, taxiSignMesh);
    },
  };
}
