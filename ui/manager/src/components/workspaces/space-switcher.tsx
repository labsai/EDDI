import { useTranslation } from "react-i18next";
import { Check, ChevronDown, Users, User, Layers, Inbox, UserCheck, FolderInput } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { ALL_SPACES, useSpaces } from "@/hooks/use-spaces";
import type { Ownership } from "@/lib/api/agents";

/**
 * Chooses which workspace the listings are filtered to, whose resources they
 * show, and — because you create where you are looking — where new ones land.
 *
 * <h3>Why it hides itself</h3> Workspaces are off by default, and on a
 * deployment without them nothing here would change anything, so the switcher
 * does not render rather than teaching a concept the deployment does not use.
 *
 * Every choice is a *narrowing*. The backend scopes every listing to what the
 * caller may see regardless, and asking for a space you cannot reach returns
 * nothing rather than granting it, so nothing here is load-bearing for access.
 */
export function SpaceSwitcher({ className }: { className?: string }) {
  const { t } = useTranslation();
  const { spaces, activeSpace, setActiveSpace, active, hasChoice, ownership, setOwnership, createSpace } = useSpaces();

  if (!hasChoice) return null;

  // The menu calls the personal space "My workspace"; the trigger used
  // `active.label` unconditionally and so showed the raw principal after
  // selecting it — the same space named two different things one click apart.
  const spaceLabel = active
    ? active.kind === "personal"
      ? t("workspaces.personalSpace", "My workspace")
      : active.label
    : t("workspaces.allSpaces", "All workspaces");
  const ownershipLabel =
    ownership === "mine"
      ? t("workspaces.ownership.mine", "Mine")
      : ownership === "shared"
        ? t("workspaces.ownership.shared", "Shared with me")
        : null;
  const label = ownershipLabel ? `${spaceLabel} · ${ownershipLabel}` : spaceLabel;
  const ActiveIcon = active ? (active.kind === "team" ? Users : User) : Layers;

  const createLabel = createSpace
    ? createSpace.kind === "personal"
      ? t("workspaces.personalSpace", "My workspace")
      : createSpace.label
    : t("workspaces.create.default", "your default workspace");

  const ownershipOptions: { value: Ownership; icon: typeof Layers; label: string; testId: string }[] = [
    { value: "", icon: Layers, label: t("workspaces.ownership.any", "Everything I can see"), testId: "ownership-option-any" },
    { value: "mine", icon: UserCheck, label: t("workspaces.ownership.mine", "Mine"), testId: "ownership-option-mine" },
    { value: "shared", icon: Inbox, label: t("workspaces.ownership.shared", "Shared with me"), testId: "ownership-option-shared" },
  ];

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn(
          "flex items-center gap-2 rounded-md border border-border bg-background px-2.5 py-1.5",
          "text-sm text-foreground transition-colors hover:bg-accent",
          "focus:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          className
        )}
        data-testid="space-switcher"
        // The label INCLUDES the active choice. An `aria-label` replaces the
        // element's text as its accessible name, so labelling this "Switch
        // workspace" alone meant a screen-reader user heard the same thing
        // whichever workspace was selected.
        aria-label={t("workspaces.switcherCurrent", "Switch workspace — currently {{space}}", { space: label })}
      >
        <ActiveIcon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="max-w-[14rem] truncate">{label}</span>
        <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" className="min-w-64">
        <DropdownMenuLabel>{t("workspaces.ownership.label", "Show")}</DropdownMenuLabel>
        {ownershipOptions.map((option) => {
          const Icon = option.icon;
          return (
            <DropdownMenuItem
              key={option.testId}
              onClick={() => setOwnership(option.value)}
              data-testid={option.testId}
              aria-current={ownership === option.value ? "true" : undefined}
            >
              <Icon className="me-2 h-4 w-4 text-muted-foreground" aria-hidden="true" />
              <span className="flex-1">{option.label}</span>
              {ownership === option.value && <Check className="ms-2 h-4 w-4" aria-hidden="true" />}
            </DropdownMenuItem>
          );
        })}

        <DropdownMenuSeparator />
        <DropdownMenuLabel>{t("workspaces.switcherLabel", "Switch workspace")}</DropdownMenuLabel>

        <DropdownMenuItem
          onClick={() => setActiveSpace(ALL_SPACES)}
          data-testid="space-option-all"
          aria-current={activeSpace === ALL_SPACES ? "true" : undefined}
        >
          <Layers className="me-2 h-4 w-4 text-muted-foreground" aria-hidden="true" />
          <span className="flex-1">{t("workspaces.allSpaces", "All workspaces")}</span>
          {activeSpace === ALL_SPACES && <Check className="ms-2 h-4 w-4" aria-hidden="true" />}
        </DropdownMenuItem>

        {spaces.map((space) => {
          const Icon = space.kind === "team" ? Users : User;
          return (
            <DropdownMenuItem
              key={space.id}
              onClick={() => setActiveSpace(space.id)}
              data-testid={`space-option-${space.id}`}
              // The tick beside the active entry is aria-hidden, so without this
              // the selection is invisible to assistive tech in the menu too.
              aria-current={activeSpace === space.id ? "true" : undefined}
            >
              <Icon className="me-2 h-4 w-4 text-muted-foreground" aria-hidden="true" />
              <span className="flex-1 truncate">
                {space.kind === "personal"
                  ? t("workspaces.personalSpace", "My workspace")
                  : space.label}
              </span>
              {activeSpace === space.id && <Check className="ms-2 h-4 w-4" aria-hidden="true" />}
            </DropdownMenuItem>
          );
        })}

        <DropdownMenuSeparator />
        {/* Where a new agent, workflow or resource lands follows the workspace in
            view. Saying so here is what makes that discoverable rather than
            surprising. */}
        <p className="flex items-start gap-2 px-2 py-1.5 text-xs text-muted-foreground" data-testid="space-create-hint">
          <FolderInput className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          <span>{t("workspaces.create.hint", "New items are created in {{space}}.", { space: createLabel })}</span>
        </p>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
