/**
 * Self-hosted Monaco wiring, as a side-effect module.
 *
 * ## Why this is not in `main.tsx`
 *
 * `import * as monaco from "monaco-editor"` is a ~7 MB value import. Sitting in
 * the entry module it landed in the entry chunk, so every user downloaded the
 * whole editor — language contributions, tokenizers and all — to see a dashboard
 * that renders no editor at all. It was the single largest thing in the bundle
 * by an order of magnitude.
 *
 * Importing it *here*, and importing this module from the four components that
 * actually render an editor, moves Monaco into those components' route chunk.
 * It now downloads when someone opens a resource editor, and not before.
 *
 * ## Why a module-scope side effect rather than an `initMonaco()` call
 *
 * `@monaco-editor/react` resolves Monaco when `<Editor>` first mounts. If
 * `loader.config()` has not run by then it falls back to fetching Monaco from
 * the **jsDelivr CDN** — an off-origin request this app does not make, and one
 * that simply fails in an air-gapped EDDI deployment. So the configuration has
 * to be guaranteed complete before any consumer renders.
 *
 * A module-scope side effect gives exactly that guarantee for free: ES modules
 * evaluate their dependencies before their own body, and `React.lazy` resolves a
 * whole chunk before rendering anything from it. An `initMonaco()` the component
 * had to remember to await would be one forgotten call away from the CDN
 * fallback. Import this module for its side effect and there is nothing to
 * forget:
 *
 * ```ts
 * import "@/lib/monaco-setup";
 * import Editor from "@monaco-editor/react";
 * ```
 */

import * as monaco from "monaco-editor";
import { loader } from "@monaco-editor/react";

// Monaco needs web workers for language intelligence (validation,
// autocompletion, formatting). Without this the JSON editor loses schema
// validation and smart features. Vite handles the worker bundling via the
// `?worker` import syntax. Monaco 0.56+'s exports map routes `./*` →
// `./esm/vs/*.js`, so we drop the `esm/vs/` prefix.
import editorWorker from "monaco-editor/editor/editor.worker?worker";
import jsonWorker from "monaco-editor/language/json/json.worker?worker";

self.MonacoEnvironment = {
  getWorker(_: string, label: string) {
    if (label === "json") return new jsonWorker();
    return new editorWorker();
  },
};

loader.config({ monaco });
