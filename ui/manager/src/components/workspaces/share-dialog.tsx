import { useCallback, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Globe, Loader2, Lock, Trash2, Users, UserPlus } from "lucide-react";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { ApiClientError, getErrorMessage } from "@/lib/api-client";
import { agentKeys } from "@/lib/query-keys";
import {
  ACCESS_LEVELS,
  getShareInfo,
  levelIncludes,
  revokeShare,
  setResourceVisibility,
  shareResource,
  type AccessLevel,
  type ResourceVisibility,
  type ShareResult,
} from "@/lib/api/sharing";
import { describeSpace, isUserSubject, parseSubjectInput } from "@/lib/spaces";

/** Which mutation produced a {@link ShareResult}, so the summary can name it. */
type ShareAction = "share" | "revoke" | "visibility";

interface ShareDialogProps {
  open: boolean;
  onClose: () => void;
  /** The resource being shared — an agent id, a workflow id, and so on. */
  resourceId: string;
  /** Shown in the title so the user knows what they are about to share. */
  resourceName?: string;
}

/**
 * Share one resource with people and teams.
 *
 * <h3>The consequence line is the point</h3> Sharing an agent cascades through
 * the workflows, rule sets, LLM configs and output sets it references — it has
 * to, or the recipient gets a name pointing at documents they cannot open. That
 * is invisible in the request and surprising in the result, so every outcome
 * here says how many resources it actually touched and names what it declined
 * to touch. "Shared" and "shared, except three things that belong to a
 * colleague" are different facts and the user needs both.
 */
export function ShareDialog({ open, onClose, resourceId, resourceName }: ShareDialogProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const [subjectInput, setSubjectInput] = useState("");
  const [level, setLevel] = useState<AccessLevel>("USE");
  const [busy, setBusy] = useState(false);
  /**
   * The subject an ownership transfer has been confirmed for, or null.
   *
   * A subject rather than a boolean because it has to be *bound* to what the
   * warning named: with a flag, arming the confirmation for "bob" and then
   * retyping "carol" handed carol ownership under a warning displayed for bob.
   */
  const [confirmedSubject, setConfirmedSubject] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<{ result: ShareResult; action: ShareAction } | null>(null);

  const {
    data: info,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ["shares", resourceId],
    queryFn: () => getShareInfo(resourceId),
    enabled: open && !!resourceId,
  });

  const isOwner = levelIncludes(info?.callerLevel, "OWN");

  const afterChange = useCallback(
    async (result: ShareResult, action: ShareAction) => {
      setLastResult({ result, action });
      await refetch();
      // Owner and visibility ride on the descriptor, so anything showing a badge
      // for this resource is now stale. `agentKeys.all` prefix-matches both
      // descriptor listings; the detail query is rooted at "agent" (singular)
      // and is NOT covered by it — invalidating only the plural key was a no-op
      // for the page a user lands on straight after sharing.
      await queryClient.invalidateQueries({ queryKey: agentKeys.all });
      await queryClient.invalidateQueries({ queryKey: agentKeys.detail(resourceId) });
      await queryClient.invalidateQueries({ queryKey: agentKeys.descriptor(resourceId) });
    },
    [refetch, queryClient, resourceId]
  );

  const handleShare = useCallback(async () => {
    // Validation first. Arming the confirmation before this showed a destructive
    // "Confirm transfer" button warning about handing ownership to nobody, for
    // an empty or malformed subject.
    const parsed = parseSubjectInput(subjectInput);
    if ("error" in parsed) {
      toast.error(
        parsed.error === "unknown-prefix"
          ? t("workspaces.share.unknownPrefix", "Use 'user:' or 'team:' — or just a name for a person.")
          : t("workspaces.share.subjectRequired", "Enter a person or team to share with.")
      );
      return;
    }

    // Handing someone OWN lets them delete the resource and re-share it to
    // anyone, and there is no "undo" that does not depend on them cooperating.
    // Every other level is reversible by the owner alone, so this is the one
    // that gets a second look — and the confirmation is bound to the subject it
    // was shown for, so retyping the name withdraws it.
    if (level === "OWN" && confirmedSubject !== parsed.subject) {
      setConfirmedSubject(parsed.subject);
      return;
    }
    setBusy(true);
    try {
      const result = await shareResource(resourceId, parsed.subject, level);
      await afterChange(result, "share");
      setSubjectInput("");
      setConfirmedSubject(null);
      toast.success(t("workspaces.share.shared", "Shared"));
    } catch (e) {
      toast.error(getErrorMessage(e));
    } finally {
      setBusy(false);
    }
  }, [subjectInput, level, confirmedSubject, resourceId, afterChange, t]);

  const handleRevoke = useCallback(
    async (subject: string) => {
      setBusy(true);
      try {
        const result = await revokeShare(resourceId, subject);
        await afterChange(result, "revoke");
        toast.success(t("workspaces.share.revoked", "Access removed"));
      } catch (e) {
        toast.error(getErrorMessage(e));
      } finally {
        setBusy(false);
      }
    },
    [resourceId, afterChange, t]
  );

  const handleVisibility = useCallback(
    async (visibility: ResourceVisibility) => {
      setBusy(true);
      try {
        const result = await setResourceVisibility(resourceId, visibility);
        await afterChange(result, "visibility");
        toast.success(t("workspaces.share.visibilityUpdated", "Visibility updated"));
      } catch (e) {
        toast.error(getErrorMessage(e));
      } finally {
        setBusy(false);
      }
    },
    [resourceId, afterChange, t]
  );

  const title = resourceName
    ? t("workspaces.share.titleNamed", "Share “{{name}}”", { name: resourceName })
    : t("workspaces.share.title", "Share");

  const grants = useMemo(() => info?.grants ?? [], [info]);

  /** Whether the button is currently asking rather than acting. */
  const awaitingOwnerConfirmation = level === "OWN" && confirmedSubject !== null;

  return (
    <AccessibleDialog open={open} onClose={onClose} title={title} maxWidth="max-w-lg" testId="share-dialog">
      {isLoading && (
        <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          {t("common.loading", "Loading…")}
        </div>
      )}

      {error && (
        <p className="py-4 text-sm text-destructive" role="alert">
          {friendlyError(t, error)}
        </p>
      )}

      {/* `info` deliberately does not render alongside an error. TanStack keeps
          the last good data through a failed refetch, so rendering both showed
          a live-looking grant list and working buttons underneath the message
          saying the request had failed — reachable whenever a revoke or a
          visibility change strips the caller's own access mid-session. */}
      {info && !error && (
        <div className="space-y-5">
          <OwnerLine ownerId={info.ownerId ?? null} spaceId={info.spaceId ?? null} />

          {!isOwner && (
            <p className="rounded-md border border-border bg-muted/40 p-3 text-sm text-muted-foreground">
              {t(
                "workspaces.share.notOwner",
                "Only the owner can change who has access. Ask them if you need this shared more widely."
              )}
            </p>
          )}

          {isOwner && (
            <>
              <VisibilityChooser current={info.visibility} busy={busy} onChange={handleVisibility} />

              <section className="space-y-2">
                <h3 className="text-sm font-medium">
                  {t("workspaces.share.peopleAndTeams", "People and teams")}
                </h3>

                <div className="flex flex-col gap-2 sm:flex-row">
                  <Input
                    value={subjectInput}
                    onChange={(e) => {
                      setSubjectInput(e.target.value);
                      setConfirmedSubject(null);
                    }}
                    placeholder={t("workspaces.share.subjectPlaceholder", "name@example.com or team:engineering")}
                    aria-label={t("workspaces.share.subjectLabel", "Person or team")}
                    data-testid="share-subject-input"
                    onKeyDown={(e) => {
                      if (e.key !== "Enter" || busy) return;
                      e.preventDefault();
                      // Enter arms an ownership transfer but never completes
                      // one: two quick presses would otherwise sail straight
                      // through the confirmation the second press is meant to
                      // read. Confirming takes a deliberate click.
                      if (level === "OWN" && confirmedSubject) return;
                      void handleShare();
                    }}
                  />
                  <select
                    value={level}
                    onChange={(e) => {
                      setLevel(e.target.value as AccessLevel);
                      setConfirmedSubject(null);
                    }}
                    aria-label={t("workspaces.share.levelLabel", "Access level")}
                    data-testid="share-level-select"
                    className="h-9 rounded-md border border-border bg-background px-2 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    {ACCESS_LEVELS.map((l) => (
                      <option key={l} value={l}>
                        {levelLabel(t, l)}
                      </option>
                    ))}
                  </select>
                  <Button
                    onClick={() => void handleShare()}
                    disabled={busy}
                    variant={awaitingOwnerConfirmation ? "destructive" : "primary"}
                    data-testid="share-submit"
                  >
                    <UserPlus className="me-2 h-4 w-4" aria-hidden="true" />
                    {awaitingOwnerConfirmation
                      ? t("workspaces.share.confirmOwner", "Confirm transfer")
                      : t("workspaces.share.add", "Share")}
                  </Button>
                </div>

                <p className="text-xs text-muted-foreground">{levelHint(t, level)}</p>

                {awaitingOwnerConfirmation && (
                  <p className="text-xs text-destructive" role="alert" data-testid="share-owner-warning">
                    {t(
                      "workspaces.share.ownerWarning",
                      "They will be able to delete this and share it with anyone. You cannot take that back on your own."
                    )}
                  </p>
                )}

                {grants.length === 0 ? (
                  <p className="py-2 text-sm text-muted-foreground">
                    {t("workspaces.share.noGrants", "Not shared with anyone yet.")}
                  </p>
                ) : (
                  <ul className="divide-y divide-border rounded-md border border-border">
                    {grants.map((grant) => (
                      <li key={grant.subject} className="flex items-center gap-3 px-3 py-2">
                        {isUserSubject(grant.subject) ? (
                          <UserPlus className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                        ) : (
                          <Users className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                        )}
                        {/* The person/team distinction was icon-only, and the icon
                            is aria-hidden — so a screen reader heard two identical
                            rows. */}
                        <span className="sr-only">
                          {isUserSubject(grant.subject)
                            ? t("workspaces.share.subjectIsPerson", "Person")
                            : t("workspaces.share.subjectIsTeam", "Team")}
                        </span>
                        <span className="flex-1 truncate text-sm">{describeSpace(grant.subject)}</span>
                        <Badge variant="secondary">{levelLabel(t, grant.level)}</Badge>
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={busy}
                          onClick={() => void handleRevoke(grant.subject)}
                          aria-label={t("workspaces.share.revokeFor", "Stop sharing with {{subject}}", {
                            subject: describeSpace(grant.subject) ?? grant.subject,
                          })}
                        >
                          <Trash2 className="h-4 w-4" aria-hidden="true" />
                        </Button>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          )}

          {lastResult && <CascadeSummary result={lastResult.result} action={lastResult.action} />}
        </div>
      )}
    </AccessibleDialog>
  );
}

function OwnerLine({ ownerId, spaceId }: { ownerId: string | null; spaceId: string | null }) {
  const { t } = useTranslation();
  const space = describeSpace(spaceId);
  return (
    <p className="text-sm text-muted-foreground" data-testid="share-owner-line">
      {ownerId
        ? t("workspaces.share.ownedBy", "Owned by {{owner}}", { owner: ownerId })
        : t("workspaces.share.unowned", "No recorded owner")}
      {space ? ` · ${t("workspaces.share.inSpace", "in {{space}}", { space })}` : ""}
    </p>
  );
}

function VisibilityChooser({
  current,
  busy,
  onChange,
}: {
  current: ResourceVisibility;
  busy: boolean;
  onChange: (v: ResourceVisibility) => void;
}) {
  const { t } = useTranslation();
  const options: { value: ResourceVisibility; icon: typeof Lock; label: string; hint: string }[] = [
    {
      value: "private",
      icon: Lock,
      label: t("workspaces.visibility.private", "Private"),
      hint: t("workspaces.visibility.privateHint", "Only you and people you share it with."),
    },
    {
      value: "space",
      icon: Users,
      label: t("workspaces.visibility.space", "Workspace"),
      hint: t("workspaces.visibility.spaceHint", "Everyone in this resource's workspace."),
    },
    {
      value: "published",
      icon: Globe,
      label: t("workspaces.visibility.published", "Published"),
      hint: t("workspaces.visibility.publishedHint", "Everyone with access to this deployment."),
    },
  ];

  return (
    <section className="space-y-2">
      <h3 className="text-sm font-medium">{t("workspaces.share.visibility", "Visibility")}</h3>
      <div className="grid gap-2 sm:grid-cols-3">
        {options.map((opt) => {
          const Icon = opt.icon;
          const selected = current === opt.value;
          return (
            <button
              key={opt.value}
              type="button"
              disabled={busy}
              onClick={() => onChange(opt.value)}
              aria-pressed={selected}
              data-testid={`visibility-${opt.value}`}
              className={[
                "flex flex-col gap-1 rounded-md border p-3 text-start transition-colors",
                "focus:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60",
                selected ? "border-primary bg-primary/5" : "border-border hover:bg-accent",
              ].join(" ")}
            >
              <span className="flex items-center gap-2 text-sm font-medium">
                <Icon className="h-4 w-4" aria-hidden="true" />
                {opt.label}
              </span>
              <span className="text-xs text-muted-foreground">{opt.hint}</span>
            </button>
          );
        })}
      </div>
    </section>
  );
}

/**
 * What the last change actually did.
 *
 * Sharing an agent reaches the whole config graph beneath it, which the user
 * never asked for explicitly — so say so. And when something was left alone
 * because it belongs to somebody else, name it: silence there reads as success
 * and leaves a recipient with a half-shared agent nobody knows is half-shared.
 */
function CascadeSummary({ result, action }: { result: ShareResult; action: ShareAction }) {
  const { t } = useTranslation();
  const skipped = result.skipped ?? [];
  const updated = result.updated ?? [];

  return (
    <div className="space-y-2 rounded-md border border-border bg-muted/30 p-3" data-testid="share-cascade-summary">
      <p className="text-sm">
        {/* Which verb matters: "Applied to 3 resources" after a revoke reads as
            though access had been granted. */}
        {action === "revoke"
          ? t("workspaces.share.cascadeRevoked", "Removed from {{count}} resource", { count: updated.length })
          : t("workspaces.share.cascadeApplied", "Applied to {{count}} resource", { count: updated.length })}
      </p>
      {skipped.length > 0 && (
        <div className="space-y-1">
          <p className="text-sm text-amber-700 dark:text-amber-400">
            {t("workspaces.share.cascadeSkipped", "{{count}} resource left unchanged — you do not own it", {
              count: skipped.length,
            })}
          </p>
          <ul className="list-inside list-disc text-xs text-muted-foreground">
            {skipped.slice(0, MAX_LISTED).map((target) => (
              <li key={target.id} className="truncate">
                {target.name ?? target.id}
              </li>
            ))}
            {/* A list of five under a count of twelve reads as the whole list.
                Silent truncation is the one thing this summary exists not to
                do. */}
            {skipped.length > MAX_LISTED && (
              <li className="list-none italic">
                {t("workspaces.share.andMore", "and {{count}} more", {
                  count: skipped.length - MAX_LISTED,
                })}
              </li>
            )}
          </ul>
        </div>
      )}
    </div>
  );
}

/**
 * The level as a human reads it.
 *
 * Falls through to the raw value rather than returning nothing. `level` is
 * typed, but it arrives over the wire — a backend that grows a fifth level
 * would otherwise render an empty badge next to somebody's name, which reads as
 * "no access" rather than as "a level this UI does not know yet".
 */
/**
 * The message for a failed read of the sharing state.
 *
 * A 403 here is not a fault, it is the USE/VIEW split doing its job: someone
 * shared an agent so you could *talk to* it, which deliberately does not let
 * you read how it was built. The server's own wording is about access levels
 * and reads as an error; this says what actually happened.
 */
function friendlyError(t: (k: string, d: string) => string, error: unknown): string {
  if (error instanceof ApiClientError && error.status === 403) {
    return t(
      "workspaces.share.useOnly",
      "You can chat with this, but its configuration has not been shared with you — so there is nothing here to manage."
    );
  }
  return getErrorMessage(error);
}

/** How many skipped resources to name before summarising the rest. */
const MAX_LISTED = 5;

function levelLabel(t: (k: string, d: string) => string, level: AccessLevel): string {
  switch (level) {
    case "USE":
      return t("workspaces.level.use", "Can chat");
    case "VIEW":
      return t("workspaces.level.view", "Can view");
    case "EDIT":
      return t("workspaces.level.edit", "Can edit");
    case "OWN":
      return t("workspaces.level.own", "Owner");
    default:
      return String(level);
  }
}

function levelHint(t: (k: string, d: string) => string, level: AccessLevel): string {
  switch (level) {
    case "USE":
      return t(
        "workspaces.level.useHint",
        "They can talk to the agent, but cannot see how it is built — no prompts, tools or credentials."
      );
    case "VIEW":
      return t("workspaces.level.viewHint", "They can read the configuration and export a copy, but not change it.");
    case "EDIT":
      return t("workspaces.level.editHint", "They can change and deploy it, but not delete it or share it further.");
    case "OWN":
      return t("workspaces.level.ownHint", "Full control, including deleting it and sharing it with others.");
    default:
      return "";
  }
}
