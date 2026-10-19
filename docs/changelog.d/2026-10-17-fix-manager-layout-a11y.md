## 📱 fix(manager): every screen size, RTL, and state handling (2026-10-17)

**Repo:** EDDI (`fix/manager-layout-a11y`)

Half of a review of both UIs for click latency, every screen size and every
state. This PR holds the Manager's layout, RTL, state-handling and
accessibility fixes; the click-latency fixes and the Chat UI are in the
companion PR on `fix/ui-click-responsiveness`. The two share no file and build
independently.

### Every screen size and RTL (Manager)

- The chat drawer was a fixed 420px column, wider than a phone screen, and its
  Close button was unreachable. It is now a full-screen overlay below `md`, and
  Escape closes it. The operator panel no longer runs off the start edge on
  phones.
- Hover-only card and row actions (the kebab menus, deletes) did not exist on
  touch screens. The new `reveal-on-touch` utility in `index.css` shows them
  under `(hover: none)` and on keyboard focus, with 32px targets.
- Agent Studio rendered the mobile tabs and the desktop chat panel together at
  768–1023px, mounting two chat panels. Both now switch at `lg`.
- The top-bar breadcrumb truncates instead of pushing the controls off screen.
  List tables scroll horizontally and hide their secondary columns below `md`.
  App shells use `h-dvh`, custom modals cap at `100dvh-2rem`, the main gutter is
  `p-4 md:p-6`, page headers wrap, and labelled three-column grids stack on
  phones.
- RTL: switch thumbs no longer slide out of their track, directional arrows and
  chevrons are mirrored, the toaster sits at the inline end, and the onboarding
  badge is centred.

### State handling and accessibility (Manager)

- The API-call header/query/body editor could silently overwrite a value: after
  a delete, Add reused an existing key name, and renaming on every keystroke
  merged rows. It now uses `nextFreeKey` and `RenamableKeyInput`, as the
  connection and rules editors already did.
- Escape inside the agent picker or a secret picker no longer closes and wipes
  the schedule form or the create-channel wizard (`defaultPrevented` is
  honoured). Neither form now discards edits on a backdrop click or Escape;
  the property-setter long-value modal does the same.
- Inline group-config edits are covered by the unsaved-changes guard. The
  version diff dialog uses `AccessibleDialog`.
- An Enter that confirms an IME composition (Chinese, Japanese, Korean) no
  longer commits half-typed text in the chip and tag inputs.
- The dashboard shows an error with a retry instead of "nothing here" when a
  load fails. Resource type counts no longer read "0" while loading. The A2A
  copy buttons report a clipboard failure.
- About 40 icon-only buttons gained accessible names. A count-plus-word
  concatenation was replaced with plural keys in all 11 locales (including
  `_many` and the Arabic categories).

