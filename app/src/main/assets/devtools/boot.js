// Loads the bundled DevTools (Eruda) into every page, before the page's own scripts run, so the
// console, network and errors are captured from the very first line. The extra plugin tabs are
// only loaded when DevTools are first opened on the page (see DevtoolsScript.plugins), unless
// the floating button is on. The app fills in the config and the bundles at the two markers.
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

    var pluginsAdded = false;
    function addPlugins() {
      if (pluginsAdded) return;
      pluginsAdded = true;
      cfg.plugins.forEach(function (name) {
        var plugin = self[name];
        if (plugin) try { eruda.add(plugin); } catch (e) { console.warn('[DevTools] ' + name + ': ' + e); }
      });
    }
    // Some plugins measure the page, so they wait for the body.
    function addPluginsWhenReady() {
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addPlugins);
      else addPlugins();
    }
    if (cfg.pluginsBundled) addPluginsWhenReady();

    // The console only draws the messages in view, and skips drawing while it's hidden or still
    // opening; it also drops a redraw asked for within 16 ms of another. That left it blank
    // (black) after opening or switching All / Info / Warning / Error until it was scrolled, so
    // it's redrawn again once things have settled.
    function redrawConsole() {
      var c = eruda.get('console');
      var logger = c && c._logger;
      if (!logger || !logger._renderViewport) return;
      try { logger._renderViewport(); } catch (e) {}
    }
    var redrawTimers = [];
    function redrawSoon() {
      redrawTimers.forEach(clearTimeout);
      redrawTimers = [0, 60, 200, 400].map(function (ms) { return setTimeout(redrawConsole, ms); });
      if (window.requestAnimationFrame) requestAnimationFrame(redrawConsole);
    }
    (function watchConsole() {
      var dt = eruda._devTools;
      if (dt && dt.show) {
        var show = dt.show;
        dt.show = function () { var r = show.apply(this, arguments); redrawSoon(); return r; };
      }
      if (dt && dt.on) dt.on('showTool', function (name) { if (name === 'console') redrawSoon(); });
      var c = eruda.get('console');
      var control = c && c._$control && (c._$control.get ? c._$control.get(0) : c._$control[0]);
      if (control) control.addEventListener('click', redrawSoon);
      window.addEventListener('resize', function () { if (isOpen()) redrawSoon(); });
    })();

    function isOpen() { return !!(eruda._devTools && eruda._devTools._isShow); }
    window.__ibDevtools = {
      isOpen: isOpen,
      // The toolbar button: closes the panel, or opens it on the Console.
      toggle: function () { if (isOpen()) eruda.hide(); else { eruda.show(); eruda.show('console'); } return isOpen(); },
      show: function (tool) { eruda.show(); if (tool) eruda.show(tool); return true; },
      hideIfOpen: function () { if (!isOpen()) return false; eruda.hide(); return true; },
      entryButton: function (on) { if (eruda._entryBtn) on ? eruda._entryBtn.show() : eruda._entryBtn.hide(); },
      // Called by the plugin bundle once the app has loaded it into the page.
      addPlugins: addPluginsWhenReady
    };
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
