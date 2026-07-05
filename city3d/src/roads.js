import * as THREE from 'three';

/**
 * Straßengraph: verwandelt das im Adapter emittierte Straßennetz (roads/ramps/
 * access) in einen routbaren Graphen.
 *
 *  - Stationen = Kreuzungen (achsweise Segment-Schnitte je Paket), Rampen-
 *    Anschlüsse und Segment-Enden. Gleiche Punkte verschmelzen zu EINEM Knoten,
 *    dadurch hängen Ring, Quer- und Seitenstraßen automatisch zusammen.
 *  - Kanten = Straßenstücke zwischen benachbarten Stationen; Rampen als
 *    Cosinus-Polylinien (Kind-Deck -> Eltern-Korridor, genau eine Terrassenstufe).
 *  - Routing: Dijkstra mit Binärheap; Routen werden mit Spurversatz (Rechts-
 *    verkehr) und gefasten Ecken zu fahrbaren Polylinien aufbereitet.
 */

const EPS = 0.08;      // Toleranz für Kreuzungserkennung
const MERGE = 0.25;    // Stationen näher als das verschmelzen
const LANE_OFF = 1.15; // Auto-Spurversatz nach rechts (Fahrtrichtung)
const PED_OFF = 2.95;  // Fußgänger laufen auf dem Gehsteig (w/2 + Bordstein)

// Kantengewichte (Bündelung): Autos bevorzugen breite Boulevards, meiden
// Rampen (kein Abkürzen über fremde Paket-Decks); Fußgänger mögen es ruhig
// und nehmen lieber die schmalen Seitenstraßen.
const carFactor = (w, isRamp) => (1 + 0.14 * Math.max(0, 6.4 - w)) * (isRamp ? 2.2 : 1);
const pedFactor = (w, isRamp) => (1 + 0.09 * Math.max(0, w - 4.6)) * (isRamp ? 1.3 : 1);

export function buildRoadGraph(roadNet) {
  const roads = new Map(roadNet.roads.map((r) => [r.id, r]));

  // ---- Stationen sammeln ----------------------------------------------------
  const stations = new Map(); // roadId -> [{pos, cw}] (cw = Breite der querenden Straße)
  const addStation = (id, pos, cw = 0) => {
    if (!stations.has(id)) stations.set(id, []);
    stations.get(id).push({ pos, cw });
  };
  for (const r of roadNet.roads) { addStation(r.id, r.a0); addStation(r.id, r.a1); }

  // Kreuzungen nur innerhalb eines Pakets suchen (dort liegen alle auf einer
  // Ebene) — hält die Paarprüfung klein statt O(n²) über die ganze Stadt.
  const byPkg = new Map();
  for (const r of roadNet.roads) {
    if (!byPkg.has(r.pkg)) byPkg.set(r.pkg, []);
    byPkg.get(r.pkg).push(r);
  }
  for (const group of byPkg.values()) {
    const xs = group.filter((r) => r.axis === 'x');
    const zs = group.filter((r) => r.axis === 'z');
    for (const rx of xs) {
      for (const rz of zs) {
        if (rz.c > rx.a0 - EPS && rz.c < rx.a1 + EPS
          && rx.c > rz.a0 - EPS && rx.c < rz.a1 + EPS) {
          addStation(rx.id, rz.c, rz.w);
          addStation(rz.id, rx.c, rx.w);
        }
      }
    }
  }
  // Rampen-Anschlüsse (cw > 0, damit Gehsteige dort Lücken lassen)
  for (const rp of roadNet.ramps) {
    addStation(rp.aRoad, rp.aPos, rp.w + 1);
    addStation(rp.bRoad, rp.bPos, rp.w + 1.5);
  }

  // ---- Knoten & Kanten ------------------------------------------------------
  const nodes = [];        // THREE.Vector3 je Knoten
  const adj = [];          // adj[i] = [{to, len, path}]
  const nodeKey = new Map();
  const keyOf = (x, y, z) => `${Math.round(x * 5)}|${Math.round(y * 5)}|${Math.round(z * 5)}`;
  const pointOf = (r, pos) => (r.axis === 'x' ? [pos, r.y, r.c] : [r.c, r.y, pos]);
  const nodeAt = (x, y, z) => {
    const k = keyOf(x, y, z);
    let i = nodeKey.get(k);
    if (i == null) { i = nodes.length; nodes.push(new THREE.Vector3(x, y, z)); adj.push([]); nodeKey.set(k, i); }
    return i;
  };
  const link = (a, b, path, w, isRamp = false) => {
    if (a === b) return;
    let len = 0;
    for (let i = 1; i < path.length; i++) len += path[i].distanceTo(path[i - 1]);
    const carF = carFactor(w, isRamp), pedF = pedFactor(w, isRamp);
    adj[a].push({ to: b, len, carF, pedF, path });
    adj[b].push({ to: a, len, carF, pedF, path: [...path].slice().reverse() });
  };

  const stationsByRoad = new Map(); // roadId -> [{pos, cw, node}] sortiert (für Gehsteig-Lücken)
  for (const [id, sts] of stations) {
    const r = roads.get(id);
    sts.sort((a, b) => a.pos - b.pos);
    const merged = [];
    for (const s of sts) {
      const last = merged[merged.length - 1];
      if (last && Math.abs(last.pos - s.pos) < MERGE) {
        // Kreuzungs-Stationen (cw > 0) gewinnen die Position: nur so landet
        // der Anschlussknoten EXAKT auf dem Knoten der querenden Straße —
        // sonst dedupliziert er je nach Rundung nicht und die Zufahrt hängt
        // als isolierte Insel im Graphen (unerreichbares Gebäude).
        if (s.cw > last.cw) { last.pos = s.pos; last.cw = s.cw; }
      } else {
        merged.push({ pos: s.pos, cw: s.cw });
      }
    }
    for (const s of merged) s.node = nodeAt(...pointOf(r, s.pos));
    stationsByRoad.set(id, merged);
    for (let i = 0; i < merged.length - 1; i++) {
      link(merged[i].node, merged[i + 1].node,
        [nodes[merged[i].node].clone(), nodes[merged[i + 1].node].clone()], r.w);
    }
  }

  // Rampen: Cosinus-Höhenprofil als Polyline-Kante (fahrbar in beide Richtungen)
  for (const rp of roadNet.ramps) {
    const ra = roads.get(rp.aRoad), rb = roads.get(rp.bRoad);
    const a = nodeAt(...pointOf(ra, rp.aPos));
    const b = nodeAt(...pointOf(rb, rp.bPos));
    const pa = nodes[a], pb = nodes[b];
    const path = [];
    const N = 8;
    for (let i = 0; i <= N; i++) {
      const t = i / N;
      const e = (1 - Math.cos(Math.PI * t)) / 2;
      path.push(new THREE.Vector3(
        pa.x + (pb.x - pa.x) * t,
        pa.y + (pb.y - pa.y) * e,
        pa.z + (pb.z - pa.z) * t));
    }
    link(a, b, path, rp.w, true);
  }

  // Zufahrts-Knoten je Klasse: BEIDE Stichstraßen-Enden am Gebäude (Nord+Süd);
  // das Routing startet/endet am jeweils günstigeren.
  const accessNode = {};
  for (const [fqn, list] of Object.entries(roadNet.access)) {
    accessNode[fqn] = list.map((ac) => nodeAt(...pointOf(roads.get(ac.road), ac.pos)));
  }

  return { nodes, adj, accessNode, stationsByRoad };
}

// ---- Dijkstra (Binärheap), gewichtete Kanten ---------------------------------
// Multi-Source/Multi-Target: startet an ALLEN Zufahrten der Quell-Klasse und
// endet an der zuerst erreichten Zufahrt der Ziel-Klasse — so wählt die Route
// automatisch die günstigere Gebäudeseite (kein Umweg ums halbe Paket mehr).
// mode 'car' bündelt auf breite Straßen, 'ped' bevorzugt schmale ruhige Wege.
function dijkstra(graph, fromList, toList, mode = 'car') {
  const { adj } = graph;
  const targets = new Set(toList);
  const dist = new Map();
  const prev = new Map(); // node -> {node, edge}
  const heap = [];
  const pop = () => {
    const top = heap[0], last = heap.pop();
    if (heap.length) {
      heap[0] = last;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1, r = l + 1;
        let m = i;
        if (l < heap.length && heap[l][0] < heap[m][0]) m = l;
        if (r < heap.length && heap[r][0] < heap[m][0]) m = r;
        if (m === i) break;
        [heap[i], heap[m]] = [heap[m], heap[i]];
        i = m;
      }
    }
    return top;
  };
  const push = (d, n) => {
    heap.push([d, n]);
    let i = heap.length - 1;
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (heap[p][0] <= heap[i][0]) break;
      [heap[i], heap[p]] = [heap[p], heap[i]];
      i = p;
    }
  };
  for (const f of fromList) {
    if (!dist.has(f)) { dist.set(f, 0); push(0, f); }
  }
  const done = new Set();
  let end = -1;
  while (heap.length) {
    const [d, n] = pop();
    if (done.has(n)) continue;
    done.add(n);
    if (targets.has(n)) { end = n; break; }
    for (const e of adj[n]) {
      const nd = d + e.len * (mode === 'ped' ? e.pedF : e.carF);
      if (nd < (dist.get(e.to) ?? Infinity)) {
        dist.set(e.to, nd);
        prev.set(e.to, { node: n, edge: e });
        push(nd, e.to);
      }
    }
  }
  if (end < 0) return null;
  // Pfad rückwärts einsammeln, Kanten-Polylinien konkatenieren; Knoten-IDs
  // werden mitgeführt (Kreuzungslast für die Hotspot-Anzeige).
  const pts = [];
  const nodeIds = [end];
  let cur = end;
  while (prev.has(cur)) {
    const p = prev.get(cur);
    const path = p.edge.path; // von p.node -> cur orientiert
    for (let i = path.length - 1; i >= 1; i--) pts.push(path[i].clone());
    cur = p.node;
    nodeIds.push(cur);
  }
  pts.push(graph.nodes[cur].clone());
  pts.reverse();
  return { pts, nodeIds };
}

// ---- Fahrbare Route: Spurversatz (rechts) + gefaste Ecken ---------------------
const _d0 = new THREE.Vector3(), _d1 = new THREE.Vector3();

function driveablePath(pts, laneOff, maxCut = 1.7) {
  // 1) Spurversatz: jeden Punkt senkrecht zur gemittelten Fahrtrichtung versetzen
  const off = [];
  for (let i = 0; i < pts.length; i++) {
    _d0.set(0, 0, 0);
    if (i > 0) _d0.add(_d1.subVectors(pts[i], pts[i - 1]).setY(0).normalize());
    if (i < pts.length - 1) _d0.add(_d1.subVectors(pts[i + 1], pts[i]).setY(0).normalize());
    if (_d0.lengthSq() < 1e-6) { off.push(pts[i].clone()); continue; }
    _d0.normalize();
    // Rechtsverkehr: rechts von Fahrtrichtung d ist (-d.z, 0, d.x) — das
    // vorherige (d.z, 0, -d.x) war die linke Seite (Australien-Modus).
    off.push(pts[i].clone().add(new THREE.Vector3(-_d0.z, 0, _d0.x).multiplyScalar(laneOff)));
  }
  // 2) Ecken runden: Innenpunkte werden durch einen kleinen Quadratic-Bézier-
  //    Bogen ersetzt (Einfahrt, Scheitel, Ausfahrt) — echte Kurven statt Knicke.
  const out = [off[0]];
  for (let i = 1; i < off.length - 1; i++) {
    const p = off[i];
    const inL = p.distanceTo(off[i - 1]), outL = p.distanceTo(off[i + 1]);
    const cut = Math.min(maxCut, inL * 0.42, outL * 0.42);
    if (cut < 0.4) { out.push(p.clone()); continue; }
    const a = p.clone().lerp(off[i - 1], cut / inL);
    const b = p.clone().lerp(off[i + 1], cut / outL);
    // Bézier-Zwischenpunkte bei t=0.25/0.5/0.75 (Kontrollpunkt = Original-Ecke)
    for (const t of [0, 0.25, 0.5, 0.75, 1]) {
      const s = 1 - t;
      out.push(new THREE.Vector3(
        s * s * a.x + 2 * s * t * p.x + t * t * b.x,
        s * s * a.y + 2 * s * t * p.y + t * t * b.y,
        s * s * a.z + 2 * s * t * p.z + t * t * b.z));
    }
  }
  out.push(off[off.length - 1]);
  // Doppelte/fast identische Punkte entfernen — Null-Länge-Segmente ließen die
  // Fahrzeug-Orientierung in Kurven für einen Frame auf Yaw 0 springen.
  const clean = [out[0]];
  for (let i = 1; i < out.length; i++) {
    if (out[i].distanceToSquared(clean[clean.length - 1]) > 0.01) clean.push(out[i]);
  }
  return clean.length > 1 ? clean : out;
}

/**
 * Trips = Abhängigkeiten als Fahrten/Fußwege: Route von Klasse A (Nutzer) zu
 * Klasse B (Genutztem). Paketlokale Beziehungen (gleiches Paket) werden zu
 * FUSSGÄNGERN auf dem Gehsteig, paketübergreifende zu AUTOS auf der Fahrbahn.
 * Verstöße (Level steigt/gleich = Zyklus) zuerst, dann Sample bis zum Cap.
 * Rückgabe: {trips: [{path, cum, len, violation, local, from, to}],
 *            nodeLoad: Map<nodeId, Anzahl Routen>} — die Last je Kreuzung.
 */
export function buildTrips(graph, model, { maxCars = 600, maxPeds = 400 } = {}) {
  const level = new Map(), district = new Map();
  for (const b of model.buildings ?? []) {
    level.set(b.fullName, b.architectureLevel ?? 0);
    district.set(b.fullName, b.districtFullName ?? null);
  }
  const maxLevel = Math.max(1, model.maxLevel ?? 1);

  const cand = [];
  for (const dep of model.dependencies ?? []) {
    const a = graph.accessNode[dep.from], b = graph.accessNode[dep.to];
    if (!a?.length || !b?.length || dep.from === dep.to) continue;
    const lf = level.get(dep.from) ?? 0, lt = level.get(dep.to) ?? 0;
    const violation = lf <= lt;
    const local = district.get(dep.from) != null && district.get(dep.from) === district.get(dep.to);
    // Tempo-Faktor 0..1: großer globaler Level-Sprung = weite Fahrt = schnell.
    const speedT = Math.min(1, Math.abs(lf - lt) / maxLevel);
    // Natürliche Abhängigkeitsrichtung: die Fahrt verlässt den Nutzer über
    // die SÜDSEITE (access[0]) und erreicht den Genutzten über die NORDSEITE
    // (access[1]) — der Verkehr fließt "bergab" durch die Level-Reihen;
    // Verstöße müssen sichtbar gegen den Strom fahren.
    cand.push({
      a: [a[0]], b: [b[b.length - 1]],
      violation, local, speedT, from: dep.from, to: dep.to,
    });
  }
  // Verstöße immer zeigen; den Rest deterministisch mischen und auffüllen.
  let s = 0x51ab7e;
  const rnd = () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
  const pickFrom = (list, max) => {
    const viol = list.filter((c) => c.violation);
    const ok = list.filter((c) => !c.violation);
    for (let i = ok.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [ok[i], ok[j]] = [ok[j], ok[i]]; }
    return [...viol.slice(0, max), ...ok.slice(0, Math.max(0, max - viol.length))];
  };
  const chosen = [
    ...pickFrom(cand.filter((c) => !c.local), maxCars),
    ...pickFrom(cand.filter((c) => c.local), maxPeds),
  ];

  const trips = [];
  const nodeLoad = new Map();
  for (const c of chosen) {
    const raw = dijkstra(graph, c.a, c.b, c.local ? 'ped' : 'car');
    if (!raw || raw.pts.length < 2) continue;
    const path = c.local
      ? driveablePath(raw.pts, PED_OFF, 1.0)   // Gehsteig, enge Bögen
      : driveablePath(raw.pts, LANE_OFF, 2.4); // Fahrbahn rechts, weite Kurven
    const cum = [0];
    for (let i = 1; i < path.length; i++) cum.push(cum[i - 1] + path[i].distanceTo(path[i - 1]));
    const len = cum[cum.length - 1];
    if (len < 4) continue;
    trips.push({ path, cum, len, violation: c.violation, local: c.local, speedT: c.speedT, from: c.from, to: c.to });
    for (const id of raw.nodeIds) nodeLoad.set(id, (nodeLoad.get(id) ?? 0) + 1);
  }
  return { trips, nodeLoad };
}
