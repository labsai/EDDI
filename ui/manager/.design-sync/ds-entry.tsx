// Design-system entry for /design-sync. Re-exports the scoped presentational
// components (src/components/ui + src/components/shared + src/components/layout)
// so the converter bundles exactly this surface — not the whole app.
// Authored input; safe to commit.

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
export {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuLabel,
  DropdownMenuGroup,
  DropdownMenuPortal,
} from "@/components/ui/dropdown-menu";
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
export { ModeSwitcher } from "@/components/shared/mode-switcher";
export { RefetchErrorNotice } from "@/components/shared/refetch-error-notice";
export { ResourceTypeBadge } from "@/components/shared/resource-type-badge";
export { SecretKeyPicker } from "@/components/shared/secret-key-picker";
export { ViewToggle } from "@/components/shared/view-toggle";

// ── layout/ ──────────────────────────────────────────────────────────────────
// The app chrome. AppLayout itself is deliberately NOT exported: it mounts the
// chat drawer, operator drawer and the onboarding tour, which drags Monaco and
// the operator tool-scope graph into the bundle. Sidebar + TopBar are the parts
// a design needs; compose them by hand around a page body.
export { MockDataBanner } from "@/components/layout/mock-data-banner";
export { PageLoader } from "@/components/layout/page-loader";
export { PlatformStatus } from "@/components/layout/platform-status";
export { Sidebar } from "@/components/layout/sidebar";
export { TopBar } from "@/components/layout/top-bar";

// ── preview provider (not a card; used as cfg.provider) ──────────────────────
export { DesignSyncProvider } from "./ds-providers";
