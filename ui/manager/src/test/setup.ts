// Runs after @eddi/app-kit's shared setup (vitest.config.ts, `setupFiles`), which
// mocks Keycloak and Monaco and runs the MSW server. What is the Manager's own:
// its i18n, with English bundled so every assertion on English text is
// synchronous.
import "@/i18n/config";
// The shared setup registers the jest-dom matchers; importing them here as well
// is what brings their types into this app's TypeScript project.
import "@testing-library/jest-dom/vitest";
