// Loads the bundled DevTools (Eruda and its plugins) into every page, before the page's own
// scripts run, so the console, network and errors are captured from the very first line.
// The app fills in the config and the bundles at the two markers (see DevtoolsScript.kt).
(function (cfg) {
  if (window.top !== window || window.__ibDevtools || window.__ibLoading) return;
  window.__ibLoading = true;

  function load() {
    // Pages that use AMD or CommonJS loaders would otherwise swallow the bundles as modules.
    var define, module, exports;
    /*@BUNDLES@*/
    var eruda = self.eruda;
    if (!eruda) return;
    eruda.init({ useShadowDom: true, autoScale: true, defaults: { displaySize: cfg.displaySize } });
    if (!cfg.entryButton && eruda._entryBtn) eruda._entryBtn.hide();

    function addPlugins() {
      cfg.plugins.forEach(function (name) {
        var plugin = self[name];
        if (plugin) try { eruda.add(plugin); } catch (e) { console.warn('[DevTools] ' + name + ': ' + e); }
      });
    }
    // Some plugins measure the page, so they wait for the body.
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addPlugins);
    else addPlugins();

    function isOpen() { return !!(eruda._devTools && eruda._devTools._isShow); }
    window.__ibDevtools = {
      isOpen: isOpen,
      toggle: function () { if (isOpen()) eruda.hide(); else eruda.show(); return isOpen(); },
      show: function (tool) { eruda.show(); if (tool) eruda.show(tool); return true; },
      hideIfOpen: function () { if (!isOpen()) return false; eruda.hide(); return true; },
      entryButton: function (on) { if (eruda._entryBtn) on ? eruda._entryBtn.show() : eruda._entryBtn.hide(); },
      consoleText: consoleText,
      consoleCount: function () { return consoleLogs().length; }
    };
    addCopyAllButton();

    // --- Everything in the console as plain text, for copying or sharing in one go ---

    function consoleLogs() {
      var c = eruda.get('console');
      return (c && c._logger && c._logger.logs) || [];
    }

    function fmt(v, depth, seen) {
      var t = typeof v;
      if (v === null) return 'null';
      if (t === 'string') return depth ? JSON.stringify(v) : v;
      if (t === 'undefined') return 'undefined';
      if (t === 'function') return 'ƒ ' + (v.name || 'anonymous') + '()';
      if (t !== 'object') return String(v);
      if (v instanceof Error) {
        var head = (v.name || 'Error') + ': ' + v.message;
        return v.stack ? (v.stack.indexOf(v.message) >= 0 ? v.stack : head + '\n' + v.stack) : head;
      }
      if (typeof Node !== 'undefined' && v instanceof Node) {
        var html = v.outerHTML || v.nodeName;
        return html.length > 500 ? html.slice(0, 500) + '…' : html;
      }
      if (seen.indexOf(v) >= 0) return '[Circular]';
      if (depth > 4) return Array.isArray(v) ? '[…]' : '{…}';
      seen = seen.concat([v]);
      try {
        if (Array.isArray(v)) {
          return '[' + v.slice(0, 100).map(function (x) { return fmt(x, depth + 1, seen); }).join(', ') + (v.length > 100 ? ', …' : '') + ']';
        }
        var keys = Object.keys(v);
        var name = v.constructor && v.constructor.name && v.constructor.name !== 'Object' ? v.constructor.name + ' ' : '';
        return name + '{' + keys.slice(0, 100).map(function (k) { return k + ': ' + fmt(v[k], depth + 1, seen); }).join(', ') +
          (keys.length > 100 ? ', …' : '') + '}';
      } catch (e) { return String(v); }
    }

    // console.log('%s is %d', …) style messages, with %c styling dropped.
    function line(args) {
      args = Array.prototype.slice.call(args || []);
      var out = [];
      if (typeof args[0] === 'string' && /%[sdifoOc]/.test(args[0])) {
        var rest = args.slice(1);
        out.push(args[0].replace(/%([sdifoOc%])/g, function (m, k) {
          if (k === '%') return '%';
          if (!rest.length) return m;
          var a = rest.shift();
          if (k === 'c') return '';
          if (k === 'd' || k === 'i') return String(parseInt(a, 10));
          if (k === 'f') return String(parseFloat(a));
          return fmt(a, k === 's' ? 0 : 1, []);
        }));
        args = rest;
      } else if (args.length) {
        out.push(fmt(args.shift(), 0, []));
      }
      args.forEach(function (a) { out.push(fmt(a, 0, [])); });
      return out.join(' ');
    }

    var labels = { input: '> ', output: '< ', warn: '[warn] ', error: '[error] ', info: '[info] ', debug: '[debug] ' };
    function consoleText() {
      var logs = consoleLogs();
      var text = logs.map(function (log) {
        var body;
        try { body = log.args && log.args.length ? line(log.args) : (log.text ? log.text() : ''); } catch (e) { body = log.text ? log.text() : ''; }
        var count = log.count > 1 ? '  (×' + log.count + ')' : '';
        return (labels[log.type] || '') + body + count;
      }).join('\n');
      return 'Console: ' + location.href + '\n' + new Date().toString() + '\n' + logs.length + ' messages\n\n' + text + '\n';
    }

    function copyText(text, done) {
      function fallback() {
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.cssText = 'position:fixed;top:0;left:0;opacity:0;';
        document.documentElement.appendChild(area);
        area.select();
        var ok = false;
        try { ok = document.execCommand('copy'); } catch (e) {}
        area.remove();
        done(ok);
      }
      if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(text).then(function () { done(true); }, fallback);
      else fallback();
    }

    // A "Copy all" button in the Console tab's toolbar, next to Eruda's own (one message) copy button.
    function addCopyAllButton() {
      var c = eruda.get('console');
      var control = c && c._$control && (c._$control.get ? c._$control.get(0) : c._$control[0]);
      if (!control) return;
      var button = document.createElement('span');
      button.textContent = 'Copy all';
      button.title = 'Copy every console message';
      button.style.cssText = 'cursor:pointer;font-size:12px;padding:0 8px;margin-left:4px;border:1px solid currentColor;border-radius:4px;opacity:.85;line-height:18px;';
      button.addEventListener('click', function (e) {
        e.stopPropagation();
        var n = consoleLogs().length;
        copyText(consoleText(), function (ok) {
          if (eruda._devTools && eruda._devTools.notify) eruda._devTools.notify(ok ? 'Copied ' + n + ' messages' : 'Couldn\'t copy; use ⋮ ▸ Copy console log', { icon: ok ? 'success' : 'error' });
        });
      });
      var filter = control.querySelector('.eruda-filter');
      if (filter) control.insertBefore(button, filter); else control.appendChild(button);
    }
  }

  function start() {
    try { load.call(window); } catch (e) { window.__ibError = String(e && e.stack || e); }
  }
  // At document start there may be no <html> or <head> yet, and Eruda puts its styles in the
  // head. The parser makes the head before any of the page's own scripts can run, so waiting
  // for it still catches everything.
  var started = false;
  function startOnce() {
    if (started) return;
    started = true;
    observer.disconnect();
    start();
  }
  var observer = new MutationObserver(function () { if (document.head) startOnce(); });
  if (document.head) startOnce();
  else {
    observer.observe(document, { childList: true, subtree: true });
    // Documents that never get a head (plain text, images, SVG): give HTML ones a head of their own.
    document.addEventListener('DOMContentLoaded', function () {
      if (started) return;
      var root = document.documentElement;
      if (root && !document.head && root.namespaceURI === 'http://www.w3.org/1999/xhtml') {
        root.insertBefore(document.createElement('head'), root.firstChild);
      }
      if (document.head) startOnce(); else observer.disconnect();
    });
  }
})(/*@CONFIG@*/);
