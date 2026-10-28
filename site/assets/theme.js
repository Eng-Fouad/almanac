/*
 * Quarkus Almanac: theme.js
 *
 * Puts the colour theme on the page before it paints. The choice is quarkus.io's: 'color-theme' in localStorage,
 * light, dark or system (light when nothing is stored), which becomes data-theme on the root for the stylesheet.
 * The almanac's own earlier key, 'quarkus-almanac-theme' (auto, light or dark), is carried over once.
 */
(() => {
  let theme = null;
  try {
    const store = window.localStorage;
    theme = store.getItem('color-theme');
    const old = store.getItem('quarkus-almanac-theme');
    if (old !== null) {
      if (theme === null && ['auto', 'light', 'dark'].includes(old)) {
        theme = old === 'auto' ? 'system' : old;
        store.setItem('color-theme', theme);
      }
      store.removeItem('quarkus-almanac-theme');
    }
  } catch {
    /* storage may be blocked: light, as on quarkus.io */
  }
  document.documentElement.dataset.theme = theme === 'dark' || theme === 'system' ? theme : 'light';
})();
