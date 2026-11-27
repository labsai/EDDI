/**
 * THE SEAM between the Agent Health UI and the rest of the Manager.
 *
 * Every shared thing the health files need — primitives, `cn`, the API client —
 * is imported here and nowhere else. The health REST client itself lives in
 * `@eddi/app-kit/lib/api/health`, next to the other feature clients, because
 * its mocks (`@eddi/app-kit/test/mocks/health-handlers`) are typed by it and the
 * mock API is app-kit's.
 *
 * Rules for health files:
 *  - import shared Manager code through `./deps` (or `../deps` from a subfolder);
 *  - import the health types and fetchers from `@eddi/app-kit/lib/api/health`;
 *  - import React, react-query, react-i18next, react-router-dom, sonner and
 *    lucide-react directly.
 */
export { Button, Badge, activeLocale } from "@eddi/ui";
export { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
export { Skeleton } from "@eddi/app-kit/components/ui/skeleton";
export { AccessibleDialog } from "@eddi/app-kit/components/ui/accessible-dialog";
export { cn, formatDuration, formatUsd } from "@eddi/app-kit/lib/utils";
export { api, ApiClientError, getErrorMessage, isApiError } from "@eddi/app-kit/lib/api-client";
