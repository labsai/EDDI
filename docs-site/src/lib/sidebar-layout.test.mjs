import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  clampWidth,
  DEFAULT_WIDTH,
  LIMITS,
  parseState,
  preloadScript,
  STEP,
  stepWidth,
  STORAGE_KEY,
  widthFromPointer,
} from './sidebar-layout.mjs';

describe('clampWidth', () => {
  it('keeps a width inside the side limits and rounds it', () => {
    assert.equal(clampWidth('left', 10), LIMITS.left[0]);
    assert.equal(clampWidth('left', 9999), LIMITS.left[1]);
    assert.equal(clampWidth('right', 250.6), 251);
  });

  it('falls back to the default for anything that is not a number', () => {
    for (const v of [undefined, null, '300', NaN, Infinity]) assert.equal(clampWidth('left', v), DEFAULT_WIDTH);
  });
});

describe('parseState', () => {
  it('reads a stored state', () => {
    assert.deepEqual(parseState('{"left":260,"right":220,"leftHidden":true,"rightHidden":false}'), {
      left: 260,
      right: 220,
      leftHidden: true,
      rightHidden: false,
    });
  });

  it('survives anything stored under the key', () => {
    const defaults = { left: DEFAULT_WIDTH, right: DEFAULT_WIDTH, leftHidden: false, rightHidden: false };
    for (const raw of [null, '', 'not json', '42', '[1,2]', '{"left":"wide","leftHidden":"yes"}']) {
      assert.deepEqual(parseState(raw), defaults, String(raw));
    }
  });

  it('clamps out-of-range widths instead of trusting them', () => {
    assert.equal(parseState('{"left":5000}').left, LIMITS.left[1]);
  });
});

describe('widthFromPointer', () => {
  it('measures the left pane from the left edge and the right pane from the right edge', () => {
    assert.equal(widthFromPointer('left', 320, 1400), 320);
    assert.equal(widthFromPointer('right', 1100, 1400), 300);
  });

  it('clamps a drag past either limit', () => {
    assert.equal(widthFromPointer('left', 20, 1400), LIMITS.left[0]);
    assert.equal(widthFromPointer('right', 100, 1400), LIMITS.right[1]);
  });
});

describe('stepWidth', () => {
  it('moves the handle with the arrows, which grows the left pane rightwards and the right pane leftwards', () => {
    assert.equal(stepWidth('left', 300, 'ArrowRight'), 300 + STEP);
    assert.equal(stepWidth('left', 300, 'ArrowLeft'), 300 - STEP);
    assert.equal(stepWidth('right', 300, 'ArrowLeft'), 300 + STEP);
    assert.equal(stepWidth('right', 300, 'ArrowRight'), 300 - STEP);
  });

  it('jumps to the limits with Home and End, and ignores other keys', () => {
    assert.equal(stepWidth('right', 300, 'Home'), LIMITS.right[0]);
    assert.equal(stepWidth('right', 300, 'End'), LIMITS.right[1]);
    assert.equal(stepWidth('left', 300, 'Enter'), null);
  });
});

describe('preloadScript', () => {
  // Runs the generated inline script against a fake document, the way the browser would.
  function run(stored) {
    const props = {};
    const attrs = new Set();
    const root = {
      style: { setProperty: (k, v) => (props[k] = v) },
      toggleAttribute: (name, on) => (on ? attrs.add(name) : attrs.delete(name)),
    };
    const localStorage = { getItem: (k) => (k === STORAGE_KEY ? stored : null) };
    new Function('document', 'localStorage', preloadScript())({ documentElement: root }, localStorage);
    return { props, attrs: [...attrs].sort() };
  }

  it('applies the stored layout before first paint', () => {
    assert.deepEqual(run('{"left":260,"right":400,"rightHidden":true}'), {
      props: { '--eddi-left-w': '260px', '--eddi-right-w': '400px' },
      attrs: ['data-eddi-right-hidden'],
    });
  });

  it('applies the defaults when nothing is stored, and never throws', () => {
    assert.deepEqual(run(null).props, { '--eddi-left-w': '300px', '--eddi-right-w': '300px' });
    assert.doesNotThrow(() => new Function('document', 'localStorage', preloadScript())({}, {}));
  });
});
