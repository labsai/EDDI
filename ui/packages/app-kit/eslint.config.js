import globals from "globals";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";
import { NO_ALWAYS_TRUE_LENGTH, sharedPackage } from "../../eslint.config.base.js";

// @eddi/app-kit is the layer the two signed-in apps (the Manager and Workforce)
// share: the authenticated API client and its TanStack Query hooks, the Keycloak
// session, the shell pieces and the components both render. Unlike the three
// packages below it, it may use the router, call the backend and read
// window.__EDDI_*__ — it is app code, just not one app's. It still never imports
// an app (see sharedPackage in ui/eslint.config.base.js), and @eddi/core,
// @eddi/ui and @eddi/conversation never import it.
export default tseslint.config(
  ...sharedPackage({ frameworkFree: false, react: true }),
  {
    // Same component-export rule the Manager applies to this code (it used to
    // live there): HMR keeps state only for modules that export components.
    files: ["**/*.{ts,tsx}"],
    plugins: { "react-refresh": reactRefresh },
    rules: {
      "react-refresh/only-export-components": [
        "warn",
        { allowConstantExport: true, allowExportNames: ["badgeVariants", "buttonVariants", "useTheme"] },
      ],
    },
  },
  {
    // The mock API and the test and E2E helpers run in Node or Playwright.
    files: ["src/test/**/*.{ts,tsx}"],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      "no-restricted-syntax": ["error", NO_ALWAYS_TRUE_LENGTH],
    },
  },
);
