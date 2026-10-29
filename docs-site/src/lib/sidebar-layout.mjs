// Resizable, hideable sidebars: the pure part. The browser code (LayoutControls.astro) and the
// pre-paint script in astro.config.mjs both use these, so the limits and the stored format exist
// once. Widths are in CSS pixels; a hidden side keeps its width for when it comes back.

/** localStorage key. Per browser only — nothing here leaves the reader's machine. */
export const STORAGE_KEY = 'eddi-docs-layout';

/** Starlight's own default, 18.75rem. */
export const DEFAULT_WIDTH = 300;

/** [min, max] per side: wide enough for the longest labels, narrow enough to leave the page. */
export const LIMITS = { left: [200, 520], right: [180, 480] };

/** One arrow-key press. */
export const STEP = 16;

/** A width within the side's limits; anything that is not a finite number is the default. */
export function clampWidth(side, px) {
  const [min, max] = LIMITS[side];
  if (typeof px !== 'number' || !Number.isFinite(px)) return DEFAULT_WIDTH;
  return Math.round(Math.min(max, Math.max(min, px)));
}

/** Reads the stored state, tolerating anything: a missing, foreign or hand-edited value. */
export function parseState(raw) {
  let data = {};
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : null;
    if (parsed && typeof parsed === 'object') data = parsed;
  } catch {
    // unreadable: defaults
  }
  return {
    left: clampWidth('left', data.left),
    right: clampWidth('right', data.right),
    leftHidden: data.leftHidden === true,
    rightHidden: data.rightHidden === true,
  };
}

/**
 * The width a drag produces. The left pane starts at the viewport's left edge, so its width is
 * the pointer's x; the right pane ends at the right edge, so its width is what lies right of it.
 */
export function widthFromPointer(side, clientX, viewportWidth) {
  return clampWidth(side, side === 'left' ? clientX : viewportWidth - clientX);
}

/**
 * The width after a key press on a resize handle, or null for a key it does not handle. The
 * arrows move the handle: on the left pane → widens it, on the right pane ← does.
 */
export function stepWidth(side, current, key) {
  const [min, max] = LIMITS[side];
  const grow = side === 'left' ? 'ArrowRight' : 'ArrowLeft';
  const shrink = side === 'left' ? 'ArrowLeft' : 'ArrowRight';
  if (key === grow) return clampWidth(side, current + STEP);
  if (key === shrink) return clampWidth(side, current - STEP);
  if (key === 'Home') return min;
  if (key === 'End') return max;
  return null;
}

/** Puts a state on the document: CSS variables for the widths, data attributes for hidden. */
export function applyState(root, state) {
  root.style.setProperty('--eddi-left-w', `${state.left}px`);
  root.style.setProperty('--eddi-right-w', `${state.right}px`);
  root.toggleAttribute('data-eddi-left-hidden', state.leftHidden);
  root.toggleAttribute('data-eddi-right-hidden', state.rightHidden);
}

/**
 * The inline <head> script: applies the stored layout before first paint, so a reader who
 * narrowed or hid a sidebar does not watch it jump back on every page. Built from the functions
 * above (their source is inlined), so it cannot drift from them; it must stay dependency-free.
 */
export function preloadScript() {
  return `(function(){try{var LIMITS=${JSON.stringify(LIMITS)},DEFAULT_WIDTH=${DEFAULT_WIDTH};${clampWidth};${parseState};${applyState};applyState(document.documentElement,parseState(localStorage.getItem(${JSON.stringify(STORAGE_KEY)})));}catch(e){}})();`;
}
