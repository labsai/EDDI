import { describeNoRawFetch } from "@eddi/app-kit/test/no-raw-fetch";

// @eddi/app-kit has no test runner of its own: the apps host its checks. This
// one guards the refreshing fetch path (Q3 of the 2026-11 review).
describeNoRawFetch();
