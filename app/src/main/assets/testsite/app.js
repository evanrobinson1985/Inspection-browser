// Inspection Browser test page: an external script, so Sources shows it and Share everything includes it.
'use strict';

const $ = (sel) => document.querySelector(sel);
const out = (id, text) => { const el = $(id); el.textContent = text + '\n' + el.textContent; };
const isTestHost = location.hostname === 'appassets.androidplatform.net';

// ---------- 1 · Info ----------
function showFacts() {
  const ua = navigator.userAgent;
  const facts = {
    'Address': location.href,
    'User agent': ua,
    'Looks like': /Android|iPhone|iPad|Mobile/.test(ua) ? 'mobile' : 'desktop',
    'Android WebView marker': /; wv\)/.test(ua) ? 'yes ("; wv")' : 'no',
    'Viewport': `${innerWidth} × ${innerHeight} CSS px, DPR ${devicePixelRatio}`,
    'Screen': `${screen.width} × ${screen.height}`,
    'Color scheme': matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
    'Secure context': String(isSecureContext),
    'Online': String(navigator.onLine),
    'Touch points': String(navigator.maxTouchPoints),
    'Language': navigator.language,
  };
  $('#facts').innerHTML = Object.entries(facts)
    .map(([k, v]) => `<dt>${k}</dt><dd>${String(v).replace(/</g, '&lt;')}</dd>`).join('');
}

// ---------- 2 · Console ----------
let ticker = null;
const consoleTests = {
  levels() {
    console.log('A plain log message');
    console.info('An info message');
    console.warn('A warning message');
    console.error('An error message');
    console.debug('A debug message');
  },
  objects() {
    console.log('An object:', { cave: 'Ginnie Springs', depth: 30, tags: ['spring', 'florida'], nested: { a: { b: { c: { d: 1 } } } } });
    console.log('An array:', [1, 'two', { three: 3 }, [4, 5], null, undefined, true]);
    console.log('A map and a set:', new Map([['k', 'v']]), new Set([1, 2, 3]));
    console.log('A function:', function surveyCave() {});
  },
  circular() {
    const cave = { name: 'Loop cave' };
    cave.self = cave;
    console.log('A circular object:', cave);
  },
  styled() {
    console.log('%cBig blue text%c and normal text', 'color:#1f5fbf;font-size:18px;font-weight:bold', '');
  },
  format() {
    console.log('%s has %d passages and %f km of survey', 'Peacock Springs', 12, 9.6);
  },
  table() {
    console.table([{ cave: 'Ginnie', county: 'Gilchrist', m: 30 }, { cave: 'Peacock', county: 'Suwannee', m: 53 }]);
  },
  group() {
    console.group('Survey trip');
    console.log('Entered the cave');
    console.group('Station 1');
    console.log('Depth 12 m');
    console.groupEnd();
    console.groupEnd();
  },
  timing() {
    console.time('timer');
    for (let i = 0; i < 1e5; i++) Math.sqrt(i);
    console.timeEnd('timer');
    console.count('counter');
    console.count('counter');
    console.assert(1 === 2, 'This assertion failed on purpose');
  },
  element() {
    console.log('An element:', $('#list'));
  },
  uncaught() {
    setTimeout(() => { throw new TypeError('An uncaught error, thrown on purpose'); });
  },
  rejection() {
    Promise.reject(new Error('An unhandled promise rejection, on purpose'));
  },
  flood() {
    for (let i = 1; i <= 500; i++) {
      if (i % 50 === 0) console.error('Flood error', i);
      else if (i % 20 === 0) console.warn('Flood warning', i);
      else console.log('Flood message', i, { i });
    }
  },
  ticker() {
    if (ticker) { clearInterval(ticker); ticker = null; $('#ticker').textContent = 'Start a log every second'; return; }
    let n = 0;
    ticker = setInterval(() => console.log('Tick', ++n), 1000);
    $('#ticker').textContent = 'Stop the ticker';
  },
  clearConsole() { console.clear(); },
};

// ---------- 3 · Network ----------
async function show(label, promise) {
  const started = performance.now();
  try {
    const res = await promise;
    const ms = Math.round(performance.now() - started);
    const body = res.headers.get('content-type')?.includes('json') ? JSON.stringify(await res.json()).slice(0, 200) : (await res.text()).slice(0, 200);
    out('#netOut', `${label}: ${res.status} ${res.statusText} in ${ms} ms\n  ${body}`);
  } catch (e) {
    out('#netOut', `${label}: failed (${e.message})`);
    console.error(label, 'failed:', e);
  }
}
const networkTests = {
  getJson: () => show('GET data/caves.json', fetch('data/caves.json')),
  get404: () => show('GET missing.json', fetch('data/missing.json')),
  postJson: () => show('POST httpbin.org/post', fetch('https://httpbin.org/post', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ cave: 'Peacock', depth: 53 }),
  })),
  xhr() {
    const x = new XMLHttpRequest();
    x.open('GET', 'data/caves.json');
    x.onload = () => out('#netOut', `XHR data/caves.json: ${x.status}, ${x.responseText.length} characters`);
    x.onerror = () => out('#netOut', 'XHR failed');
    x.send();
  },
  slow: () => show('GET httpbin.org/delay/3', fetch('https://httpbin.org/delay/3')),
  status500: () => show('GET httpbin.org/status/500', fetch('https://httpbin.org/status/500')),
  big: () => show('GET data/big.json', fetch('data/big.json')),
  headers: () => show('GET httpbin.org/headers', fetch('https://httpbin.org/headers', { headers: { 'X-Test-Header': 'inspection-browser' } })),
  dnsFail: () => show('GET does-not-exist.invalid', fetch('https://does-not-exist.invalid/')),
  cors: () => show('GET example.com (no CORS)', fetch('https://example.com/')),
  image() {
    const img = new Image();
    img.onload = () => out('#netOut', `Image loaded: ${img.naturalWidth} × ${img.naturalHeight}`);
    img.onerror = () => out('#netOut', 'Image failed');
    img.src = 'img/icon.svg?' + Date.now();
  },
  beacon() {
    const ok = navigator.sendBeacon('https://httpbin.org/post', JSON.stringify({ event: 'test' }));
    out('#netOut', 'sendBeacon queued: ' + ok);
  },
};

// ---------- 4 · Resources ----------
const resourceTests = {
  setStorage() {
    localStorage.setItem('favoriteCave', 'Ginnie Springs');
    localStorage.setItem('settings', JSON.stringify({ units: 'metric', zoom: 7 }));
    sessionStorage.setItem('visit', String(Date.now()));
    out('#resOut', 'Set localStorage favoriteCave, settings and sessionStorage visit');
  },
  clearStorage() {
    ['favoriteCave', 'settings'].forEach((k) => localStorage.removeItem(k));
    sessionStorage.removeItem('visit');
    out('#resOut', 'Cleared the test storage keys');
  },
  setCookie() {
    document.cookie = `testCookie=${Date.now()}; path=/; max-age=3600`;
    out('#resOut', 'Cookies now: ' + (document.cookie || '(none)'));
  },
  clearCookies() {
    document.cookie.split(';').forEach((c) => {
      const name = c.split('=')[0].trim();
      if (name) document.cookie = `${name}=; path=/; max-age=0`;
    });
    out('#resOut', 'Cookies now: ' + (document.cookie || '(none)'));
  },
  indexedDb() {
    const open = indexedDB.open('testDb', 1);
    open.onupgradeneeded = () => open.result.createObjectStore('caves', { keyPath: 'id' });
    open.onsuccess = () => {
      const tx = open.result.transaction('caves', 'readwrite');
      tx.objectStore('caves').put({ id: Date.now(), name: 'Test cave' });
      tx.oncomplete = () => out('#resOut', 'Wrote a record to IndexedDB testDb / caves');
    };
    open.onerror = () => out('#resOut', 'IndexedDB failed: ' + open.error);
  },
};

// ---------- 5 · Elements ----------
let items = 1;
const elementTests = {
  addNode() {
    const li = document.createElement('li');
    li.textContent = `Item ${++items}`;
    li.dataset.added = new Date().toLocaleTimeString();
    $('#list').appendChild(li);
  },
  removeNode() { const last = $('#list').lastElementChild; if (last) last.remove(); },
  toggleClass() { [...$('#list').children].forEach((li) => li.classList.toggle('highlight')); },
  shadow() {
    const host = $('#shadowHost');
    if (host.shadowRoot) return;
    const root = host.attachShadow({ mode: 'open' });
    root.innerHTML = '<style>p{padding:8px;border-radius:8px;background:#1f5fbf22}</style><p>I live in a Shadow DOM.</p>';
  },
};

// ---------- 6 · Page speed ----------
const speedTests = {
  shift() {
    const banner = document.createElement('div');
    banner.className = 'shift-banner';
    banner.textContent = 'A late banner pushed things down (layout shift).';
    $('#shiftArea').prepend(banner);
    const hero = document.querySelector('.hero');
    hero.parentNode.insertBefore(banner.cloneNode(true), hero);
  },
  busy() {
    const end = performance.now() + 1000;
    while (performance.now() < end) { /* block the main thread on purpose */ }
    console.warn('Blocked the main thread for 1 s');
  },
};

// ---------- 7 & 8 · Tabs and browser features ----------
const featureTests = {
  windowOpen() {
    const w = window.open('popup.html?opened-by-script', '_blank');
    console.log('window.open returned', w ? 'a window' : 'null');
  },
  location() {
    if (!navigator.geolocation) return out('#featOut', 'No geolocation here');
    navigator.geolocation.getCurrentPosition(
      (p) => out('#featOut', `Location: ${p.coords.latitude.toFixed(4)}, ${p.coords.longitude.toFixed(4)} (±${Math.round(p.coords.accuracy)} m)`),
      (e) => out('#featOut', 'Location refused or failed: ' + e.message),
      { timeout: 15000 },
    );
  },
  async camera() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
      const v = $('#video');
      v.hidden = false;
      v.srcObject = stream;
      await v.play();
      out('#featOut', 'Camera and microphone on; tap again after reloading to test the remembered answer');
      setTimeout(() => { stream.getTracks().forEach((t) => t.stop()); v.hidden = true; }, 8000);
    } catch (e) {
      out('#featOut', 'Camera / mic refused or failed: ' + e.message);
    }
  },
  fullscreen() {
    const el = $('#features');
    (el.requestFullscreen ? el.requestFullscreen() : Promise.reject(new Error('not supported')))
      .catch((e) => out('#featOut', 'Fullscreen failed: ' + e.message));
  },
  dialogs() {
    alert('An alert');
    const ok = confirm('A confirm: OK or Cancel?');
    const name = prompt('A prompt: type something', 'stalactite');
    out('#featOut', `confirm → ${ok}, prompt → ${JSON.stringify(name)}`);
  },
  async clipboard() {
    try {
      await navigator.clipboard.writeText('Copied by the test page at ' + new Date().toLocaleTimeString());
      out('#featOut', 'Copied to the clipboard');
    } catch (e) {
      out('#featOut', 'Clipboard API refused: ' + e.message);
    }
  },
  vibrate() { out('#featOut', 'vibrate → ' + (navigator.vibrate ? navigator.vibrate([100, 50, 100]) : 'not supported')); },
};

const tests = Object.assign({}, consoleTests, networkTests, resourceTests, elementTests, speedTests, featureTests);

document.addEventListener('click', (e) => {
  const button = e.target.closest('button[data-run]');
  if (!button) return;
  const name = button.dataset.run;
  console.log(`▶ ${name}`);
  try { tests[name](); } catch (err) { console.error(name, 'threw', err); }
});

// ---------- 9 · Pull to refresh: an inner scroller and a drawing pad that handles touches ----------
function setUpPull() {
  $('#scroller').innerHTML = Array.from({ length: 40 }, (_, i) => `<p>Scrollable line ${i + 1}</p>`).join('');
  const pad = $('#pad');
  const ctx = pad.getContext('2d');
  const color = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#1f5fbf';
  let last = null;
  const point = (e) => { const r = pad.getBoundingClientRect(); return [(e.clientX - r.left) * pad.width / r.width, (e.clientY - r.top) * pad.height / r.height]; };
  pad.addEventListener('pointerdown', (e) => { last = point(e); pad.setPointerCapture(e.pointerId); });
  pad.addEventListener('pointermove', (e) => {
    if (!last) return;
    const p = point(e);
    ctx.strokeStyle = color; ctx.lineWidth = 4; ctx.lineCap = 'round';
    ctx.beginPath(); ctx.moveTo(...last); ctx.lineTo(...p); ctx.stroke();
    last = p;
  });
  pad.addEventListener('pointerup', () => { last = null; });
  ctx.font = '28px system-ui';
  ctx.fillStyle = color;
  ctx.fillText('Draw here', 20, 50);
}

$('#upload')?.addEventListener('change', (e) => {
  const files = [...e.target.files].map((f) => `${f.name} (${f.size} bytes, ${f.type || 'unknown type'})`);
  out('#featOut', 'Chose: ' + (files.join(', ') || 'nothing'));
});

showFacts();
setUpPull();
addEventListener('resize', showFacts);
console.info(`[ready] app.js loaded ${Math.round(performance.now() - window.__testStart)} ms after the head script` +
  (isTestHost ? ' (built-in copy)' : ''));
