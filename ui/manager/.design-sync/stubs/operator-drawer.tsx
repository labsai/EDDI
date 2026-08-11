// Design-system stub for `@/components/operator/operator-drawer`.
//
// WHY THIS EXISTS
// `TopBar` is part of the synced surface, and since the operator launcher moved
// out of `AppLayout` into both shells' headers it statically imports
// `OperatorDrawer`. That drags the whole operator subsystem — the tool-scope
// allow-list (`WRITE_ENDPOINTS`), the activation flow and the operator chat —
// into `_ds_bundle.js`, which is the exact bloat the scoped entry exists to
// avoid. It cost +179 KB when measured.
//
// Lazy-loading does not help: the converter emits a single IIFE
// (`.ds-sync/lib/bundle.mjs`, `format: 'iife'`), and esbuild cannot code-split
// that format, so a dynamic `import()` is inlined all the same. Verified, not
// assumed.
//
// So the converter resolves the module to this file instead, via an exact
// `paths` entry in `tsconfig.ds-bundle.json` — the file `config.json` points
// `cfg.tsconfig` at. `tsconfig.design-sync.json` extends it for type-checking,
// so `tsc -b` validates this stub against TopBar's actual usage — if the real
// component's signature changes, the build fails here rather than at sync time.
//
// WHAT IT RENDERS
// The launcher button only, in its resting state, matching the real markup so
// `TopBar` looks right in the design system. The drawer panel is behaviour, not
// design surface: it needs the operator config, a chat transcript and an
// approval stream, none of which exist in a preview.
import { Sparkles } from "lucide-react";

/**
 * Stub of the real `OperatorDrawer`. Same signature (no props), so it is a
 * drop-in for TopBar. Inert by design — clicking it does nothing.
 */
export function OperatorDrawer() {
  return (
    <div className="relative">
      <button
        type="button"
        className="relative flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
        title="Platform Operator"
        aria-label="Platform Operator"
        aria-expanded={false}
        data-testid="operator-drawer-fab"
      >
        <Sparkles className="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
  );
}
