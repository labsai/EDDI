import { describeAppBoundary } from "@eddi/app-kit/test/app-boundary";

// The Manager never imports Workforce code; what both need lives in a package
// (frontend unification plan, phase 5). ESLint flags it at edit time
// (`appBoundary`, ui/eslint.config.base.js); this checks the resolved paths.
describeAppBoundary("manager");
