// Gathers everything the DevTools tabs show about the page, for "Copy / Share everything".
// Runs in the page and returns one JSON string; the app adds what only it can see (every
// request, HttpOnly cookies, the server's HTML, external script and stylesheet files).
(function () {
  var MAX_BODY = 200000;
  function cut(s, n) { s = s == null ? '' : String(s); return s.length > n ? s.slice(0, n) + '\n… (' + s.length + ' characters, cut)' : s; }
  function text(html) { var d = document.createElement('div'); d.innerHTML = html; return (d.innerText || d.textContent || '').trim(); }
  function pairs(obj) { var out = []; if (obj) for (var k in obj) if (Object.prototype.hasOwnProperty.call(obj, k)) out.push([k, String(obj[k])]); return out; }
  // Eruda's own settings live in storage too; they aren't the page's.
  function storage(s) {
    var out = [];
    try {
      for (var i = 0; i < s.length; i++) { var k = s.key(i); if (k.indexOf('eruda-') !== 0) out.push([k, cut(s.getItem(k), MAX_BODY)]); }
    } catch (e) { out.push(['(unreadable)', String(e)]); }
    return out;
  }
  // The DevTools' own elements: their panel, the styles Eruda adds to the page, a helper element.
  function isDevtools(el) {
    if (el.id === 'eruda' || (el.classList && el.classList.contains('__chobitsu-hide__'))) return true;
    return el.tagName === 'STYLE' && /eruda/.test(el.textContent.slice(0, 2000));
  }
  var eruda = window.eruda;
  var tool = function (name) { try { return eruda && eruda.get(name); } catch (e) { return null; } };
  var r = { url: location.href, title: document.title, time: new Date().toString(), devtools: !!window.__ibDevtools };

  // Info tab: Eruda's own rows (location, user agent, device, system), plus a few more facts.
  var info = [];
  var infoTool = tool('info');
  (infoTool && infoTool._infos || []).forEach(function (row) {
    if (/sponsor|about/i.test(row.name)) return;
    try { info.push([row.name, text(typeof row.val === 'function' ? row.val() : row.val)]); } catch (e) {}
  });
  var n = navigator, s = screen;
  info.push(['Viewport', innerWidth + ' × ' + innerHeight + ' CSS px, device pixel ratio ' + devicePixelRatio]);
  info.push(['Screen', s.width + ' × ' + s.height]);
  info.push(['Language', (n.languages || [n.language]).join(', ')]);
  info.push(['Color scheme', matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light']);
  info.push(['Referrer', document.referrer || '(none)']);
  info.push(['Charset / mode', document.characterSet + ' / ' + document.compatMode]);
  info.push(['Meta viewport', (document.querySelector('meta[name=viewport]') || {}).content || '(none)']);
  r.info = info;

  // Console: every message (the same text as Copy all with "All" selected).
  try { r.console = window.__ibDevtools && __ibDevtools.consoleText ? __ibDevtools.consoleText() : null; } catch (e) { r.console = 'Console could not be read: ' + e; }

  // Elements: the live DOM, without the DevTools panel itself.
  try {
    var root = document.documentElement.cloneNode(true);
    [].slice.call(root.querySelectorAll('#eruda, .__chobitsu-hide__, style')).forEach(function (el) { if (isDevtools(el)) el.remove(); });
    var dt = document.doctype ? new XMLSerializer().serializeToString(document.doctype) + '\n' : '';
    r.dom = dt + root.outerHTML;
  } catch (e) { r.dom = 'DOM could not be read: ' + e; }

  // Network tab: fetch / XHR requests with headers and bodies.
  var net = tool('network');
  var reqs = (net && net._requests) || {};
  r.network = Object.keys(reqs).map(function (id) {
    var q = reqs[id];
    return {
      method: q.method, url: q.url, status: q.status, type: [q.type, q.subType].filter(Boolean).join('/'),
      size: q.size, time: q.time, reqHeaders: pairs(q.reqHeaders), reqBody: q.data ? cut(q.data, MAX_BODY) : null,
      resHeaders: pairs(q.resHeaders), resBody: q.resTxt ? cut(q.resTxt, MAX_BODY) : null
    };
  });

  // Resources tab.
  r.localStorage = storage(window.localStorage);
  r.sessionStorage = storage(window.sessionStorage);
  r.cookies = document.cookie;
  r.scripts = [].slice.call(document.scripts).map(function (el) {
    return el.src ? { url: el.src } : { inline: cut(el.textContent, MAX_BODY) };
  });
  var styles = [];
  [].slice.call(document.querySelectorAll('link[rel~=stylesheet][href], style')).forEach(function (el) {
    if (isDevtools(el) || (el.closest && el.closest('#eruda'))) return;
    styles.push(el.href ? { url: el.href } : { inline: cut(el.textContent, MAX_BODY) });
  });
  r.styles = styles;
  r.iframes = [].slice.call(document.querySelectorAll('iframe')).map(function (f) { return f.src || '(no src)'; });
  r.images = [].slice.call(document.images).map(function (i) { return i.currentSrc || i.src; }).filter(Boolean);
  return JSON.stringify(r);
})()
