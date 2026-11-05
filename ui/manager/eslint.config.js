import tseslint from "typescript-eslint";
import { reactApp, signedInApp } from "../eslint.config.base.js";

export default tseslint.config(
  // ESLint + typescript-eslint recommended and the two hook rules, shared with
  // ui/chat and ui/workforce (ui/eslint.config.base.js).
  ...reactApp({ ecmaVersion: 2020 }),
  // The react-refresh rule, the app boundary (the Manager never imports
  // Workforce code; what both need lives in @eddi/app-kit) and the rules that
  // keep tests able to fail — shared with ui/workforce.
  ...signedInApp({ forbiddenApps: ["workforce"] }),
);
