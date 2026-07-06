// Design-system entry for /design-sync. Re-exports the scoped presentational
// components (src/components/ui + src/components/shared) so the converter bundles
// exactly this surface — not the whole app. Authored input; safe to commit.

// ── ui/ ────────────────────────────────────────────────────────────────────
export { AccessibleDialog } from "@/components/ui/accessible-dialog";
export { AlertDialog } from "@/components/ui/alert-dialog";
export { Badge, badgeVariants } from "@/components/ui/badge";
export { Button, buttonVariants } from "@/components/ui/button";
export {
  Card,
  CardHeader,
  CardFooter,
  CardTitle,
  CardDescription,
  CardContent,
} from "@/components/ui/card";
export { ErrorBoundary } from "@/components/ui/error-boundary";
export { Input } from "@/components/ui/input";
export { Skeleton } from "@/components/ui/skeleton";
export { StreamBadge } from "@/components/ui/stream-badge";
export { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";

// ── shared/ ──────────────────────────────────────────────────────────────────
export { ActionBadge } from "@/components/shared/action-badge";
export { AgentPicker } from "@/components/shared/agent-picker";
export { BackLink } from "@/components/shared/back-link";
export { CommandPalette } from "@/components/shared/command-palette";
export { CreateOrWizardDialog } from "@/components/shared/create-or-wizard-dialog";
export { EmptyState } from "@/components/shared/empty-state";
export { ErrorState } from "@/components/shared/error-state";
export { InfiniteScrollSentinel } from "@/components/shared/infinite-scroll-sentinel";
export { ResourceTypeBadge } from "@/components/shared/resource-type-badge";
export { SecretKeyPicker } from "@/components/shared/secret-key-picker";
export { ViewToggle } from "@/components/shared/view-toggle";

// ── preview provider (not a card; used as cfg.provider) ──────────────────────
export { DesignSyncProvider } from "./ds-providers";
