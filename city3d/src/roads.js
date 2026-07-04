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
const LANE_OFF = 1.15; // Spurversatz nach rechts (Fahrtrichtung)

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
  const link = (a, b, path) => {
    if (a === b) return;
    let len = 0;
    for (let i = 1; i < path.length; i++) len += path[i].distanceTo(path[i - 1]);
    adj[a].push({ to: b, len, path });
    adj[b].push({ to: a, len, path: [...path].slice().reverse() });
  };

  const stationsByRoad = new Map(); // roadId -> [{pos, cw, node}] sortiert (für Gehsteig-Lücken)
  for (const [id, sts] of stations) {
    const r = roads.get(id);
    sts.sort((a, b) => a.pos - b.pos);
    const merged = [];
    for (const s of sts) {
      const last = merged[merged.length - 1];
      if (last && Math.abs(last.pos - s.pos) < MERGE) last.cw = Math.max(last.cw, s.cw);
      else merged.push({ pos: s.pos, cw: s.cw });
    }
    for (const s of merged) s.node = nodeAt(...pointOf(r, s.pos));
    stationsByRoad.set(id, merged);
    for (let i = 0; i < merged.length - 1; i++) {
      link(merged[i].node, merged[i + 1].node,
        [nodes[merged[i].node].clone(), nodes[merged[i + 1].node].clone()]);
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
    link(a, b, path);
  }

  // Zufahrts-Knoten je Klasse (Stichstraßen-Ende am Gebäude)
  const accessNode = {};
  for (const [fqn, ac] of Object.entries(roadNet.access)) {
    accessNode[fqn] = nodeAt(...pointOf(roads.get(ac.road), ac.pos));
  }

  return { nodes, adj, accessNode, stationsByRoad };
}

// ---- Dijkstra (Binärheap), Ziel-Abbruch --------------------------------------
function dijkstra(graph, from, to) {
  const { adj } = graph;
  const dist = new Map([[from, 0]]);
  const prev = new Map(); // node -> {node, edge}
  const heap = [[0, from]];
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
  const done = new Set();
  while (heap.length) {
    const [d, n] = pop();
    if (done.has(n)) continue;
    done.add(n);
    if (n === to) break;
    for (const e of adj[n]) {
      const nd = d + e.len;
      if (nd < (dist.get(e.to) ?? Infinity)) {
        dist.set(e.to, nd);
        prev.set(e.to, { node: n, edge: e });
        push(nd, e.to);
      }
    }
  }
  if (!done.has(to)) return null;
  // Pfad rückwärts einsammeln, Kanten-Polylinien konkatenieren
  const pts = [];
  let cur = to;
  while (cur !== from) {
    const p = prev.get(cur);
    const path = p.edge.path; // von p.node -> cur orientiert
    for (let i = path.length - 1; i >= 1; i--) pts.push(path[i].clone());
    cur = p.node;
  }
  pts.push(graph.nodes[from].clone());
  pts.reverse();
  return pts;
}

// ---- Fahrbare Route: Spurversatz (rechts) + gefaste Ecken ---------------------
const _d0 = new THREE.Vector3(), _d1 = new THREE.Vector3();

function driveablePath(pts, laneOff) {
  // 1) Spurversatz: jeden Punkt senkrecht zur gemittelten Fahrtrichtung versetzen
  const off = [];
  for (let i = 0; i < pts.length; i++) {
    _d0.set(0, 0, 0);
    if (i > 0) _d0.add(_d1.subVectors(pts[i], pts[i - 1]).setY(0).normalize());
    if (i < pts.length - 1) _d0.add(_d1.subVectors(pts[i + 1], pts[i]).setY(0).normalize());
    if (_d0.lengthSq() < 1e-6) { off.push(pts[i].clone()); continue; }
    _d0.normalize();
    off.push(pts[i].clone().add(new THREE.Vector3(_d0.z, 0, -_d0.x).multiplyScalar(laneOff)));
  }
  // 2) Ecken fasen: Innenpunkte durch zwei nahe Punkte ersetzen (weichere Kurven)
  const out = [off[0]];
  for (let i = 1; i < off.length - 1; i++) {
    const p = off[i];
    const inL = p.distanceTo(off[i - 1]), outL = p.distanceTo(off[i + 1]);
    const cut = Math.min(1.7, inL * 0.4, outL * 0.4);
    if (cut < 0.4) { out.push(p.clone()); continue; }
    out.push(
      p.clone().lerp(off[i - 1], cut / inL),
      p.clone().lerp(off[i + 1], cut / outL));
  }
  out.push(off[off.length - 1]);
  return out;
}

/**
 * Trips = Abhängigkeiten als Fahrten: Route von Klasse A (Nutzer) zu Klasse B
 * (Genutztem). Verstöße (Level steigt/gleich = Zyklus) zuerst, dann Sample der
 * restlichen Kanten bis zum Cap. Rückgabe: [{path, cum, len, violation}].
 */
export function buildTrips(graph, model, { max = 320 } = {}) {
  const level = new Map();
  for (const b of model.buildings ?? []) level.set(b.fullName, b.architectureLevel ?? 0);

  const cand = [];
  for (const dep of model.dependencies ?? []) {
    const a = graph.accessNode[dep.from], b = graph.accessNode[dep.to];
    if (a == null || b == null || a === b) continue;
    const violation = (level.get(dep.from) ?? 0) <= (level.get(dep.to) ?? 0);
    cand.push({ a, b, violation });
  }
  // Verstöße immer zeigen; den Rest deterministisch mischen und auffüllen.
  const viol = cand.filter((c) => c.violation);
  const ok = cand.filter((c) => !c.violation);
  let s = 0x51ab7e;
  const rnd = () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
  for (let i = ok.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [ok[i], ok[j]] = [ok[j], ok[i]]; }
  const chosen = [...viol.slice(0, max), ...ok.slice(0, Math.max(0, max - viol.length))];

  const trips = [];
  for (const c of chosen) {
    const raw = dijkstra(graph, c.a, c.b);
    if (!raw || raw.length < 2) continue;
    const path = driveablePath(raw, LANE_OFF);
    const cum = [0];
    for (let i = 1; i < path.length; i++) cum.push(cum[i - 1] + path[i].distanceTo(path[i - 1]));
    const len = cum[cum.length - 1];
    if (len < 4) continue;
    trips.push({ path, cum, len, violation: c.violation });
  }
  return trips;
}
