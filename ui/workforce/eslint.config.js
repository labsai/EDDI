import tseslint from "typescript-eslint";
import { reactApp, signedInApp } from "../eslint.config.base.js";

export default tseslint.config(
  // ESLint + typescript-eslint recommended and the two hook rules, shared with
  // ui/manager and ui/chat (ui/eslint.config.base.js).
  ...reactApp({ ecmaVersion: 2020 }),
  // The react-refresh rule, the app boundary (Workforce never imports Manager
  // code; what both need lives in @eddi/app-kit) and the rules that keep tests
  // able to fail — shared with ui/manager.
  ...signedInApp({ forbiddenApps: ["manager"] }),
);
