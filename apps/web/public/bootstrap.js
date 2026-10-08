// Runs synchronously before first paint; theme storage matches useAppStore.
(function () {
  var COLORS = { dark: '#0b0907', light: '#f4ede0', noir: '#0d0d0f', matrix: '#020403' };
  function systemTheme() {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }
  try {
    var raw = localStorage.getItem('cinemarchive-library');
    var state = raw && JSON.parse(raw).state;
    var theme = state && state.theme;
    var unlocked = (state && state.unlockedThemes) || ['dark', 'light'];
    // themeMode 'system' always re-resolves against the live OS
    // preference rather than trusting whatever was last persisted.
    if (state && state.themeMode === 'system') theme = systemTheme();
    if (!Object.prototype.hasOwnProperty.call(COLORS, theme) || unlocked.indexOf(theme) === -1) theme = systemTheme();
    document.documentElement.setAttribute('data-theme', theme);
    if (theme !== 'dark') {
      var m = document.querySelector('meta[name="theme-color"]');
      if (m) m.setAttribute('content', COLORS[theme]);
    }
  } catch {
    var fallback = systemTheme();
    document.documentElement.setAttribute('data-theme', fallback);
    if (fallback !== 'dark') {
      var m2 = document.querySelector('meta[name="theme-color"]');
      if (m2) m2.setAttribute('content', COLORS[fallback]);
    }
  }
})();

// Consume the GitHub Pages fallback once, before the router reads the URL.
;(function () {
  try {
    var redirect = sessionStorage.getItem("redirect");
    sessionStorage.removeItem("redirect");
    if (!redirect) return;
    var target = new URL(redirect, location.href);
    if (target.origin === location.origin && target.href !== location.href) {
      history.replaceState(null, "", target.href);
    }
  } catch {
    // Restricted storage or a malformed/stale redirect must not break startup.
  }
})();
