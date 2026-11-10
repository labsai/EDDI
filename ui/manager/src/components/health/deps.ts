/**
 * THE SEAM between the Agent Health UI and the rest of the Manager.
 *
 * Every shared thing the health files need — primitives, `cn`, the API client,
 * the path-segment helper — is imported here and nowhere else. The frontend
 * unification refactor moves `lib/api/*`, `components/ui/*`, `lib/utils`,
 * `lib/api-client` and friends into `@eddi/app-kit` (and `Button` into
 * `@eddi/ui`); when it lands, this is the ONE file whose import paths change.
 *
 * Rules for health files:
 *  - import shared Manager code through `./deps` (or `../deps` from a subfolder);
 *  - import React, react-query, react-i18next, react-router-dom, sonner and
 *    lucide-react directly — they do not move.
 */
export { Button } from "@/components/ui/button";
export { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
export { Badge } from "@/components/ui/badge";
export { Skeleton } from "@/components/ui/skeleton";
export { AccessibleDialog } from "@/components/ui/accessible-dialog";
export { cn, activeLocale, formatDuration, formatUsd } from "@/lib/utils";
export { api, ApiClientError, getErrorMessage, isApiError } from "@/lib/api-client";
export { seg } from "@/lib/api/path-segment";
