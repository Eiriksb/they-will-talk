/* They Will Talk - admin dashboard (vanilla JS, no build step, no external requests). */
(() => {
  'use strict';

  // ------------------------------------------------------------------------------------------------------------
  // tiny DOM helpers (all text goes through textContent - villager names and chat lines are untrusted)
  // ------------------------------------------------------------------------------------------------------------
  const $ = (s, r = document) => r.querySelector(s);
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k === 'class') el.className = v;
        else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
        else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
        else if (k === 'html') el.innerHTML = v; // only ever used with our own static markup
        else el.setAttribute(k, v === true ? '' : v);
      }
    }
    for (const kid of kids.flat(Infinity)) {
      if (kid == null || kid === false) continue;
      el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    }
    return el;
  }
  const svgNS = 'http://www.w3.org/2000/svg';
  function s(tag, attrs, ...kids) {
    const el = document.createElementNS(svgNS, tag);
    for (const [k, v] of Object.entries(attrs || {})) if (v != null) el.setAttribute(k, v);
    for (const kid of kids.flat(Infinity)) if (kid != null) el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    return el;
  }
  const ICONS = {
    home: '<path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/>',
    users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.8-3.6 3.4-5.5 6.5-5.5s5.7 1.9 6.5 5.5"/><path d="M16 4.8a3.5 3.5 0 010 6.4M18.5 14.8c1.6.9 2.6 2.6 3 5.2"/>',
    village: '<path d="M3 21V10l5-4 5 4v11"/><path d="M13 21v-7l4-3 4 3v7"/><path d="M6.5 21v-4h3v4"/><path d="M2 21h20"/>',
    player: '<circle cx="12" cy="7" r="4"/><path d="M4 21c1-4.4 4.1-6.5 8-6.5s7 2.1 8 6.5"/>',
    chat: '<path d="M4 5h16v11H9l-5 4z"/><path d="M8 9h8M8 12h5"/>',
    map: '<path d="M9 4L3 6v14l6-2 6 2 6-2V4l-6 2z"/><path d="M9 4v14M15 6v14"/>',
    cpu: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
    moon: '<path d="M20 14.5A8 8 0 019.5 4a8 8 0 1010.5 10.5z"/>',
    play: '<path d="M7 5l12 7-12 7z"/>',
    send: '<path d="M4 12l16-8-6 16-3-7z"/>',
    refresh: '<path d="M20 11a8 8 0 10-2.3 5.7M20 5v6h-6"/>',
    external: '<path d="M14 4h6v6M20 4l-9 9M18 14v6H4V6h6"/>',
    logout: '<path d="M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10"/>',
    download: '<path d="M12 4v11M7 10l5 5 5-5"/><path d="M4 19h16"/>',
    sliders: '<path d="M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1"/><circle cx="15" cy="6" r="2"/><circle cx="9" cy="12" r="2"/><circle cx="17" cy="18" r="2"/>',
  };
  const icon = (name) => { const e = h('span', { style: { display: 'inline-flex' } }); e.innerHTML = `<svg class="i" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`; return e.firstChild; };
  const VILLAGER_SVG = '<svg viewBox="0 0 16 16" shape-rendering="crispEdges"><rect width="16" height="16" fill="#2a5d3f"/><rect x="3" y="2" width="10" height="3" fill="#4a2f1b"/><rect x="3" y="4" width="10" height="8" fill="#bd8b72"/><rect x="4" y="6" width="3" height="1" fill="#3b2414"/><rect x="9" y="6" width="3" height="1" fill="#3b2414"/><rect x="5" y="7" width="1" height="1" fill="#2f8a3b"/><rect x="10" y="7" width="1" height="1" fill="#2f8a3b"/><rect x="7" y="8" width="2" height="4" fill="#a86f58"/><rect x="3" y="12" width="10" height="3" fill="#6b4a2b"/></svg>';

  // ------------------------------------------------------------------------------------------------------------
  // formatting
  // ------------------------------------------------------------------------------------------------------------
  const KIND_LABEL = { vanilla: 'Villager', mca: 'MCA villager', minecolonies: 'Colonist', wandering_trader: 'Wandering trader' };
  const fmt = new Intl.NumberFormat();
  function ago(ts) {
    if (!ts) return 'never';
    const d = (Date.now() - ts) / 1000;
    if (d < 45) return 'just now';
    if (d < 3600) return Math.round(d / 60) + ' min ago';
    if (d < 86400) return Math.round(d / 3600) + ' h ago';
    if (d < 86400 * 30) return Math.round(d / 86400) + ' d ago';
    return new Date(ts).toLocaleDateString();
  }
  const time = (ts) => new Date(ts).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  const initials = (n) => (n || '?').split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();
  const cap = (s) => s ? s.charAt(0).toUpperCase() + s.slice(1) : '';
  /** Initials in the villager's colour, replaced by their real face (drawn from their skin) once it loads. */
  function avatar(name, kind, alive = true, big = false, uuid = null) {
    const el = h('div', { class: `avatar ${kind || ''} ${alive ? '' : 'dead'} ${big ? 'lg' : ''}`, 'aria-hidden': 'true' }, initials(name));
    if (uuid) {
      const img = new Image();
      img.alt = '';
      img.onload = () => { el.replaceChildren(img); el.classList.add('face'); };
      img.src = `/api/villager/${encodeURIComponent(uuid)}/face`;
    }
    return el;
  }
  const kindTag = (kind) => h('span', { class: `kind ${kind}` }, KIND_LABEL[kind] || kind);
  function affinityBar(v) {
    v = Math.max(-100, Math.min(100, v || 0));
    return h('div', { class: 'row', title: `Affinity ${v}` },
      h('div', { class: 'aff' },
        h('div', { class: 'n' }, v < 0 ? h('i', { style: { width: (-v) + '%' } }) : null),
        h('div', { class: 'p' }, v > 0 ? h('i', { style: { width: v + '%' } }) : null)),
      h('span', { class: 'num secondary', style: { minWidth: '34px', textAlign: 'right' } }, (v > 0 ? '+' : '') + v));
  }
  /** Same as SpeechRenderer.seedOf: low 31 bits of the UUID's most significant half, so previews match the game. */
  const voiceSeed = (uuid) => parseInt(uuid.replace(/-/g, '').slice(8, 16), 16) & 0x7fffffff;
  function feeling(v) {
    if (v <= -50) return 'Hates them';
    if (v <= -15) return 'Dislikes them';
    if (v < 15) return 'Neutral';
    if (v < 50) return 'Likes them';
    return 'Close friends';
  }
  function toast(msg) {
    const t = h('div', { class: 'toast', role: 'status' }, msg);
    document.body.append(t);
    setTimeout(() => t.remove(), 2600);
  }

  // ------------------------------------------------------------------------------------------------------------
  // API
  // ------------------------------------------------------------------------------------------------------------
  class AuthError extends Error {}
  async function api(path, body) {
    const opts = { headers: { 'X-TWT': '1' }, credentials: 'same-origin' };
    if (body !== undefined) {
      opts.method = 'POST';
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    const r = await fetch('/api' + path, opts);
    if (r.status === 401) throw new AuthError();
    const ct = r.headers.get('content-type') || '';
    const data = ct.includes('json') ? await r.json() : await r.blob();
    if (!r.ok || (data && data.error)) throw new Error((data && data.error) || ('HTTP ' + r.status));
    return data;
  }

  // ------------------------------------------------------------------------------------------------------------
  // tooltip
  // ------------------------------------------------------------------------------------------------------------
  const tip = $('#tooltip');
  function showTip(e, build) {
    tip.replaceChildren(...[].concat(build()));
    tip.classList.add('on');
    const x = Math.min(window.innerWidth - tip.offsetWidth - 8, e.clientX + 14);
    const y = Math.min(window.innerHeight - tip.offsetHeight - 8, e.clientY + 14);
    tip.style.left = x + 'px';
    tip.style.top = y + 'px';
  }
  const hideTip = () => tip.classList.remove('on');

  // ------------------------------------------------------------------------------------------------------------
  // shell + router
  // ------------------------------------------------------------------------------------------------------------
  const NAV = [
    ['#/', 'home', 'Overview'],
    ['#/villagers', 'users', 'Villagers'],
    ['#/villages', 'village', 'Villages'],
    ['#/players', 'player', 'Players'],
    ['#/conversations', 'chat', 'Conversations'],
    ['#/map', 'map', 'Map'],
    ['#/runtime', 'cpu', 'AI & voices'],
    ['#/models', 'download', 'Models'],
    ['#/settings', 'sliders', 'Settings'],
  ];
  let main, titleEl, crumbsEl, statusEl, liveEl;
  let cleanup = [];
  function onLeave(fn) { cleanup.push(fn); }

  function shell() {
    const brand = h('div', { class: 'brand' });
    const logo = h('span');
    logo.innerHTML = VILLAGER_SVG;
    brand.append(logo.firstChild, h('div', null, h('b', null, 'They Will Talk'), h('small', null, 'Villager admin')));
    const nav = h('nav', { class: 'nav', 'aria-label': 'Sections' },
      NAV.map(([href, ic, label]) => h('a', { href, 'data-href': href }, icon(ic), label)));
    statusEl = h('div', { class: 'stack', style: { gap: '6px' } });
    const sidebar = h('aside', { class: 'sidebar' }, brand, nav,
      h('div', { class: 'foot' }, statusEl,
        h('a', { href: '#', class: 'muted', style: { fontSize: '12px' }, onclick: (e) => { e.preventDefault(); logout(); } }, 'Log out')));

    const searchInput = h('input', { type: 'search', placeholder: 'Search villagers, jobs, villages...', 'aria-label': 'Search villagers' });
    searchInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') location.hash = '#/villagers?q=' + encodeURIComponent(searchInput.value.trim());
    });
    titleEl = h('h1');
    crumbsEl = h('div', { class: 'crumbs' });
    liveEl = h('span', { class: 'live', title: 'Live updates' }, h('span', { class: 'beacon' }), 'live');
    const themeBtn = h('button', { class: 'icon-btn', title: 'Toggle theme', 'aria-label': 'Toggle theme', onclick: toggleTheme }, icon('moon'));
    main = h('div', { id: 'view' });
    const mainCol = h('main', { class: 'main' },
      h('div', { class: 'topbar' }, h('div', { class: 'title' }, crumbsEl, titleEl), liveEl,
        h('label', { class: 'search' }, icon('search'), searchInput), themeBtn),
      main);
    $('#app').replaceChildren(h('div', { class: 'shell' }, sidebar, mainCol));
  }

  function toggleTheme() {
    const cur = document.documentElement.dataset.theme
      || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    const next = cur === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('twt-theme', next); } catch (e) { /* private mode */ }
  }

  function setTitle(title, crumbs) {
    titleEl.textContent = title;
    crumbsEl.replaceChildren(...(crumbs || []).flatMap((c, i) => [i ? ' / ' : '', c]));
    document.title = title + ' - They Will Talk';
  }

  function parseHash() {
    const raw = location.hash.replace(/^#/, '') || '/';
    const [path, qs] = raw.split('?');
    const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
    return { parts, q: new URLSearchParams(qs || '') };
  }

  async function route() {
    cleanup.forEach(fn => { try { fn(); } catch (e) { /* ignore */ } });
    cleanup = [];
    hideTip();
    const { parts, q } = parseHash();
    const section = parts[0] || '';
    document.querySelectorAll('.nav a').forEach(a => {
      const href = a.dataset.href.replace('#/', '');
      a.classList.toggle('active', href === section || (href === 'villagers' && section === 'villager')
        || (href === 'villages' && section === 'village') || (href === 'players' && section === 'player')
        || (href === 'conversations' && section === 'conversation'));
    });
    main.replaceChildren(h('div', { class: 'empty' }, 'Loading...'));
    try {
      switch (section) {
        case '': await viewOverview(); break;
        case 'villagers': await viewVillagers(q); break;
        case 'villager': await viewVillager(parts[1], parts[2] || 'profile'); break;
        case 'villages': await viewVillages(); break;
        case 'village': await viewVillage(parts[1]); break;
        case 'players': await viewPlayers(); break;
        case 'player': await viewPlayer(parts[1]); break;
        case 'conversations': await viewConversations(q); break;
        case 'conversation': await viewConversation(parts[1]); break;
        case 'map': await viewMap(q); break;
        case 'runtime': await viewRuntime(); break;
        case 'models': await viewModels(); break;
        case 'settings': await viewSettings(); break;
        default: main.replaceChildren(h('div', { class: 'empty' }, 'Not found'));
      }
    } catch (e) {
      if (e instanceof AuthError) return showLogin();
      main.replaceChildren(h('div', { class: 'banner bad' }, 'Something went wrong: ' + e.message));
    }
    window.scrollTo(0, 0);
  }

  // ------------------------------------------------------------------------------------------------------------
  // login
  // ------------------------------------------------------------------------------------------------------------
  function showLogin() {
    stopStream();
    const input = h('input', { class: 'input', type: 'password', placeholder: 'Admin token', autocomplete: 'current-password', 'aria-label': 'Admin token' });
    const err = h('div', { class: 'muted', style: { minHeight: '20px', marginTop: '8px', color: 'var(--critical)' } });
    const expired = new URLSearchParams(location.search).get('login') === 'expired';
    async function submit(e) {
      e.preventDefault();
      err.textContent = '';
      try {
        const r = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: input.value }) });
        if (!r.ok) throw new Error('That token is not right.');
        history.replaceState(null, '', '/' + location.hash);
        start();
      } catch (ex) { err.textContent = ex.message; }
    }
    const logo = h('div', { style: { width: '56px', height: '56px' } });
    logo.innerHTML = VILLAGER_SVG.replace('<svg', '<svg width="56" height="56" style="border-radius:12px"');
    $('#app').replaceChildren(h('div', { class: 'login' }, h('form', { class: 'card', onsubmit: submit },
      logo,
      h('h1', null, 'They Will Talk'),
      h('p', null, expired ? 'That login link has expired. Run /twt dashboard again, or paste the admin token.'
        : 'Paste the admin token from config/theywilltalk-admin-token.txt, or run /twt dashboard in game for a one-click link.'),
      input, err,
      h('button', { class: 'btn primary', type: 'submit', style: { width: '100%', marginTop: '6px' } }, 'Log in'))));
    input.focus();
  }
  async function logout() {
    try { await api('/logout', {}); } catch (e) { /* already logged out */ }
    showLogin();
  }

  // ------------------------------------------------------------------------------------------------------------
  // live stream
  // ------------------------------------------------------------------------------------------------------------
  let es = null;
  const feedListeners = new Set();
  function startStream() {
    if (es) return;
    es = new EventSource('/api/stream');
    es.onopen = () => liveEl && liveEl.classList.add('on');
    es.onerror = () => liveEl && liveEl.classList.remove('on');
    es.onmessage = (m) => {
      let ev;
      try { ev = JSON.parse(m.data); } catch (e) { return; }
      feedListeners.forEach(fn => fn(ev));
    };
  }
  function stopStream() { if (es) { es.close(); es = null; } }

  async function refreshStatus() {
    try {
      const o = await api('/overview');
      const r = o.runtime;
      statusEl.replaceChildren(
        r.setupNeeded
          ? h('a', { class: 'pill warn', href: '#/models' }, h('span', { class: 'dot' }), 'AI not installed')
          : h('span', { class: 'pill ' + (r.llmReady ? 'ok' : 'warn') }, h('span', { class: 'dot' }), r.llmReady ? 'Brain ready' : 'Brain starting'),
        r.setupNeeded ? null : r.expressiveVoices
          ? h('span', { class: 'pill ok' }, h('span', { class: 'dot' }), 'Expressive voices')
          : h('span', { class: 'pill ' + (r.voiceReady ? 'ok' : 'warn') }, h('span', { class: 'dot' }), r.voiceReady ? `${r.voices} voices` : 'Voices starting'));
    } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------------------------------------------------------
  // feed rendering (overview + villager pages)
  // ------------------------------------------------------------------------------------------------------------
  const FEED_ICON = { heard: '👂', reply: '💬', relationship: '💞', ambient: '🗨', event: '⚔', death: '🕯', errand: '📜', gift: '🎁', trade: '💰' };
  function feedItem(ev) {
    const link = (uuid, name) => uuid ? h('a', { href: '#/villager/' + uuid }, name) : name;
    let line;
    switch (ev.type) {
      case 'heard':
        line = [h('b', null, ev.player), ' → ', link(ev.villager, ev.villagerName), ': “', ev.text, '”',
          ev.original && ev.original !== ev.text ? h('div', { class: 'muted' }, `(${ev.lang}) ${ev.original}`) : null];
        break;
      case 'reply':
        line = [link(ev.villager, ev.villagerName), ' ', h('span', { class: 'emotion' }, ev.emotion), ': “', ev.text, '”',
          h('div', { class: 'muted' }, `voice after ${fmt.format(ev.latencyMs || 0)} ms · ${ev.tokensPerSecond} tok/s`)];
        break;
      case 'relationship':
        line = [link(ev.villager, ev.villagerName), ` feels ${ev.delta > 0 ? 'better' : ev.delta < 0 ? 'worse' : 'the same'} about `, h('b', null, ev.player),
          ` (${ev.delta > 0 ? '+' : ''}${ev.delta}, ${ev.mood})`, ev.memory ? h('div', { class: 'muted' }, 'Remembers: ' + ev.memory) : null];
        break;
      case 'ambient':
        line = [link(ev.villager, ev.villagerName), ' and ', link(ev.other, ev.otherName), ' chatted:',
          h('div', { class: 'muted', style: { whiteSpace: 'pre-line' } }, ev.text)];
        break;
      case 'death':
        line = [ev.text];
        break;
      default:
        line = [link(ev.villager, ev.villagerName), ': ', ev.memory || ev.text || ev.type];
    }
    return h('div', { class: 'item' }, h('div', { class: 'ico', 'aria-hidden': 'true' }, FEED_ICON[ev.type] || '•'),
      h('div', { class: 'txt' }, h('div', { class: 'line' }, line), h('div', { class: 'time' }, ago(ev.ts))));
  }

  // ------------------------------------------------------------------------------------------------------------
  // charts
  // ------------------------------------------------------------------------------------------------------------
  /** Columns: replies per hour for the last 24 h (single series -> title names it, no legend). */
  function hourlyChart(rows) {
    const now = Math.floor(Date.now() / 3600000);
    const byHour = new Map(rows.map(r => [r.hour, r.n]));
    const data = [];
    for (let i = 23; i >= 0; i--) data.push({ hour: now - i, n: byHour.get(now - i) || 0 });
    const W = 640, H = 170, padL = 30, padB = 22, padT = 8;
    const max = Math.max(4, ...data.map(d => d.n));
    const niceMax = Math.ceil(max / 4) * 4;
    const band = (W - padL) / data.length;
    const bw = Math.min(24, band - 4);
    const y = (v) => padT + (H - padT - padB) * (1 - v / niceMax);
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Villager replies per hour, last 24 hours' });
    for (let t = 0; t <= 4; t++) {
      const v = niceMax * t / 4;
      svg.append(s('line', { class: t ? 'gridline' : 'baseline', x1: padL, x2: W, y1: y(v), y2: y(v) }),
        s('text', { class: 'tick num', x: padL - 6, y: y(v) + 4, 'text-anchor': 'end' }, fmt.format(v)));
    }
    data.forEach((d, i) => {
      const x = padL + i * band + (band - bw) / 2;
      const top = y(d.n), base = y(0);
      const hgt = Math.max(0, base - top);
      if (hgt > 0) {
        const r = Math.min(4, hgt);
        svg.append(s('path', { class: 'bar', d: `M${x},${base} V${top + r} Q${x},${top} ${x + r},${top} H${x + bw - r} Q${x + bw},${top} ${x + bw},${top + r} V${base} Z` }));
      }
      const hit = s('rect', { x: padL + i * band, y: padT, width: band, height: H - padT - padB, fill: 'transparent', tabindex: 0 });
      const label = new Date(d.hour * 3600000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      const show = (e) => showTip(e, () => [h('b', { class: 'num' }, fmt.format(d.n)), h('div', { class: 'muted' }, `replies · ${label}`)]);
      hit.addEventListener('pointermove', show);
      hit.addEventListener('focus', (e) => { const b = hit.getBoundingClientRect(); show({ clientX: b.x + b.width / 2, clientY: b.y }); });
      hit.addEventListener('pointerleave', hideTip);
      hit.addEventListener('blur', hideTip);
      svg.append(hit);
      if (i % 6 === 0 || i === data.length - 1) {
        svg.append(s('text', { class: 'tick', x: padL + i * band + band / 2, y: H - 6, 'text-anchor': 'middle' }, label));
      }
    });
    return h('div', { class: 'chart' }, svg);
  }

  /** Horizontal bars with labels (villagers per kind) - labels carry identity, swatch mirrors the map color. */
  function kindBars(rows) {
    const max = Math.max(1, ...rows.map(r => r.n));
    return h('div', { class: 'stack', style: { gap: '10px' } }, rows.map(r =>
      h('div', null,
        h('div', { class: 'row', style: { justifyContent: 'space-between', marginBottom: '4px' } }, kindTag(r.kind), h('span', { class: 'num secondary' }, fmt.format(r.n))),
        h('div', { class: 'meter' }, h('i', { style: { width: (100 * r.n / max) + '%', background: `var(--kind-${r.kind === 'minecolonies' ? 'colony' : r.kind === 'wandering_trader' ? 'trader' : r.kind})` } })))));
  }

  // ------------------------------------------------------------------------------------------------------------
  // Overview
  // ------------------------------------------------------------------------------------------------------------
  async function viewOverview() {
    setTitle('Overview', ['They Will Talk']);
    const o = await api('/overview');
    const c = o.counts || {};
    const r = o.runtime;
    const gpu = (r.gpus || [])[0];
    const banners = [];
    if (r.setupNeeded) {
      banners.push(h('div', { class: 'banner row' }, h('span', { class: 'spacer' }, 'The villagers\' AI isn\'t installed yet. Download it in one click on the Models page.'),
        h('a', { class: 'btn primary', href: '#/models' }, icon('download'), 'Set up')));
    } else {
      (r.problems || []).forEach(p => banners.push(h('div', { class: 'banner bad' }, p)));
      if (!r.llmReady) banners.push(h('div', { class: 'banner' }, 'The villager brain (LLM) is still starting. Villagers will answer once it is ready.'));
    }

    const stat = (label, value, sub) => h('div', { class: 'card stat' }, h('div', { class: 'label' }, label), h('div', { class: 'value num' }, value), h('div', { class: 'sub' }, sub));
    const stats = h('div', { class: 'grid g-4' },
      stat('Villagers', fmt.format(c.villagers || 0), `${fmt.format(c.villages || 0)} villages · ${fmt.format(c.deceased || 0)} deceased`),
      stat('Replies today', fmt.format(c.repliesToday || 0), `${fmt.format(c.conversations || 0)} conversations · ${fmt.format(c.errandsActive || 0)} errands going, ${fmt.format(c.errandsDone || 0)} done`),
      stat('Time to voice', c.avgLatencyMs ? fmt.format(c.avgLatencyMs) + ' ms' : '–', `from hearing to speaking · ${r.tokensPerSecond || '–'} tok/s`),
      stat('GPU memory', gpu ? `${(gpu.memoryUsedMb / 1024).toFixed(1)} GB` : '–', gpu ? `of ${(gpu.memoryTotalMb / 1024).toFixed(1)} GB · ${gpu.name}` : 'no NVIDIA GPU found'));

    const feed = h('div', { class: 'feed scroll' });
    const items = (o.feed || []).slice().reverse();
    if (!items.length) feed.append(h('div', { class: 'empty' }, 'Nothing yet. Talk to a villager in game!'));
    items.slice(0, 60).forEach(ev => feed.append(feedItem(ev)));
    const onEv = (ev) => {
      if (feed.firstChild && feed.firstChild.classList.contains('empty')) feed.replaceChildren();
      feed.prepend(feedItem(ev));
    };
    feedListeners.add(onEv);
    onLeave(() => feedListeners.delete(onEv));

    const talkers = h('div', { class: 'stack', style: { gap: '4px' } }, (o.topTalkers || []).length ? o.topTalkers.map(t =>
      h('a', { class: 'who', href: '#/villager/' + t.uuid, style: { padding: '6px 0', color: 'inherit' } }, avatar(t.name, t.kind, true, false, t.uuid),
        h('div', { style: { flex: 1, minWidth: 0 } }, h('b', null, t.name), h('div', { class: 'sub' }, [t.job, t.village].filter(Boolean).join(' · '))),
        h('span', { class: 'num secondary' }, fmt.format(t.talks)))) : h('div', { class: 'empty' }, 'No conversations yet'));

    const integrations = o.integrations || {};
    const integ = h('div', { class: 'row wrap' }, [
      ['voicechat', 'Simple Voice Chat'], ['sipher', 'Sipher'], ['mca', 'MCA Reborn'], ['minecolonies', 'MineColonies'], ['bluemap', 'BlueMap'],
    ].map(([k, label]) => h('span', { class: 'pill ' + (integrations[k] ? 'ok' : '') }, h('span', { class: 'dot' }), label + (integrations[k] ? '' : ' (not installed)'))));

    main.replaceChildren(h('div', { class: 'stack' }, banners, stats,
      h('div', { class: 'grid g-main' },
        h('div', { class: 'stack' },
          h('section', { class: 'card' }, h('header', null, h('h2', null, 'Villager replies per hour'), h('span', { class: 'muted' }, 'last 24 hours')),
            h('div', { class: 'body' }, hourlyChart(o.activityByHour || []))),
          h('section', { class: 'card' }, h('header', null, h('h2', null, 'Live activity')), h('div', { class: 'body' }, feed))),
        h('div', { class: 'stack' },
          h('section', { class: 'card' }, h('header', null, h('h2', null, 'Chattiest villagers')), h('div', { class: 'body' }, talkers)),
          h('section', { class: 'card' }, h('header', null, h('h2', null, 'Who lives here')),
            h('div', { class: 'body' }, (o.byKind || []).length ? kindBars(o.byKind) : h('div', { class: 'empty' }, 'No villagers discovered yet'))),
          h('section', { class: 'card' }, h('header', null, h('h2', null, 'Integrations')), h('div', { class: 'body' }, integ))))));
    const t = setInterval(refreshStatus, 10000);
    onLeave(() => clearInterval(t));
  }

  // ------------------------------------------------------------------------------------------------------------
  // Villagers list
  // ------------------------------------------------------------------------------------------------------------
  let villageCache = null;
  async function villages() { if (!villageCache) villageCache = await api('/villages'); return villageCache; }

  async function viewVillagers(q) {
    setTitle('Villagers', ['They Will Talk']);
    const state = { q: q.get('q') || '', village: q.get('village') || '', kind: q.get('kind') || '', sort: q.get('sort') || 'talks', alive: q.get('alive') || '1' };
    const vills = await villages();
    const search = h('input', { class: 'input', type: 'search', value: state.q, placeholder: 'Name, job, personality or village', style: { minWidth: '260px' }, 'aria-label': 'Filter villagers' });
    const villageSel = h('select', { class: 'input', 'aria-label': 'Village' }, h('option', { value: '' }, 'All villages'),
      vills.map(v => h('option', { value: v.key, selected: v.key === state.village }, `${v.name} (${v.known})`)));
    const kindSel = h('select', { class: 'input', 'aria-label': 'Kind' }, h('option', { value: '' }, 'All kinds'),
      Object.entries(KIND_LABEL).map(([k, l]) => h('option', { value: k, selected: k === state.kind }, l)));
    const aliveSeg = h('div', { class: 'seg', role: 'group', 'aria-label': 'Alive filter' });
    [['1', 'Alive'], ['0', 'Deceased'], ['all', 'All']].forEach(([v, l]) => aliveSeg.append(h('button', { class: state.alive === v ? 'on' : '', onclick: () => { state.alive = v; update(); } }, l)));
    const count = h('span', { class: 'muted' });
    const tbody = h('tbody');
    const cols = [['name', 'Villager'], [null, 'Kind'], ['job', 'Job'], [null, 'Personality'], ['village', 'Village'], ['talks', 'Talks'], ['recent', 'Last spoke']];
    const thead = h('thead', null, h('tr', null, cols.map(([key, label]) => h('th', {
      class: key ? 'sortable' : '', 'aria-sort': key && state.sort === key ? 'descending' : null,
      onclick: key ? () => { state.sort = key; update(); } : null, style: key === 'talks' ? { textAlign: 'right' } : null,
    }, label + (key && state.sort === key ? ' ↓' : '')))));
    const table = h('table', null, thead, tbody);

    let timer;
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { state.q = search.value.trim(); update(); }, 200); });
    villageSel.addEventListener('change', () => { state.village = villageSel.value; update(); });
    kindSel.addEventListener('change', () => { state.kind = kindSel.value; update(); });

    async function update() {
      const p = new URLSearchParams(Object.entries(state).filter(([, v]) => v));
      history.replaceState(null, '', '#/villagers?' + p);
      aliveSeg.querySelectorAll('button').forEach((b, i) => b.classList.toggle('on', ['1', '0', 'all'][i] === state.alive));
      thead.querySelectorAll('th').forEach((th, i) => {
        const key = cols[i][0];
        th.textContent = cols[i][1] + (key && state.sort === key ? ' ↓' : '');
      });
      const res = await api('/villagers?' + p + '&limit=300');
      count.textContent = `${fmt.format(res.total)} villager${res.total === 1 ? '' : 's'}`;
      tbody.replaceChildren(...(res.items.length ? res.items.map(v => h('tr', { onclick: () => location.hash = '#/villager/' + v.uuid, tabindex: 0, onkeydown: (e) => e.key === 'Enter' && (location.hash = '#/villager/' + v.uuid) },
        h('td', null, h('div', { class: 'who' }, avatar(v.name, v.kind, v.alive === 1, false, v.uuid),
          h('div', { style: { minWidth: 0 } }, h('b', null, v.name), h('div', { class: 'sub' }, [v.age_group !== 'adult' ? v.age_group : null, v.gender === 'f' ? 'female' : v.gender === 'm' ? 'male' : null, v.alive ? null : 'deceased'].filter(Boolean).join(' · '))))),
        h('td', null, kindTag(v.kind)),
        h('td', null, cap(v.job || '–')),
        h('td', null, h('span', { class: 'tag' }, (v.persona || '').replace('_', ' '))),
        h('td', null, v.village || h('span', { class: 'muted' }, 'none')),
        h('td', { class: 'num' }, fmt.format(v.talks || 0)),
        h('td', { class: 'muted' }, ago(v.last_spoke)))) : [h('tr', { class: 'static' }, h('td', { colspan: 7 }, h('div', { class: 'empty' }, 'No villagers match. Villagers appear here once a player has been near them.')))]));
    }
    main.replaceChildren(h('div', { class: 'filters' }, search, villageSel, kindSel, aliveSeg, h('span', { class: 'spacer' }), count),
      h('section', { class: 'card' }, h('div', { class: 'table-wrap' }, table)));
    await update();
    search.focus();
  }

  // ------------------------------------------------------------------------------------------------------------
  // Villager detail
  // ------------------------------------------------------------------------------------------------------------
  async function viewVillager(uuid, tab) {
    const v = await api('/villager/' + uuid);
    setTitle(v.name, [h('a', { href: '#/villagers' }, 'Villagers'), v.village ? h('a', { href: '#/village/' + encodeURIComponent(v.village_key) }, v.village) : null].filter(Boolean));
    const extra = v.extra_json || {};
    const hero = h('section', { class: 'card hero' }, avatar(v.name, v.kind, v.alive === 1, true, v.uuid),
      h('div', { class: 'info' },
        h('div', { class: 'row wrap' }, h('h1', null, v.name), kindTag(v.kind), v.alive ? null : h('span', { class: 'tag dead' }, 'deceased'), v.loaded ? h('span', { class: 'pill ok' }, h('span', { class: 'dot' }), 'loaded in world') : null),
        h('div', { class: 'meta' },
          h('span', null, cap(v.job || 'no job') + (extra['career level'] ? ` (${extra['career level']})` : '')),
          h('span', null, 'Personality: ', h('b', null, (v.persona || '').replace('_', ' '))),
          v.mood ? h('span', null, 'Mood: ', h('b', null, v.mood)) : null,
          v.age_group && v.age_group !== 'adult' ? h('span', null, cap(v.age_group)) : null,
          h('span', null, `${fmt.format(v.talks || 0)} conversations`),
          v.dimension ? h('span', { class: 'num' }, `${v.dimension.replace('minecraft:', '')} ${Math.round(v.x)}, ${Math.round(v.y)}, ${Math.round(v.z)}`) : null),
        h('div', { class: 'quote' }, `${v.name.split(' ')[0]} ${v.quirk}. They ${v.backstory}.`)),
      h('div', { class: 'stack', style: { gap: '8px' } },
        h('a', { class: 'btn', href: '#/map?focus=' + uuid }, icon('map'), 'Show on map'),
        h('button', { class: 'btn', onclick: () => previewVoice(v.voice, v.pitch, v.speed, `Hello there. I'm ${v.name.split(' ')[0]}.`, 'happy',
          v.expressiveVoices ? (v.voice_design || v.generatedVoiceDesign) : '', voiceSeed(v.uuid)) }, icon('play'), 'Hear voice')));

    const tabs = [['profile', 'Profile'], ['conversations', 'Conversations'], ['relationships', 'Relationships', (v.relationships || []).length],
      ['family', 'Family tree', (v.familyLinks || []).length], ['memories', 'Memories', (v.memories || []).length],
      ['errands', 'Errands', (v.errands || []).length]];
    const tabBar = h('div', { class: 'tabs', role: 'tablist' }, tabs.map(([k, l, n]) => h('button', { role: 'tab', 'aria-selected': k === tab, class: k === tab ? 'on' : '', onclick: () => location.hash = `#/villager/${uuid}/${k}` }, l, n != null ? h('span', { class: 'count' }, n) : null)));
    const body = h('div');
    main.replaceChildren(h('div', { class: 'stack', style: { gap: 0 } }, hero, tabBar, body));
    switch (tab) {
      case 'conversations': return villagerConversations(uuid, body);
      case 'relationships': return villagerRelationships(v, body);
      case 'family': return villagerFamily(uuid, body);
      case 'memories': return villagerMemories(v, body);
      case 'errands': return body.replaceChildren(h('section', { class: 'card', style: { marginTop: '16px' } },
        h('header', null, h('h2', null, 'Errands'), h('span', { class: 'muted' }, 'favours ' + v.name.split(' ')[0] + ' asked for, and letters to them')),
        errandList(v.errands || [], true)));
      default: return villagerProfile(v, body);
    }
  }

  async function villagerProfile(v, body) {
    const [voices, personas] = await Promise.all([api('/voices').catch(() => []), api('/personas')]);
    const name = h('input', { class: 'input', value: v.name });
    const persona = h('select', { class: 'input' }, personas.map(p => h('option', { value: p.key, selected: p.key === v.persona }, `${p.key.replace('_', ' ')} – ${p.description}`)));
    const quirk = h('input', { class: 'input', value: v.quirk || '' });
    const backstory = h('textarea', { class: 'input', rows: 2 }, v.backstory || '');
    const prompt = h('textarea', { class: 'input', rows: 3, placeholder: 'Anything the villager should always know or do. e.g. "Secretly the mayor. Hates the color purple."' }, v.custom_prompt || '');
    const byEngine = {};
    voices.forEach(x => (byEngine[x.engine] = byEngine[x.engine] || []).push(x));
    const voice = h('select', { class: 'input' }, Object.entries(byEngine).map(([eng, list]) => h('optgroup', { label: eng },
      list.map(x => h('option', { value: x.id, selected: x.id === v.voice }, `${x.name} (${x.gender === 'f' ? 'female' : 'male'}${x.traits ? ', ' + x.traits : ''})`)))));
    if (!voices.length) voice.append(h('option', { value: v.voice, selected: true }, v.voice || 'voice server offline'));
    const slider = (val, min, max) => h('input', { type: 'range', min, max, step: 0.01, value: val });
    const pitch = slider(v.pitch || 1, 0.7, 1.5), speed = slider(v.speed || 1, 0.6, 1.6);
    const design = h('textarea', { class: 'input', rows: 3, placeholder: v.generatedVoiceDesign || '' }, v.voice_design || '');
    const designEmotion = h('select', { class: 'input', style: { width: 'auto' }, 'aria-label': 'Emotion to preview' },
      ['neutral', 'happy', 'angry', 'sad', 'scared', 'laugh', 'excited', 'surprised', 'sleepy'].map(e => h('option', { value: e }, e)));
    const seed = voiceSeed(v.uuid);
    const pitchOut = h('span', { class: 'num secondary' }), speedOut = h('span', { class: 'num secondary' });
    const sync = () => { pitchOut.textContent = (+pitch.value).toFixed(2) + '×'; speedOut.textContent = (+speed.value).toFixed(2) + '×'; };
    pitch.oninput = speed.oninput = sync; sync();
    const save = h('button', { class: 'btn primary', onclick: async () => {
      save.disabled = true;
      try {
        await api('/villager/' + v.uuid, { name: name.value.trim() || v.name, persona: persona.value, quirk: quirk.value, backstory: backstory.value,
          customPrompt: prompt.value, voice: voice.value, pitch: +pitch.value, speed: +speed.value, voiceDesign: design.value });
        toast('Saved - ' + v.name.split(' ')[0] + ' will use this from the next line on');
      } catch (e) { toast('Could not save: ' + e.message); }
      save.disabled = false;
    } }, 'Save changes');

    const ask = h('input', { class: 'input', placeholder: `Say something to ${v.name.split(' ')[0]}... (they answer out loud in game)` });
    const askBtn = h('button', { class: 'btn', onclick: async () => {
      if (!ask.value.trim()) return;
      try { await api('/villager/' + v.uuid + '/say', { text: ask.value.trim() }); toast('Sent. The reply shows up in Live activity.'); ask.value = ''; }
      catch (e) { toast(e.message); }
    } }, icon('send'), 'Ask');
    ask.addEventListener('keydown', e => e.key === 'Enter' && askBtn.click());

    const offers = (v.extra_json && v.extra_json.offers) || [];
    const facts = h('dl', { class: 'kv' },
      h('dt', null, 'UUID'), h('dd', { class: 'mono' }, v.uuid),
      h('dt', null, 'Voice'), h('dd', { class: 'mono' }, v.voice || '–'),
      h('dt', null, 'First seen'), h('dd', null, v.created_at ? time(v.created_at) : '–'),
      h('dt', null, 'Last seen'), h('dd', null, ago(v.last_seen)),
      v.traits ? [h('dt', null, 'Traits'), h('dd', null, v.traits)] : null,
      ...Object.entries(v.extra_json || {}).filter(([k]) => k !== 'offers').map(([k, val]) => [h('dt', null, cap(k)), h('dd', null, String(val))]),
      offers.length ? [h('dt', null, 'Trades'), h('dd', null, h('div', { class: 'stack', style: { gap: '2px' } }, offers.map(o => h('span', null, o))))] : null);

    body.append(h('div', { class: 'grid g-main' },
      h('section', { class: 'card' }, h('header', null, h('h2', null, 'Character')),
        h('div', { class: 'body stack' },
          h('div', { class: 'grid g-2' }, h('label', { class: 'field' }, 'Name', name), h('label', { class: 'field' }, 'Personality', persona)),
          h('label', { class: 'field' }, 'Quirk', quirk),
          h('label', { class: 'field' }, 'Backstory', backstory),
          h('label', { class: 'field' }, 'Extra instructions', prompt),
          v.expressiveVoices ? h('label', { class: 'field' },
            h('span', { class: 'row' }, 'Voice description (Qwen3-TTS)', h('span', { class: 'spacer' }), h('span', { class: 'muted' }, 'empty = generated from personality')),
            design,
            h('div', { class: 'row' }, designEmotion,
              h('button', { class: 'btn', type: 'button', onclick: () => previewVoice(null, 1, 1, `Hrmm. I'm ${name.value.split(' ')[0]}. ${quirk.value ? 'You know, I ' + quirk.value + '.' : ''}`,
                designEmotion.value, design.value.trim() || v.generatedVoiceDesign, seed) }, icon('play'), 'Hear this voice'))) : null,
          h('div', { class: 'grid g-3' },
            h('label', { class: 'field' }, v.expressiveVoices ? 'Fallback voice (Kokoro)' : 'Voice', voice),
            h('label', { class: 'field' }, h('span', { class: 'row' }, 'Pitch', h('span', { class: 'spacer' }), pitchOut), pitch),
            h('label', { class: 'field' }, h('span', { class: 'row' }, 'Speed', h('span', { class: 'spacer' }), speedOut), speed)),
          h('div', { class: 'row' },
            h('button', { class: 'btn', onclick: () => previewVoice(voice.value, +pitch.value, +speed.value, `Hrmm. I'm ${name.value.split(' ')[0]}. ${quirk.value ? 'You know, I ' + quirk.value + '.' : ''}`) }, icon('play'), 'Preview'),
            h('span', { class: 'spacer' }), save))),
      h('div', { class: 'stack' },
        v.expressiveVoices ? voiceCard(v, name) : null,
        h('section', { class: 'card' }, h('header', null, h('h2', null, 'Talk to them')), h('div', { class: 'body stack' }, h('div', { class: 'row' }, ask, askBtn),
          h('div', { class: 'muted', style: { fontSize: '12.5px' } }, 'The villager must be loaded (a player nearby). They answer the nearest player out loud.'))),
        h('section', { class: 'card' }, h('header', null, h('h2', null, 'Facts')), h('div', { class: 'body' }, facts)),
        h('section', { class: 'card' }, h('header', null, h('h2', null, 'Danger zone')), h('div', { class: 'body' },
          h('button', { class: 'btn danger', onclick: async () => {
            if (!confirm(`Make ${v.name} forget every memory and relationship? This can't be undone.`)) return;
            await api('/villager/' + v.uuid + '/forget', {}); toast('Memories wiped'); route();
          } }, 'Forget all memories'))))));
  }

  // ------------------------------------------------------------------------------------------------------------
  // a villager's own voice: in-game preview, voice clips (upload or record)
  // ------------------------------------------------------------------------------------------------------------
  const CLONE_SCRIPT = 'The harvest came in early this year, so the whole village is in a good mood. Come by the market tomorrow, and I will have fresh bread and apples waiting for you.';

  /** Any audio the browser can play -> 24 kHz mono 16-bit WAV (what the voice cloner wants), trimmed and levelled. */
  async function toCloneWav(blob, maxSeconds = 25) {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    let decoded;
    try { decoded = await ctx.decodeAudioData(await blob.arrayBuffer()); } finally { ctx.close(); }
    const seconds = Math.min(decoded.duration, maxSeconds);
    const off = new OfflineAudioContext(1, Math.max(1, Math.ceil(seconds * 24000)), 24000);
    const src = off.createBufferSource();
    src.buffer = decoded;
    src.connect(off.destination);
    src.start();
    const pcm = (await off.startRendering()).getChannelData(0);
    let peak = 0;
    for (let i = 0; i < pcm.length; i++) peak = Math.max(peak, Math.abs(pcm[i]));
    const gain = peak > 0 ? Math.min(8, 0.89 / peak) : 1;
    const out = new DataView(new ArrayBuffer(44 + pcm.length * 2));
    const str = (at, t) => [...t].forEach((c, i) => out.setUint8(at + i, c.charCodeAt(0)));
    str(0, 'RIFF'); out.setUint32(4, 36 + pcm.length * 2, true); str(8, 'WAVE');
    str(12, 'fmt '); out.setUint32(16, 16, true); out.setUint16(20, 1, true); out.setUint16(22, 1, true);
    out.setUint32(24, 24000, true); out.setUint32(28, 48000, true); out.setUint16(32, 2, true); out.setUint16(34, 16, true);
    str(36, 'data'); out.setUint32(40, pcm.length * 2, true);
    for (let i = 0; i < pcm.length; i++) out.setInt16(44 + i * 2, Math.max(-1, Math.min(1, pcm[i] * gain)) * 32767, true);
    return { wav: new Blob([out.buffer], { type: 'audio/wav' }), seconds };
  }

  async function playFrom(path, body) {
    if (currentAudio) currentAudio.pause();
    const blob = await api(path, body);
    currentAudio = new Audio(URL.createObjectURL(blob));
    currentAudio.play();
  }

  function voiceCard(v, name) {
    const first = () => (name.value || v.name).split(' ')[0];
    const emotion = h('select', { class: 'input', style: { width: 'auto' }, 'aria-label': 'Mood' },
      ['neutral', 'happy', 'angry', 'sad', 'scared'].map(e => h('option', { value: e }, e)));
    const line = h('input', { class: 'input', placeholder: `Hrmm. I'm ${first()}. Welcome to the village, traveller.` });
    const hear = h('button', { class: 'btn primary', type: 'button', onclick: async () => {
      hear.disabled = true;
      try { toast('Generating...'); await playFrom('/villager/' + v.uuid + '/voice-preview', { text: line.value.trim() || undefined, emotion: emotion.value }); }
      catch (e) { if (e instanceof AuthError) return showLogin(); toast('Could not play: ' + e.message); }
      hear.disabled = false;
    } }, icon('play'), 'Hear their in-game voice');

    if (!v.voiceCloning) {
      return h('section', { class: 'card' }, h('header', null, h('h2', null, 'Their voice')),
        h('div', { class: 'body stack', style: { gap: '10px' } },
          h('div', { class: 'row wrap' }, line, emotion, hear),
          h('div', { class: 'muted', style: { fontSize: '12.5px' } }, 'To give villagers a voice of your own, install Qwen3-TTS voice cloning on the ',
            h('a', { href: '#/models' }, 'Models'), ' page.')));
    }

    let prepared = null;
    const status = h('div', { class: 'stack', style: { gap: '8px' } });
    const renderStatus = () => {
      const c = v.customVoice;
      status.replaceChildren(c
        ? h('div', { class: 'stack', style: { gap: '6px' } },
            h('div', null, h('b', null, 'Speaking with your voice clip'), h('span', { class: 'muted' }, ` · ${c.seconds} s` + (c.transcript ? '' : ' · no transcript'))),
            h('audio', { controls: true, preload: 'none', src: `/api/villager/${v.uuid}/voice-clip?t=${Date.now()}`, style: { width: '100%' } }),
            h('div', null, h('button', { class: 'btn danger', type: 'button', onclick: async () => {
              if (!confirm(`Go back to ${first()}'s designed voice?`)) return;
              try { await api('/villager/' + v.uuid + '/voice-clip/remove', {}); v.customVoice = null; renderStatus(); toast('Back to the designed voice'); }
              catch (e) { toast(e.message); }
            } }, 'Remove clip')))
        : h('div', { class: 'muted' }, `Speaking with a voice designed from ${first()}'s description. Upload or record a clip to use a real voice instead.`));
    };
    renderStatus();

    const transcript = h('textarea', { class: 'input', rows: 2, placeholder: 'What is said in the clip, word for word (optional: makes the clone much closer)' });
    const clipInfo = h('div', { class: 'row wrap', style: { gap: '8px' } });
    const upload = h('button', { class: 'btn primary', type: 'button', disabled: true, onclick: async () => {
      if (!prepared) return;
      upload.disabled = true;
      try {
        const r = await fetch(`/api/villager/${v.uuid}/voice-clip?transcript=${encodeURIComponent(transcript.value.trim())}`,
          { method: 'POST', headers: { 'X-TWT': '1', 'Content-Type': 'audio/wav' }, credentials: 'same-origin', body: prepared.wav });
        if (r.status === 401) return showLogin();
        const data = await r.json();
        if (!r.ok || data.error) throw new Error(data.error || 'HTTP ' + r.status);
        v.customVoice = { seconds: Math.round(prepared.seconds * 10) / 10, transcript: transcript.value.trim() };
        prepared = null; clipInfo.replaceChildren(); renderStatus();
        toast(`${first()} now speaks with this voice (new moods take a second the first time)`);
      } catch (e) { toast('Upload failed: ' + e.message); upload.disabled = false; }
    } }, 'Use this voice');
    const prepare = async (blob, label) => {
      try {
        prepared = await toCloneWav(blob);
        if (prepared.seconds < 2) throw new Error('that is too short: use 5 to 20 seconds of speech');
        clipInfo.replaceChildren(h('span', { class: 'muted' }, `${label} · ${prepared.seconds.toFixed(1)} s`),
          h('audio', { controls: true, src: URL.createObjectURL(prepared.wav), style: { height: '32px' } }));
        upload.disabled = false;
      } catch (e) { prepared = null; upload.disabled = true; toast('Could not read that audio: ' + e.message); }
    };
    const file = h('input', { type: 'file', accept: 'audio/*', style: { display: 'none' }, onchange: () => file.files[0] && prepare(file.files[0], file.files[0].name) });

    let recorder = null;
    const recordLabel = h('span', null, 'Record');
    const record = h('button', { class: 'btn', type: 'button', onclick: async () => {
      if (recorder) { recorder.stop(); return; }
      if (!navigator.mediaDevices || !window.MediaRecorder) return toast('Recording needs the dashboard on localhost or https. Upload a file instead.');
      try {
        const mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: true, autoGainControl: false } });
        const chunks = [];
        recorder = new MediaRecorder(mic);
        recorder.ondataavailable = e => e.data.size && chunks.push(e.data);
        recorder.onstop = () => {
          mic.getTracks().forEach(t => t.stop());
          recorder = null; recordLabel.textContent = 'Record';
          prepare(new Blob(chunks, { type: chunks[0] ? chunks[0].type : 'audio/webm' }), 'Recording');
        };
        recorder.start();
        recordLabel.textContent = 'Stop recording';
        if (!transcript.value.trim()) transcript.value = CLONE_SCRIPT;
        setTimeout(() => recorder && recorder.stop(), 25000);
      } catch (e) { toast('No microphone: ' + e.message); }
    } }, recordLabel);

    return h('section', { class: 'card' }, h('header', null, h('h2', null, 'Their voice')),
      h('div', { class: 'body stack', style: { gap: '12px' } },
        status,
        h('div', { class: 'row wrap' }, line, emotion, hear),
        h('details', null, h('summary', { style: { cursor: 'pointer', fontWeight: 600 } }, v.customVoice ? 'Use a different voice clip' : 'Use your own voice clip'),
          h('div', { class: 'stack', style: { gap: '10px', marginTop: '10px' } },
            h('div', { class: 'muted', style: { fontSize: '12.5px' } },
              '5 to 20 seconds of one person speaking clearly, without music or background noise. Recording? Read this aloud: “' + CLONE_SCRIPT + '”'),
            h('div', { class: 'row wrap' }, h('button', { class: 'btn', type: 'button', onclick: () => file.click() }, 'Choose audio file...'), file, record),
            clipInfo,
            h('label', { class: 'field' }, 'Transcript', transcript),
            h('div', { class: 'row wrap' }, upload,
              h('span', { class: 'muted', style: { fontSize: '12.5px' } }, 'Only use a voice you have permission to use.'))))));
  }

  async function villagerConversations(uuid, body) {
    const res = await api('/villager/' + uuid + '/conversations');
    if (!res.conversations.length) { body.append(h('div', { class: 'empty' }, 'No conversations yet.')); return; }
    res.conversations.forEach(c => body.append(conversationCard(c)));
  }

  function conversationCard(c) {
    return h('article', { class: 'convo' },
      h('header', null, h('b', null, c.player || c.villager || 'Unknown'), h('span', null, `${c.channel} · ${c.turns} lines`), h('span', { class: 'spacer' }), h('span', null, time(c.started))),
      h('div', { class: 'msgs' }, (c.messages || []).map(m => h('div', { class: 'msg ' + (m.role === 'player' ? 'player' : 'villager') },
        h('div', { class: 'who' }, m.speaker, m.emotion ? h('span', { class: 'emotion' }, m.emotion) : null, m.latency_ms ? h('span', { class: 'muted' }, `${fmt.format(m.latency_ms)} ms`) : null),
        h('div', null, m.text),
        m.original_text && m.original_text !== m.text ? h('div', { class: 'orig' }, `said in ${m.lang}: ${m.original_text}`) : null))));
  }

  function villagerRelationships(v, body) {
    const rels = v.relationships || [];
    if (!rels.length) { body.append(h('div', { class: 'empty' }, 'Has not talked to any players yet.')); return; }
    body.append(h('section', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', null,
      h('thead', null, h('tr', null, ['Player', 'Feeling', 'Affinity', 'Hearts (MCA)', 'Relation', 'Talks', 'Last talk'].map((l, i) => h('th', { class: i === 5 ? 'num' : '' }, l)))),
      h('tbody', null, rels.map(r => h('tr', { onclick: () => location.hash = '#/player/' + r.player_uuid },
        h('td', null, h('div', { class: 'who' }, avatar(r.player || '?', ''), h('b', null, r.player || r.player_uuid))),
        h('td', null, feeling(r.affinity)),
        h('td', null, affinityBar(r.affinity)),
        h('td', { class: 'num' }, r.hearts == null ? '–' : fmt.format(r.hearts)),
        h('td', null, r.relation || h('span', { class: 'muted' }, '–')),
        h('td', { class: 'num' }, fmt.format(r.talks || 0)),
        h('td', { class: 'muted' }, ago(r.last_talk)))))))));
  }

  function villagerMemories(v, body) {
    const mem = v.memories || [];
    const events = v.events || [];
    body.append(h('div', { class: 'grid g-2' },
      h('section', { class: 'card' }, h('header', null, h('h2', null, 'What they remember')), h('div', { class: 'body feed' },
        mem.length ? mem.map(m => h('div', { class: 'item' }, h('div', { class: 'ico' }, FEED_ICON[m.kind] || '💭'),
          h('div', { class: 'txt' }, h('div', { class: 'line' }, m.text), h('div', { class: 'time' }, `${m.player ? 'about ' + m.player + ' · ' : ''}${ago(m.created)}`)))) : h('div', { class: 'empty' }, 'No memories yet.'))),
      h('section', { class: 'card' }, h('header', null, h('h2', null, 'Life events')), h('div', { class: 'body feed' },
        events.length ? events.map(e => h('div', { class: 'item' }, h('div', { class: 'ico' }, FEED_ICON[e.type] || '•'), h('div', { class: 'txt' }, h('div', { class: 'line' }, e.text), h('div', { class: 'time' }, ago(e.ts))))) : h('div', { class: 'empty' }, 'Nothing notable.')))));
  }

  // ---- family tree ----------------------------------------------------------------------------------------------
  async function villagerFamily(uuid, body) {
    const g = await api('/villager/' + uuid + '/family');
    if (g.nodes.length <= 1) {
      body.append(h('div', { class: 'empty' }, 'No known family. Family trees come from MCA Reborn villagers and MineColonies citizens.'));
      return;
    }
    const gen = new Map([[g.root, 0]]);
    const adj = new Map();
    const add = (a, b, d, rel) => { if (!adj.has(a)) adj.set(a, []); adj.get(a).push([b, d, rel]); };
    const delta = (rel) => ['father', 'mother', 'parent'].includes(rel) ? -1 : rel === 'child' ? 1 : 0;
    g.edges.forEach(e => { const d = delta(e.relation); add(e.from, e.to, d, e.relation); add(e.to, e.from, -d, e.relation); });
    const queue = [g.root];
    while (queue.length) {
      const cur = queue.shift();
      for (const [nb, d] of adj.get(cur) || []) if (!gen.has(nb)) { gen.set(nb, gen.get(cur) + d); queue.push(nb); }
    }
    const byGen = new Map();
    g.nodes.forEach(n => { const k = gen.has(n.id) ? gen.get(n.id) : 0; if (!byGen.has(k)) byGen.set(k, []); byGen.get(k).push(n); });
    const gens = [...byGen.keys()].sort((a, b) => a - b);
    // order: spouses next to each other; children under parents (barycenter), two sweeps
    const pos = new Map();
    gens.forEach(k => byGen.get(k).forEach((n, i) => pos.set(n.id, i)));
    const spouseOf = new Map();
    g.edges.filter(e => ['spouse', 'partner'].includes(e.relation)).forEach(e => { spouseOf.set(e.from, e.to); spouseOf.set(e.to, e.from); });
    for (let sweep = 0; sweep < 3; sweep++) {
      gens.forEach(k => {
        const list = byGen.get(k);
        const score = (n) => {
          const links = (adj.get(n.id) || []).filter(([nb]) => gen.get(nb) !== k && pos.has(nb)).map(([nb]) => pos.get(nb));
          return links.length ? links.reduce((a, b) => a + b, 0) / links.length : pos.get(n.id);
        };
        list.sort((a, b) => (a.id === g.root ? -0.001 : 0) + score(a) - score(b) - (b.id === g.root ? -0.001 : 0));
        const ordered = [];
        list.forEach(n => {
          if (ordered.includes(n)) return;
          ordered.push(n);
          const sp = spouseOf.get(n.id);
          const partner = sp && list.find(x => x.id === sp);
          if (partner && !ordered.includes(partner)) ordered.push(partner);
        });
        byGen.set(k, ordered);
        ordered.forEach((n, i) => pos.set(n.id, i));
      });
    }
    const BW = 158, BH = 48, GX = 22, GY = 74, PAD = 30;
    const widest = Math.max(...gens.map(k => byGen.get(k).length));
    const W = Math.max(640, widest * (BW + GX) + PAD * 2);
    const H = gens.length * (BH + GY) + PAD;
    const xy = new Map();
    gens.forEach((k, gi) => {
      const list = byGen.get(k);
      const rowW = list.length * (BW + GX) - GX;
      list.forEach((n, i) => xy.set(n.id, { x: (W - rowW) / 2 + i * (BW + GX), y: PAD + gi * (BH + GY) }));
    });
    const svg = s('svg', { width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Family tree' });
    const genName = (k) => ({ '-3': 'Great-grandparents', '-2': 'Grandparents', '-1': 'Parents', '0': 'Siblings & partners', '1': 'Children', '2': 'Grandchildren', '3': 'Great-grandchildren' })[k] || '';
    gens.forEach((k, gi) => svg.append(s('text', { class: 'gen-label', x: 10, y: PAD + gi * (BH + GY) - 8 }, genName(k))));
    const drawn = new Set();
    g.edges.forEach(e => {
      const a = xy.get(e.from), b = xy.get(e.to);
      if (!a || !b) return;
      const d = delta(e.relation);
      const key = [e.from, e.to].sort().join('|') + d;
      if (drawn.has(key)) return;
      drawn.add(key);
      if (['spouse', 'partner'].includes(e.relation)) {
        const [l, r] = a.x < b.x ? [a, b] : [b, a];
        svg.append(s('path', { class: 'edge spouse', d: `M${l.x + BW},${l.y + BH / 2} H${r.x}` }));
      } else if (d !== 0) {
        const [p, c] = d < 0 ? [b, a] : [a, b]; // parent above child
        const midY = p.y + BH + GY / 2;
        svg.append(s('path', { class: 'edge', d: `M${p.x + BW / 2},${p.y + BH} V${midY} H${c.x + BW / 2} V${c.y}` }));
      }
    });
    g.nodes.forEach(n => {
      const p = xy.get(n.id);
      if (!p) return;
      const cls = ['node', n.id === g.root ? 'root' : '', n.player ? 'player' : '', n.alive === false ? 'dead' : '', n.known && n.id !== g.root ? 'link' : ''].join(' ');
      const grp = s('g', { class: cls, transform: `translate(${p.x},${p.y})`, tabindex: n.known ? 0 : null },
        s('rect', { width: BW, height: BH, rx: 10 }),
        s('text', { x: 12, y: 20 }, (n.name || '?').slice(0, 20)),
        s('text', { class: 'sub', x: 12, y: 36 }, n.player ? 'player' : [n.job, n.alive === false ? '✝' : null].filter(Boolean).join(' ') || (n.known ? '' : 'not met')));
      if (n.known && n.id !== g.root) {
        grp.addEventListener('click', () => location.hash = '#/villager/' + n.id + '/family');
        grp.addEventListener('keydown', (e) => e.key === 'Enter' && (location.hash = '#/villager/' + n.id + '/family'));
      }
      svg.append(grp);
    });
    body.append(h('div', { class: 'tree' }, svg),
      h('div', { class: 'muted', style: { marginTop: '8px', fontSize: '12.5px' } }, 'Click a relative to open their tree. Orange lines are marriages; dashed boxes are players.'));
  }

  // ------------------------------------------------------------------------------------------------------------
  // Villages
  // ------------------------------------------------------------------------------------------------------------
  async function viewVillages() {
    setTitle('Villages', ['They Will Talk']);
    villageCache = null;
    const list = await villages();
    if (!list.length) { main.replaceChildren(h('div', { class: 'empty' }, 'No villages discovered yet.')); return; }
    main.replaceChildren(h('div', { class: 'grid g-3' }, list.map(v => h('a', { class: 'card village-card', href: '#/village/' + encodeURIComponent(v.key), style: { color: 'inherit', textDecoration: 'none' } },
      h('div', { class: 'row' }, h('h2', null, v.name), h('span', { class: 'spacer' }), h('span', { class: 'tag' }, v.source === 'minecolonies' ? 'colony' : v.source)),
      h('div', { class: 'row secondary' }, h('span', null, `${fmt.format(Math.max(v.population, v.known))} residents`), h('span', null, '·'), h('span', null, `${fmt.format(v.talks)} talks`)),
      h('div', { class: 'muted num', style: { fontSize: '12px' } }, `${(v.dimension || '').replace('minecraft:', '')} ${v.x}, ${v.z}`)))));
  }

  async function viewVillage(key) {
    const v = await api('/village/' + encodeURIComponent(key));
    setTitle(v.name, [h('a', { href: '#/villages' }, 'Villages')]);
    const residents = v.residents || [];
    main.replaceChildren(h('div', { class: 'stack' },
      h('div', { class: 'grid g-4' },
        h('div', { class: 'card stat' }, h('div', { class: 'label' }, 'Residents'), h('div', { class: 'value num' }, fmt.format(residents.filter(r => r.alive).length))),
        h('div', { class: 'card stat' }, h('div', { class: 'label' }, 'Type'), h('div', { class: 'value', style: { fontSize: '20px' } }, v.source === 'minecolonies' ? 'Colony' : v.source === 'mca' ? 'MCA village' : 'Village')),
        h('div', { class: 'card stat' }, h('div', { class: 'label' }, 'Talks'), h('div', { class: 'value num' }, fmt.format(residents.reduce((a, r) => a + (r.talks || 0), 0)))),
        h('div', { class: 'card stat' }, h('div', { class: 'label' }, 'Center'), h('div', { class: 'value num', style: { fontSize: '18px' } }, `${v.x}, ${v.y}, ${v.z}`),
          h('div', { class: 'sub' }, h('a', { href: `#/map?x=${v.x}&z=${v.z}&dim=${encodeURIComponent(v.dimension)}` }, 'Show on map')))),
      h('div', { class: 'grid g-main' },
        h('section', { class: 'card' }, h('header', null, h('h2', null, 'Residents')), h('div', { class: 'table-wrap' }, h('table', null,
          h('thead', null, h('tr', null, ['Name', 'Job', 'Personality', 'Mood', 'Talks'].map((l, i) => h('th', { class: i === 4 ? 'num' : '' }, l)))),
          h('tbody', null, residents.map(r => h('tr', { onclick: () => location.hash = '#/villager/' + r.uuid },
            h('td', null, h('div', { class: 'who' }, avatar(r.name, r.kind, r.alive === 1, false, r.uuid), h('b', null, r.name))),
            h('td', null, cap(r.job || '–')), h('td', null, h('span', { class: 'tag' }, r.persona)), h('td', null, r.mood || h('span', { class: 'muted' }, '–')),
            h('td', { class: 'num' }, fmt.format(r.talks || 0)))))))),
        h('section', { class: 'card' }, h('header', null, h('h2', null, 'Village gossip')), h('div', { class: 'body feed' },
          (v.gossip || []).length ? v.gossip.map(g => h('div', { class: 'item' }, h('div', { class: 'ico' }, '🗣'), h('div', { class: 'txt' }, h('div', { class: 'line' }, h('b', null, g.villager), ': ', g.text), h('div', { class: 'time' }, `${g.player ? 'about ' + g.player + ' · ' : ''}${ago(g.created)}`)))) : h('div', { class: 'empty' }, 'No gossip yet.'))))));
  }

  // ------------------------------------------------------------------------------------------------------------
  // Players
  // ------------------------------------------------------------------------------------------------------------
  async function viewPlayers() {
    setTitle('Players', ['They Will Talk']);
    const list = await api('/players');
    if (!list.length) { main.replaceChildren(h('div', { class: 'empty' }, 'No players have talked to villagers yet.')); return; }
    main.replaceChildren(h('section', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', null,
      h('thead', null, h('tr', null, ['Player', 'Status', 'Villagers known', 'Conversations', 'Average affinity', 'Last seen'].map((l, i) => h('th', { class: i === 2 || i === 3 ? 'num' : '' }, l)))),
      h('tbody', null, list.map(p => h('tr', { onclick: () => location.hash = '#/player/' + p.uuid },
        h('td', null, h('div', { class: 'who' }, avatar(p.name, ''), h('b', null, p.name))),
        h('td', null, h('span', { class: 'pill ' + (p.online ? 'ok' : '') }, h('span', { class: 'dot' }), p.online ? 'online' : 'offline')),
        h('td', { class: 'num' }, fmt.format(p.villagersKnown)), h('td', { class: 'num' }, fmt.format(p.talks)),
        h('td', null, affinityBar(p.avgAffinity || 0)), h('td', { class: 'muted' }, ago(p.last_seen)))))))));
  }

  const ERRAND_STATUS = { offered: ['Offered', ''], active: ['In progress', 'warn'], done: ['Done', 'ok'], declined: ['Declined', ''],
    expired: ['Ran out of time', 'bad'], abandoned: ['Given up', 'bad'], failed: ['Called off', 'bad'] };
  function errandTask(e) {
    switch (e.kind) {
      case 'fetch': return `Bring ${e.count} ${e.label} to ${e.villager_name}`;
      case 'hunt': return `Kill ${e.count} ${e.label} for ${e.villager_name}`;
      default: return `Take ${e.villager_name}'s letter to ${e.target_name}`;
    }
  }
  function errandList(list, showPlayer) {
    if (!list.length) return h('div', { class: 'empty' }, 'No errands yet.');
    const progress = (e) => e.status === 'done' ? `${e.count}/${e.count}` : e.kind === 'hunt' ? `${e.progress}/${e.count}` : e.kind === 'fetch' ? `–/${e.count}` : '–';
    return h('div', { class: 'table-wrap' }, h('table', null,
      h('thead', null, h('tr', null, [showPlayer ? 'Player' : 'Villager', 'Errand', 'Progress', 'Reward', 'Status', 'Updated']
        .map((l, i) => h('th', { class: i === 2 || i === 3 ? 'num' : '' }, l)))),
      h('tbody', null, list.map(e => h('tr', null,
        h('td', null, showPlayer ? h('a', { href: '#/player/' + e.player_uuid }, e.player_name) : h('a', { href: '#/villager/' + e.villager_uuid }, e.villager_name)),
        h('td', null, errandTask(e), e.request ? h('div', { class: 'sub muted clamp2', title: e.request }, '“' + e.request + '”') : null),
        h('td', { class: 'num' }, progress(e)),
        h('td', { class: 'num' }, `${e.reward} emerald${e.reward === 1 ? '' : 's'}`),
        h('td', null, h('span', { class: 'pill ' + (ERRAND_STATUS[e.status] || ['', ''])[1] }, h('span', { class: 'dot' }), (ERRAND_STATUS[e.status] || [e.status])[0])),
        h('td', { class: 'muted' }, ago(e.updated)))))));
  }

  async function viewPlayer(uuid) {
    const p = await api('/player/' + uuid);
    setTitle(p.name, [h('a', { href: '#/players' }, 'Players')]);
    const rels = p.relationships || [];
    main.replaceChildren(h('div', { class: 'stack' }, h('div', { class: 'grid g-main' },
      h('section', { class: 'card' }, h('header', null, h('h2', null, 'How villagers feel about ' + p.name)), h('div', { class: 'table-wrap' }, rels.length ? h('table', null,
        h('thead', null, h('tr', null, ['Villager', 'Village', 'Feeling', 'Affinity', 'Hearts', 'Talks'].map((l, i) => h('th', { class: i >= 4 ? 'num' : '' }, l)))),
        h('tbody', null, rels.map(r => h('tr', { onclick: () => location.hash = '#/villager/' + r.villager_uuid },
          h('td', null, h('div', { class: 'who' }, avatar(r.villager, r.kind, r.alive === 1, false, r.villager_uuid), h('div', null, h('b', null, r.villager), h('div', { class: 'sub' }, [cap(r.job), r.relation].filter(Boolean).join(' · '))))),
          h('td', null, r.village || '–'), h('td', null, feeling(r.affinity)), h('td', null, affinityBar(r.affinity)),
          h('td', { class: 'num' }, r.hearts == null ? '–' : fmt.format(r.hearts)), h('td', { class: 'num' }, fmt.format(r.talks)))))) : h('div', { class: 'empty' }, 'No relationships yet.'))),
      h('section', { class: 'card' }, h('header', null, h('h2', null, 'What villagers remember')), h('div', { class: 'body feed' },
        (p.memories || []).length ? p.memories.map(m => h('div', { class: 'item' }, h('div', { class: 'ico' }, FEED_ICON[m.kind] || '💭'),
          h('div', { class: 'txt' }, h('div', { class: 'line' }, h('a', { href: '#/villager/' + m.villager_uuid }, m.villager), ': ', m.text), h('div', { class: 'time' }, ago(m.created))))) : h('div', { class: 'empty' }, 'Nothing yet.')))),
      h('section', { class: 'card' }, h('header', null, h('h2', null, 'Errands'), h('span', { class: 'muted' }, 'favours villagers asked of ' + p.name)),
        errandList(p.errands || [], false))));
  }

  // ------------------------------------------------------------------------------------------------------------
  // Conversations
  // ------------------------------------------------------------------------------------------------------------
  async function viewConversations(q) {
    setTitle('Conversations', ['They Will Talk']);
    const input = h('input', { class: 'input', type: 'search', value: q.get('q') || '', placeholder: 'Search everything anyone has said...', style: { maxWidth: '520px' }, 'aria-label': 'Search conversations' });
    const results = h('div');
    let timer;
    async function run() {
      const term = input.value.trim();
      history.replaceState(null, '', '#/conversations' + (term ? '?q=' + encodeURIComponent(term) : ''));
      const res = await api('/conversations?q=' + encodeURIComponent(term));
      if (!res.items.length) { results.replaceChildren(h('div', { class: 'empty' }, term ? 'Nobody said that.' : 'No conversations yet.')); return; }
      if (res.mode === 'recent') {
        results.replaceChildren(h('section', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', null,
          h('thead', null, h('tr', null, ['Villager', 'Player', 'Last line', 'Lines', 'When'].map((l, i) => h('th', { class: i === 3 ? 'num' : '' }, l)))),
          h('tbody', null, res.items.map(c => h('tr', { onclick: () => location.hash = '#/conversation/' + c.id },
            h('td', null, h('b', null, c.villager || '?')), h('td', null, c.player || '–'),
            h('td', { class: 'secondary', style: { maxWidth: '420px' } }, c.lastText || ''), h('td', { class: 'num' }, c.turns), h('td', { class: 'muted' }, ago(c.ended)))))))));
      } else {
        const re = new RegExp('(' + term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'ig');
        const mark = (text) => text.split(re).map((part, i) => i % 2 ? h('mark', null, part) : part);
        results.replaceChildren(h('div', { class: 'feed' }, res.items.map(m => h('div', { class: 'item', style: { cursor: 'pointer' }, onclick: () => location.hash = '#/conversation/' + m.id },
          h('div', { class: 'ico' }, m.role === 'player' ? '🧑' : '💬'),
          h('div', { class: 'txt' }, h('div', { class: 'line' }, h('b', null, m.speaker), ': ', mark(m.text || '')),
            h('div', { class: 'time' }, `${m.role === 'player' ? 'to ' + (m.villager || '?') : 'to ' + (m.player || 'someone')} · ${ago(m.ts)}`))))));
      }
    }
    input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(run, 250); });
    main.replaceChildren(h('div', { class: 'filters' }, input), results);
    await run();
    input.focus();
  }

  async function viewConversation(id) {
    const c = await api('/conversation/' + id);
    setTitle(`${c.villager || '?'} & ${c.player || 'someone'}`, [h('a', { href: '#/conversations' }, 'Conversations')]);
    main.replaceChildren(h('div', { class: 'stack' },
      h('div', { class: 'row' }, h('a', { class: 'btn', href: '#/villager/' + c.villager_uuid }, 'Open ' + (c.villager || 'villager')),
        c.player_uuid ? h('a', { class: 'btn', href: '#/player/' + c.player_uuid }, 'Open ' + (c.player || 'player')) : null),
      conversationCard(c)));
  }

  // ------------------------------------------------------------------------------------------------------------
  // Map: schematic canvas map + BlueMap 3D
  // ------------------------------------------------------------------------------------------------------------
  async function viewMap(q) {
    setTitle('Map', ['They Will Talk']);
    const data = await api('/map');
    const bm = data.bluemap || {};
    if (!bm.installed || !bm.running) {
      main.replaceChildren(h('div', { class: 'empty' }, !bm.installed
        ? 'The map needs BlueMap. Install BlueMap on the server to see your world in 3D, with every talking villager on it.'
        : 'BlueMap is installed but not running. It needs its resource download accepted: set accept-download: true in config/bluemap/core.conf and run /bluemap reload.'));
      return;
    }
    // "Show on map" links centre on a villager (?focus=uuid) or a place (?x=&z=&dim=); otherwise open on the villages.
    const focus = q.get('focus') && data.villagers.find(v => v.uuid === q.get('focus'));
    const home = data.villages.find(v => v.dimension === 'minecraft:overworld') || data.villagers.find(v => v.dimension === 'minecraft:overworld');
    const place = focus || (q.get('x') != null ? { x: +q.get('x'), z: +q.get('z'), dimension: q.get('dim') || 'minecraft:overworld' } : home);
    const dim = place ? place.dimension : 'minecraft:overworld';
    const mapId = (bm.maps || {})[dim] || Object.values(bm.maps || {})[0] || 'world';
    const at = place ? `:${Math.round(place.x)}:64:${Math.round(place.z)}:${focus ? 90 : 200}:0:0:0:0:flat` : '';
    main.replaceChildren(h('div', { class: 'map-wrap' }, h('iframe', { src: `/bluemap/#${mapId}${at}`, title: 'BlueMap' })));
  }

  // ------------------------------------------------------------------------------------------------------------
  // Runtime & voices
  // ------------------------------------------------------------------------------------------------------------
  let currentAudio = null;
  async function previewVoice(voice, pitch, speed, text, emotion, design, seed) {
    try {
      toast('Generating voice...');
      const blob = await api('/voices/preview', { voice, pitch, speed, text, emotion: emotion || 'neutral', design: design || '', seed: seed == null ? 42 : seed });
      if (currentAudio) currentAudio.pause();
      currentAudio = new Audio(URL.createObjectURL(blob));
      currentAudio.play();
    } catch (e) {
      if (e instanceof AuthError) return showLogin();
      toast('Voice preview failed: ' + e.message);
    }
  }

  async function viewRuntime() {
    setTitle('AI & voices', ['They Will Talk']);
    const r = await api('/runtime');
    const voices = await api('/voices').catch(() => []);
    const gpu = (r.gpus || [])[0];
    const procCard = (p) => h('section', { class: 'card' },
      h('header', null, h('h2', null, { llm: 'Villager brain (llama.cpp)', 'qwen-tts': 'Expressive voices (Qwen3-TTS)', 'qwen-clone': 'Voice cloning (Qwen3-TTS Base)' }[p.name] || 'CPU voices (Kokoro)'),
        p.onDemand && p.state === 'STOPPED'
          ? h('span', { class: 'pill', title: 'Starts by itself while a new villager voice is designed, then stops to free GPU memory' }, h('span', { class: 'dot' }), 'idle · on demand')
          : h('span', { class: 'pill ' + (p.state === 'READY' ? 'ok' : p.state === 'FAILED' ? 'bad' : 'warn') }, h('span', { class: 'dot' }), p.state.toLowerCase())),
      h('div', { class: 'body stack', style: { gap: '10px' } },
        h('dl', { class: 'kv' }, h('dt', null, 'PID'), h('dd', { class: 'num' }, p.pid > 0 ? p.pid : '–'),
          h('dt', null, 'Uptime'), h('dd', null, p.uptimeMs ? Math.round(p.uptimeMs / 60000) + ' min' : '–'),
          h('dt', null, 'Log'), h('dd', { class: 'mono' }, p.logFile),
          p.lastError ? [h('dt', null, 'Last error'), h('dd', { style: { color: 'var(--critical)' } }, p.lastError)] : null),
        h('details', null, h('summary', { class: 'muted', style: { cursor: 'pointer' } }, 'Recent log'), h('div', { class: 'log' }, (p.log || []).join('\n')))));

    // voice lab
    const voiceSel = h('select', { class: 'input' }, voices.map(v => h('option', { value: v.id }, `${v.engine} / ${v.name} (${v.gender === 'f' ? 'female' : 'male'}${v.traits ? ', ' + v.traits : ''})`)));
    const emotionSel = h('select', { class: 'input' }, ['neutral', 'happy', 'excited', 'laugh', 'angry', 'annoyed', 'sad', 'scared', 'surprised', 'confused', 'sleepy', 'flirty'].map(e => h('option', { value: e }, e)));
    const text = h('textarea', { class: 'input', rows: 2 }, 'Hrmm! A traveler? Welcome to Mossbrook. Mind the carrots, the zombies trampled half of them last night.');
    const pitch = h('input', { type: 'range', min: 0.7, max: 1.5, step: 0.01, value: 1 }), speed = h('input', { type: 'range', min: 0.6, max: 1.6, step: 0.01, value: 1 });
    const po = h('span', { class: 'num secondary' }), so = h('span', { class: 'num secondary' });
    const labDesign = h('textarea', { class: 'input', rows: 3 }, 'An elderly man, a medieval village farmer, with a low-pitched, gravelly voice; speaks slowly. Personality: grumpy and easily irritated, but secretly soft-hearted.');
    let labMode = r.expressiveVoices ? 'design' : 'voice';
    const labBody = h('div', { class: 'stack' });
    const labSeg = h('div', { class: 'seg' });
    const renderLab = () => {
      labSeg.replaceChildren(
        h('button', { class: labMode === 'design' ? 'on' : '', disabled: !r.expressiveVoices, onclick: () => { labMode = 'design'; renderLab(); } }, 'Describe a voice (Qwen3-TTS)'),
        h('button', { class: labMode === 'voice' ? 'on' : '', onclick: () => { labMode = 'voice'; renderLab(); } }, 'Stock voices (Kokoro)'));
      labBody.replaceChildren(labMode === 'design'
        ? h('label', { class: 'field' }, 'Voice description', labDesign)
        : h('div', { class: 'stack' }, h('label', { class: 'field' }, 'Voice', voiceSel), h('div', { class: 'grid g-2' },
            h('label', { class: 'field' }, h('span', { class: 'row' }, 'Pitch', h('span', { class: 'spacer' }), po), pitch),
            h('label', { class: 'field' }, h('span', { class: 'row' }, 'Speed', h('span', { class: 'spacer' }), so), speed))));
    };
    const sync = () => { po.textContent = (+pitch.value).toFixed(2) + '×'; so.textContent = (+speed.value).toFixed(2) + '×'; };
    pitch.oninput = speed.oninput = sync; sync();

    renderLab();
    main.replaceChildren(h('div', { class: 'stack' },
      (r.problems || []).map(p => h('div', { class: 'banner bad' }, p)),
      h('div', { class: 'grid g-4' },
        h('div', { class: 'card stat' }, h('div', { class: 'label' }, 'Model'), h('div', { class: 'value', style: { fontSize: '16px', overflowWrap: 'anywhere' } }, r.model || '–'), h('div', { class: 'sub' }, `${fmt.format(r.requests)} requests · ${fmt.format(r.tokensGenerated)} tokens`)),
        h('div', { class: 'card stat' }, h('div', { class: 'label' }, 'Speed'), h('div', { class: 'value num' }, (r.tokensPerSecond || '–') + ' tok/s'), h('div', { class: 'sub' }, `first token ${r.firstTokenMs > 0 ? r.firstTokenMs + ' ms' : '–'} · queue ${r.queued}`)),
        h('div', { class: 'card stat' }, h('div', { class: 'label' }, 'Time to voice'), h('div', { class: 'value num' }, r.lastLatencyMs ? fmt.format(r.lastLatencyMs) + ' ms' : '–'), h('div', { class: 'sub' }, `${r.activeConversations} talking now`)),
        h('div', { class: 'card stat' }, h('div', { class: 'label' }, 'GPU'), h('div', { class: 'value num' }, gpu ? `${gpu.utilization}%` : '–'),
          gpu ? h('div', { class: 'stack', style: { gap: '4px', marginTop: '4px' } }, h('div', { class: 'meter' }, h('i', { style: { width: (100 * gpu.memoryUsedMb / gpu.memoryTotalMb) + '%' } })),
            h('div', { class: 'sub' }, `${(gpu.memoryUsedMb / 1024).toFixed(1)} / ${(gpu.memoryTotalMb / 1024).toFixed(1)} GB · ${gpu.temperature}°C · ${gpu.name}`)) : h('div', { class: 'sub' }, 'no NVIDIA GPU detected'))),
      h('div', { class: 'grid g-3' }, (r.processes || []).map(procCard)),
      h('div', { class: 'grid g-main' },
        h('section', { class: 'card' }, h('header', null, h('h2', null, 'Voice lab'), h('span', { class: 'muted' }, `${voices.length} voices`)),
          h('div', { class: 'body stack' },
            labSeg, labBody,
            h('div', { class: 'grid g-2' }, h('label', { class: 'field' }, 'Line', text), h('label', { class: 'field' }, 'Emotion', emotionSel)),
            h('div', { class: 'row' }, h('button', { class: 'btn primary', onclick: () => labMode === 'design'
              ? previewVoice(null, 1, 1, text.value, emotionSel.value, labDesign.value, Math.floor(Math.random() * 1e6))
              : previewVoice(voiceSel.value, +pitch.value, +speed.value, text.value, emotionSel.value) }, icon('play'), 'Speak')))),
        h('section', { class: 'card' }, h('header', null, h('h2', null, 'Settings'), h('button', { class: 'btn', onclick: async () => { await api('/runtime/restart', {}); toast('Restarting AI...'); } }, icon('refresh'), 'Restart AI')),
          h('div', { class: 'body stack' },
            h('dl', { class: 'kv' }, Object.entries(r.config || {}).map(([k, v]) => [h('dt', null, k), h('dd', { class: 'mono' }, String(v))]),
              h('dt', null, 'runtimeDir'), h('dd', { class: 'mono' }, r.runtimeDir)),
            h('div', { class: 'muted', style: { fontSize: '12.5px' } }, 'Edit config/theywilltalk-common.toml and restart the AI to change these.'))))));
  }

  // ------------------------------------------------------------------------------------------------------------
  // models: download and pick the AI programs, brains and voices
  // ------------------------------------------------------------------------------------------------------------
  const gb = (b) => b >= 1e9 ? (b / 1e9).toFixed(1) + ' GB' : Math.max(1, Math.round(b / 1e6)) + ' MB';
  const UNCENSORED_WARNING = 'Uncensored models have their refusals removed: villagers can say offensive, crude or disturbing things, '
    + 'especially with crude language on. Only use one where every player is fine with that. Continue?';
  const RUNNING = ['QUEUED', 'DOWNLOADING', 'VERIFYING', 'UNPACKING'];

  async function viewModels() {
    setTitle('Models', ['They Will Talk']);
    let data = await api('/models');
    const root = h('div', { class: 'stack' });
    main.replaceChildren(root);
    let timer = null;
    let alive = true;
    onLeave(() => { alive = false; clearTimeout(timer); });

    const poll = () => {
      clearTimeout(timer);
      if (!alive) return;
      timer = setTimeout(async () => {
        try { data = await api('/models'); render(); } catch (e) { /* retry on the next tick */ }
        poll();
      }, data.busy ? 1000 : 5000);
    };
    const act = async (path, body, question) => {
      if (question && !confirm(question)) return;
      try {
        data = await api('/models/' + path, body);
        render();
        refreshStatus();
      } catch (e) {
        if (e instanceof AuthError) return showLogin();
        toast(e.message);
      }
      poll();
    };

    const progress = (p) => {
      const j = p.job;
      if (!j) return null;
      if (j.phase === 'FAILED') return h('div', { class: 'banner bad', style: { margin: 0 } }, 'Download failed: ' + j.error);
      if (!RUNNING.includes(j.phase)) return null;
      const pct = j.total ? Math.min(100, 100 * j.done / j.total) : 0;
      const label = {
        QUEUED: 'Waiting for the other downloads...',
        DOWNLOADING: `${gb(j.done)} of ${gb(j.total)}` + (j.bytesPerSecond ? ` · ${gb(j.bytesPerSecond)}/s` : ''),
        VERIFYING: 'Checking the download...',
        UNPACKING: 'Unpacking...',
      }[j.phase];
      return h('div', { class: 'stack', style: { gap: '6px' } },
        h('div', { class: 'meter' }, h('i', { style: { width: pct + '%' } })),
        h('div', { class: 'row' }, h('span', { class: 'muted num', style: { fontSize: '12.5px' } }, label), h('span', { class: 'spacer' }),
          h('button', { class: 'btn', onclick: () => act('cancel', { id: p.id }) }, 'Cancel')));
    };

    const card = (p) => {
      const running = p.job && RUNNING.includes(p.job.phase);
      const uncensored = p.tags.includes('uncensored');
      const actions = [];
      if (!p.installed && !p.available) {
        actions.push(h('div', { class: 'muted', style: { fontSize: '12.5px' } }, p.unavailable || 'Not available on this platform.'));
      } else if (!p.installed && p.blockedBy.length) {
        actions.push(h('div', { class: 'muted', style: { fontSize: '12.5px' } }, `Needs the ${p.blockedBy.join(', ')}, which can't be downloaded yet.`));
      } else if (!p.installed && !running) {
        actions.push(h('button', { class: 'btn primary', onclick: () => act('install', { id: p.id }, uncensored ? UNCENSORED_WARNING : null) },
          icon('download'), 'Download · ' + gb(p.size)));
      }
      if (p.installed && p.selectable && !p.active) {
        actions.push(h('button', { class: 'btn primary', onclick: () => act('use', { id: p.id }, uncensored ? UNCENSORED_WARNING : null) }, 'Use this'));
      }
      if (p.installed && !running) {
        actions.push(h('button', { class: 'btn danger', onclick: () => act('remove', { id: p.id }, `Delete ${p.name} from the server? You can download it again later.`) }, 'Remove'));
      }
      return h('section', { class: 'card' },
        h('header', null, h('h2', null, p.name),
          p.active ? h('span', { class: 'pill ok' }, h('span', { class: 'dot' }), 'In use')
            : p.installed ? h('span', { class: 'pill' }, h('span', { class: 'dot' }), 'Installed') : null),
        h('div', { class: 'body stack', style: { gap: '10px' } },
          (p.recommended || uncensored || p.wrongGpu ? h('div', { class: 'row' }, p.recommended ? h('span', { class: 'tag' }, 'Recommended') : null,
            uncensored ? h('span', { class: 'tag', style: { color: 'var(--critical)' } }, 'Uncensored') : null,
            p.wrongGpu ? h('span', { class: 'tag', style: { color: 'var(--warning)' } }, data.nvidia ? 'For AMD / Intel / CPU' : 'Needs an NVIDIA GPU') : null) : null),
          h('div', null, p.summary),
          h('div', { class: 'muted', style: { fontSize: '12.5px' } }, [p.size ? gb(p.size) : null, p.licence, p.source].filter(Boolean).join(' · ')),
          progress(p),
          actions.length ? h('div', { class: 'row wrap' }, actions) : null));
    };

    const group = (title, sub, id) => [
      h('div', null, h('h2', null, title), h('div', { class: 'muted', style: { fontSize: '12.5px', marginTop: '2px' } }, sub)),
      h('div', { class: 'grid g-2' }, data.packages.filter(p => p.group === id).map(card)),
    ];

    function render() {
      const s = data.settings;
      const setup = data.setupNeeded ? h('section', { class: 'card' },
        h('header', null, h('h2', null, 'Set up the villagers\' AI')),
        h('div', { class: 'body stack', style: { gap: '12px' } },
          h('div', null, 'Villagers need a brain (a language model, run by llama.cpp) and voices. ',
            data.nvidia ? 'The recommended setup for your NVIDIA GPU is Gemma 4 E2B with the Kokoro voices, plus the expressive Qwen3-TTS voices. '
              : 'No NVIDIA GPU was found, so the brain runs through Vulkan (AMD or Intel GPUs, or the CPU, which is slow) and villagers use the Kokoro voices; the expressive Qwen3-TTS voices need an NVIDIA GPU. ',
            'Nothing is downloaded until you click; ',
            'every file comes from GitHub or Hugging Face and is checked against a pinned SHA-256 checksum.'),
          h('div', { class: 'row wrap' },
            h('button', { class: 'btn primary', disabled: data.busy || !data.recommendedBytes, onclick: () => act('install', { id: 'recommended' }) },
              icon('download'), 'Install recommended · ' + gb(data.recommendedBytes)),
            h('span', { class: 'muted', style: { fontSize: '12.5px' } }, data.freeBytes >= 0 ? gb(data.freeBytes) + ' free on this disk' : '')))) : null;

      const crude = h('input', { type: 'checkbox', checked: s.crudeLanguage, onchange: (e) => act('settings', { crudeLanguage: e.target.checked }) });
      const bleep = h('input', { type: 'checkbox', checked: s.bleepSwearing, onchange: (e) => act('settings', { bleepSwearing: e.target.checked }) });
      const engine = h('select', { class: 'input', onchange: (e) => act('settings', { ttsEngine: e.target.value }) },
        [['auto', 'Automatic (Qwen3-TTS when installed, else Kokoro)'], ['qwen3', 'Qwen3-TTS (GPU, expressive)'], ['kokoro', 'Kokoro (CPU)'], ['supertonic', 'Supertonic (CPU)']]
          .map(([v, label]) => h('option', { value: v, selected: v === s.ttsEngine }, label)));
      const behaviour = h('section', { class: 'card' }, h('header', null, h('h2', null, 'Behaviour')),
        h('div', { class: 'body stack', style: { gap: '14px' } },
          h('label', { class: 'row', style: { alignItems: 'flex-start', cursor: 'pointer' } }, crude,
            h('div', null, h('b', null, 'Crude language'),
              h('div', { class: 'muted', style: { fontSize: '12.5px' } }, 'Villagers may swear and be vulgar when it fits their mood and personality. Off keeps them clean. ',
                'The standard Gemma models only swear mildly; pick an uncensored brain for strong language.'))),
          h('label', { class: 'row', style: { alignItems: 'flex-start', cursor: 'pointer' } }, bleep,
            h('div', null, h('b', null, 'Bleep swear words'),
              h('div', { class: 'muted', style: { fontSize: '12.5px' } }, 'YouTube style: swear words show as f*** in subtitles and bubbles and are beeped in the voice. ',
                'Turn off to let villagers swear uncensored. Slurs are always censored.'))),
          h('label', { class: 'field' }, 'Voice engine', engine)));

      root.replaceChildren(setup,
        ...group('Villager brain', 'The language model villagers think with. Bigger is smarter but slower and needs more GPU memory.', 'brain'),
        ...group('Voices', 'How villagers sound. Kokoro runs on the CPU; Qwen3-TTS acts out emotions on the GPU.', 'voices'),
        ...group('Programs', 'What runs the models. Installed automatically with the first brain or voice that needs them.', 'programs'),
        behaviour,
        h('div', { class: 'muted', style: { fontSize: '12.5px' } }, `Runtime folder: ${data.runtimeDir} (${data.platform})`
          + (data.freeBytes >= 0 ? ` · ${gb(data.freeBytes)} free` : '')));
    }

    render();
    poll();
  }

  // ------------------------------------------------------------------------------------------------------------
  // settings
  // ------------------------------------------------------------------------------------------------------------
  const SECTION_TITLES = { runtime: 'AI programs', conversation: 'Conversations', villagers: 'Villagers', events: 'Villager events', dashboard: 'Dashboard & map' };
  const ENGINE_LABELS = { auto: 'Automatic', qwen3: 'Qwen3-TTS (GPU)', kokoro: 'Kokoro (CPU)', supertonic: 'Supertonic (CPU)' };

  async function viewSettings() {
    setTitle('Settings', ['They Will Talk']);
    let data = await api('/settings');
    let restartNeeded = false;
    const root = h('div', { class: 'stack' });
    main.replaceChildren(root);

    const save = async (o, value, control) => {
      try {
        const res = await api('/settings', { key: o.key, value });
        data = res;
        if (res.restartAi) restartNeeded = true;
        toast(o.label + ' saved');
      } catch (e) {
        if (e instanceof AuthError) return showLogin();
        toast(e.message);
      }
      render();
      if (control) control.focus();
    };

    const control = (o) => {
      if (o.readOnly) return h('code', { class: 'setting-ro', title: 'Change it in config/theywilltalk-common.toml' }, String(o.value === '' ? '(empty)' : o.value));
      if (o.type === 'boolean') {
        const box = h('input', { type: 'checkbox', class: 'switch', checked: o.value ? '' : null, 'aria-label': o.label });
        box.checked = o.value;
        box.addEventListener('change', () => save(o, box.checked));
        return box;
      }
      if (o.choices) {
        const sel = h('select', { class: 'input', 'aria-label': o.label },
          o.choices.map(c => h('option', { value: c, selected: c === o.value ? '' : null }, ENGINE_LABELS[c] || c)));
        sel.addEventListener('change', () => save(o, sel.value));
        return sel;
      }
      const number = o.type !== 'string';
      const step = o.type === 'int' ? 1 : (o.max - o.min) <= 2 ? 0.05 : 0.5;
      const input = h('input', { class: 'input', type: number ? 'number' : 'text', 'aria-label': o.label,
        min: number ? o.min : null, max: number ? o.max : null, step: number ? step : null, value: o.value });
      const commit = () => {
        const v = number ? Number(input.value) : input.value.trim();
        if (number && (input.value === '' || Number.isNaN(v))) { input.value = o.value; return; }
        if (v !== o.value) save(o, v);
      };
      input.addEventListener('change', commit);
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); input.blur(); } });
      return input;
    };

    const row = (o) => {
      const changed = JSON.stringify(o.value) !== JSON.stringify(o.default);
      const shown = (v) => o.type === 'boolean' ? (v ? 'on' : 'off') : o.choices ? (ENGINE_LABELS[v] || v) : v === '' ? '(empty)' : v;
      const hint = [o.comment,
        o.min != null && !o.readOnly ? `${o.min} to ${o.max}.` : null,
        o.readOnly ? 'Only in the config file.' : null,
        o.restartAi ? 'Takes effect when the AI restarts.' : null,
        changed && !o.readOnly ? `Default: ${shown(o.default)}.` : null,
      ].filter(Boolean).join(' ');
      return h('div', { class: 'setting' },
        h('div', null, h('div', { class: 'setting-label' }, o.label, changed ? h('span', { class: 'setting-changed', title: 'Changed from the default' }) : null),
          hint ? h('div', { class: 'muted setting-hint' }, hint) : null),
        h('div', { class: 'setting-control' }, control(o)));
    };

    function render() {
      const banner = restartNeeded ? h('div', { class: 'banner', style: { margin: 0, display: 'flex', alignItems: 'center', gap: '12px' } },
        h('span', { style: { flex: 1 } }, 'Some changes take effect when the AI programs restart. Villagers can\'t talk for a moment while they do.'),
        h('button', { class: 'btn primary', onclick: async () => {
          try { await api('/runtime/restart', {}); restartNeeded = false; toast('Restarting the AI...'); render(); refreshStatus(); }
          catch (e) { if (e instanceof AuthError) return showLogin(); toast(e.message); }
        } }, icon('refresh'), 'Restart the AI')) : null;
      root.replaceChildren(...[banner].filter(Boolean),
        h('div', { class: 'muted', style: { fontSize: '12.5px' } }, 'Changes are saved to config/theywilltalk-common.toml as you make them and take effect right away unless noted.'),
        ...data.sections.map(sec => h('section', { class: 'card' },
          h('header', null, h('h2', null, SECTION_TITLES[sec.id] || sec.id)),
          h('div', { class: 'body' },
            sec.comment ? h('div', { class: 'muted', style: { fontSize: '12.5px', marginBottom: '4px' } }, sec.comment) : null,
            sec.options.map(row)))));
    }
    render();
  }

  // ------------------------------------------------------------------------------------------------------------
  // boot
  // ------------------------------------------------------------------------------------------------------------
  let routed = false;
  async function start() {
    try {
      await api('/overview');
    } catch (e) {
      if (e instanceof AuthError) return showLogin();
    }
    shell();
    startStream();
    refreshStatus();
    if (!routed) { window.addEventListener('hashchange', route); routed = true; }
    route();
  }
  start();
})();
