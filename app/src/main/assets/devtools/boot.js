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
      entryButton: function (on) { if (eruda._entryBtn) on ? eruda._entryBtn.show() : eruda._entryBtn.hide(); }
    };
  }

  function start() {
    try { load.call(window); } catch (e) { window.__ibError = String(e && e.stack || e); }
  }
  // At document start there may be no <html> element yet; Eruda needs one to attach to.
  if (document.documentElement) start();
  else {
    var observer = new MutationObserver(function () {
      if (document.documentElement) { observer.disconnect(); start(); }
    });
    observer.observe(document, { childList: true });
  }
})(/*@CONFIG@*/);
