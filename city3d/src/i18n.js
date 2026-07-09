/**
 * Minimale i18n-Schicht für die City3D-Oberfläche (DE / EN / PT).
 *
 * Statische Texte werden über data-Attribute in index.html gebunden:
 *   data-i18n             → textContent
 *   data-i18n-html        → innerHTML (nur für Einträge mit eigenem Markup,
 *                           alle Strings stammen aus diesem Modul, nie vom Nutzer)
 *   data-i18n-title       → title-Attribut (Tooltips)
 *   data-i18n-placeholder → placeholder-Attribut
 *
 * Sprachwahl: ?lang=xx schlägt localStorage schlägt Browsersprache; Fallback en.
 * Dynamische Texte (Info-Panel, Tageszeit, FPS-Zeile) holen sich t() direkt.
 */

const DICTS = {
  de: {
    'loader.sub': 'baue software-stadt…',
    // Hilfe-Panel
    'help.title': 'Navigation',
    'help.orbit': '<b>Orbit</b> · Maus ziehen',
    'help.rotate': 'drehen',
    'help.scroll': 'Scroll',
    'help.zoom': 'zoom',
    'help.flyMode': 'Fly-Modus',
    'help.tour': '<b>🎥 Rundfahrt</b> · Pod-Klick → 📍',
    'help.ride': 'Mitfahrt',
    'help.move': 'bewegen',
    'help.upDown': 'hoch/runter ·',
    'help.boost': 'boost',
    'help.release': 'Maus freigeben',
    // Einstellungs-Panel
    'panel.title': 'Einstellungen',
    'panel.time': 'Tageszeit',
    'panel.bloom': 'Bloom',
    'panel.fog': 'Nebel',
    'panel.wet': 'Nässe / Wetter',
    'panel.traffic': 'Verkehr',
    'panel.speed': 'Tempo',
    'panel.deps': 'Abhängigkeiten',
    'panel.metric': 'Metrik-Licht',
    'panel.windows': 'Fenster',
    'panel.language': 'Sprache',
    'btn.off': 'Aus',
    'btn.selection': 'Auswahl',
    'btn.all': 'Alle',
    'btn.violations': 'Verstöße',
    'btn.methods': 'Meth.',
    'btn.tour': '🎥 Rundfahrt',
    'btn.cinematic': 'Kino-Kamera',
    'btn.timelapse': 'Zeitraffer',
    'btn.rebuild': 'Neu bauen',
    'btn.legend': 'Legende',
    'tip.ssao': 'Umgebungsverschattung — bei riesigen Architekturen abschalten für mehr FPS',
    'tip.hotspots': 'Meistbefahrene Kreuzungen hervorheben',
    'tip.tour': 'Kamera folgt einem zufälligen Fahrzeug',
    // Suche
    'search.placeholder': 'Klasse oder Paket suchen …  ( / )',
    // Legende
    'legend.buildings': 'Gebäude',
    'legend.glass': 'Glas = hohes Level · Interface',
    'legend.stone': 'Stein /',
    'legend.brick': 'Backstein = Mitte',
    'legend.concrete': 'Beton = Basis-Level (unten)',
    'legend.height': 'Höhe = Methoden · Fläche = Fan-In/Out',
    'legend.terraces': 'Terrassen = Paket-Verschachtelung',
    'legend.deps': 'Abhängigkeiten',
    'legend.ok': 'regelkonform (Level fällt)',
    'legend.violUp': 'Verstoß: gegen die Schichtung (aufwärts / Back-Edge)',
    'legend.outgoing': 'ausgehend ·',
    'legend.incoming': 'eingehend',
    'legend.traffic': 'Verkehr (klickbar)',
    'legend.cab': '🚕 Cab = paketübergreifend',
    'legend.ped': '🚶 Figur = paketlokal (Gehsteig)',
    'legend.roofCyan': 'Dachleuchte/Figur cyan = regelkonform',
    'legend.redViol': 'rot = Verstoß · Tempo = Level-Sprung',
    'legend.hotspots': 'Hotspots (Schalter): Glühen = viele Routen über diese Kreuzung',
    'legend.podClick': 'Klick auf Pod: Fahrt-Info + 📍 Verfolgen · 🎥 Rundfahrt = Zufalls-Pod',
    'legend.warnings': 'Warnsignale',
    'legend.beacon': 'Dach-Beacon = Klasse im Zyklus',
    'legend.tangle': 'rote Plattform = Paket-Tangle',
    'follow.hint': '🎥 Verfolgungsfahrt — <b>Esc</b> oder Klick beendet',
    // Tageszeiten
    'time.night': 'Nacht',
    'time.blue': 'Blaue Stunde',
    'time.golden': 'Goldene Stunde',
    'time.day': 'Tag',
    // Info-Panel (Klasse / Paket / Pod)
    'info.close': 'schließen',
    'info.package': 'Paket',
    'info.archLevel': 'Architektur-Level',
    'info.methods': 'Methoden',
    'info.fanInOut': 'Fan-In / Fan-Out',
    'info.uses': '→ verwendet',
    'info.usedBy': '← wird verwendet von',
    'info.more': '+{n} weitere',
    'info.fly': '✈ Anfliegen',
    'info.nesting': 'Verschachtelungstiefe',
    'info.directClasses': 'Klassen (direkt)',
    'info.subPackages': 'Unterpakete',
    'pod.pedestrian': '🚶 Fußgänger',
    'pod.cab': '🚕 Cab',
    'pod.local': 'paketlokal',
    'pod.cross': 'paketübergreifend',
    'pod.violTag': '⚠ Verstoß',
    'pod.relation': 'Beziehung',
    'pod.violUp': 'Verstoß: gegen die Schichtung (aufwärts)',
    'pod.ok': 'regelkonform',
    'pod.way': 'Weg',
    'pod.sidewalk': 'Gehsteig',
    'pod.road': 'Fahrbahn',
    'pod.routeLen': 'Routenlänge',
    'pod.srcDst': 'Quelle → Ziel',
    'pod.follow': '📍 Verfolgen',
    // Statuszeile / Titel
    'perf.buildings': 'Gebäude',
    'title.classes': 'Klassen',
    'title.packages': 'Pakete',
  },

  en: {
    'loader.sub': 'building software city…',
    'help.title': 'Navigation',
    'help.orbit': '<b>Orbit</b> · drag mouse',
    'help.rotate': 'rotate',
    'help.scroll': 'Scroll',
    'help.zoom': 'zoom',
    'help.flyMode': 'fly mode',
    'help.tour': '<b>🎥 Tour</b> · click pod → 📍',
    'help.ride': 'ride along',
    'help.move': 'move',
    'help.upDown': 'up/down ·',
    'help.boost': 'boost',
    'help.release': 'release mouse',
    'panel.title': 'Settings',
    'panel.time': 'Time of day',
    'panel.bloom': 'Bloom',
    'panel.fog': 'Fog',
    'panel.wet': 'Wetness / weather',
    'panel.traffic': 'Traffic',
    'panel.speed': 'Speed',
    'panel.deps': 'Dependencies',
    'panel.metric': 'Metric light',
    'panel.windows': 'windows',
    'panel.language': 'Language',
    'btn.off': 'Off',
    'btn.selection': 'Selection',
    'btn.all': 'All',
    'btn.violations': 'Violations',
    'btn.methods': 'Meth.',
    'btn.tour': '🎥 Tour',
    'btn.cinematic': 'Cinematic cam',
    'btn.timelapse': 'Time-lapse',
    'btn.rebuild': 'Rebuild',
    'btn.legend': 'Legend',
    'tip.ssao': 'Ambient occlusion — turn off for huge architectures to gain FPS',
    'tip.hotspots': 'Highlight the busiest intersections',
    'tip.tour': 'Camera follows a random vehicle',
    'search.placeholder': 'Search class or package …  ( / )',
    'legend.buildings': 'Buildings',
    'legend.glass': 'glass = high level · interface',
    'legend.stone': 'stone /',
    'legend.brick': 'brick = middle',
    'legend.concrete': 'concrete = base level (bottom)',
    'legend.height': 'height = methods · footprint = fan-in/out',
    'legend.terraces': 'terraces = package nesting',
    'legend.deps': 'Dependencies',
    'legend.ok': 'compliant (level decreases)',
    'legend.violUp': 'violation: against the layering (upward / back-edge)',
    'legend.outgoing': 'outgoing ·',
    'legend.incoming': 'incoming',
    'legend.traffic': 'Traffic (clickable)',
    'legend.cab': '🚕 cab = cross-package',
    'legend.ped': '🚶 figure = package-local (sidewalk)',
    'legend.roofCyan': 'roof light/figure cyan = compliant',
    'legend.redViol': 'red = violation · speed = level jump',
    'legend.hotspots': 'Hotspots (toggle): glow = many routes through this intersection',
    'legend.podClick': 'Click a pod: trip info + 📍 follow · 🎥 tour = random pod',
    'legend.warnings': 'Warning signs',
    'legend.beacon': 'roof beacon = class in a cycle',
    'legend.tangle': 'red platform = package tangle',
    'follow.hint': '🎥 Chase ride — <b>Esc</b> or click to end',
    'time.night': 'Night',
    'time.blue': 'Blue hour',
    'time.golden': 'Golden hour',
    'time.day': 'Day',
    'info.close': 'close',
    'info.package': 'package',
    'info.archLevel': 'architecture level',
    'info.methods': 'methods',
    'info.fanInOut': 'fan-in / fan-out',
    'info.uses': '→ uses',
    'info.usedBy': '← used by',
    'info.more': '+{n} more',
    'info.fly': '✈ Fly to',
    'info.nesting': 'nesting depth',
    'info.directClasses': 'direct classes',
    'info.subPackages': 'sub-packages',
    'pod.pedestrian': '🚶 Pedestrian',
    'pod.cab': '🚕 Cab',
    'pod.local': 'package-local',
    'pod.cross': 'cross-package',
    'pod.violTag': '⚠ violation',
    'pod.relation': 'relation',
    'pod.violUp': 'violation: against the layering (upward)',
    'pod.ok': 'compliant',
    'pod.way': 'path',
    'pod.sidewalk': 'sidewalk',
    'pod.road': 'road',
    'pod.routeLen': 'route length',
    'pod.srcDst': 'source → target',
    'pod.follow': '📍 Follow',
    'perf.buildings': 'buildings',
    'title.classes': 'classes',
    'title.packages': 'packages',
  },

  pt: {
    'loader.sub': 'a construir a cidade de software…',
    'help.title': 'Navegação',
    'help.orbit': '<b>Órbita</b> · arrastar o rato',
    'help.rotate': 'rodar',
    'help.scroll': 'Scroll',
    'help.zoom': 'zoom',
    'help.flyMode': 'modo voo',
    'help.tour': '<b>🎥 Passeio</b> · clique no pod → 📍',
    'help.ride': 'acompanhar',
    'help.move': 'mover',
    'help.upDown': 'subir/descer ·',
    'help.boost': 'turbo',
    'help.release': 'libertar o rato',
    'panel.title': 'Definições',
    'panel.time': 'Hora do dia',
    'panel.bloom': 'Bloom',
    'panel.fog': 'Nevoeiro',
    'panel.wet': 'Chuva / tempo',
    'panel.traffic': 'Tráfego',
    'panel.speed': 'Velocidade',
    'panel.deps': 'Dependências',
    'panel.metric': 'Luz métrica',
    'panel.windows': 'janelas',
    'panel.language': 'Idioma',
    'btn.off': 'Desl.',
    'btn.selection': 'Seleção',
    'btn.all': 'Todas',
    'btn.violations': 'Violações',
    'btn.methods': 'Mét.',
    'btn.tour': '🎥 Passeio',
    'btn.cinematic': 'Câmara cinema',
    'btn.timelapse': 'Time-lapse',
    'btn.rebuild': 'Reconstruir',
    'btn.legend': 'Legenda',
    'tip.ssao': 'Oclusão ambiental — desligue em arquiteturas enormes para mais FPS',
    'tip.hotspots': 'Realçar os cruzamentos mais movimentados',
    'tip.tour': 'A câmara segue um veículo aleatório',
    'search.placeholder': 'Procurar classe ou pacote …  ( / )',
    'legend.buildings': 'Edifícios',
    'legend.glass': 'vidro = nível alto · interface',
    'legend.stone': 'pedra /',
    'legend.brick': 'tijolo = meio',
    'legend.concrete': 'betão = nível base (em baixo)',
    'legend.height': 'altura = métodos · área = fan-in/out',
    'legend.terraces': 'terraços = aninhamento de pacotes',
    'legend.deps': 'Dependências',
    'legend.ok': 'conforme (nível desce)',
    'legend.violUp': 'violação: contra as camadas (para cima / back-edge)',
    'legend.outgoing': 'de saída ·',
    'legend.incoming': 'de entrada',
    'legend.traffic': 'Tráfego (clicável)',
    'legend.cab': '🚕 táxi = entre pacotes',
    'legend.ped': '🚶 figura = local ao pacote (passeio)',
    'legend.roofCyan': 'luz de tejadilho/figura ciano = conforme',
    'legend.redViol': 'vermelho = violação · velocidade = salto de nível',
    'legend.hotspots': 'Hotspots (botão): brilho = muitas rotas neste cruzamento',
    'legend.podClick': 'Clique num pod: info da viagem + 📍 seguir · 🎥 passeio = pod aleatório',
    'legend.warnings': 'Sinais de alerta',
    'legend.beacon': 'farol no telhado = classe em ciclo',
    'legend.tangle': 'plataforma vermelha = tangle de pacotes',
    'follow.hint': '🎥 Perseguição — <b>Esc</b> ou clique para terminar',
    'time.night': 'Noite',
    'time.blue': 'Hora azul',
    'time.golden': 'Hora dourada',
    'time.day': 'Dia',
    'info.close': 'fechar',
    'info.package': 'pacote',
    'info.archLevel': 'nível de arquitetura',
    'info.methods': 'métodos',
    'info.fanInOut': 'fan-in / fan-out',
    'info.uses': '→ usa',
    'info.usedBy': '← usado por',
    'info.more': '+{n} mais',
    'info.fly': '✈ Voar até',
    'info.nesting': 'profundidade de aninhamento',
    'info.directClasses': 'classes diretas',
    'info.subPackages': 'subpacotes',
    'pod.pedestrian': '🚶 Peão',
    'pod.cab': '🚕 Táxi',
    'pod.local': 'local ao pacote',
    'pod.cross': 'entre pacotes',
    'pod.violTag': '⚠ violação',
    'pod.relation': 'relação',
    'pod.violUp': 'violação: contra as camadas (para cima)',
    'pod.ok': 'conforme',
    'pod.way': 'percurso',
    'pod.sidewalk': 'passeio',
    'pod.road': 'estrada',
    'pod.routeLen': 'comprimento da rota',
    'pod.srcDst': 'origem → destino',
    'pod.follow': '📍 Seguir',
    'perf.buildings': 'edifícios',
    'title.classes': 'classes',
    'title.packages': 'pacotes',
  },
};

export const LANGS = Object.keys(DICTS);
const STORE_KEY = 'city3d.lang';

function detectLang() {
  const fromUrl = new URLSearchParams(window.location.search).get('lang');
  if (fromUrl && DICTS[fromUrl]) return fromUrl;
  try {
    const stored = localStorage.getItem(STORE_KEY);
    if (stored && DICTS[stored]) return stored;
  } catch (_) { /* z. B. blockierter Storage */ }
  const nav = (navigator.language || 'en').slice(0, 2).toLowerCase();
  return DICTS[nav] ? nav : 'en';
}

let lang = detectLang();
const listeners = [];

export function getLang() { return lang; }

export function t(key, params) {
  let s = DICTS[lang][key] ?? DICTS.en[key] ?? key;
  if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, v);
  return s;
}

export function setLang(next) {
  if (!DICTS[next] || next === lang) return;
  lang = next;
  try { localStorage.setItem(STORE_KEY, next); } catch (_) { /* ignorieren */ }
  applyTranslations();
  for (const fn of listeners) fn(next);
}

/** Nach dem Umschalten aufgerufen — für dynamische Texte außerhalb der data-Attribute. */
export function onLangChange(fn) { listeners.push(fn); }

/** Überträgt das aktive Wörterbuch auf alle data-i18n*-Elemente. */
export function applyTranslations(root = document) {
  document.documentElement.lang = lang;
  for (const el of root.querySelectorAll('[data-i18n]')) el.textContent = t(el.dataset.i18n);
  for (const el of root.querySelectorAll('[data-i18n-html]')) el.innerHTML = t(el.dataset.i18nHtml);
  for (const el of root.querySelectorAll('[data-i18n-title]')) el.title = t(el.dataset.i18nTitle);
  for (const el of root.querySelectorAll('[data-i18n-placeholder]')) el.placeholder = t(el.dataset.i18nPlaceholder);
}
